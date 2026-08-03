package io.github.akaryc1b.approval.ai.openai;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * P6-C transport port contract.
 *
 * <p>No production implementation is introduced in P6-C. The port carries only bounded protocol
 * bytes and redaction-safe evidence. DNS, TLS, endpoint admission and the concrete sender remain
 * gated to P6-D.</p>
 */
public interface OpenAiResponsesTransportPort {

    Response exchange(Request request);

    final class Request {

        private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofSeconds(2);
        private static final Duration MAXIMUM_TOTAL_TIMEOUT = Duration.ofSeconds(15);

        private final byte[] body;
        private final String bodyHash;
        private final Duration connectTimeout;
        private final Duration totalTimeout;

        public Request(
            byte[] body,
            String bodyHash,
            Duration connectTimeout,
            Duration totalTimeout
        ) {
            Objects.requireNonNull(body, "body must not be null");
            if (body.length == 0
                || body.length > OpenAiResponsesProtocol.MAXIMUM_REQUEST_BYTES) {
                throw new IllegalArgumentException("request body must be non-empty and bounded");
            }
            this.body = Arrays.copyOf(body, body.length);
            this.bodyHash = OpenAiResponsesProtocol.requireSha256(bodyHash, "bodyHash");
            if (!this.bodyHash.equals(OpenAiResponsesProtocol.sha256(this.body))) {
                throw new IllegalArgumentException("bodyHash must match the request body");
            }
            this.connectTimeout = requireTimeout(
                connectTimeout,
                MAXIMUM_CONNECT_TIMEOUT,
                "connectTimeout"
            );
            this.totalTimeout = requireTimeout(
                totalTimeout,
                MAXIMUM_TOTAL_TIMEOUT,
                "totalTimeout"
            );
            if (this.connectTimeout.compareTo(this.totalTimeout) > 0) {
                throw new IllegalArgumentException(
                    "connectTimeout must not exceed totalTimeout"
                );
            }
        }

        public byte[] bodyCopy() {
            return Arrays.copyOf(body, body.length);
        }

        public int bodyLength() {
            return body.length;
        }

        public String bodyHash() {
            return bodyHash;
        }

        public Duration connectTimeout() {
            return connectTimeout;
        }

        public Duration totalTimeout() {
            return totalTimeout;
        }

        @Override
        public String toString() {
            return "OpenAiResponsesTransportPort.Request[bodyHash="
                + bodyHash + ", bodyLength=" + body.length + ", connectTimeout="
                + connectTimeout + ", totalTimeout=" + totalTimeout + "]";
        }
    }

    final class Response {

        private final int statusCode;
        private final String requestId;
        private final byte[] body;

        public Response(int statusCode, String requestId, byte[] body) {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode must be a valid HTTP status");
            }
            Objects.requireNonNull(body, "body must not be null");
            if (body.length > OpenAiResponsesProtocol.MAXIMUM_TRANSPORT_RESPONSE_BYTES) {
                throw new IllegalArgumentException("transport response body must be bounded");
            }
            this.statusCode = statusCode;
            this.requestId = requestId;
            this.body = Arrays.copyOf(body, body.length);
        }

        public int statusCode() {
            return statusCode;
        }

        public String requestId() {
            return requestId;
        }

        public byte[] bodyCopy() {
            return Arrays.copyOf(body, body.length);
        }

        public int bodyLength() {
            return body.length;
        }

        @Override
        public String toString() {
            String requestIdEvidence = requestId == null || requestId.isBlank()
                ? "missing"
                : OpenAiResponsesProtocol.sha256Utf8(requestId);
            return "OpenAiResponsesTransportPort.Response[statusCode="
                + statusCode + ", requestIdHash=" + requestIdEvidence
                + ", bodyLength=" + body.length + "]";
        }
    }

    private static Duration requireTimeout(
        Duration value,
        Duration maximum,
        String name
    ) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be positive and bounded");
        }
        return value;
    }
}
