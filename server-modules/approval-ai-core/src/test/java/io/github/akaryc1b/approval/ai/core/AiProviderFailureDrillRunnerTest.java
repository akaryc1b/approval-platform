package io.github.akaryc1b.approval.ai.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderFailureDrillRunnerTest {

    @Test
    void expectedFailClosedFaultsProduceDeterministicPassingEvidence() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");
        AiProviderDeploymentReadinessReport expiredSecret = expiredSecretReport(setup);
        AiProviderDeploymentReadinessReport missingValidator = new AiProviderDeploymentReadinessGate()
            .inspect(
                setup.advisory(),
                setup.advisoryPreflight(),
                setup.deployment(),
                new AiProviderProtocolValidatorRegistry(List.of())
            );

        AiProviderFailureDrillCase secretCase = new AiProviderFailureDrillCase(
            "expired-secret",
            AiProviderDeploymentReadinessReport.FaultClass.SECRET_ROTATION_BLOCKED,
            AiProviderDeploymentReadinessReport.Status.BLOCKED,
            AiProviderFailureDrillCase.Criticality.CRITICAL
        );
        AiProviderFailureDrillCase validatorCase = new AiProviderFailureDrillCase(
            "missing-validator",
            AiProviderDeploymentReadinessReport.FaultClass.VALIDATOR_NOT_REGISTERED,
            AiProviderDeploymentReadinessReport.Status.BLOCKED,
            AiProviderFailureDrillCase.Criticality.REQUIRED
        );
        AiProviderFailureDrillObservation secretObservation =
            new AiProviderFailureDrillObservation(
                secretCase.caseId(),
                "3".repeat(64),
                expiredSecret
            );
        AiProviderFailureDrillObservation validatorObservation =
            new AiProviderFailureDrillObservation(
                validatorCase.caseId(),
                "4".repeat(64),
                missingValidator
            );

        AiProviderFailureDrillRunner runner = new AiProviderFailureDrillRunner();
        AiProviderFailureDrillReport first = runner.evaluate(
            "deployment-faults",
            "1",
            List.of(secretCase, validatorCase),
            List.of(secretObservation, validatorObservation)
        );
        AiProviderFailureDrillReport second = runner.evaluate(
            "deployment-faults",
            "1",
            List.of(validatorCase, secretCase),
            List.of(validatorObservation, secretObservation)
        );

        assertEquals(AiProviderFailureDrillReport.Status.PASSED, first.status());
        assertEquals(first.reportHash(), second.reportHash());
        assertEquals(2, first.passedCases());
        assertFalse(first.providerInvocationAttempted());
        assertFalse(first.productionEnablementAuthorized());
    }

    @Test
    void missingObservationFailsWithoutAuthorizingActivation() {
        AiProviderFailureDrillCase expected = new AiProviderFailureDrillCase(
            "missing-validator",
            AiProviderDeploymentReadinessReport.FaultClass.VALIDATOR_NOT_REGISTERED,
            AiProviderDeploymentReadinessReport.Status.BLOCKED,
            AiProviderFailureDrillCase.Criticality.CRITICAL
        );
        AiProviderFailureDrillReport report = new AiProviderFailureDrillRunner().evaluate(
            "deployment-faults",
            "1",
            List.of(expected),
            List.of()
        );

        assertEquals(AiProviderFailureDrillReport.Status.FAILED, report.status());
        assertEquals(1, report.failedCases());
        assertFalse(report.networkCallAttempted());
        assertFalse(report.approvalAutomationAuthorized());
    }

    @Test
    void faultDrillReportsRejectProductionAuthority() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderFailureDrillReport(
                "deployment-faults",
                "1",
                AiProviderFailureDrillReport.Status.PASSED,
                0,
                0,
                0,
                List.of(),
                "5".repeat(64),
                false,
                false,
                false,
                true,
                false
            )
        );
    }

    private static AiProviderDeploymentReadinessReport expiredSecretReport(
        AiProviderDeploymentTestFixtures.Setup setup
    ) {
        AiExternalSecretReference expired = AiProviderDeploymentTestFixtures.secret(
            setup.versions(),
            setup.secret().referenceId(),
            AiExternalSecretReference.RotationState.EXPIRED
        );
        AiProviderDeploymentSnapshot deployment = AiProviderDeploymentSnapshot.create(
            setup.deployment().snapshotId(),
            "expired-secret",
            setup.advisory().declaredContentHash(),
            setup.deployment().bindings(),
            setup.deployment().endpoints(),
            setup.deployment().egressPolicies(),
            Map.of(expired.authorizationKey(), expired),
            setup.deployment().validationProfiles()
        );
        return new AiProviderDeploymentReadinessGate().inspect(
            setup.advisory(),
            setup.advisoryPreflight(),
            deployment,
            new AiProviderProtocolValidatorRegistry(List.of(setup.validator()))
        );
    }
}
