package io.github.akaryc1b.approval.connector.contract;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates production ownership without enabling any production capability.
 */
public final class DeterministicConnectorProductionIntegrationGateEvaluator {

    public static final String POLICY_VERSION =
        "m6a.production-integration-gate.v1";

    private static final Map<
        ConnectorProductionCapability,
        ExpectedOwnership
    > REQUIRED_OWNERSHIP = requiredOwnership();

    public ConnectorProductionIntegrationEvidence evaluate(
        ConnectorProductionIntegrationGateRequest request
    ) {
        if (request == null) {
            throw new NullPointerException("request must not be null");
        }

        ConnectorProductionIntegrationGateStatus status = classify(request);
        return new ConnectorProductionIntegrationEvidence(
            request.policyVersion(),
            status,
            request.foundationAnchor().evidenceHash(),
            request.ownershipEntries(),
            request.evaluatedAt()
        );
    }

    public static List<ConnectorProductionOwnershipEntry> requiredBaselineEntries() {
        return REQUIRED_OWNERSHIP.entrySet().stream()
            .map(entry -> entry.getValue().toEntry(entry.getKey()))
            .sorted((left, right) -> left.capability().name()
                .compareTo(right.capability().name()))
            .toList();
    }

    private static ConnectorProductionIntegrationGateStatus classify(
        ConnectorProductionIntegrationGateRequest request
    ) {
        if (!POLICY_VERSION.equals(request.policyVersion())
            || !ConnectorFoundationAcceptanceAnchor.current()
                .equals(request.foundationAnchor())) {
            return ConnectorProductionIntegrationGateStatus
                .FOUNDATION_EVIDENCE_MISMATCH;
        }

        Set<ConnectorProductionCapability> seen =
            EnumSet.noneOf(ConnectorProductionCapability.class);
        for (ConnectorProductionOwnershipEntry entry : request.ownershipEntries()) {
            if (!seen.add(entry.capability())) {
                return ConnectorProductionIntegrationGateStatus
                    .DUPLICATE_CAPABILITY;
            }
        }

        if (!seen.equals(EnumSet.allOf(ConnectorProductionCapability.class))) {
            return ConnectorProductionIntegrationGateStatus
                .INCOMPLETE_CAPABILITY_MATRIX;
        }

        ConnectorProductionOwnershipEntry approvalActions =
            entryFor(
                request.ownershipEntries(),
                ConnectorProductionCapability.APPROVAL_STATE_ACTIONS
            );
        if (approvalActions.owner()
                != ConnectorProductionOwner.NO_RUNTIME_OWNER
            || approvalActions.decision()
                != ConnectorProductionDecision.BLOCKED) {
            return ConnectorProductionIntegrationGateStatus
                .PROHIBITED_CAPABILITY_OPENED;
        }

        for (ConnectorProductionOwnershipEntry entry : request.ownershipEntries()) {
            ExpectedOwnership expected = REQUIRED_OWNERSHIP.get(entry.capability());
            if (!expected.matches(entry)) {
                return ConnectorProductionIntegrationGateStatus
                    .OWNERSHIP_CONFLICT;
            }
        }

        return ConnectorProductionIntegrationGateStatus
            .READY_FOR_SCOPED_IMPLEMENTATION_REVIEW;
    }

    private static ConnectorProductionOwnershipEntry entryFor(
        List<ConnectorProductionOwnershipEntry> entries,
        ConnectorProductionCapability capability
    ) {
        return entries.stream()
            .filter(entry -> entry.capability() == capability)
            .findFirst()
            .orElseThrow();
    }

    private static Map<
        ConnectorProductionCapability,
        ExpectedOwnership
    > requiredOwnership() {
        Map<ConnectorProductionCapability, ExpectedOwnership> ownership =
            new EnumMap<>(ConnectorProductionCapability.class);

        ownership.put(
            ConnectorProductionCapability.PROVIDER_TRANSPORT,
            expected(
                ConnectorProductionOwner.CONNECTOR_ADAPTER,
                ConnectorProductionDecision.FUTURE_EXPLICIT_GATE,
                "ADAPTER_TRANSPORT_GATE"
            )
        );
        ownership.put(
            ConnectorProductionCapability.CREDENTIAL_RESOLUTION,
            expected(
                ConnectorProductionOwner.PLATFORM_SECURITY,
                ConnectorProductionDecision.SHARED_COORDINATION_REQUIRED,
                "SECURITY_CREDENTIAL_COORDINATION"
            )
        );
        ownership.put(
            ConnectorProductionCapability.TENANT_ROUTING,
            expected(
                ConnectorProductionOwner.PLATFORM_APPLICATION,
                ConnectorProductionDecision.SHARED_COORDINATION_REQUIRED,
                "APPLICATION_TENANT_ROUTING_COORDINATION"
            )
        );
        ownership.put(
            ConnectorProductionCapability.EXECUTION_COORDINATION,
            expected(
                ConnectorProductionOwner.PLATFORM_APPLICATION,
                ConnectorProductionDecision.FUTURE_EXPLICIT_GATE,
                "APPLICATION_SINGLE_ATTEMPT_GATE"
            )
        );
        ownership.put(
            ConnectorProductionCapability.IDEMPOTENCY,
            expected(
                ConnectorProductionOwner.PLATFORM_INTEGRATION_CORE,
                ConnectorProductionDecision.EXISTING_PLATFORM_BOUNDARY,
                "INTEGRATION_IDEMPOTENCY_REUSE"
            )
        );
        ownership.put(
            ConnectorProductionCapability.PERSISTENCE,
            expected(
                ConnectorProductionOwner.PLATFORM_INTEGRATION_CORE,
                ConnectorProductionDecision.SHARED_COORDINATION_REQUIRED,
                "INTEGRATION_PERSISTENCE_COORDINATION"
            )
        );
        ownership.put(
            ConnectorProductionCapability.RETRY_POLICY,
            expected(
                ConnectorProductionOwner.PLATFORM_INTEGRATION_CORE,
                ConnectorProductionDecision.SHARED_COORDINATION_REQUIRED,
                "INTEGRATION_RETRY_COORDINATION"
            )
        );
        ownership.put(
            ConnectorProductionCapability.RECONCILIATION_RECOVERY,
            expected(
                ConnectorProductionOwner.PLATFORM_APPLICATION,
                ConnectorProductionDecision.FUTURE_EXPLICIT_GATE,
                "APPLICATION_RECONCILIATION_GATE"
            )
        );
        ownership.put(
            ConnectorProductionCapability.AUTHORIZATION_INTEGRATION,
            expected(
                ConnectorProductionOwner.PLATFORM_SECURITY,
                ConnectorProductionDecision.SHARED_COORDINATION_REQUIRED,
                "SECURITY_AUTHORIZATION_COORDINATION"
            )
        );
        ownership.put(
            ConnectorProductionCapability.AUDIT_INTEGRATION,
            expected(
                ConnectorProductionOwner.PLATFORM_AUDIT,
                ConnectorProductionDecision.SHARED_COORDINATION_REQUIRED,
                "AUDIT_HANDOFF_COORDINATION"
            )
        );
        ownership.put(
            ConnectorProductionCapability.OBSERVABILITY,
            expected(
                ConnectorProductionOwner.PLATFORM_OPERATIONS,
                ConnectorProductionDecision.SHARED_COORDINATION_REQUIRED,
                "OPERATIONS_OBSERVABILITY_COORDINATION"
            )
        );
        ownership.put(
            ConnectorProductionCapability.APPROVAL_STATE_ACTIONS,
            expected(
                ConnectorProductionOwner.NO_RUNTIME_OWNER,
                ConnectorProductionDecision.BLOCKED,
                "APPROVAL_ACTIONS_PROHIBITED"
            )
        );

        if (!ownership.keySet().equals(
            EnumSet.allOf(ConnectorProductionCapability.class)
        )) {
            throw new IllegalStateException(
                "required ownership must cover every production capability"
            );
        }
        return Map.copyOf(ownership);
    }

    private static ExpectedOwnership expected(
        ConnectorProductionOwner owner,
        ConnectorProductionDecision decision,
        String rationaleCode
    ) {
        return new ExpectedOwnership(owner, decision, rationaleCode);
    }

    private record ExpectedOwnership(
        ConnectorProductionOwner owner,
        ConnectorProductionDecision decision,
        String rationaleCode
    ) {

        private boolean matches(ConnectorProductionOwnershipEntry entry) {
            return owner == entry.owner()
                && decision == entry.decision()
                && rationaleCode.equals(entry.rationaleCode());
        }

        private ConnectorProductionOwnershipEntry toEntry(
            ConnectorProductionCapability capability
        ) {
            return new ConnectorProductionOwnershipEntry(
                capability,
                owner,
                decision,
                rationaleCode
            );
        }
    }
}
