package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;

import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class JdkDingTalkHttpSender implements DingTalkHttpSender {

    private static final int MAX_RESPONSE_BODY_BYTES = 65_536;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final Clock clock;

    private JdkDingTalkHttpSender(HttpClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("DingTalk HttpClient must not follow redirects");
        }
        if (client.authenticator().isPresent() || client.cookieHandler().isPresent()) {
            throw new IllegalArgumentException(
                "DingTalk HttpClient must not use ambient authentication or cookies"
            );
        }
    }

    static JdkDingTalkHttpSender create(Clock clock) {
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        sslParameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(NoProxySelector.INSTANCE)
            .sslParameters(sslParameters)
            .version(HttpClient.Version.HTTP_2)
            .build();
        return new JdkDingTalkHttpSender(client, clock);
    }

    static JdkDingTalkHttpSender forTesting(HttpClient client, Clock clock) {
        return new JdkDingTalkHttpSender(client, clock);
    }

    @Override
    public DingTalkTransportResponse send(
        URI uri,
        Map<String, String> headers,
        String body,
        Duration timeout
    ) {
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            HttpResponse<InputStream> response = client.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofInputStream()
            );
            return response(response);
        } catch (HttpTimeoutException exception) {
            return DingTalkTransportResponse.timeout(clock.instant());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DingTalkTransportResponse.unknown(clock.instant());
        } catch (IOException | RuntimeException exception) {
            return DingTalkTransportResponse.unknown(clock.instant());
        }
    }

    private DingTalkTransportResponse response(HttpResponse<InputStream> response) {
        int statusCode = response.statusCode();
        if (statusCode < 100 || statusCode > 599) {
            closeQuietly(response.body());
            return DingTalkTransportResponse.unknown(clock.instant());
        }
        try (InputStream input = response.body()) {
            if (input == null) {
                return DingTalkTransportResponse.unknown(clock.instant());
            }
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BODY_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BODY_BYTES) {
                return DingTalkTransportResponse.unknown(clock.instant());
            }
            String responseBody = decodeUtf8(bytes);
            return DingTalkTransportResponse.responded(
                statusCode,
                providerRequestId(response),
                responseBody,
                clock.instant()
            );
        } catch (IOException exception) {
            return DingTalkTransportResponse.unknown(clock.instant());
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    private static String providerRequestId(HttpResponse<?> response) {
        for (String name : List.of("x-acs-request-id", "x-dingtalk-request-id", "x-request-id")) {
            Optional<String> value = response.headers().firstValue(name);
            if (value.isPresent()) {
                String normalized = value.orElseThrow().trim();
                if (!normalized.isEmpty()
                    && normalized.length() <= 128
                    && normalized.chars().allMatch(
                        character -> character >= 0x21 && character <= 0x7e
                    )) {
                    return normalized;
                }
            }
        }
        return null;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The response is already classified as unknown.
        }
    }

    @Override
    public String toString() {
        return "JdkDingTalkHttpSender[redirects=NEVER, proxy=NONE, credential=<redacted>]";
    }

    private static final class NoProxySelector extends ProxySelector {

        private static final NoProxySelector INSTANCE = new NoProxySelector();

        private NoProxySelector() {
        }

        @Override
        public List<Proxy> select(URI uri) {
            Objects.requireNonNull(uri, "uri must not be null");
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            Objects.requireNonNull(uri, "uri must not be null");
            Objects.requireNonNull(address, "address must not be null");
            Objects.requireNonNull(failure, "failure must not be null");
        }
    }
}
