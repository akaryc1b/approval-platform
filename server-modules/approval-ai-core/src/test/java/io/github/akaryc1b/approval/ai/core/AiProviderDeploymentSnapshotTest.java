package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderDeploymentSnapshotTest {

    @Test
    void equivalentMetadataOrderingProducesTheSameHash() {
        AiVersionReferences first = AiConfigurationTestFixtures.versions(
            "provider-a",
            "model-a"
        );
        AiVersionReferences second = AiConfigurationTestFixtures.versions(
            "provider-b",
            "model-b"
        );
        AiProviderRoutingPolicy routing = new AiProviderRoutingPolicy(
            true,
            false,
            false,
            List.of(
                AiConfigurationTestFixtures.route("route-b", 2, second),
                AiConfigurationTestFixtures.route("route-a", 1, first)
            )
        );
        AiAdvisoryConfigurationSnapshot advisory = AiConfigurationTestFixtures.snapshot(
            routing,
            first,
            second
        );

        AiProviderEndpointDescriptor firstEndpoint =
            AiProviderDeploymentTestFixtures.endpoint(first, "endpoint-a");
        AiProviderEndpointDescriptor secondEndpoint =
            AiProviderDeploymentTestFixtures.endpoint(second, "endpoint-b");
        AiProviderEgressPolicy firstEgress =
            AiProviderDeploymentTestFixtures.egress("egress-a", firstEndpoint);
        AiProviderEgressPolicy secondEgress =
            AiProviderDeploymentTestFixtures.egress("egress-b", secondEndpoint);
        AiExternalSecretReference firstSecret = AiProviderDeploymentTestFixtures.secret(
            first,
            "secret-a",
            AiExternalSecretReference.RotationState.CURRENT
        );
        AiExternalSecretReference secondSecret = AiProviderDeploymentTestFixtures.secret(
            second,
            "secret-b",
            AiExternalSecretReference.RotationState.CURRENT
        );
        AiProviderProtocolProfile firstProfile =
            AiProviderDeploymentTestFixtures.profile(first, "validator-a");
        AiProviderProtocolProfile secondProfile =
            AiProviderDeploymentTestFixtures.profile(second, "validator-b");
        AiProviderDeploymentSnapshot.DeploymentBinding firstBinding =
            AiProviderDeploymentTestFixtures.binding(
                first,
                firstEndpoint,
                firstEgress,
                firstSecret,
                firstProfile,
                AiProviderDeploymentSnapshot.OperationalStage.FAULT_DRILL_ONLY
            );
        AiProviderDeploymentSnapshot.DeploymentBinding secondBinding =
            AiProviderDeploymentTestFixtures.binding(
                second,
                secondEndpoint,
                secondEgress,
                secondSecret,
                secondProfile,
                AiProviderDeploymentSnapshot.OperationalStage.FAULT_DRILL_ONLY
            );

        AiProviderDeploymentSnapshot left = deployment(
            advisory,
            List.of(firstBinding, secondBinding),
            List.of(firstEndpoint, secondEndpoint),
            List.of(firstEgress, secondEgress),
            List.of(firstSecret, secondSecret),
            List.of(firstProfile, secondProfile)
        );
        AiProviderDeploymentSnapshot right = deployment(
            advisory,
            List.of(secondBinding, firstBinding),
            List.of(secondEndpoint, firstEndpoint),
            List.of(secondEgress, firstEgress),
            List.of(secondSecret, firstSecret),
            List.of(secondProfile, firstProfile)
        );

        assertEquals(left.declaredContentHash(), right.declaredContentHash());
        assertTrue(left.contentHashMatches());
        assertTrue(right.contentHashMatches());
        assertEquals(AiProviderDeploymentSnapshot.Stage.FAULT_DRILL_ONLY, left.stage());
    }

    @Test
    void tamperedHashRemainsDetectableAndAuthorityFlagsAreRejected() {
        AiProviderDeploymentTestFixtures.Setup setup =
            AiProviderDeploymentTestFixtures.setup("provider-a", "model-a");
        AiProviderDeploymentSnapshot valid = setup.deployment();
        AiProviderDeploymentSnapshot tampered = new AiProviderDeploymentSnapshot(
            valid.snapshotId(),
            valid.snapshotVersion(),
            "0".repeat(64),
            valid.advisoryConfigurationHash(),
            valid.bindings(),
            valid.endpoints(),
            valid.egressPolicies(),
            valid.secretReferences(),
            valid.validationProfiles(),
            false,
            false,
            false,
            false,
            false
        );

        assertFalse(tampered.contentHashMatches());
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderDeploymentSnapshot(
                valid.snapshotId(),
                valid.snapshotVersion(),
                valid.declaredContentHash(),
                valid.advisoryConfigurationHash(),
                valid.bindings(),
                valid.endpoints(),
                valid.egressPolicies(),
                valid.secretReferences(),
                valid.validationProfiles(),
                true,
                false,
                false,
                false,
                false
            )
        );
    }

    @Test
    void endpointSecretAndEgressContractsRejectUnsafeMetadata() {
        AiVersionReferences versions = AiConfigurationTestFixtures.versions(
            "provider-a",
            "model-a"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderEndpointDescriptor(
                "endpoint-a",
                versions.provider(),
                AiProviderEndpointDescriptor.Scheme.HTTPS,
                "localhost",
                443,
                "/v1/advisory",
                true,
                false,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiExternalSecretReference(
                "secret-a",
                "1",
                AiExternalSecretReference.StoreKind.EXTERNAL_SECRET_MANAGER,
                versions.provider(),
                Set.of(AiExternalSecretReference.Purpose.PROVIDER_AUTHENTICATION),
                AiExternalSecretReference.RotationState.CURRENT,
                true,
                false
            )
        );
        AiProviderEndpointDescriptor endpoint =
            AiProviderDeploymentTestFixtures.endpoint(versions, "endpoint-a");
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderEgressPolicy(
                "egress-a",
                "1",
                "0".repeat(64),
                Set.of(endpoint),
                true,
                true,
                false,
                true
            )
        );
    }

    private static AiProviderDeploymentSnapshot deployment(
        AiAdvisoryConfigurationSnapshot advisory,
        List<AiProviderDeploymentSnapshot.DeploymentBinding> bindings,
        List<AiProviderEndpointDescriptor> endpoints,
        List<AiProviderEgressPolicy> egress,
        List<AiExternalSecretReference> secrets,
        List<AiProviderProtocolProfile> profiles
    ) {
        Map<AiVersionReferences.ProviderVersion, AiProviderDeploymentSnapshot.DeploymentBinding>
            bindingMap = new LinkedHashMap<>();
        bindings.forEach(value -> bindingMap.put(value.providerVersion(), value));
        Map<String, AiProviderEndpointDescriptor> endpointMap = new LinkedHashMap<>();
        endpoints.forEach(value -> endpointMap.put(value.endpointId(), value));
        Map<String, AiProviderEgressPolicy> egressMap = new LinkedHashMap<>();
        egress.forEach(value -> egressMap.put(value.authorizationKey(), value));
        Map<String, AiExternalSecretReference> secretMap = new LinkedHashMap<>();
        secrets.forEach(value -> secretMap.put(value.authorizationKey(), value));
        Map<String, AiProviderProtocolProfile> profileMap = new LinkedHashMap<>();
        profiles.forEach(value -> profileMap.put(value.authorizationKey(), value));
        return AiProviderDeploymentSnapshot.create(
            "deployment-combined",
            "1",
            advisory.declaredContentHash(),
            bindingMap,
            endpointMap,
            egressMap,
            secretMap,
            profileMap
        );
    }
}
