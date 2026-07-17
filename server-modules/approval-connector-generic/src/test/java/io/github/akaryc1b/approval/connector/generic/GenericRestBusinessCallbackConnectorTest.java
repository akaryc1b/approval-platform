package io.github.akaryc1b.approval.connector.generic;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericRestBusinessCallbackConnectorTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef"
        .getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final URI ENDPOINT = URI.create("https://host.example.test/approval/callback");

    @Test
    void sendsCanonicalSignedRequest() {
        var httpClient = new CapturingHttpClient(
            204,
            "",
            Map.of("X-Request-Id", List.of("provider-123"))
        );
        var connector = connector(httpClient);

        var receipt = connector.deliver(context(), event());

        HttpRequest request = httpClient.capturedRequest();
        String requestBody = httpClient.capturedBody();
        String signature = header(request, "X-Approval-Signature");
        String timestamp = header(request, "X-Approval-Timestamp");
        String nonce = header(request, "X-Approval-Nonce");

        assertEquals(BusinessCallbackConnector.DeliveryStatus.DELIVERED, receipt.status());
        assertEquals(204, receipt.responseCode());
        assertEquals("provider-123", receipt.providerRequestId());
        assertEquals("tenant-a", header(request, "X-Tenant-Id"));
        assertEquals("process-1-approved", header(request, "Idempotency-Key"));
        assertEquals("approval-platform", header(request, "X-Client"));
        assertTrue(new HmacSha256WebhookSigner().verify(
            SECRET,
            Long.parseLong(timestamp),
            nonce,
            requestBody,
            signature
        ));
    }

    @Test
    void classifiesRateLimitAsRetryable() {
        var httpClient = new CapturingHttpClient(429, "slow down", Map.of());

        var receipt = connector(httpClient).deliver(context(), event());

        assertEquals(BusinessCallbackConnector.DeliveryStatus.RETRYABLE_FAILURE, receipt.status());
        assertEquals(429, receipt.responseCode());
        assertEquals("slow down", receipt.errorMessage());
    }

    private GenericRestBusinessCallbackConnector connector(HttpClient httpClient) {
        var endpoint = new GenericWebhookEndpoint(
            ENDPOINT,
            "key-1",
            SECRET,
            Duration.ofSeconds(5),
            Map.of("X-Client", "approval-platform")
        );
        return new GenericRestBusinessCallbackConnector(
            httpClient,
            context -> endpoint,
            new HmacSha256WebhookSigner(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "nonce-1"
        );
    }

    private static String header(HttpRequest request, String name) {
        return request.headers().firstValue(name).orElseThrow();
    }

    private static ConnectorContext context() {
        return new ConnectorContext("generic", "tenant-a", "request-1", "trace-1", NOW);
    }

    private static BusinessCallbackConnector.BusinessEvent event() {
        return new BusinessCallbackConnector.BusinessEvent(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "PROCESS_APPROVED.v1",
            "PROCESS",
            "process-1",
            NOW,
            "process-1-approved",
            Map.of("amount", 1200, "currency", "CNY")
        );
    }

    private static final class CapturingHttpClient extends HttpClient {

        private final int responseCode;
        private final String responseBody;
        private final HttpHeaders responseHeaders;
        private HttpRequest capturedRequest;
        private String capturedBody;

        private CapturingHttpClient(
            int responseCode,
            String responseBody,
            Map<String, List<String>> responseHeaders
        ) {
            this.responseCode = responseCode;
            this.responseBody = responseBody;
            this.responseHeaders = HttpHeaders.of(responseHeaders, (name, value) -> true);
        }

        HttpRequest capturedRequest() {
            return capturedRequest;
        }

        String capturedBody() {
            return capturedBody;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
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
                throw new IllegalStateException("Default SSL context is unavailable", exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
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
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            capturedRequest = request;
            capturedBody = readBody(request);
            return new FakeHttpResponse<>(
                responseCode,
                request,
                responseHeaders,
                (T) responseBody
            );
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return sendAsync(request, responseBodyHandler);
        }

        private static String readBody(HttpRequest request) {
            var publisher = request.bodyPublisher().orElseThrow();
            var bytes = new ByteArrayOutputStream();
            var completed = new CompletableFuture<Void>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    byte[] chunk = new byte[item.remaining()];
                    item.get(chunk);
                    bytes.writeBytes(chunk);
                }

                @Override
                public void onError(Throwable throwable) {
                    completed.completeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    completed.complete(null);
                }
            });
            completed.join();
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private record FakeHttpResponse<T>(
        int statusCode,
        HttpRequest request,
        HttpHeaders headers,
        T body
    ) implements HttpResponse<T> {

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
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
