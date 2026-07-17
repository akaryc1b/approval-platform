package io.github.akaryc1b.approval.connector.generic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.integration.webhook.CanonicalJson;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Signed JSON transport shared by Generic REST connector ports.
 */
public final class GenericRestTransport {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final HttpClient httpClient;
    private final GenericRestHostEndpointResolver endpointResolver;
    private final HmacSha256WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    public GenericRestTransport(
        HttpClient httpClient,
        GenericRestHostEndpointResolver endpointResolver,
        HmacSha256WebhookSigner signer,
        ObjectMapper objectMapper,
        Clock clock,
        Supplier<String> nonceSupplier
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.endpointResolver = Objects.requireNonNull(
            endpointResolver,
            "endpointResolver must not be null"
        );
        this.signer = Objects.requireNonNull(signer, "signer must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nonceSupplier = Objects.requireNonNull(nonceSupplier, "nonceSupplier must not be null");
    }

    public GenericRestTransport(
        GenericRestHostEndpointResolver endpointResolver,
        ObjectMapper objectMapper
    ) {
        this(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            endpointResolver,
            new HmacSha256WebhookSigner(),
            objectMapper,
            Clock.systemUTC(),
            () -> UUID.randomUUID().toString()
        );
    }

    public GenericRestResponse post(
        ConnectorContext context,
        String path,
        String operation,
        Map<String, Object> body
    ) {
        Objects.requireNonNull(context, "context must not be null");
        operation = requireText(operation, "operation");
        body = body == null ? Map.of() : Map.copyOf(body);
        GenericRestHostEndpoint endpoint = endpointResolver.resolve(context);
        String jsonBody = CanonicalJson.write(body);
        long timestamp = clock.instant().getEpochSecond();
        String nonce = requireText(nonceSupplier.get(), "nonce");
        String signature = signer.sign(endpoint.secret(), timestamp, nonce, jsonBody);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(endpoint.timeout())
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Approval-Key-Id", endpoint.keyId())
            .header("X-Approval-Timestamp", Long.toString(timestamp))
            .header("X-Approval-Nonce", nonce)
            .header("X-Approval-Signature", signature)
            .header("X-Approval-Operation", operation)
            .header("X-Tenant-Id", context.tenantId())
            .header("X-Request-Id", context.requestId());
        if (context.traceId() != null) {
            requestBuilder.header("X-Trace-Id", context.traceId());
        }
        endpoint.headers().forEach((name, value) -> {
            if (!isReservedHeader(name)) {
                requestBuilder.header(name, value);
            }
        });

        try {
            HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
            );
            return new GenericRestResponse(
                response.statusCode(),
                parseBody(response.body()),
                response.headers().firstValue("X-Request-Id").orElse(null)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GenericRestConnectorException(
                "Interrupted while calling Generic REST host",
                0,
                true,
                exception
            );
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof GenericRestConnectorException connectorException) {
                throw connectorException;
            }
            throw new GenericRestConnectorException(
                describe(exception),
                0,
                true,
                exception
            );
        }
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            return TextNode.valueOf(truncate(body));
        }
    }

    private static boolean isReservedHeader(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("content-type")
            || normalized.equals("accept")
            || normalized.equals("x-approval-key-id")
            || normalized.equals("x-approval-timestamp")
            || normalized.equals("x-approval-nonce")
            || normalized.equals("x-approval-signature")
            || normalized.equals("x-approval-operation")
            || normalized.equals("x-tenant-id")
            || normalized.equals("x-request-id")
            || normalized.equals("x-trace-id");
    }

    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return truncate(exception.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record GenericRestResponse(
        int statusCode,
        JsonNode body,
        String providerRequestId
    ) {
        public GenericRestResponse {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode must be a valid HTTP status");
            }
            body = Objects.requireNonNull(body, "body must not be null");
            providerRequestId = providerRequestId == null || providerRequestId.isBlank()
                ? null
                : providerRequestId;
        }

        public boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }

        public boolean notFound() {
            return statusCode == 404;
        }

        public void requireSuccess(String operation) {
            if (successful()) {
                return;
            }
            boolean retryable = statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
            throw new GenericRestConnectorException(
                operation + " failed with HTTP " + statusCode + ": " + truncate(body.toString()),
                statusCode,
                retryable
            );
        }
    }
}
