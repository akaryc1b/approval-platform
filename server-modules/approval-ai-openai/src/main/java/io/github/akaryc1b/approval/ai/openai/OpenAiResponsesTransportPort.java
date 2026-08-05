package io.github.akaryc1b.approval.ai.openai;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/** P6-C/P6-D bounded transport contract and hash-only connection evidence. */
public interface OpenAiResponsesTransportPort {

    Response exchange(Request request);

    final class Request {

        private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofSeconds(2);
        private static final Duration MAXIMUM_TOTAL_TIMEOUT = Duration.ofSeconds(15);

        private final byte[] body;
        private final String bodyHash;
        private final Duration connectTimeout;
        private final Duration totalTimeout;
        private final CancellationSignal cancellationSignal;

        public Request(
            byte[] body,
            String bodyHash,
            Duration connectTimeout,
            Duration totalTimeout
        ) {
            this(
                body,
                bodyHash,
                connectTimeout,
                totalTimeout,
                CancellationSignal.never()
            );
        }

        public Request(
            byte[] body,
            String bodyHash,
            Duration connectTimeout,
            Duration totalTimeout,
            CancellationSignal cancellationSignal
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
            this.cancellationSignal = Objects.requireNonNull(
                cancellationSignal,
                "cancellationSignal must not be null"
            );
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

        public boolean cancelled() {
            return cancellationSignal.cancelled();
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
        private final TransportEvidence transportEvidence;

        public Response(int statusCode, String requestId, byte[] body) {
            this(statusCode, requestId, body, TransportEvidence.unavailable());
        }

        public Response(
            int statusCode,
            String requestId,
            byte[] body,
            TransportEvidence transportEvidence
        ) {
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
            this.transportEvidence = Objects.requireNonNull(
                transportEvidence,
                "transportEvidence must not be null"
            );
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

        public TransportEvidence transportEvidence() {
            return transportEvidence;
        }

        @Override
        public String toString() {
            String requestIdEvidence = requestId == null || requestId.isBlank()
                ? "missing"
                : OpenAiResponsesProtocol.sha256Utf8(requestId);
            return "OpenAiResponsesTransportPort.Response[statusCode="
                + statusCode + ", requestIdHash=" + requestIdEvidence
                + ", bodyLength=" + body.length + ", transportEvidenceHash="
                + transportEvidence.evidenceHash() + "]";
        }
    }

    record TransportEvidence(
        String endpointHash,
        String admissionHash,
        String dnsResolutionHash,
        String connectedAddressHash,
        String tlsPeerHash,
        String clientRequestIdHash,
        String responseBodyHash,
        int attemptCount,
        boolean tlsVerified,
        boolean redirectObserved,
        String evidenceHash
    ) {
        private static final String UNAVAILABLE_HASH = "0".repeat(64);

        public TransportEvidence {
            endpointHash = requireEvidenceHash(endpointHash, "endpointHash");
            admissionHash = requireEvidenceHash(admissionHash, "admissionHash");
            dnsResolutionHash = requireEvidenceHash(
                dnsResolutionHash,
                "dnsResolutionHash"
            );
            connectedAddressHash = requireEvidenceHash(
                connectedAddressHash,
                "connectedAddressHash"
            );
            tlsPeerHash = requireEvidenceHash(tlsPeerHash, "tlsPeerHash");
            clientRequestIdHash = requireEvidenceHash(
                clientRequestIdHash,
                "clientRequestIdHash"
            );
            responseBodyHash = requireEvidenceHash(
                responseBodyHash,
                "responseBodyHash"
            );
            if (attemptCount < 0 || attemptCount > 1) {
                throw new IllegalArgumentException("attemptCount must be zero or one");
            }
            if (redirectObserved) {
                throw new IllegalArgumentException("redirect evidence is prohibited");
            }
            evidenceHash = requireEvidenceHash(evidenceHash, "evidenceHash");
            boolean allUnavailable = UNAVAILABLE_HASH.equals(endpointHash)
                && UNAVAILABLE_HASH.equals(admissionHash)
                && UNAVAILABLE_HASH.equals(dnsResolutionHash)
                && UNAVAILABLE_HASH.equals(connectedAddressHash)
                && UNAVAILABLE_HASH.equals(tlsPeerHash)
                && UNAVAILABLE_HASH.equals(clientRequestIdHash)
                && UNAVAILABLE_HASH.equals(responseBodyHash)
                && UNAVAILABLE_HASH.equals(evidenceHash);
            if (!tlsVerified) {
                if (attemptCount != 0 || !allUnavailable) {
                    throw new IllegalArgumentException(
                        "unverified transport evidence must be fully unavailable"
                    );
                }
            } else {
                if (attemptCount != 1 || allUnavailable
                    || UNAVAILABLE_HASH.equals(endpointHash)
                    || UNAVAILABLE_HASH.equals(admissionHash)
                    || UNAVAILABLE_HASH.equals(dnsResolutionHash)
                    || UNAVAILABLE_HASH.equals(connectedAddressHash)
                    || UNAVAILABLE_HASH.equals(tlsPeerHash)
                    || UNAVAILABLE_HASH.equals(clientRequestIdHash)
                    || UNAVAILABLE_HASH.equals(responseBodyHash)) {
                    throw new IllegalArgumentException(
                        "verified transport evidence requires one complete attempt"
                    );
                }
                String expectedEvidence = evidenceHash(
                    endpointHash,
                    admissionHash,
                    dnsResolutionHash,
                    connectedAddressHash,
                    tlsPeerHash,
                    clientRequestIdHash,
                    responseBodyHash
                );
                if (!expectedEvidence.equals(evidenceHash)) {
                    throw new IllegalArgumentException(
                        "evidenceHash must match the exact transport evidence"
                    );
                }
            }
        }

        public static TransportEvidence verified(
            String endpointHash,
            String admissionHash,
            String dnsResolutionHash,
            String connectedAddressHash,
            String tlsPeerHash,
            String clientRequestIdHash,
            String responseBodyHash
        ) {
            String evidence = evidenceHash(
                endpointHash,
                admissionHash,
                dnsResolutionHash,
                connectedAddressHash,
                tlsPeerHash,
                clientRequestIdHash,
                responseBodyHash
            );
            return new TransportEvidence(
                endpointHash,
                admissionHash,
                dnsResolutionHash,
                connectedAddressHash,
                tlsPeerHash,
                clientRequestIdHash,
                responseBodyHash,
                1,
                true,
                false,
                evidence
            );
        }

        public static TransportEvidence unavailable() {
            return new TransportEvidence(
                UNAVAILABLE_HASH,
                UNAVAILABLE_HASH,
                UNAVAILABLE_HASH,
                UNAVAILABLE_HASH,
                UNAVAILABLE_HASH,
                UNAVAILABLE_HASH,
                UNAVAILABLE_HASH,
                0,
                false,
                false,
                UNAVAILABLE_HASH
            );
        }

        public boolean verified() {
            return tlsVerified && attemptCount == 1;
        }

        private static String evidenceHash(
            String endpointHash,
            String admissionHash,
            String dnsResolutionHash,
            String connectedAddressHash,
            String tlsPeerHash,
            String clientRequestIdHash,
            String responseBodyHash
        ) {
            return OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-transport-evidence-v1",
                endpointHash,
                admissionHash,
                dnsResolutionHash,
                connectedAddressHash,
                tlsPeerHash,
                clientRequestIdHash,
                responseBodyHash,
                "1",
                "true",
                "false"
            ));
        }

        private static String requireEvidenceHash(String value, String name) {
            if (UNAVAILABLE_HASH.equals(value)) {
                return value;
            }
            return OpenAiResponsesProtocol.requireSha256(value, name);
        }
    }

    @FunctionalInterface
    interface CancellationSignal {

        boolean cancelled();

        static CancellationSignal never() {
            return () -> false;
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
