package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class AiProviderDeploymentTestFixtures {

    private static final String REQUEST_HASH = "1".repeat(64);
    private static final String RESPONSE_HASH = "2".repeat(64);

    private AiProviderDeploymentTestFixtures() {
    }

    static Setup setup(String providerId, String modelId) {
        AiVersionReferences versions = AiConfigurationTestFixtures.versions(providerId, modelId);
        AiProviderRoute route = AiConfigurationTestFixtures.route("route-" + providerId, 1, versions);
        AiProviderRoutingPolicy routing = new AiProviderRoutingPolicy(
            true,
            false,
            false,
            List.of(route)
        );
        AiAdvisoryConfigurationSnapshot advisory = AiConfigurationTestFixtures.snapshot(
            routing,
            versions
        );
        AiAdvisoryArtifactRegistry artifacts = AiConfigurationTestFixtures.artifactRegistry(
            versions
        );
        AiProviderRegistry providers = AiConfigurationTestFixtures.providerRegistry(
            artifacts,
            AiConfigurationTestFixtures.provider(versions)
        );
        AiAdvisoryPreflightReport advisoryPreflight = new AiAdvisoryStartupPreflight().inspect(
            advisory,
            providers,
            artifacts
        );

        AiProviderEndpointDescriptor endpoint = endpoint(versions, "endpoint-" + providerId);
        AiProviderEgressPolicy egress = AiProviderEgressPolicy.create(
            "egress-" + providerId,
            "1",
            Set.of(endpoint)
        );
        AiExternalSecretReference secret = secret(
            versions,
            "secret-" + providerId,
            AiExternalSecretReference.RotationState.CURRENT
        );
        AiProviderProtocolProfile profile = profile(versions, "validator-" + providerId);
        AiProviderDeploymentSnapshot.DeploymentBinding binding = binding(
            versions,
            endpoint,
            egress,
            secret,
            profile,
            AiProviderDeploymentSnapshot.OperationalStage.FAULT_DRILL_ONLY
        );
        AiProviderDeploymentSnapshot deployment = deployment(
            advisory,
            binding,
            endpoint,
            egress,
            secret,
            profile
        );
        DeterministicProtocolValidator validator = new DeterministicProtocolValidator(
            profile,
            DeterministicProtocolValidator.Mode.VALID
        );
        return new Setup(
            versions,
            route,
            advisory,
            advisoryPreflight,
            endpoint,
            egress,
            secret,
            profile,
            binding,
            deployment,
            validator
        );
    }

    static AiProviderEndpointDescriptor endpoint(
        AiVersionReferences versions,
        String endpointId
    ) {
        return new AiProviderEndpointDescriptor(
            endpointId,
            versions.provider(),
            AiProviderEndpointDescriptor.Scheme.HTTPS,
            versions.provider().providerId() + ".example.com",
            443,
            "/v1/advisory",
            true,
            false,
            false
        );
    }

    static AiProviderEgressPolicy egress(
        String policyId,
        AiProviderEndpointDescriptor... endpoints
    ) {
        return AiProviderEgressPolicy.create(policyId, "1", Set.of(endpoints));
    }

    static AiExternalSecretReference secret(
        AiVersionReferences versions,
        String referenceId,
        AiExternalSecretReference.RotationState state
    ) {
        return new AiExternalSecretReference(
            referenceId,
            "1",
            AiExternalSecretReference.StoreKind.EXTERNAL_SECRET_MANAGER,
            versions.provider(),
            Set.of(AiExternalSecretReference.Purpose.PROVIDER_AUTHENTICATION),
            state,
            false,
            false
        );
    }

    static AiProviderProtocolProfile profile(
        AiVersionReferences versions,
        String validatorId
    ) {
        return new AiProviderProtocolProfile(
            validatorId,
            "1",
            versions.provider(),
            Set.of(AiCapability.APPROVAL_SUMMARY),
            REQUEST_HASH,
            RESPONSE_HASH,
            65_536,
            65_536,
            true,
            true,
            false
        );
    }

    static AiProviderDeploymentSnapshot.DeploymentBinding binding(
        AiVersionReferences versions,
        AiProviderEndpointDescriptor endpoint,
        AiProviderEgressPolicy egress,
        AiExternalSecretReference secret,
        AiProviderProtocolProfile profile,
        AiProviderDeploymentSnapshot.OperationalStage stage
    ) {
        return new AiProviderDeploymentSnapshot.DeploymentBinding(
            versions.provider(),
            endpoint.endpointId(),
            egress.authorizationKey(),
            Set.of(secret.authorizationKey()),
            profile.authorizationKey(),
            stage
        );
    }

    static AiProviderDeploymentSnapshot deployment(
        AiAdvisoryConfigurationSnapshot advisory,
        AiProviderDeploymentSnapshot.DeploymentBinding binding,
        AiProviderEndpointDescriptor endpoint,
        AiProviderEgressPolicy egress,
        AiExternalSecretReference secret,
        AiProviderProtocolProfile profile
    ) {
        return AiProviderDeploymentSnapshot.create(
            "deployment-" + binding.providerVersion().providerId(),
            "1",
            advisory.declaredContentHash(),
            Map.of(binding.providerVersion(), binding),
            Map.of(endpoint.endpointId(), endpoint),
            Map.of(egress.authorizationKey(), egress),
            Map.of(secret.authorizationKey(), secret),
            Map.of(profile.authorizationKey(), profile)
        );
    }

    record Setup(
        AiVersionReferences versions,
        AiProviderRoute route,
        AiAdvisoryConfigurationSnapshot advisory,
        AiAdvisoryPreflightReport advisoryPreflight,
        AiProviderEndpointDescriptor endpoint,
        AiProviderEgressPolicy egress,
        AiExternalSecretReference secret,
        AiProviderProtocolProfile profile,
        AiProviderDeploymentSnapshot.DeploymentBinding binding,
        AiProviderDeploymentSnapshot deployment,
        DeterministicProtocolValidator validator
    ) {
    }
}
