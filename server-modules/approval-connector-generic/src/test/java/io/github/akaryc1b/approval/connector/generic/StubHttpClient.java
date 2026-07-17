package io.github.akaryc1b.approval.connector.generic;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Function;

final class StubHttpClient extends HttpClient {

    private final Function<HttpRequest, StubResponse> responder;
    private HttpRequest capturedRequest;
    private String capturedBody;

    StubHttpClient(Function<HttpRequest, StubResponse> responder) {
        this.responder = responder;
    }

    StubHttpClient(int statusCode, String body) {
        this(request -> new StubResponse(statusCode, body, Map.of()));
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
        StubResponse response = responder.apply(request);
        return new Response<>(
            response.statusCode(),
            request,
            HttpHeaders.of(response.headers(), (name, value) -> true),
            (T) response.body()
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

    record StubResponse(
        int statusCode,
        String body,
        Map<String, List<String>> headers
    ) {
        StubResponse {
            body = body == null ? "" : body;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    private record Response<T>(
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
        public Version version() {
            return Version.HTTP_2;
        }
    }
}
