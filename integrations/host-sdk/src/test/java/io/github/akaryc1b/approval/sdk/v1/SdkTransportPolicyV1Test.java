package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkTransportPolicyV1Test {
    @Test
    void crossLanguageFixtureProducesExactDeterministicTrace() throws IOException {
        Map<String, Object> fixture = TransportPolicyFixtureSupport.fixture();
        SdkTransportPolicyV1.OperationPolicy policy = policy(fixture);
        ApprovalSdk.Request request = request(fixture);
        SdkTransportPolicyV1.ScriptedAdapter adapter = new SdkTransportPolicyV1.ScriptedAdapter(script(fixture));

        SdkTransportPolicyV1.ExecutionResult result = SdkTransportPolicyV1.execute(request, policy, adapter);
        Map<String, Object> expected = TransportPolicyFixtureSupport.object(fixture, "expectations");
        assertEquals("succeeded", result.status().name().toLowerCase());
        assertEquals(expected.get("value"), result.value());
        assertEquals(((Number) expected.get("totalElapsedMillis")).longValue(), result.totalElapsedMillis());

        List<Object> expectedAttempts = TransportPolicyFixtureSupport.list(expected, "attempts");
        assertEquals(expectedAttempts.size(), result.attempts().size());
        for (int index = 0; index < expectedAttempts.size(); index++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> expectedAttempt = (Map<String, Object>) expectedAttempts.get(index);
            SdkTransportPolicyV1.AttemptTrace actual = result.attempts().get(index);
            assertEquals(((Number) expectedAttempt.get("attempt")).intValue(), actual.attempt());
            assertEquals(((Number) expectedAttempt.get("timeoutMillis")).longValue(), actual.timeoutMillis());
            assertEquals(((Number) expectedAttempt.get("durationMillis")).longValue(), actual.durationMillis());
            assertEquals(
                ((Number) expectedAttempt.get("elapsedAfterAttemptMillis")).longValue(),
                actual.elapsedAfterAttemptMillis()
            );
            assertEquals(expectedAttempt.get("category"), actual.category().name().toLowerCase());
            assertEquals(
                ((Number) expectedAttempt.get("scheduledDelayMillis")).longValue(),
                actual.scheduledDelayMillis()
            );
        }
        assertEquals(List.of(0L, 1000L), adapter.invocations().stream()
            .map(SdkTransportPolicyV1.TransportAttempt::elapsedMillis)
            .toList());
        assertTrue(adapter.invocations().stream().allMatch(invocation -> invocation.request() == request));
    }

    @Test
    void unknownVersionsDuplicateStatusesAndUnboundedBudgetsFailClosed() throws IOException {
        Map<String, Object> fixture = TransportPolicyFixtureSupport.fixture();
        SdkTransportPolicyV1.OperationPolicy valid = policy(fixture);
        assertThrows(
            SdkTransportPolicyV1.UnsupportedPolicyVersionException.class,
            () -> new SdkTransportPolicyV1.OperationPolicy(
                "2",
                valid.operation(),
                valid.retryMode(),
                valid.budget(),
                valid.retryableStatusCodes()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new SdkTransportPolicyV1.OperationPolicy(
                "1",
                valid.operation(),
                valid.retryMode(),
                valid.budget(),
                List.of(503, 503)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new SdkTransportPolicyV1.RequestBudget(3, 300_001, 2_000, 100, 1_000)
        );
    }

    @Test
    void responseMappingIsStructuredAndDefaultsPermanentFailures() throws IOException {
        SdkTransportPolicyV1.OperationPolicy policy = policy(TransportPolicyFixtureSupport.fixture());
        assertEquals(
            SdkTransportPolicyV1.ResponseCategory.RETRYABLE,
            SdkTransportPolicyV1.classify(policy, response(429, "temporary", "Retry", null), "req-1").category()
        );
        assertEquals(
            ApprovalSdk.ErrorCategory.UNAUTHORIZED,
            SdkTransportPolicyV1.classify(policy, response(403, "denied", "Denied", null), "req-1")
                .error().category()
        );
        assertEquals(
            SdkTransportPolicyV1.ResponseCategory.CONFLICT,
            SdkTransportPolicyV1.classify(policy, response(409, "conflict", "Conflict", null), "req-1")
                .category()
        );
        assertEquals(
            SdkTransportPolicyV1.ResponseCategory.UNSUPPORTED_VERSION,
            SdkTransportPolicyV1.classify(policy, response(426, "upgrade", "Upgrade", null), "req-1")
                .category()
        );
        ApprovalSdk.Error permanent = SdkTransportPolicyV1.classify(
            policy,
            response(422, null, null, null),
            "req-1"
        ).error();
        assertEquals("transport_status_422", permanent.code());
        assertEquals("Transport response status 422", permanent.message());
        assertEquals(ApprovalSdk.ErrorCategory.PERMANENT, permanent.category());
        assertEquals("req-1", permanent.requestId());
    }

    @Test
    void retryRequiresIdempotentPolicyAndIdempotencyKey() throws IOException {
        SdkTransportPolicyV1.OperationPolicy policy = policy(TransportPolicyFixtureSupport.fixture());
        SdkTransportPolicyV1.ResponseClassification classification = SdkTransportPolicyV1.classify(
            policy,
            response(503, "temporary", "Retry", null),
            "req-1"
        );
        SdkTransportPolicyV1.OperationPolicy never = new SdkTransportPolicyV1.OperationPolicy(
            "1",
            policy.operation(),
            SdkTransportPolicyV1.RetryMode.NEVER,
            policy.budget(),
            policy.retryableStatusCodes()
        );
        assertEquals(
            SdkTransportPolicyV1.DecisionReason.RETRY_DISABLED,
            SdkTransportPolicyV1.decide(
                never,
                new SdkTransportPolicyV1.AttemptContext(1, 100, true),
                classification
            ).reason()
        );
        assertEquals(
            SdkTransportPolicyV1.DecisionReason.IDEMPOTENCY_REQUIRED,
            SdkTransportPolicyV1.decide(
                policy,
                new SdkTransportPolicyV1.AttemptContext(1, 100, false),
                classification
            ).reason()
        );
    }

    @Test
    void retryAfterCannotExceedRemainingBudget() throws IOException {
        SdkTransportPolicyV1.OperationPolicy policy = policy(TransportPolicyFixtureSupport.fixture());
        SdkTransportPolicyV1.ResponseClassification classification = SdkTransportPolicyV1.classify(
            policy,
            response(503, "temporary", "Retry", 4_900L),
            "req-1"
        );
        SdkTransportPolicyV1.RetryDecision decision = SdkTransportPolicyV1.decide(
            policy,
            new SdkTransportPolicyV1.AttemptContext(1, 200, true),
            classification
        );
        assertEquals(SdkTransportPolicyV1.DecisionAction.FAIL, decision.action());
        assertEquals(SdkTransportPolicyV1.DecisionReason.BUDGET_EXHAUSTED, decision.reason());
    }

    @Test
    void attemptTimeoutsAreRetryableButAttemptsRemainBounded() throws IOException {
        SdkTransportPolicyV1.OperationPolicy fixturePolicy = policy(TransportPolicyFixtureSupport.fixture());
        SdkTransportPolicyV1.OperationPolicy policy = new SdkTransportPolicyV1.OperationPolicy(
            "1",
            fixturePolicy.operation(),
            SdkTransportPolicyV1.RetryMode.IDEMPOTENT,
            new SdkTransportPolicyV1.RequestBudget(2, 3_000, 1_000, 50, 50),
            fixturePolicy.retryableStatusCodes()
        );
        SdkTransportPolicyV1.AdapterExchange timeout = new SdkTransportPolicyV1.AdapterExchange(
            response(200, null, null, null),
            1_200
        );
        SdkTransportPolicyV1.ExecutionResult result = SdkTransportPolicyV1.execute(
            request(TransportPolicyFixtureSupport.fixture()),
            policy,
            new SdkTransportPolicyV1.ScriptedAdapter(List.of(timeout, timeout))
        );
        assertEquals(SdkTransportPolicyV1.ExecutionStatus.ATTEMPTS_EXHAUSTED, result.status());
        assertEquals("transport_attempts_exhausted", result.error().code());
        assertEquals(List.of(1_000L, 1_000L), result.attempts().stream()
            .map(SdkTransportPolicyV1.AttemptTrace::durationMillis)
            .toList());
        assertTrue(result.attempts().stream()
            .allMatch(attempt -> attempt.category() == SdkTransportPolicyV1.ResponseCategory.RETRYABLE));
    }

    @Test
    void operationMismatchFailsAndPolicyContainsNoTrustedEvidence() throws IOException {
        Map<String, Object> fixture = TransportPolicyFixtureSupport.fixture();
        SdkTransportPolicyV1.OperationPolicy policy = policy(fixture);
        ApprovalSdk.Request original = request(fixture);
        ApprovalSdk.Request mismatch = new ApprovalSdk.Request(
            "approval.task.complete",
            original.payload(),
            original.correlation(),
            original.idempotencyKey()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SdkTransportPolicyV1.execute(
                mismatch,
                policy,
                new SdkTransportPolicyV1.ScriptedAdapter(script(fixture))
            )
        );
        List<String> recordComponents = new ArrayList<>();
        for (var component : SdkTransportPolicyV1.OperationPolicy.class.getRecordComponents()) {
            recordComponents.add(component.getName());
        }
        for (String forbidden : List.of(
            "tenantId", "operator", "permission", "authority", "auditEvidence", "endpoint", "token"
        )) {
            assertFalse(recordComponents.contains(forbidden));
        }
    }

    private static SdkTransportPolicyV1.OperationPolicy policy(Map<String, Object> fixture) {
        Map<String, Object> value = TransportPolicyFixtureSupport.object(fixture, "policy");
        Map<String, Object> budget = TransportPolicyFixtureSupport.object(value, "budget");
        List<Integer> statuses = TransportPolicyFixtureSupport.list(value, "retryableStatusCodes").stream()
            .map(number -> ((Number) number).intValue())
            .toList();
        return new SdkTransportPolicyV1.OperationPolicy(
            (String) value.get("policyVersion"),
            (String) value.get("operation"),
            SdkTransportPolicyV1.RetryMode.valueOf(((String) value.get("retryMode")).toUpperCase()),
            new SdkTransportPolicyV1.RequestBudget(
                ((Number) budget.get("maxAttempts")).intValue(),
                ((Number) budget.get("totalBudgetMillis")).longValue(),
                ((Number) budget.get("attemptTimeoutMillis")).longValue(),
                ((Number) budget.get("baseBackoffMillis")).longValue(),
                ((Number) budget.get("maxBackoffMillis")).longValue()
            ),
            statuses
        );
    }

    private static ApprovalSdk.Request request(Map<String, Object> fixture) {
        Map<String, Object> value = TransportPolicyFixtureSupport.object(fixture, "request");
        Map<String, Object> correlation = TransportPolicyFixtureSupport.object(value, "correlation");
        return new ApprovalSdk.Request(
            (String) value.get("operation"),
            value.get("payload"),
            new ApprovalSdk.Correlation(
                (String) correlation.get("requestId"),
                (String) correlation.get("traceId")
            ),
            (String) value.get("idempotencyKey")
        );
    }

    private static List<SdkTransportPolicyV1.AdapterExchange> script(Map<String, Object> fixture) {
        List<SdkTransportPolicyV1.AdapterExchange> output = new ArrayList<>();
        for (Object raw : TransportPolicyFixtureSupport.list(fixture, "script")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> exchange = (Map<String, Object>) raw;
            Map<String, Object> response = TransportPolicyFixtureSupport.object(exchange, "response");
            output.add(new SdkTransportPolicyV1.AdapterExchange(
                new SdkTransportPolicyV1.AdapterResponse(
                    ((Number) response.get("statusCode")).intValue(),
                    response.get("payload"),
                    (String) response.get("errorCode"),
                    (String) response.get("errorMessage"),
                    response.get("retryAfterMillis") == null
                        ? null
                        : ((Number) response.get("retryAfterMillis")).longValue()
                ),
                ((Number) exchange.get("durationMillis")).longValue()
            ));
        }
        return output;
    }

    private static SdkTransportPolicyV1.AdapterResponse response(
        int status,
        String code,
        String message,
        Long retryAfter
    ) {
        return new SdkTransportPolicyV1.AdapterResponse(
            status,
            status == 200 ? Map.of("ok", true) : null,
            code,
            message,
            retryAfter
        );
    }
}
