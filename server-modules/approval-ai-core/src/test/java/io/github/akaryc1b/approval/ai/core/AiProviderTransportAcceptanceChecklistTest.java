package io.github.akaryc1b.approval.ai.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderTransportAcceptanceChecklistTest {

    @Test
    void completeChecklistRemainsNonExecutable() {
        AiProviderTransportAcceptanceChecklist checklist = checklist(
            AiProviderTransportTestFixtures.completeGates()
        );

        assertEquals(
            AiProviderTransportAcceptanceChecklist.Status.REVIEW_COMPLETE,
            checklist.status()
        );
        assertEquals(
            AiProviderTransportAcceptanceChecklist.Stage
                .NON_EXECUTABLE_TRANSPORT_ACCEPTANCE,
            checklist.stage()
        );
        assertFalse(checklist.applyAuthorized());
        assertFalse(checklist.networkAccessAuthorized());
        assertFalse(checklist.providerInvocationAuthorized());
    }

    @Test
    void missingRequiredGateBlocksChecklist() {
        List<AiProviderTransportAcceptanceChecklist.GateEvidence> gates =
            new ArrayList<>(AiProviderTransportTestFixtures.completeGates());
        gates.remove(0);
        assertEquals(
            AiProviderTransportAcceptanceChecklist.Status.BLOCKED,
            checklist(gates).status()
        );
    }

    @Test
    void failedGateBlocksChecklist() {
        List<AiProviderTransportAcceptanceChecklist.GateEvidence> gates =
            new ArrayList<>(AiProviderTransportTestFixtures.completeGates());
        AiProviderTransportAcceptanceChecklist.GateEvidence original = gates.get(0);
        gates.set(
            0,
            new AiProviderTransportAcceptanceChecklist.GateEvidence(
                original.gateId(),
                original.category(),
                AiProviderTransportAcceptanceChecklist.Decision.FAILED,
                original.evidenceHash()
            )
        );
        assertEquals(
            AiProviderTransportAcceptanceChecklist.Status.BLOCKED,
            checklist(gates).status()
        );
    }

    @Test
    void constructorRejectsExecutionAuthority() {
        AiProviderTransportAcceptanceChecklist safe = checklist(
            AiProviderTransportTestFixtures.completeGates()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderTransportAcceptanceChecklist(
                safe.checklistId(),
                safe.checklistVersion(),
                safe.providerVersion(),
                safe.deploymentSnapshotHash(),
                safe.activationReviewHash(),
                safe.mapperContractHash(),
                safe.canonicalizationPolicyHash(),
                safe.lifecycleDrillHash(),
                safe.malformedResponseDrillHash(),
                safe.schemaDriftDrillHash(),
                safe.cancellationDrillHash(),
                safe.transportAuditHash(),
                safe.gates(),
                safe.status(),
                safe.checklistHash(),
                true,
                false,
                false,
                false,
                false
            )
        );
    }

    private static AiProviderTransportAcceptanceChecklist checklist(
        List<AiProviderTransportAcceptanceChecklist.GateEvidence> gates
    ) {
        return AiProviderTransportAcceptanceChecklist.create(
            "transport-acceptance",
            "1",
            AiProviderTransportTestFixtures.provider(),
            AiProviderTransportTestFixtures.hash('1'),
            AiProviderTransportTestFixtures.hash('2'),
            AiProviderTransportTestFixtures.hash('3'),
            AiProviderTransportTestFixtures.policy().policyHash(),
            AiProviderTransportTestFixtures.hash('4'),
            AiProviderTransportTestFixtures.hash('5'),
            AiProviderTransportTestFixtures.hash('6'),
            AiProviderTransportTestFixtures.hash('7'),
            AiProviderTransportTestFixtures.hash('8'),
            gates
        );
    }
}
