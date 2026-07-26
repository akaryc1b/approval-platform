package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkRequestEncoder;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportRequest;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class DingTalkEndpointPolicy {

    static final String OPEN_API_HOST = "api.dingtalk.com";
    static final String LEGACY_OAPI_HOST = "oapi.dingtalk.com";
    static final String ACCESS_TOKEN_HEADER = "x-acs-dingtalk-access-token";
    static final String ACCESS_TOKEN_QUERY = "access_token";

    private static final int MAX_REQUEST_BODY_BYTES = 16_384;
    private static final int MAX_ACCESS_TOKEN_BYTES = 4_096;
    private static final String HEX = "0123456789ABCDEF";

    private final HostAddressResolver addressResolver;

    DingTalkEndpointPolicy(HostAddressResolver addressResolver) {
        this.addressResolver = Objects.requireNonNull(
            addressResolver,
            "addressResolver must not be null"
        );
    }

    static DingTalkEndpointPolicy systemDefault() {
        return new DingTalkEndpointPolicy(InetAddress::getAllByName);
    }

    PreparedEndpoint prepare(
        ConnectorOperation operation,
        DingTalkTransportRequest request
    ) throws UnknownHostException {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (request.method() != DingTalkTransportRequest.HttpMethod.POST) {
            throw new DingTalkTransportPolicyException("DingTalk production transport allows POST only");
        }
        validateHeaders(request.headers());
        if (request.body().getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BODY_BYTES) {
            throw new DingTalkTransportPolicyException("DingTalk request body exceeds byte limit");
        }

        EndpointBinding binding = endpointFor(operation, request);
        validatePublicResolution(binding.host());
        return new PreparedEndpoint(uri(binding.host(), binding.path()), binding.authentication());
    }

    private static EndpointBinding endpointFor(
        ConnectorOperation operation,
        DingTalkTransportRequest request
    ) {
        return switch (operation) {
            case ORGANIZATION_READ -> switch (request.apiFamily()) {
                case OPEN_API_V1 -> endpoint(
                    OPEN_API_HOST,
                    DingTalkRequestEncoder.USER_SEARCH_PATH,
                    request.path(),
                    AuthenticationLocation.HEADER
                );
                case LEGACY_OAPI -> endpoint(
                    LEGACY_OAPI_HOST,
                    DingTalkRequestEncoder.USER_DETAIL_PATH,
                    request.path(),
                    AuthenticationLocation.QUERY
                );
            };
            case IDENTITY_RESOLVE -> {
                if (request.apiFamily() != DingTalkTransportRequest.ApiFamily.LEGACY_OAPI) {
                    throw new DingTalkTransportPolicyException(
                        "DingTalk identity resolution requires the user-detail API family"
                    );
                }
                yield endpoint(
                    LEGACY_OAPI_HOST,
                    DingTalkRequestEncoder.USER_DETAIL_PATH,
                    request.path(),
                    AuthenticationLocation.QUERY
                );
            }
            default -> throw new DingTalkTransportPolicyException(
                "DingTalk operation is outside the production transport allowlist"
            );
        };
    }

    DingTalkTransportResponse sendWithCredential(
        PreparedEndpoint endpoint,
        byte[] accessToken,
        DingTalkTransportRequest request,
        DingTalkHttpSender sender
    ) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(sender, "sender must not be null");
        requireAccessToken(accessToken);

        URI uri = endpoint.uri();
        Map<String, String> headers = new LinkedHashMap<>(request.headers());
        String renderedCredential;
        if (endpoint.authentication() == AuthenticationLocation.HEADER) {
            renderedCredential = new String(accessToken, StandardCharsets.US_ASCII);
            headers.put(ACCESS_TOKEN_HEADER, renderedCredential);
        } else {
            renderedCredential = percentEncode(accessToken);
            uri = URI.create(
                endpoint.uri().toASCIIString()
                    + "?"
                    + ACCESS_TOKEN_QUERY
                    + "="
                    + renderedCredential
            );
        }
        DingTalkTransportResponse response = sender.send(
            uri,
            Map.copyOf(headers),
            request.body(),
            request.timeout()
        );
        return redactCredentialEcho(response, accessToken, renderedCredential);
    }

    private static DingTalkTransportResponse redactCredentialEcho(
        DingTalkTransportResponse response,
        byte[] accessToken,
        String renderedCredential
    ) {
        if (response == null || response.providerRequestId() == null) {
            return response;
        }
        String requestId = response.providerRequestId();
        byte[] requestIdBytes = requestId.getBytes(StandardCharsets.US_ASCII);
        try {
            if (!contains(requestIdBytes, accessToken)
                && (renderedCredential == null || !containsIgnoreCase(requestId, renderedCredential))) {
                return response;
            }
            return new DingTalkTransportResponse(
                response.state(),
                response.statusCode(),
                null,
                response.body(),
                response.completedAt()
            );
        } finally {
            Arrays.fill(requestIdBytes, (byte) 0);
        }
    }

    private static boolean contains(byte[] value, byte[] candidate) {
        if (candidate.length == 0 || candidate.length > value.length) {
            return false;
        }
        for (int start = 0; start <= value.length - candidate.length; start++) {
            boolean matched = true;
            for (int index = 0; index < candidate.length; index++) {
                if (value[start + index] != candidate[index]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String candidate) {
        if (candidate.isEmpty() || candidate.length() > value.length()) {
            return false;
        }
        for (int start = 0; start <= value.length() - candidate.length(); start++) {
            if (value.regionMatches(true, start, candidate, 0, candidate.length())) {
                return true;
            }
        }
        return false;
    }

    private static EndpointBinding endpoint(
        String host,
        String allowedPath,
        String actualPath,
        AuthenticationLocation authentication
    ) {
        if (!allowedPath.equals(actualPath)) {
            throw new DingTalkTransportPolicyException(
                "DingTalk provider path is outside the production allowlist"
            );
        }
        return new EndpointBinding(host, allowedPath, authentication);
    }

    private void validatePublicResolution(String host) throws UnknownHostException {
        InetAddress[] addresses = addressResolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("DingTalk host resolved without addresses");
        }
        for (InetAddress address : addresses) {
            if (address == null || !isPublicAddress(address)) {
                throw new DingTalkTransportPolicyException(
                    "DingTalk host resolved to a non-public address"
                );
            }
        }
    }

    private static void validateHeaders(Map<String, String> headers) {
        String contentType = null;
        for (var entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if ("Content-Type".equalsIgnoreCase(name)) {
                contentType = value;
            } else if ("Accept".equalsIgnoreCase(name)) {
                if (!"application/json".equalsIgnoreCase(value.trim())) {
                    throw new DingTalkTransportPolicyException(
                        "DingTalk Accept header must be application/json"
                    );
                }
            } else {
                throw new DingTalkTransportPolicyException(
                    "DingTalk captured header is outside the production allowlist"
                );
            }
        }
        if (contentType == null
            || !"application/json".equalsIgnoreCase(contentType.trim())) {
            throw new DingTalkTransportPolicyException(
                "DingTalk Content-Type must be application/json"
            );
        }
    }

    private static void requireAccessToken(byte[] value) {
        Objects.requireNonNull(value, "accessToken must not be null");
        if (value.length == 0 || value.length > MAX_ACCESS_TOKEN_BYTES) {
            throw new DingTalkTransportPolicyException("DingTalk access token length is invalid");
        }
        for (byte item : value) {
            int character = Byte.toUnsignedInt(item);
            if (character < 0x21 || character > 0x7e) {
                throw new DingTalkTransportPolicyException(
                    "DingTalk access token contains unsupported bytes"
                );
            }
        }
    }

    private static URI uri(String host, String path) {
        try {
            return new URI("https", null, host, -1, path, null, null);
        } catch (URISyntaxException exception) {
            throw new DingTalkTransportPolicyException(
                "DingTalk endpoint policy produced an invalid URI",
                exception
            );
        }
    }

    private static String percentEncode(byte[] value) {
        StringBuilder encoded = new StringBuilder(value.length * 3);
        for (byte item : value) {
            int character = Byte.toUnsignedInt(item);
            if (isUnreserved(character)) {
                encoded.append((char) character);
            } else {
                encoded.append('%')
                    .append(HEX.charAt((character >>> 4) & 0x0f))
                    .append(HEX.charAt(character & 0x0f));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int character) {
        return character >= 'A' && character <= 'Z'
            || character >= 'a' && character <= 'z'
            || character >= '0' && character <= '9'
            || character == '-'
            || character == '.'
            || character == '_'
            || character == '~';
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (bytes.length == 16) {
            if (isIpv4Mapped(bytes)) {
                return isPublicIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            return isPublicIpv6(bytes);
        }
        return false;
    }

    private static boolean isPublicIpv6(byte[] bytes) {
        int firstSegment = ipv6Segment(bytes, 0);
        int secondSegment = ipv6Segment(bytes, 2);
        if ((firstSegment & 0xe000) != 0x2000) {
            return false;
        }
        if (firstSegment == 0x2001 && secondSegment <= 0x01ff) {
            return false;
        }
        if (firstSegment == 0x2001 && secondSegment == 0x0db8) {
            return false;
        }
        if (firstSegment == 0x2002 || firstSegment == 0x3ffe) {
            return false;
        }
        return firstSegment != 0x3fff || (secondSegment & 0xf000) != 0;
    }

    private static int ipv6Segment(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) << 8
            | Byte.toUnsignedInt(bytes[offset + 1]);
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192 && second == 168) {
            return false;
        }
        if (first == 192 && second == 0 && (third == 0 || third == 2)) {
            return false;
        }
        if (first == 192 && second == 88 && third == 99) {
            return false;
        }
        if (first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)) {
            return false;
        }
        return !(first == 203 && second == 0 && third == 113);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    @Override
    public String toString() {
        return "DingTalkEndpointPolicy[hosts=official-only, credential=<redacted>]";
    }

    @FunctionalInterface
    interface HostAddressResolver {

        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    record PreparedEndpoint(URI uri, AuthenticationLocation authentication) {

        PreparedEndpoint {
            uri = Objects.requireNonNull(uri, "uri must not be null");
            authentication = Objects.requireNonNull(
                authentication,
                "authentication must not be null"
            );
        }
    }

    private record EndpointBinding(
        String host,
        String path,
        AuthenticationLocation authentication
    ) {
    }

    enum AuthenticationLocation {
        HEADER,
        QUERY
    }
}
