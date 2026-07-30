package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkDingTalkHttpSenderTest {

    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final URI URI_VALUE = URI.create(
        "https://api.dingtalk.com/v1.0/contact/users/search"
    );

    @Test
    void sendsOneBoundedPostAndCapturesProviderRequestId() {
        FixtureHttpClient client = new FixtureHttpClient();
        client.statusCode = 200;
        client.body = "{\"result\":{}}".getBytes();
        client.responseHeaders = Map.of("x-acs-request-id", List.of("request-1"));
        JdkDingTalkHttpSender sender = JdkDingTalkHttpSender.forTesting(client, clock());

        DingTalkTransportResponse response = sender.send(
            URI_VALUE,
            Map.of(
                "Content-Type",
                "application/json",
                DingTalkEndpointPolicy.ACCESS_TOKEN_HEADER,
                "test-token"
            ),
            "{}",
            Duration.ofSeconds(5)
        );

        assertEquals(DingTalkTransportResponse.State.RESPONDED, response.state());
        assertEquals(200, response.statusCode());
        assertEquals("request-1", response.providerRequestId());
        assertEquals(1, client.sendCount);
        assertEquals("POST", client.request.method());
        assertEquals(URI_VALUE, client.request.uri());
        assertEquals(Duration.ofSeconds(5), client.request.timeout().orElseThrow());
        assertEquals(
            "test-token",
            client.request.headers().firstValue(
                DingTalkEndpointPolicy.ACCESS_TOKEN_HEADER
            ).orElseThrow()
        );
    }

    @Test
    void timeoutAndIoFailureReturnClosedOutcomesWithoutRetry() {
        FixtureHttpClient timeoutClient = new FixtureHttpClient();
        timeoutClient.failure = Failure.TIMEOUT;
        DingTalkTransportResponse timeout = JdkDingTalkHttpSender
            .forTesting(timeoutClient, clock())
            .send(URI_VALUE, Map.of("Content-Type", "application/json"), "{}", Duration.ofMillis(1));
        assertEquals(DingTalkTransportResponse.State.TIMEOUT, timeout.state());
        assertEquals(1, timeoutClient.sendCount);

        FixtureHttpClient ioClient = new FixtureHttpClient();
        ioClient.failure = Failure.IO;
        DingTalkTransportResponse unknown = JdkDingTalkHttpSender
            .forTesting(ioClient, clock())
            .send(URI_VALUE, Map.of("Content-Type", "application/json"), "{}", Duration.ofSeconds(1));
        assertEquals(DingTalkTransportResponse.State.UNKNOWN, unknown.state());
        assertEquals(1, ioClient.sendCount);
    }

    @Test
    void oversizedAndMalformedBodiesReturnUnknownWhileEmptyBodyPreservesStatus() {
        for (byte[] body : new byte[][] {
            new byte[65_537],
            new byte[] {(byte) 0xc3, (byte) 0x28}
        }) {
            FixtureHttpClient client = new FixtureHttpClient();
            client.statusCode = 200;
            client.body = body;
            DingTalkTransportResponse response = JdkDingTalkHttpSender
                .forTesting(client, clock())
                .send(
                    URI_VALUE,
                    Map.of("Content-Type", "application/json"),
                    "{}",
                    Duration.ofSeconds(1)
                );
            assertEquals(DingTalkTransportResponse.State.UNKNOWN, response.state());
            assertEquals(1, client.sendCount);
        }

        FixtureHttpClient empty = new FixtureHttpClient();
        empty.statusCode = 429;
        empty.body = new byte[0];
        DingTalkTransportResponse emptyResponse = JdkDingTalkHttpSender
            .forTesting(empty, clock())
            .send(
                URI_VALUE,
                Map.of("Content-Type", "application/json"),
                "{}",
                Duration.ofSeconds(1)
            );
        assertEquals(DingTalkTransportResponse.State.RESPONDED, emptyResponse.state());
        assertEquals(429, emptyResponse.statusCode());
        assertEquals("", emptyResponse.body());
        assertEquals(1, empty.sendCount);
    }

    @Test
    void clientWithRedirectsOrAmbientStateIsRejected() {
        FixtureHttpClient redirects = new FixtureHttpClient();
        redirects.redirect = HttpClient.Redirect.NORMAL;
        assertThrows(
            IllegalArgumentException.class,
            () -> JdkDingTalkHttpSender.forTesting(redirects, clock())
        );

        FixtureHttpClient authenticator = new FixtureHttpClient();
        authenticator.authenticator = Optional.of(new Authenticator() { });
        assertThrows(
            IllegalArgumentException.class,
            () -> JdkDingTalkHttpSender.forTesting(authenticator, clock())
        );
    }

    @Test
    void renderingNeverContainsSensitiveHeaderValue() {
        FixtureHttpClient client = new FixtureHttpClient();
        JdkDingTalkHttpSender sender = JdkDingTalkHttpSender.forTesting(client, clock());

        assertFalse(sender.toString().contains("test-token"));
        assertTrue(sender.toString().contains("credential=<redacted>"));
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private enum Failure {
        NONE,
        TIMEOUT,
        IO
    }

    private static final class FixtureHttpClient extends HttpClient {

        private int statusCode = 200;
        private byte[] body = "{}".getBytes();
        private Map<String, List<String>> responseHeaders = Map.of();
        private Failure failure = Failure.NONE;
        private Redirect redirect = Redirect.NEVER;
        private Optional<Authenticator> authenticator = Optional.empty();
        private int sendCount;
        private HttpRequest request;

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(5));
        }

        @Override
        public Redirect followRedirects() {
            return redirect;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return parameters;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return authenticator;
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(
            HttpRequest sentRequest,
            HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException, InterruptedException {
            sendCount++;
            request = sentRequest;
            if (failure == Failure.TIMEOUT) {
                throw new HttpTimeoutException("test timeout");
            }
            if (failure == Failure.IO) {
                throw new IOException("test I/O failure");
            }
            HttpResponse<InputStream> response = new FixtureResponse(
                sentRequest,
                statusCode,
                responseHeaders,
                new ByteArrayInputStream(body)
            );
            return (HttpResponse<T>) response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    private record FixtureResponse(
        HttpRequest request,
        int statusCode,
        Map<String, List<String>> responseHeaders,
        InputStream body
    ) implements HttpResponse<InputStream> {

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(responseHeaders, (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }
}
