package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Framework-neutral client, result, correlation, idempotency and mock transport contracts. */
public final class ApprovalSdk {
    private ApprovalSdk() {
    }

    public interface Client {
        <T> Result<T> execute(Request request, Class<T> responseType);
    }

    public interface Transport {
        <T> Result<T> exchange(Request request, Class<T> responseType);
    }

    public static final class DefaultClient implements Client {
        private final Transport transport;

        public DefaultClient(Transport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
        }

        @Override
        public <T> Result<T> execute(Request request, Class<T> responseType) {
            return transport.exchange(request, responseType);
        }
    }

    /** Trusted tenant, operator, authority and audit evidence are deliberately absent. */
    public record Request(
        String operation,
        Object payload,
        Correlation correlation,
        String idempotencyKey
    ) {
        public Request {
            operation = EventEnvelopeV1.required(operation, "operation");
            payload = Objects.requireNonNull(payload, "payload");
            correlation = Objects.requireNonNull(correlation, "correlation");
            idempotencyKey = EventEnvelopeV1.required(idempotencyKey, "idempotencyKey");
        }
    }

    public record Correlation(String requestId, String traceId) {
        public Correlation {
            requestId = EventEnvelopeV1.required(requestId, "requestId");
            traceId = EventEnvelopeV1.required(traceId, "traceId");
        }
    }

    public record Error(String code, String message, ErrorCategory category, String requestId) {
        public Error {
            code = EventEnvelopeV1.required(code, "error.code");
            message = EventEnvelopeV1.required(message, "error.message");
            category = Objects.requireNonNull(category, "error.category");
            requestId = EventEnvelopeV1.required(requestId, "error.requestId");
        }
    }

    public enum ErrorCategory {
        RETRYABLE,
        PERMANENT,
        UNAUTHORIZED,
        CONFLICT,
        EXPIRED,
        UNSUPPORTED_VERSION
    }

    public record Result<T>(T value, Error error) {
        public Result {
            if ((value == null) == (error == null)) {
                throw new IllegalArgumentException("Result must contain exactly one of value or error");
            }
        }

        public static <T> Result<T> success(T value) {
            return new Result<>(Objects.requireNonNull(value, "value"), null);
        }

        public static <T> Result<T> failure(Error error) {
            return new Result<>(null, Objects.requireNonNull(error, "error"));
        }

        public boolean successful() {
            return error == null;
        }
    }

    public static String idempotencyKey(String operation, String requestId, Object payload) {
        String material = EventEnvelopeV1.required(operation, "operation") + "\n"
            + EventEnvelopeV1.required(requestId, "requestId") + "\n"
            + CanonicalJson.canonicalizeValue(Objects.requireNonNull(payload, "payload"));
        return CanonicalJson.sha256Hex(material.getBytes(StandardCharsets.UTF_8));
    }

    public static final class MockTransport implements Transport {
        private final Function<Request, Result<?>> handler;
        private final List<Request> invocations = new ArrayList<>();

        public MockTransport(Function<Request, Result<?>> handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        @Override
        public synchronized <T> Result<T> exchange(Request request, Class<T> responseType) {
            invocations.add(request);
            Result<?> result = handler.apply(request);
            if (!result.successful()) {
                @SuppressWarnings("unchecked")
                Result<T> failure = (Result<T>) result;
                return failure;
            }
            Object value = result.value();
            if (!responseType.isInstance(value)) {
                return Result.failure(new Error(
                    "mock_type_mismatch",
                    "Mock response does not match requested type",
                    ErrorCategory.PERMANENT,
                    request.correlation().requestId()
                ));
            }
            return Result.success(responseType.cast(value));
        }

        public synchronized List<Request> invocations() {
            return Collections.unmodifiableList(new ArrayList<>(invocations));
        }
    }
}
