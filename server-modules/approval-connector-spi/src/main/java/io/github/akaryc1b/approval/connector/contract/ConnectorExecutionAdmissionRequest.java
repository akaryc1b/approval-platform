package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * Server-only current-state evidence used to revalidate a previously created orchestration plan.
 */
public record ConnectorExecutionAdmissionRequest<P, R>(
    ConnectorOrchestrationPlan plan,
    ConnectorProviderRegistry registry,
    ConnectorOperationContract<P, R> contract,
    ConnectorProviderSelection<P, R> selection,
    ConnectorProviderCompatibilityReport compatibilityReport,
    ConnectorInvocationAuthorizationEvidence authorizationEvidence,
    TrustedConnectorExecutionContext context,
    ConnectorRequest<P> request,
    Instant checkedAt
) {

    public ConnectorExecutionAdmissionRequest {
        plan = Objects.requireNonNull(plan, "plan must not be null");
        registry = Objects.requireNonNull(registry, "registry must not be null");
        contract = Objects.requireNonNull(contract, "contract must not be null");
        selection = Objects.requireNonNull(selection, "selection must not be null");
        compatibilityReport = Objects.requireNonNull(
            compatibilityReport,
            "compatibilityReport must not be null"
        );
        authorizationEvidence = Objects.requireNonNull(
            authorizationEvidence,
            "authorizationEvidence must not be null"
        );
        context = Objects.requireNonNull(context, "context must not be null");
        request = Objects.requireNonNull(request, "request must not be null");
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt must not be null");
    }
}
