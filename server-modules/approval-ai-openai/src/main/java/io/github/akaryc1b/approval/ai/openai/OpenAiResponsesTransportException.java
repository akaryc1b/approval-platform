package io.github.akaryc1b.approval.ai.openai;

import java.io.Serial;
import java.util.Objects;

/** Closed, body-free P6-D transport failure. */
public final class OpenAiResponsesTransportException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Failure failure;

    public OpenAiResponsesTransportException(Failure failure) {
        super(Objects.requireNonNull(failure, "failure must not be null").stableCode());
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }

    @Override
    public String toString() {
        return "OpenAiResponsesTransportException[failure=" + failure + "]";
    }

    public enum Failure {
        REQUEST_INVALID,
        CANCELLED,
        KILL_SWITCH_DISABLED,
        KILL_SWITCH_DRIFT,
        CIRCUIT_OPEN,
        RATE_LIMITED,
        COST_POLICY_STALE,
        COST_LIMIT_EXCEEDED,
        ENDPOINT_REJECTED,
        DNS_FAILURE,
        DNS_EMPTY,
        DNS_UNSAFE,
        DNS_DRIFT,
        CONNECTION_DRIFT,
        TLS_FAILURE,
        TLS_HOSTNAME_MISMATCH,
        TLS_CHAIN_INVALID,
        TLS_CERTIFICATE_EXPIRED,
        SECRET_UNAVAILABLE,
        HTTP_PROTOCOL_INVALID,
        REDIRECT_REJECTED,
        RESPONSE_TOO_LARGE,
        TIMEOUT,
        IO_FAILURE,
        UNKNOWN;

        public String stableCode() {
            return "OPENAI_RESPONSES_TRANSPORT_" + name();
        }
    }
}
