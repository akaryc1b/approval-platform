package io.github.akaryc1b.approval.connector.testing;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorError;
import io.github.akaryc1b.approval.connector.contract.ConnectorExecutionPort;
import io.github.akaryc1b.approval.connector.contract.ConnectorOutcome;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorResult;
import io.github.akaryc1b.approval.connector.contract.IdempotencyEvidence;
import io.github.akaryc1b.approval.connector.contract.IdempotencyEvidence.IdempotencyResult;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.ProviderFailureClass;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test adapter with deterministic outcomes and no external side effects.
 */
public final class DeterministicMockConnector
    implements ConnectorExecutionPort<DeterministicMockConnector.MockCommand,
    DeterministicMockConnector.MockResponse> {

    public static final String PROVIDER_KEY = "deterministic-mock";

    private final String expectedTenantId;
    private final Clock clock;
    private final ProviderDescriptor descriptor;
    private final Map<String, StoredExecution> executions = new ConcurrentHashMap<>();

    public DeterministicMockConnector(String expectedTenantId, Clock clock) {
        this.expectedTenantId = requireText(expectedTenantId, "expectedTenantId");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.descriptor = new ProviderDescriptor(
            PROVIDER_KEY,
            ProviderDescriptor.ProviderType.TEST,
            "m6-a.v1",
            Set.of(
                ConnectorProvider.Capability.AUTHENTICATION,
                ConnectorProvider.Capability.ORGANIZATION,
                ConnectorProvider.Capability.NOTIFICATION,
                ConnectorProvider.Capability.EXTERNAL_TODO,
                ConnectorProvider.Capability.BUSINESS_CALLBACK
            ),
            ProviderDescriptor.ProviderState.ENABLED,
            Map.of("adapter", "deterministic", "network", "disabled")
        );
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult<MockResponse> execute(
        TrustedConnectorExecutionContext context,
        ConnectorRequest<MockCommand> request
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!PROVIDER_KEY.equals(context.providerKey())) {
            throw new IllegalArgumentException("trusted context targets another provider");
        }
        descriptor.requireEnabledCapability(request.operation().requiredCapability());
        if (!expectedTenantId.equals(context.tenantId())) {
            return failure(
                request,
                ConnectorOutcome.REJECTED,
                ProviderFailureClass.AUTHORIZATION,
                "TENANT_BOUNDARY_VIOLATION",
                "trusted tenant is not assigned to this mock provider",
                403,
                IdempotencyResult.FIRST_SEEN,
                Map.of()
            );
        }
        if (request.securityEvidence() != null
            && !request.securityEvidence().timestampIsValidAt(context.requestedAt())) {
            return failure(
                request,
                ConnectorOutcome.REJECTED,
                ProviderFailureClass.AUTHENTICATION,
                "SIGNING_WINDOW_EXPIRED",
                "connector signing timestamp is outside the validity window",
                401,
                IdempotencyResult.FIRST_SEEN,
                Map.of()
            );
        }

        StoredExecution stored = executions.get(request.idempotencyKey());
        if (stored != null) {
            return replayOrConflict(request, stored);
        }
        ConnectorResult<MockResponse> first = executeScenario(request);
        StoredExecution previous = executions.putIfAbsent(
            request.idempotencyKey(),
            new StoredExecution(request.canonicalPayloadHash(), first)
        );
        return previous == null ? first : replayOrConflict(request, previous);
    }

    public int uniqueExecutionCount() {
        return executions.size();
    }

    private ConnectorResult<MockResponse> replayOrConflict(
        ConnectorRequest<MockCommand> request,
        StoredExecution stored
    ) {
        if (!stored.requestHash().equals(request.canonicalPayloadHash())) {
            return failure(
                request,
                ConnectorOutcome.REJECTED,
                ProviderFailureClass.VALIDATION,
                "IDEMPOTENCY_CONFLICT",
                "idempotency key was already used with a different payload hash",
                409,
                IdempotencyResult.CONFLICT,
                Map.of()
            );
        }
        return stored.result().withIdempotencyEvidence(new IdempotencyEvidence(
            request.idempotencyKey(),
            request.canonicalPayloadHash(),
            IdempotencyResult.REPLAYED_SAME_RESULT
        ));
    }

    private ConnectorResult<MockResponse> executeScenario(ConnectorRequest<MockCommand> request) {
        return switch (request.payload().scenario()) {
            case SUCCESS -> ConnectorResult.success(
                new MockResponse(
                    request.payload().commandId(),
                    "accepted:" + request.payload().content()
                ),
                providerResult(request, 200, Map.of()),
                idempotency(request, IdempotencyResult.FIRST_SEEN),
                request.securityEvidence()
            );
            case REJECTED -> failure(
                request,
                ConnectorOutcome.REJECTED,
                ProviderFailureClass.VALIDATION,
                "MOCK_REJECTED",
                "deterministic mock rejection",
                422,
                IdempotencyResult.FIRST_SEEN,
                Map.of()
            );
            case RATE_LIMITED -> failure(
                request,
                ConnectorOutcome.RATE_LIMITED,
                ProviderFailureClass.RATE_LIMIT,
                "MOCK_RATE_LIMITED",
                "deterministic mock rate limit",
                429,
                IdempotencyResult.FIRST_SEEN,
                Map.of("retryAfterSeconds", "30")
            );
            case RETRYABLE_FAILURE -> failure(
                request,
                ConnectorOutcome.RETRYABLE_PROVIDER_FAILURE,
                ProviderFailureClass.TRANSIENT,
                "MOCK_TRANSIENT_FAILURE",
                "deterministic mock transient failure",
                503,
                IdempotencyResult.FIRST_SEEN,
                Map.of()
            );
            case PERMANENT_FAILURE -> failure(
                request,
                ConnectorOutcome.PERMANENT_PROVIDER_FAILURE,
                ProviderFailureClass.PERMANENT,
                "MOCK_PERMANENT_FAILURE",
                "deterministic mock permanent failure",
                400,
                IdempotencyResult.FIRST_SEEN,
                Map.of()
            );
            case TIMEOUT -> failure(
                request,
                ConnectorOutcome.TIMEOUT,
                ProviderFailureClass.TIMEOUT,
                "MOCK_TIMEOUT",
                "deterministic mock timeout with uncertain remote completion",
                0,
                IdempotencyResult.FIRST_SEEN,
                Map.of()
            );
            case UNKNOWN -> failure(
                request,
                ConnectorOutcome.UNKNOWN,
                ProviderFailureClass.UNKNOWN,
                "MOCK_UNKNOWN_RESULT",
                "deterministic mock returned no authoritative result",
                0,
                IdempotencyResult.FIRST_SEEN,
                Map.of()
            );
        };
    }

    private ConnectorResult<MockResponse> failure(
        ConnectorRequest<MockCommand> request,
        ConnectorOutcome outcome,
        ProviderFailureClass failureClass,
        String code,
        String message,
        int statusCode,
        IdempotencyResult idempotencyResult,
        Map<String, String> details
    ) {
        return ConnectorResult.failure(
            outcome,
            providerResult(request, statusCode, Map.of()),
            idempotency(request, idempotencyResult),
            request.securityEvidence(),
            new ConnectorError(code, failureClass, message, details)
        );
    }

    private ConnectorProviderResult providerResult(
        ConnectorRequest<MockCommand> request,
        int statusCode,
        Map<String, String> metadata
    ) {
        String providerRequestId = "mock-" + CanonicalPayloadHash.sha256Utf8(
            request.idempotencyKey() + "\n" + request.canonicalPayloadHash()
        ).substring(0, 20);
        return new ConnectorProviderResult(
            providerRequestId,
            statusCode,
            clock.instant(),
            metadata
        );
    }

    private static IdempotencyEvidence idempotency(
        ConnectorRequest<MockCommand> request,
        IdempotencyResult result
    ) {
        return new IdempotencyEvidence(
            request.idempotencyKey(),
            request.canonicalPayloadHash(),
            result
        );
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1 to 128 characters");
        }
        return value;
    }

    private record StoredExecution(String requestHash, ConnectorResult<MockResponse> result) {
    }

    public record MockCommand(String commandId, Scenario scenario, String content) {
        public MockCommand {
            commandId = requireText(commandId, "commandId");
            scenario = Objects.requireNonNull(scenario, "scenario must not be null");
            content = requireText(content, "content");
        }

        public String canonicalPayload() {
            return scenario.name() + "\n" + commandId + "\n" + content;
        }
    }

    public record MockResponse(String commandId, String acknowledgement) {
        public MockResponse {
            commandId = requireText(commandId, "commandId");
            acknowledgement = requireText(acknowledgement, "acknowledgement");
        }
    }

    public enum Scenario {
        SUCCESS,
        REJECTED,
        RATE_LIMITED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE,
        TIMEOUT,
        UNKNOWN
    }
}
