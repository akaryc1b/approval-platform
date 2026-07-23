package io.github.akaryc1b.approval.sdk.v1;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure request-budget, response-mapping and retry policy for transport adapters. */
public final class SdkTransportPolicyV1 {
    public static final String POLICY_VERSION = "1";
    private static final int MAX_ATTEMPTS_LIMIT = 10;
    private static final long MAX_TOTAL_BUDGET_MILLIS = 300_000L;

    private SdkTransportPolicyV1() {
    }

    public enum RetryMode {
        NEVER,
        IDEMPOTENT
    }

    public enum ResponseCategory {
        SUCCESS,
        RETRYABLE,
        PERMANENT,
        UNAUTHORIZED,
        CONFLICT,
        EXPIRED,
        UNSUPPORTED_VERSION,
        MALFORMED_RESPONSE
    }

    public enum DecisionAction {
        COMPLETE,
        RETRY,
        FAIL
    }

    public enum DecisionReason {
        SUCCESS,
        RETRY_SCHEDULED,
        NON_RETRYABLE,
        RETRY_DISABLED,
        IDEMPOTENCY_REQUIRED,
        MAX_ATTEMPTS_EXHAUSTED,
        BUDGET_EXHAUSTED
    }

    public enum ExecutionStatus {
        SUCCEEDED,
        FAILED,
        BUDGET_EXHAUSTED,
        ATTEMPTS_EXHAUSTED
    }

    public record RequestBudget(
        int maxAttempts,
        long totalBudgetMillis,
        long attemptTimeoutMillis,
        long baseBackoffMillis,
        long maxBackoffMillis
    ) {
        public RequestBudget {
            if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
                throw new IllegalArgumentException("maxAttempts must be between 1 and " + MAX_ATTEMPTS_LIMIT);
            }
            if (totalBudgetMillis < 1 || totalBudgetMillis > MAX_TOTAL_BUDGET_MILLIS) {
                throw new IllegalArgumentException(
                    "totalBudgetMillis must be between 1 and " + MAX_TOTAL_BUDGET_MILLIS
                );
            }
            if (attemptTimeoutMillis < 1 || attemptTimeoutMillis > totalBudgetMillis) {
                throw new IllegalArgumentException("attemptTimeoutMillis must fit within totalBudgetMillis");
            }
            if (baseBackoffMillis < 0 || maxBackoffMillis < baseBackoffMillis) {
                throw new IllegalArgumentException("backoff bounds are invalid");
            }
        }
    }

    public record OperationPolicy(
        String policyVersion,
        String operation,
        RetryMode retryMode,
        RequestBudget budget,
        List<Integer> retryableStatusCodes
    ) {
        public OperationPolicy {
            requirePolicyVersion(policyVersion);
            operation = required(operation, "policy.operation");
            retryMode = Objects.requireNonNull(retryMode, "policy.retryMode");
            budget = Objects.requireNonNull(budget, "policy.budget");
            retryableStatusCodes = uniqueStatusCodes(retryableStatusCodes);
        }
    }

    /** Adapter response metadata is untrusted and contains no tenant, operator or authority evidence. */
    public record AdapterResponse(
        int statusCode,
        Object payload,
        String errorCode,
        String errorMessage,
        Long retryAfterMillis
    ) {
        public AdapterResponse {
            if (retryAfterMillis != null && retryAfterMillis < 0) {
                throw new IllegalArgumentException("retryAfterMillis cannot be negative");
            }
        }
    }

    public record ResponseClassification(
        ResponseCategory category,
        boolean retryable,
        Object value,
        ApprovalSdk.Error error,
        Long retryAfterMillis
    ) {
        public ResponseClassification {
            category = Objects.requireNonNull(category, "category");
            if (category == ResponseCategory.SUCCESS) {
                if (error != null || retryable) {
                    throw new IllegalArgumentException("Successful classification cannot contain an error or retry flag");
                }
            } else if (error == null) {
                throw new IllegalArgumentException("Failure classification requires a structured error");
            }
        }
    }

    public record AttemptContext(int attempt, long elapsedMillis, boolean idempotencyKeyPresent) {
        public AttemptContext {
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            if (elapsedMillis < 0) {
                throw new IllegalArgumentException("elapsedMillis cannot be negative");
            }
        }
    }

    public record RetryDecision(
        DecisionAction action,
        DecisionReason reason,
        int nextAttempt,
        long delayMillis,
        long timeoutMillis,
        ResponseCategory terminalCategory
    ) {
        public RetryDecision {
            action = Objects.requireNonNull(action, "action");
            reason = Objects.requireNonNull(reason, "reason");
            if (delayMillis < 0 || timeoutMillis < 0 || nextAttempt < 0) {
                throw new IllegalArgumentException("Retry decision values cannot be negative");
            }
        }
    }

    public record TransportAttempt(
        ApprovalSdk.Request request,
        int attempt,
        long timeoutMillis,
        long elapsedMillis
    ) {
        public TransportAttempt {
            request = Objects.requireNonNull(request, "request");
            if (attempt < 1 || timeoutMillis < 1 || elapsedMillis < 0) {
                throw new IllegalArgumentException("Transport attempt values are invalid");
            }
        }
    }

    public record AdapterExchange(AdapterResponse response, long durationMillis) {
        public AdapterExchange {
            response = Objects.requireNonNull(response, "response");
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis cannot be negative");
            }
        }
    }

    public interface Adapter {
        AdapterExchange exchange(TransportAttempt attempt);
    }

    public record AttemptTrace(
        int attempt,
        long timeoutMillis,
        long durationMillis,
        long elapsedAfterAttemptMillis,
        ResponseCategory category,
        long scheduledDelayMillis
    ) {
        public AttemptTrace {
            category = Objects.requireNonNull(category, "category");
        }
    }

    public record ExecutionResult(
        ExecutionStatus status,
        Object value,
        ApprovalSdk.Error error,
        List<AttemptTrace> attempts,
        long totalElapsedMillis
    ) {
        public ExecutionResult {
            status = Objects.requireNonNull(status, "status");
            attempts = List.copyOf(attempts);
            if (totalElapsedMillis < 0) {
                throw new IllegalArgumentException("totalElapsedMillis cannot be negative");
            }
            if (status == ExecutionStatus.SUCCEEDED && error != null) {
                throw new IllegalArgumentException("Successful execution cannot contain an error");
            }
            if (status != ExecutionStatus.SUCCEEDED && error == null) {
                throw new IllegalArgumentException("Failed execution requires a structured error");
            }
        }

        public boolean successful() {
            return status == ExecutionStatus.SUCCEEDED;
        }
    }

    /** Deterministic, in-memory adapter for conformance tests; it never performs I/O. */
    public static final class ScriptedAdapter implements Adapter {
        private final ArrayDeque<AdapterExchange> script;
        private final List<TransportAttempt> invocations = new ArrayList<>();

        public ScriptedAdapter(List<AdapterExchange> script) {
            Objects.requireNonNull(script, "script");
            if (script.isEmpty()) {
                throw new IllegalArgumentException("script must contain at least one exchange");
            }
            this.script = new ArrayDeque<>(script);
        }

        @Override
        public synchronized AdapterExchange exchange(TransportAttempt attempt) {
            invocations.add(Objects.requireNonNull(attempt, "attempt"));
            AdapterExchange exchange = script.pollFirst();
            if (exchange == null) {
                return new AdapterExchange(
                    new AdapterResponse(
                        0,
                        null,
                        "adapter_script_exhausted",
                        "Adapter script has no response for this attempt",
                        null
                    ),
                    0
                );
            }
            return exchange;
        }

        public synchronized List<TransportAttempt> invocations() {
            return List.copyOf(invocations);
        }
    }

    public static ResponseClassification classify(
        OperationPolicy policy,
        AdapterResponse response,
        String requestId
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(response, "response");
        String requiredRequestId = required(requestId, "requestId");
        int statusCode = response.statusCode();
        if (statusCode < 100 || statusCode > 599) {
            return failure(
                ResponseCategory.MALFORMED_RESPONSE,
                false,
                response,
                requiredRequestId,
                ApprovalSdk.ErrorCategory.PERMANENT,
                "transport_malformed_response",
                "Adapter response status is outside the supported range"
            );
        }
        if (statusCode >= 200 && statusCode <= 299) {
            return new ResponseClassification(
                ResponseCategory.SUCCESS,
                false,
                response.payload(),
                null,
                null
            );
        }
        if (policy.retryableStatusCodes().contains(statusCode)) {
            return failure(
                ResponseCategory.RETRYABLE,
                true,
                response,
                requiredRequestId,
                ApprovalSdk.ErrorCategory.RETRYABLE,
                defaultCode(response, statusCode),
                defaultMessage(response, statusCode)
            );
        }
        if (statusCode == 401 || statusCode == 403) {
            return failure(
                ResponseCategory.UNAUTHORIZED,
                false,
                response,
                requiredRequestId,
                ApprovalSdk.ErrorCategory.UNAUTHORIZED,
                defaultCode(response, statusCode),
                defaultMessage(response, statusCode)
            );
        }
        if (statusCode == 409) {
            return failure(
                ResponseCategory.CONFLICT,
                false,
                response,
                requiredRequestId,
                ApprovalSdk.ErrorCategory.CONFLICT,
                defaultCode(response, statusCode),
                defaultMessage(response, statusCode)
            );
        }
        if (statusCode == 410) {
            return failure(
                ResponseCategory.EXPIRED,
                false,
                response,
                requiredRequestId,
                ApprovalSdk.ErrorCategory.EXPIRED,
                defaultCode(response, statusCode),
                defaultMessage(response, statusCode)
            );
        }
        if (statusCode == 426) {
            return failure(
                ResponseCategory.UNSUPPORTED_VERSION,
                false,
                response,
                requiredRequestId,
                ApprovalSdk.ErrorCategory.UNSUPPORTED_VERSION,
                defaultCode(response, statusCode),
                defaultMessage(response, statusCode)
            );
        }
        return failure(
            ResponseCategory.PERMANENT,
            false,
            response,
            requiredRequestId,
            ApprovalSdk.ErrorCategory.PERMANENT,
            defaultCode(response, statusCode),
            defaultMessage(response, statusCode)
        );
    }

    public static RetryDecision decide(
        OperationPolicy policy,
        AttemptContext context,
        ResponseClassification classification
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(classification, "classification");
        if (classification.category() == ResponseCategory.SUCCESS) {
            return new RetryDecision(
                DecisionAction.COMPLETE,
                DecisionReason.SUCCESS,
                0,
                0,
                0,
                ResponseCategory.SUCCESS
            );
        }
        if (!classification.retryable()) {
            return failed(DecisionReason.NON_RETRYABLE, classification.category());
        }
        if (policy.retryMode() == RetryMode.NEVER) {
            return failed(DecisionReason.RETRY_DISABLED, classification.category());
        }
        if (!context.idempotencyKeyPresent()) {
            return failed(DecisionReason.IDEMPOTENCY_REQUIRED, classification.category());
        }
        if (context.attempt() >= policy.budget().maxAttempts()) {
            return failed(DecisionReason.MAX_ATTEMPTS_EXHAUSTED, classification.category());
        }

        long delay = exponentialBackoff(policy.budget(), context.attempt());
        if (classification.retryAfterMillis() != null) {
            delay = Math.max(delay, classification.retryAfterMillis());
        }
        long remainingBeforeDelay = remaining(policy.budget(), context.elapsedMillis());
        if (delay >= remainingBeforeDelay) {
            return failed(DecisionReason.BUDGET_EXHAUSTED, classification.category());
        }
        long remainingAfterDelay = remainingBeforeDelay - delay;
        long timeout = Math.min(policy.budget().attemptTimeoutMillis(), remainingAfterDelay);
        if (timeout < 1) {
            return failed(DecisionReason.BUDGET_EXHAUSTED, classification.category());
        }
        return new RetryDecision(
            DecisionAction.RETRY,
            DecisionReason.RETRY_SCHEDULED,
            context.attempt() + 1,
            delay,
            timeout,
            null
        );
    }

    public static ExecutionResult execute(
        ApprovalSdk.Request request,
        OperationPolicy policy,
        Adapter adapter
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(adapter, "adapter");
        if (!policy.operation().equals(request.operation())) {
            throw new IllegalArgumentException("Request operation does not match transport policy");
        }

        long elapsed = 0;
        int attempt = 1;
        List<AttemptTrace> traces = new ArrayList<>();
        while (true) {
            long timeout = Math.min(
                policy.budget().attemptTimeoutMillis(),
                remaining(policy.budget(), elapsed)
            );
            if (timeout < 1) {
                return terminal(
                    ExecutionStatus.BUDGET_EXHAUSTED,
                    request,
                    traces,
                    elapsed,
                    "transport_budget_exhausted",
                    "Transport request budget is exhausted",
                    ApprovalSdk.ErrorCategory.EXPIRED
                );
            }

            TransportAttempt transportAttempt = new TransportAttempt(request, attempt, timeout, elapsed);
            AdapterExchange exchange;
            try {
                exchange = adapter.exchange(transportAttempt);
            } catch (RuntimeException exception) {
                exchange = new AdapterExchange(
                    new AdapterResponse(
                        0,
                        null,
                        "adapter_exception",
                        "Adapter raised an exception",
                        null
                    ),
                    0
                );
            }
            if (exchange == null) {
                exchange = new AdapterExchange(
                    new AdapterResponse(
                        0,
                        null,
                        "adapter_null_exchange",
                        "Adapter returned no exchange",
                        null
                    ),
                    0
                );
            }

            ResponseClassification classification;
            long duration;
            if (exchange.durationMillis() > timeout) {
                duration = timeout;
                classification = timeoutClassification(request.correlation().requestId());
            } else {
                duration = exchange.durationMillis();
                classification = classify(policy, exchange.response(), request.correlation().requestId());
            }
            elapsed += duration;
            RetryDecision decision = decide(
                policy,
                new AttemptContext(attempt, elapsed, !request.idempotencyKey().isEmpty()),
                classification
            );
            traces.add(new AttemptTrace(
                attempt,
                timeout,
                duration,
                elapsed,
                classification.category(),
                decision.action() == DecisionAction.RETRY ? decision.delayMillis() : 0
            ));

            if (decision.action() == DecisionAction.COMPLETE) {
                return new ExecutionResult(
                    ExecutionStatus.SUCCEEDED,
                    classification.value(),
                    null,
                    traces,
                    elapsed
                );
            }
            if (decision.action() == DecisionAction.RETRY) {
                elapsed += decision.delayMillis();
                attempt = decision.nextAttempt();
                continue;
            }
            if (decision.reason() == DecisionReason.BUDGET_EXHAUSTED) {
                return terminal(
                    ExecutionStatus.BUDGET_EXHAUSTED,
                    request,
                    traces,
                    elapsed,
                    "transport_budget_exhausted",
                    "Transport request budget cannot accommodate another attempt",
                    ApprovalSdk.ErrorCategory.EXPIRED
                );
            }
            if (decision.reason() == DecisionReason.MAX_ATTEMPTS_EXHAUSTED) {
                return terminal(
                    ExecutionStatus.ATTEMPTS_EXHAUSTED,
                    request,
                    traces,
                    elapsed,
                    "transport_attempts_exhausted",
                    "Transport retry attempts are exhausted",
                    ApprovalSdk.ErrorCategory.RETRYABLE
                );
            }
            return new ExecutionResult(
                ExecutionStatus.FAILED,
                null,
                classification.error(),
                traces,
                elapsed
            );
        }
    }

    private static ResponseClassification timeoutClassification(String requestId) {
        return new ResponseClassification(
            ResponseCategory.RETRYABLE,
            true,
            null,
            new ApprovalSdk.Error(
                "transport_attempt_timeout",
                "Transport attempt exceeded its timeout budget",
                ApprovalSdk.ErrorCategory.RETRYABLE,
                requestId
            ),
            null
        );
    }

    private static ExecutionResult terminal(
        ExecutionStatus status,
        ApprovalSdk.Request request,
        List<AttemptTrace> traces,
        long elapsed,
        String code,
        String message,
        ApprovalSdk.ErrorCategory category
    ) {
        return new ExecutionResult(
            status,
            null,
            new ApprovalSdk.Error(code, message, category, request.correlation().requestId()),
            traces,
            elapsed
        );
    }

    private static RetryDecision failed(DecisionReason reason, ResponseCategory category) {
        return new RetryDecision(DecisionAction.FAIL, reason, 0, 0, 0, category);
    }

    private static ResponseClassification failure(
        ResponseCategory category,
        boolean retryable,
        AdapterResponse response,
        String requestId,
        ApprovalSdk.ErrorCategory errorCategory,
        String code,
        String message
    ) {
        return new ResponseClassification(
            category,
            retryable,
            null,
            new ApprovalSdk.Error(code, message, errorCategory, requestId),
            response.retryAfterMillis()
        );
    }

    private static String defaultCode(AdapterResponse response, int statusCode) {
        return nonEmpty(response.errorCode(), "transport_status_" + statusCode);
    }

    private static String defaultMessage(AdapterResponse response, int statusCode) {
        return nonEmpty(response.errorMessage(), "Transport response status " + statusCode);
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static long exponentialBackoff(RequestBudget budget, int completedAttempt) {
        long value = budget.baseBackoffMillis();
        for (int index = 1; index < completedAttempt && value < budget.maxBackoffMillis(); index++) {
            if (value > budget.maxBackoffMillis() / 2) {
                return budget.maxBackoffMillis();
            }
            value *= 2;
        }
        return Math.min(value, budget.maxBackoffMillis());
    }

    private static long remaining(RequestBudget budget, long elapsedMillis) {
        if (elapsedMillis >= budget.totalBudgetMillis()) {
            return 0;
        }
        return budget.totalBudgetMillis() - elapsedMillis;
    }

    private static List<Integer> uniqueStatusCodes(List<Integer> values) {
        Objects.requireNonNull(values, "policy.retryableStatusCodes");
        Set<Integer> unique = new HashSet<>();
        List<Integer> output = new ArrayList<>();
        for (Integer value : values) {
            int status = Objects.requireNonNull(value, "retryable status code");
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("Retryable status code is outside the supported range: " + status);
            }
            if (!unique.add(status)) {
                throw new IllegalArgumentException("Duplicate retryable status code: " + status);
            }
            output.add(status);
        }
        return List.copyOf(output);
    }

    private static void requirePolicyVersion(String value) {
        if (!POLICY_VERSION.equals(value)) {
            throw new UnsupportedPolicyVersionException(value);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value;
    }

    public static final class UnsupportedPolicyVersionException extends IllegalArgumentException {
        private final String policyVersion;

        public UnsupportedPolicyVersionException(String policyVersion) {
            super("Unsupported transport policy version: " + policyVersion);
            this.policyVersion = policyVersion;
        }

        public String policyVersion() {
            return policyVersion;
        }
    }
}
