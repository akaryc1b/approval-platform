package io.github.akaryc1b.approval.ai.openai;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Hash-only DNS, channel and deadline contracts used by the isolated P6-D sender. */
final class OpenAiResponsesNetworkSupport {

    private OpenAiResponsesNetworkSupport() {
    }

    interface SecureNetwork {
        Resolution resolve(
            OpenAiResponsesEndpointPolicy endpoint,
            OpenAiResponsesTransportPort.Request request,
            Deadline deadline
        );

        SecureChannel connect(
            OpenAiResponsesEndpointPolicy endpoint,
            Resolution resolution,
            OpenAiResponsesTransportPort.Request request,
            Deadline deadline
        );
    }

    interface SecureChannel extends AutoCloseable {
        ExchangeResult exchange(
            OpenAiResponsesTransportPort.Request request,
            byte[] secret,
            String clientRequestId,
            Deadline deadline
        );

        String connectedAddressHash();

        String tlsPeerHash();

        boolean tlsVerified();

        @Override
        void close();
    }

    @FunctionalInterface
    interface ClientRequestIdSource {
        String next();
    }

    static final class Resolution {
        private final String endpointHash;
        private final List<InetAddress> addresses;
        private final List<String> addressHashes;
        private final Instant resolvedAt;
        private final String evidenceHash;

        Resolution(String endpointHash, List<InetAddress> addresses, Instant resolvedAt) {
            this.endpointHash = OpenAiResponsesProtocol.requireSha256(
                endpointHash,
                "endpointHash"
            );
            Objects.requireNonNull(addresses, "addresses must not be null");
            if (addresses.isEmpty() || addresses.size() > 32) {
                throw failure(OpenAiResponsesTransportException.Failure.DNS_EMPTY);
            }
            List<InetAddress> sorted = addresses.stream()
                .map(OpenAiResponsesNetworkSupport::copyAddress)
                .sorted(Comparator.comparing(OpenAiResponsesNetworkSupport::addressHex))
                .toList();
            if (sorted.stream().anyMatch(address -> !isPublicAddress(address))) {
                throw failure(OpenAiResponsesTransportException.Failure.DNS_UNSAFE);
            }
            this.addresses = List.copyOf(sorted);
            this.addressHashes = sorted.stream()
                .map(OpenAiResponsesNetworkSupport::addressHash)
                .toList();
            if (new HashSet<>(addressHashes).size() != addressHashes.size()) {
                throw failure(OpenAiResponsesTransportException.Failure.DNS_DRIFT);
            }
            this.resolvedAt = Objects.requireNonNull(
                resolvedAt,
                "resolvedAt must not be null"
            );
            this.evidenceHash = OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-dns-resolution-v1",
                endpointHash,
                String.join(",", addressHashes),
                resolvedAt.toString()
            ));
        }

        String endpointHash() {
            return endpointHash;
        }

        List<InetAddress> addresses() {
            return addresses;
        }

        List<String> addressHashes() {
            return addressHashes;
        }

        InetAddress selectedAddress() {
            return addresses.get(0);
        }

        Instant resolvedAt() {
            return resolvedAt;
        }

        String evidenceHash() {
            return evidenceHash;
        }

        @Override
        public String toString() {
            return "Resolution[evidenceHash=" + evidenceHash + ", addressCount="
                + addresses.size() + "]";
        }
    }

    static final class ExchangeResult {
        private final int statusCode;
        private final String requestId;
        private final byte[] body;
        private final String clientRequestIdHash;

        ExchangeResult(
            int statusCode,
            String requestId,
            byte[] body,
            String clientRequestIdHash
        ) {
            if (statusCode < 100 || statusCode > 599) {
                throw failure(OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID);
            }
            Objects.requireNonNull(body, "body must not be null");
            if (body.length > OpenAiResponsesProtocol.MAXIMUM_TRANSPORT_RESPONSE_BYTES) {
                throw failure(OpenAiResponsesTransportException.Failure.RESPONSE_TOO_LARGE);
            }
            this.statusCode = statusCode;
            this.requestId = requestId;
            this.body = Arrays.copyOf(body, body.length);
            this.clientRequestIdHash = OpenAiResponsesProtocol.requireSha256(
                clientRequestIdHash,
                "clientRequestIdHash"
            );
        }

        int statusCode() {
            return statusCode;
        }

        String requestId() {
            return requestId;
        }

        byte[] bodyCopy() {
            return Arrays.copyOf(body, body.length);
        }

        String clientRequestIdHash() {
            return clientRequestIdHash;
        }
    }

    static final class Deadline {
        private final long deadlineNanos;

        private Deadline(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        static Deadline start(Duration duration) {
            long now = System.nanoTime();
            long nanos;
            try {
                nanos = duration.toNanos();
            } catch (ArithmeticException failure) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
            long deadline;
            try {
                deadline = Math.addExact(now, nanos);
            } catch (ArithmeticException overflow) {
                deadline = Long.MAX_VALUE;
            }
            return new Deadline(deadline);
        }

        Duration remaining() {
            long nanos = deadlineNanos - System.nanoTime();
            if (nanos <= 0) {
                throw failure(OpenAiResponsesTransportException.Failure.TIMEOUT);
            }
            return Duration.ofNanos(nanos);
        }

        int remainingMillis() {
            long millis = Math.max(1L, remaining().toMillis());
            return (int) Math.min(Integer.MAX_VALUE, millis);
        }

        void requireRemaining() {
            remaining();
        }
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
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
            if (first == 192 && second == 0 && third == 0) {
                return false;
            }
            if (first == 192 && second == 0 && third == 2) {
                return false;
            }
            if (first == 192 && second == 88 && third == 99) {
                return false;
            }
            if (first == 192 && second == 168) {
                return false;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false;
            }
            if (first == 198 && second == 51 && third == 100) {
                return false;
            }
            if (first == 203 && second == 0 && third == 113) {
                return false;
            }
            return true;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if ((first & 0xe0) != 0x20) {
                return false;
            }
            int third = Byte.toUnsignedInt(bytes[2]);
            int fourth = Byte.toUnsignedInt(bytes[3]);
            if (first == 0x20 && second == 0x01
                && ((third == 0x00 && fourth == 0x00)
                || (third == 0x00 && fourth == 0x02)
                || (third == 0x00 && (fourth & 0xf0) == 0x10)
                || (third == 0x00 && (fourth & 0xf0) == 0x20)
                || (third == 0x0d && fourth == 0xb8))) {
                return false;
            }
            return !(first == 0x20 && second == 0x02);
        }
        return false;
    }

    static String addressHash(InetAddress address) {
        return OpenAiResponsesProtocol.sha256(address.getAddress());
    }

    static void requireNotCancelled(OpenAiResponsesTransportPort.Request request) {
        if (request.cancelled() || Thread.currentThread().isInterrupted()) {
            throw failure(OpenAiResponsesTransportException.Failure.CANCELLED);
        }
    }

    private static InetAddress copyAddress(InetAddress address) {
        try {
            return InetAddress.getByAddress(address.getAddress());
        } catch (UnknownHostException failure) {
            throw failure(OpenAiResponsesTransportException.Failure.DNS_FAILURE);
        }
    }

    private static String addressHex(InetAddress address) {
        return java.util.HexFormat.of().formatHex(address.getAddress());
    }

    static OpenAiResponsesTransportException failure(
        OpenAiResponsesTransportException.Failure failure
    ) {
        return new OpenAiResponsesTransportException(failure);
    }
}
