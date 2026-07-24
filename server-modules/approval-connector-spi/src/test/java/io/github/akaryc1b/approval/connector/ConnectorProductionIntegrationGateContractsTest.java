package io.github.akaryc1b.approval.connector;

import io.github.akaryc1b.approval.connector.contract.ConnectorFoundationAcceptanceAnchor;
import io.github.akaryc1b.approval.connector.contract.ConnectorProductionCapability;
import io.github.akaryc1b.approval.connector.contract.ConnectorProductionDecision;
import io.github.akaryc1b.approval.connector.contract.ConnectorProductionIntegrationEvidence;
import io.github.akaryc1b.approval.connector.contract.ConnectorProductionIntegrationGateRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorProductionIntegrationGateStatus;
import io.github.akaryc1b.approval.connector.contract.ConnectorProductionOwner;
import io.github.akaryc1b.approval.connector.contract.ConnectorProductionOwnershipEntry;
import io.github.akaryc1b.approval.connector.contract.DeterministicConnectorProductionIntegrationGateEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorProductionIntegrationGateContractsTest {

    private static final Instant NOW = Instant.parse("2026-07-24T02:15:00Z");

    private final DeterministicConnectorProductionIntegrationGateEvaluator evaluator =
        new DeterministicConnectorProductionIntegrationGateEvaluator();

    @Test
    void baselineOwnershipMatrixIsReadyForScopedImplementationReview() {
        ConnectorProductionIntegrationEvidence evidence = evaluate(
            baselineEntries(),
            ConnectorFoundationAcceptanceAnchor.current(),
            DeterministicConnectorProductionIntegrationGateEvaluator.POLICY_VERSION
        );

        assertEquals(
            ConnectorProductionIntegrationGateStatus
                .READY_FOR_SCOPED_IMPLEMENTATION_REVIEW,
            evidence.status()
        );
        assertTrue(evidence.readyForScopedImplementationReview());
        assertTrue(evidence.requiresExplicitCapabilityGate());
    }

    @Test
    void evidenceHashIsDeterministicAcrossEntryOrder() {
        List<ConnectorProductionOwnershipEntry> reversed =
            new ArrayList<>(baselineEntries());
        Collections.reverse(reversed);

        ConnectorProductionIntegrationEvidence first = evaluate(
            baselineEntries(),
            ConnectorFoundationAcceptanceAnchor.current(),
            DeterministicConnectorProductionIntegrationGateEvaluator.POLICY_VERSION
        );
        ConnectorProductionIntegrationEvidence second = evaluate(
            reversed,
            ConnectorFoundationAcceptanceAnchor.current(),
            DeterministicConnectorProductionIntegrationGateEvaluator.POLICY_VERSION
        );

        assertEquals(first.evidenceHash(), second.evidenceHash());
        assertEquals(first.ownershipEntries(), second.ownershipEntries());
    }

    @Test
    void foundationAnchorIsPinnedToFormalAcceptance() {
        ConnectorFoundationAcceptanceAnchor anchor =
            ConnectorFoundationAcceptanceAnchor.current();

        assertEquals(
            "FORMALLY_ACCEPTED_CONTRACT_FOUNDATION",
            anchor.governanceStatus()
        );
        assertEquals(
            "3bb9f28e3c72d29413bb494b814cd601ad7da3c7",
            anchor.acceptedHead()
        );
        assertEquals(30060608341L, anchor.validationRunId());
        assertEquals(
            "ba46bfb1ac6b4c516af0f3aa2e3617bedf4c8c5c357306cbf8dcf96c99fa6f41",
            anchor.artifactDigest()
        );
        assertEquals(
            "docs/m6/M6_A_CONNECTOR_FOUNDATION_ACCEPTANCE.md",
            anchor.acceptanceDocument()
        );
    }

    @Test
    void mismatchedFoundationAnchorFailsClosed() {
        ConnectorFoundationAcceptanceAnchor current =
            ConnectorFoundationAcceptanceAnchor.current();
        ConnectorFoundationAcceptanceAnchor changed =
            new ConnectorFoundationAcceptanceAnchor(
                current.governanceStatus(),
                "0".repeat(40),
                current.validationRunId(),
                current.artifactDigest(),
                current.acceptanceDocument()
            );

        assertStatus(
            ConnectorProductionIntegrationGateStatus
                .FOUNDATION_EVIDENCE_MISMATCH,
            baselineEntries(),
            changed,
            DeterministicConnectorProductionIntegrationGateEvaluator.POLICY_VERSION
        );
    }

    @Test
    void mismatchedPolicyVersionFailsClosed() {
        assertStatus(
            ConnectorProductionIntegrationGateStatus
                .FOUNDATION_EVIDENCE_MISMATCH,
            baselineEntries(),
            ConnectorFoundationAcceptanceAnchor.current(),
            "m6a.production-integration-gate.v2"
        );
    }

    @Test
    void duplicateCapabilityIsClassified() {
        List<ConnectorProductionOwnershipEntry> entries =
            new ArrayList<>(baselineEntries());
        entries.add(entries.getFirst());

        assertStatus(
            ConnectorProductionIntegrationGateStatus.DUPLICATE_CAPABILITY,
            entries
        );
    }

    @Test
    void missingCapabilityIsClassified() {
        List<ConnectorProductionOwnershipEntry> entries =
            new ArrayList<>(baselineEntries());
        entries.removeIf(
            entry -> entry.capability()
                == ConnectorProductionCapability.OBSERVABILITY
        );

        assertStatus(
            ConnectorProductionIntegrationGateStatus
                .INCOMPLETE_CAPABILITY_MATRIX,
            entries
        );
    }

    @Test
    void approvalStateActionsCannotBeOpened() {
        List<ConnectorProductionOwnershipEntry> entries = replace(
            ConnectorProductionCapability.APPROVAL_STATE_ACTIONS,
            new ConnectorProductionOwnershipEntry(
                ConnectorProductionCapability.APPROVAL_STATE_ACTIONS,
                ConnectorProductionOwner.PLATFORM_APPLICATION,
                ConnectorProductionDecision.FUTURE_EXPLICIT_GATE,
                "APPROVAL_ACTIONS_GATE"
            )
        );

        assertStatus(
            ConnectorProductionIntegrationGateStatus
                .PROHIBITED_CAPABILITY_OPENED,
            entries
        );
    }

    @Test
    void wrongOwnerIsRejected() {
        ConnectorProductionOwnershipEntry current = entry(
            ConnectorProductionCapability.PROVIDER_TRANSPORT
        );
        List<ConnectorProductionOwnershipEntry> entries = replace(
            current.capability(),
            new ConnectorProductionOwnershipEntry(
                current.capability(),
                ConnectorProductionOwner.PLATFORM_APPLICATION,
                current.decision(),
                current.rationaleCode()
            )
        );

        assertStatus(
            ConnectorProductionIntegrationGateStatus.OWNERSHIP_CONFLICT,
            entries
        );
    }

    @Test
    void wrongDecisionIsRejected() {
        ConnectorProductionOwnershipEntry current = entry(
            ConnectorProductionCapability.PERSISTENCE
        );
        List<ConnectorProductionOwnershipEntry> entries = replace(
            current.capability(),
            new ConnectorProductionOwnershipEntry(
                current.capability(),
                current.owner(),
                ConnectorProductionDecision.EXISTING_PLATFORM_BOUNDARY,
                current.rationaleCode()
            )
        );

        assertStatus(
            ConnectorProductionIntegrationGateStatus.OWNERSHIP_CONFLICT,
            entries
        );
    }

    @Test
    void wrongRationaleIsRejected() {
        ConnectorProductionOwnershipEntry current = entry(
            ConnectorProductionCapability.AUDIT_INTEGRATION
        );
        List<ConnectorProductionOwnershipEntry> entries = replace(
            current.capability(),
            new ConnectorProductionOwnershipEntry(
                current.capability(),
                current.owner(),
                current.decision(),
                "AUDIT_UNCOORDINATED"
            )
        );

        assertStatus(
            ConnectorProductionIntegrationGateStatus.OWNERSHIP_CONFLICT,
            entries
        );
    }

    @Test
    void evidenceNeverEnablesProductionCapabilities() {
        ConnectorProductionIntegrationEvidence evidence = evaluate(
            baselineEntries()
        );

        assertFalse(evidence.productionEnabled());
        assertFalse(evidence.providerTransportEnabled());
        assertFalse(evidence.credentialResolutionEnabled());
        assertFalse(evidence.tenantRoutingEnabled());
        assertFalse(evidence.persistenceEnabled());
        assertFalse(evidence.providerExecutionEnabled());
        assertFalse(evidence.automaticRetryEnabled());
        assertFalse(evidence.recoveryWorkerEnabled());
        assertFalse(evidence.schemaChangeAllowed());
        assertFalse(evidence.approvalStateMutationAllowed());
    }

    @Test
    void existingIntegrationCoreOwnershipIsPreserved() {
        Map<ConnectorProductionCapability, ConnectorProductionOwnershipEntry> entries =
            byCapability(baselineEntries());

        assertEquals(
            ConnectorProductionOwner.PLATFORM_INTEGRATION_CORE,
            entries.get(ConnectorProductionCapability.IDEMPOTENCY).owner()
        );
        assertEquals(
            ConnectorProductionDecision.EXISTING_PLATFORM_BOUNDARY,
            entries.get(ConnectorProductionCapability.IDEMPOTENCY).decision()
        );
        assertEquals(
            ConnectorProductionOwner.PLATFORM_INTEGRATION_CORE,
            entries.get(ConnectorProductionCapability.PERSISTENCE).owner()
        );
        assertEquals(
            ConnectorProductionOwner.PLATFORM_INTEGRATION_CORE,
            entries.get(ConnectorProductionCapability.RETRY_POLICY).owner()
        );
    }

    @Test
    void transportAndExecutionRemainFutureExplicitGates() {
        Map<ConnectorProductionCapability, ConnectorProductionOwnershipEntry> entries =
            byCapability(baselineEntries());

        assertEquals(
            ConnectorProductionDecision.FUTURE_EXPLICIT_GATE,
            entries.get(ConnectorProductionCapability.PROVIDER_TRANSPORT)
                .decision()
        );
        assertEquals(
            ConnectorProductionDecision.FUTURE_EXPLICIT_GATE,
            entries.get(ConnectorProductionCapability.EXECUTION_COORDINATION)
                .decision()
        );
        assertEquals(
            ConnectorProductionDecision.FUTURE_EXPLICIT_GATE,
            entries.get(
                ConnectorProductionCapability.RECONCILIATION_RECOVERY
            ).decision()
        );
    }

    @Test
    void credentialsAuthorizationAndAuditRequireSharedCoordination() {
        Map<ConnectorProductionCapability, ConnectorProductionOwnershipEntry> entries =
            byCapability(baselineEntries());

        for (ConnectorProductionCapability capability : List.of(
            ConnectorProductionCapability.CREDENTIAL_RESOLUTION,
            ConnectorProductionCapability.AUTHORIZATION_INTEGRATION,
            ConnectorProductionCapability.AUDIT_INTEGRATION,
            ConnectorProductionCapability.OBSERVABILITY
        )) {
            assertEquals(
                ConnectorProductionDecision.SHARED_COORDINATION_REQUIRED,
                entries.get(capability).decision()
            );
        }
    }

    @Test
    void requestAndEvidenceDefensivelyCopyOwnershipEntries() {
        List<ConnectorProductionOwnershipEntry> mutable =
            new ArrayList<>(baselineEntries());
        ConnectorProductionIntegrationGateRequest request =
            new ConnectorProductionIntegrationGateRequest(
                ConnectorFoundationAcceptanceAnchor.current(),
                DeterministicConnectorProductionIntegrationGateEvaluator.POLICY_VERSION,
                mutable,
                NOW
            );
        mutable.clear();

        ConnectorProductionIntegrationEvidence evidence = evaluator.evaluate(request);
        assertEquals(
            ConnectorProductionCapability.values().length,
            evidence.ownershipEntries().size()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> evidence.ownershipEntries().clear()
        );
        assertNotEquals(0, evidence.evidenceHash().length());
    }

    private ConnectorProductionIntegrationEvidence evaluate(
        List<ConnectorProductionOwnershipEntry> entries
    ) {
        return evaluate(
            entries,
            ConnectorFoundationAcceptanceAnchor.current(),
            DeterministicConnectorProductionIntegrationGateEvaluator.POLICY_VERSION
        );
    }

    private ConnectorProductionIntegrationEvidence evaluate(
        List<ConnectorProductionOwnershipEntry> entries,
        ConnectorFoundationAcceptanceAnchor anchor,
        String policyVersion
    ) {
        return evaluator.evaluate(
            new ConnectorProductionIntegrationGateRequest(
                anchor,
                policyVersion,
                entries,
                NOW
            )
        );
    }

    private void assertStatus(
        ConnectorProductionIntegrationGateStatus status,
        List<ConnectorProductionOwnershipEntry> entries
    ) {
        assertStatus(
            status,
            entries,
            ConnectorFoundationAcceptanceAnchor.current(),
            DeterministicConnectorProductionIntegrationGateEvaluator.POLICY_VERSION
        );
    }

    private void assertStatus(
        ConnectorProductionIntegrationGateStatus status,
        List<ConnectorProductionOwnershipEntry> entries,
        ConnectorFoundationAcceptanceAnchor anchor,
        String policyVersion
    ) {
        assertEquals(
            status,
            evaluate(entries, anchor, policyVersion).status()
        );
    }

    private static List<ConnectorProductionOwnershipEntry> baselineEntries() {
        return DeterministicConnectorProductionIntegrationGateEvaluator
            .requiredBaselineEntries();
    }

    private static ConnectorProductionOwnershipEntry entry(
        ConnectorProductionCapability capability
    ) {
        return byCapability(baselineEntries()).get(capability);
    }

    private static List<ConnectorProductionOwnershipEntry> replace(
        ConnectorProductionCapability capability,
        ConnectorProductionOwnershipEntry replacement
    ) {
        List<ConnectorProductionOwnershipEntry> entries =
            new ArrayList<>(baselineEntries());
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).capability() == capability) {
                entries.set(index, replacement);
                return entries;
            }
        }
        throw new IllegalArgumentException("capability is not present");
    }

    private static Map<
        ConnectorProductionCapability,
        ConnectorProductionOwnershipEntry
    > byCapability(List<ConnectorProductionOwnershipEntry> entries) {
        Map<ConnectorProductionCapability, ConnectorProductionOwnershipEntry> result =
            new EnumMap<>(ConnectorProductionCapability.class);
        entries.forEach(entry -> result.put(entry.capability(), entry));
        return result;
    }
}
