package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderDeploymentChangeSetTest {

    @Test
    void identicalSnapshotsProduceNoChangeAndNoApprovalRequirement() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");
        AiProviderDeploymentChangeSet changeSet = AiProviderDeploymentChangeSet.compare(
            setup.deployment(),
            setup.deployment()
        );

        assertTrue(changeSet.changes().isEmpty());
        assertEquals(AiProviderDeploymentChangeSet.RiskLevel.NONE, changeSet.highestRisk());
        assertFalse(changeSet.humanApprovalRequired());
        assertFalse(changeSet.applyAuthorized());
    }

    @Test
    void endpointSecretAndValidationChangesRequireCriticalHumanReview() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");
        AiProviderEndpointDescriptor changedEndpoint = new AiProviderEndpointDescriptor(
            setup.endpoint().endpointId(),
            setup.versions().provider(),
            AiProviderEndpointDescriptor.Scheme.HTTPS,
            "changed.example.com",
            443,
            "/v2/advisory",
            true,
            false,
            false
        );
        AiProviderEgressPolicy changedEgress = AiProviderEgressPolicy.create(
            setup.egress().policyId(),
            setup.egress().policyVersion(),
            Set.of(changedEndpoint)
        );
        AiExternalSecretReference expired = AiProviderDeploymentTestFixtures.secret(
            setup.versions(),
            setup.secret().referenceId(),
            AiExternalSecretReference.RotationState.EXPIRED
        );
        AiProviderProtocolProfile changedProfile = new AiProviderProtocolProfile(
            setup.profile().validatorId(),
            setup.profile().validatorVersion(),
            setup.profile().providerVersion(),
            setup.profile().capabilities(),
            "6".repeat(64),
            "7".repeat(64),
            setup.profile().maximumRequestBytes(),
            setup.profile().maximumResponseBytes(),
            true,
            true,
            false
        );
        AiProviderDeploymentSnapshot target = AiProviderDeploymentSnapshot.create(
            setup.deployment().snapshotId(),
            "2",
            setup.advisory().declaredContentHash(),
            setup.deployment().bindings(),
            Map.of(changedEndpoint.endpointId(), changedEndpoint),
            Map.of(changedEgress.authorizationKey(), changedEgress),
            Map.of(expired.authorizationKey(), expired),
            Map.of(changedProfile.authorizationKey(), changedProfile)
        );

        AiProviderDeploymentChangeSet first = AiProviderDeploymentChangeSet.compare(
            setup.deployment(),
            target
        );
        AiProviderDeploymentChangeSet second = AiProviderDeploymentChangeSet.compare(
            setup.deployment(),
            target
        );

        assertEquals(AiProviderDeploymentChangeSet.RiskLevel.CRITICAL, first.highestRisk());
        assertTrue(first.humanApprovalRequired());
        assertFalse(first.applyAuthorized());
        assertFalse(first.productionEnablementAuthorized());
        assertEquals(first.changeSetHash(), second.changeSetHash());
        assertTrue(first.changes().stream().anyMatch(change -> change.changeType()
            == AiProviderDeploymentChangeSet.ChangeType.ENDPOINT_METADATA_CHANGED));
        assertTrue(first.changes().stream().anyMatch(change -> change.changeType()
            == AiProviderDeploymentChangeSet.ChangeType.SECRET_ROTATION_STATE_CHANGED));
        assertTrue(first.changes().stream().anyMatch(change -> change.changeType()
            == AiProviderDeploymentChangeSet.ChangeType.VALIDATION_SCHEMA_CHANGED));
    }

    @Test
    void changeSetCannotAuthorizeApply() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderDeploymentChangeSet(
                "source",
                "1",
                "1".repeat(64),
                "target",
                "2",
                "2".repeat(64),
                List.of(),
                AiProviderDeploymentChangeSet.RiskLevel.NONE,
                "3".repeat(64),
                false,
                true,
                false,
                false
            )
        );
    }
}
