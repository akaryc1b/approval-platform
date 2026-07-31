package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderDeploymentProfileMetadataChangeTest {

    @Test
    void capabilityAndProviderChangesRequireHumanReview() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");

        AiProviderProtocolProfile capabilityChanged = profile(
            setup.profile(),
            setup.profile().providerVersion(),
            Set.of(AiCapability.APPROVAL_SUMMARY, AiCapability.RISK_SIGNALS)
        );
        AiProviderDeploymentChangeSet capabilityChanges = AiProviderDeploymentChangeSet.compare(
            setup.deployment(),
            deployment(setup, capabilityChanged)
        );

        assertTrue(capabilityChanges.humanApprovalRequired());
        assertTrue(capabilityChanges.changes().stream().anyMatch(change ->
            change.changeType()
                == AiProviderDeploymentChangeSet.ChangeType.VALIDATION_CAPABILITIES_CHANGED));

        AiProviderProtocolProfile providerChanged = profile(
            setup.profile(),
            new AiVersionReferences.ProviderVersion("provider-b", "1"),
            setup.profile().capabilities()
        );
        AiProviderDeploymentChangeSet providerChanges = AiProviderDeploymentChangeSet.compare(
            setup.deployment(),
            deployment(setup, providerChanged)
        );

        assertTrue(providerChanges.humanApprovalRequired());
        assertTrue(providerChanges.changes().stream().anyMatch(change ->
            change.changeType()
                == AiProviderDeploymentChangeSet.ChangeType.VALIDATION_PROVIDER_CHANGED));
    }

    private static AiProviderProtocolProfile profile(
        AiProviderProtocolProfile source,
        AiVersionReferences.ProviderVersion providerVersion,
        Set<AiCapability> capabilities
    ) {
        return new AiProviderProtocolProfile(
            source.validatorId(),
            source.validatorVersion(),
            providerVersion,
            capabilities,
            source.requestSchemaHash(),
            source.responseSchemaHash(),
            source.maximumRequestBytes(),
            source.maximumResponseBytes(),
            source.structuredOutputRequired(),
            source.unknownResponseFieldsRejected(),
            source.providerInvocationAllowed()
        );
    }

    private static AiProviderDeploymentSnapshot deployment(
        AiProviderDeploymentTestFixtures.Setup setup,
        AiProviderProtocolProfile profile
    ) {
        return AiProviderDeploymentTestFixtures.deployment(
            setup.advisory(),
            setup.binding(),
            setup.endpoint(),
            setup.egress(),
            setup.secret(),
            profile
        );
    }
}
