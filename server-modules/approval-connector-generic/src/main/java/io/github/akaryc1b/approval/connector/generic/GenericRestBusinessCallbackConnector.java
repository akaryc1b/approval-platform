package io.github.akaryc1b.approval.connector.generic;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.integration.webhook.CanonicalJson;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Generic signed JSON callback transport for host systems.
 */
public final class GenericRestBusinessCallbackConnector implements BusinessCallbackConnector {

    private static final int MAX_RESPONSE_MESSAGE_LENGTH = 2000;

    private final HttpClient httpClient;
    private final GenericWebhookEndpointResolver endpointResolver;
    private final HmacSha256WebhookSigner signer;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    public GenericRestBusinessCallbackConnector(
        HttpClient httpClient,
        GenericWebhookEndpointResolver endpointResolver,
        HmacSha256WebhookSigner signer,
        Clock clock,
        Supplier<String> nonceSupplier
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver must not be null");
        this.signer = Objects.requireNonNull(signer, "signer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nonceSupplier = Objects.requireNonNull(nonceSupplier, "nonceSupplier must not be null");
    }

    public GenericRestBusinessCallbackConnector(
        GenericWebhookEndpointResolver endpointResolver
    ) {
        this(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            endpointResolver,
            new HmacSha256WebhookSigner(),
            Clock.systemUTC(),
            () -> UUID.randomUUID().toString()
        );
    }

    @Override
    public CallbackReceipt deliver(ConnectorContext context, BusinessEvent event) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(event, "event must not be null");
        GenericWebhookEndpoint endpoint = endpointResolver.resolve(context);
        String body = eventBody(event);
        long timestamp = clock.instant().getEpochSecond();
        String nonce = requireText(nonceSupplier.get(), "nonce");
        String signature = signer.sign(endpoint.secret(), timestamp, nonce, body);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint.uri())
            .timeout(endpoint.timeout())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .header("X-Approval-Key-Id", endpoint.keyId())
            .header("X-Approval-Timestamp", Long.toString(timestamp))
            .header("X-Approval-Nonce", nonce)
            .header("X-Approval-Signature", signature)
            .header("X-Approval-Event-Id", event.eventId().toString())
            .header("X-Tenant-Id", context.tenantId())
            .header("X-Request-Id", context.requestId())
            .header("Idempotency-Key", event.idempotencyKey());
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
            return classify(response, clock.instant());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return retryable("Interrupted while delivering callback", clock.instant());
        } catch (IOException | RuntimeException exception) {
            return retryable(describe(exception), clock.instant());
        }
    }

    static String eventBody(BusinessEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.eventId());
        body.put("eventType", event.eventType());
        body.put("aggregateType", event.aggregateType());
        body.put("aggregateId", event.aggregateId());
        body.put("occurredAt", event.occurredAt());
        body.put("idempotencyKey", event.idempotencyKey());
        body.put("payload", event.payload());
        return CanonicalJson.write(body);
    }

    private static CallbackReceipt classify(HttpResponse<String> response, Instant completedAt) {
        int code = response.statusCode();
        String providerRequestId = response.headers().firstValue("X-Request-Id").orElse(null);
        if (code >= 200 && code < 300) {
            return new CallbackReceipt(
                DeliveryStatus.DELIVERED,
                providerRequestId,
                code,
                completedAt,
                null
            );
        }
        DeliveryStatus status = isRetryableStatus(code)
            ? DeliveryStatus.RETRYABLE_FAILURE
            : DeliveryStatus.PERMANENT_FAILURE;
        return new CallbackReceipt(
            status,
            providerRequestId,
            code,
            completedAt,
            truncate(response.body())
        );
    }

    private static CallbackReceipt retryable(String message, Instant completedAt) {
        return new CallbackReceipt(
            DeliveryStatus.RETRYABLE_FAILURE,
            null,
            0,
            completedAt,
            truncate(message)
        );
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
            || statusCode == 425
            || statusCode == 429
            || statusCode >= 500;
    }

    private static boolean isReservedHeader(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("content-type")
            || normalized.equals("x-approval-key-id")
            || normalized.equals("x-approval-timestamp")
            || normalized.equals("x-approval-nonce")
            || normalized.equals("x-approval-signature")
            || normalized.equals("x-approval-event-id")
            || normalized.equals("x-tenant-id")
            || normalized.equals("x-request-id")
            || normalized.equals("x-trace-id")
            || normalized.equals("idempotency-key");
    }

    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= MAX_RESPONSE_MESSAGE_LENGTH
            ? value
            : value.substring(0, MAX_RESPONSE_MESSAGE_LENGTH);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
