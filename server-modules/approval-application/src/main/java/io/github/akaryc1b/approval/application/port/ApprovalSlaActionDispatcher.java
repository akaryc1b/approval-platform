package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;

import java.util.Objects;

/** Dispatches one claimed SLA action outside the database claim transaction. */
@FunctionalInterface
public interface ApprovalSlaActionDispatcher {

    DispatchResult dispatch(ExecutionIntent intent);

    record DispatchResult(
        boolean successful,
        boolean retryable,
        String errorCode,
        String errorSummary
    ) {
        public DispatchResult {
            if (successful) {
                if (retryable || errorCode != null || errorSummary != null) {
                    throw new IllegalArgumentException(
                        "successful dispatch must not retain failure evidence"
                    );
                }
            } else {
                errorCode = requireText(errorCode, "errorCode", 128);
                errorSummary = requireText(errorSummary, "errorSummary", 1000);
            }
        }

        public static DispatchResult succeeded() {
            return new DispatchResult(true, false, null, null);
        }

        public static DispatchResult retryableFailure(String code, String summary) {
            return new DispatchResult(false, true, code, summary);
        }

        public static DispatchResult permanentFailure(String code, String summary) {
            return new DispatchResult(false, false, code, summary);
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }
}
