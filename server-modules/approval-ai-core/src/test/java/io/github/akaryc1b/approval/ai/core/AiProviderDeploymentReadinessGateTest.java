package io.github.akaryc1b.approval.ai.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderDeploymentReadinessGateTest {

    @Test
    void completeMetadataIsReadyForOfflineFaultDrillWithoutProviderInvocation() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");
        AiProviderDeploymentReadinessReport report = inspect(
            setup,
            setup.deployment(),
            setup.validator()
        );

        assertEquals(
            AiProviderDeploymentReadinessReport.Status.READY_FOR_FAULT_DRILL,
            report.status()
        );
        assertEquals(1, setup.validator().validations());
        assertFalse(report.providerInvocationAttempted());
        assertFalse(report.secretResolutionAttempted());
        assertFalse(report.networkCallAttempted());
        assertFalse(report.productionEnablementAuthorized());
        assertEquals(
            AiProviderDeploymentReadinessReport.RouteStatus.READY,
            report.routeChecks().get(0).status()
        );
    }

    @Test
    void expiredSecretFailsClosedBeforeAnyRuntimeResolution() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");
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
        AiProviderDeploymentReadinessReport report = inspect(
            setup,
            deployment,
            setup.validator()
        );

        assertEquals(AiProviderDeploymentReadinessReport.Status.BLOCKED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.faultClass()
            == AiProviderDeploymentReadinessReport.FaultClass.SECRET_ROTATION_BLOCKED));
        assertFalse(report.secretResolutionAttempted());
    }

    @Test
    void endpointOutsideExactAllowlistAndMissingValidatorAreBlocked() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");
        AiProviderEndpointDescriptor otherEndpoint = new AiProviderEndpointDescriptor(
            "endpoint-other",
            setup.versions().provider(),
            AiProviderEndpointDescriptor.Scheme.HTTPS,
            "other.example.com",
            443,
            "/v1/advisory",
            true,
            false,
            false
        );
        AiProviderEgressPolicy deny = AiProviderEgressPolicy.create(
            setup.egress().policyId(),
            setup.egress().policyVersion(),
            Set.of(otherEndpoint)
        );
        AiProviderDeploymentSnapshot deployment = AiProviderDeploymentSnapshot.create(
            setup.deployment().snapshotId(),
            "deny-endpoint",
            setup.advisory().declaredContentHash(),
            setup.deployment().bindings(),
            setup.deployment().endpoints(),
            Map.of(deny.authorizationKey(), deny),
            setup.deployment().secretReferences(),
            setup.deployment().validationProfiles()
        );
        AiProviderDeploymentReadinessReport report = new AiProviderDeploymentReadinessGate()
            .inspect(
                setup.advisory(),
                setup.advisoryPreflight(),
                deployment,
                new AiProviderProtocolValidatorRegistry(List.of())
            );

        assertEquals(AiProviderDeploymentReadinessReport.Status.BLOCKED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.faultClass()
            == AiProviderDeploymentReadinessReport.FaultClass.ENDPOINT_NOT_ALLOWLISTED));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.faultClass()
            == AiProviderDeploymentReadinessReport.FaultClass.VALIDATOR_NOT_REGISTERED));
    }

    @Test
    void invalidProtocolEvidenceFailsClosedWithoutCallingAProvider() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");
        DeterministicProtocolValidator invalid = new DeterministicProtocolValidator(
            setup.profile(),
            DeterministicProtocolValidator.Mode.INVALID
        );
        AiProviderDeploymentReadinessReport report = inspect(
            setup,
            setup.deployment(),
            invalid
        );

        assertEquals(AiProviderDeploymentReadinessReport.Status.BLOCKED, report.status());
        assertEquals(1, invalid.validations());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.faultClass()
            == AiProviderDeploymentReadinessReport.FaultClass.VALIDATION_REJECTED));
        assertFalse(report.providerInvocationAttempted());
    }

    private static AiProviderDeploymentReadinessReport inspect(
        AiProviderDeploymentTestFixtures.Setup setup,
        AiProviderDeploymentSnapshot deployment,
        DeterministicProtocolValidator validator
    ) {
        return new AiProviderDeploymentReadinessGate().inspect(
            setup.advisory(),
            setup.advisoryPreflight(),
            deployment,
            new AiProviderProtocolValidatorRegistry(List.of(validator))
        );
    }
}
