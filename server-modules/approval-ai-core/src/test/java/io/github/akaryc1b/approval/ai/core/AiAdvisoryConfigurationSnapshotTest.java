package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAdvisoryConfigurationSnapshotTest {

    @Test
    void computesTheSameHashForEquivalentRouteAndPolicyOrdering() {
        AiVersionReferences first = AiConfigurationTestFixtures.versions(
            "provider-first",
            "model-first"
        );
        AiVersionReferences second = new AiVersionReferences(
            new AiVersionReferences.ProviderVersion("provider-second", "1.0.0"),
            new AiVersionReferences.ModelVersion(
                "provider-second",
                "model-second",
                "2026-07-23"
            ),
            first.promptTemplate(),
            first.knowledgeSource(),
            first.policy(),
            first.outputSchema()
        );
        AiProviderRoute firstRoute = AiConfigurationTestFixtures.route("alpha", 1, first);
        AiProviderRoute secondRoute = AiConfigurationTestFixtures.route("beta", 2, second);
        AiProviderRoutingPolicy forward = new AiProviderRoutingPolicy(
            true,
            true,
            false,
            List.of(firstRoute, secondRoute)
        );
        AiProviderRoutingPolicy reversed = new AiProviderRoutingPolicy(
            true,
            true,
            false,
            List.of(secondRoute, firstRoute)
        );
        Map<AiVersionReferences.PolicyVersion, AiDataMinimizationPolicy> firstPolicies =
            new LinkedHashMap<>();
        firstPolicies.put(first.policy(), AiConfigurationTestFixtures.dataPolicy(first));
        Map<AiVersionReferences.PolicyVersion, AiDataMinimizationPolicy> secondPolicies =
            new LinkedHashMap<>();
        secondPolicies.put(second.policy(), AiConfigurationTestFixtures.dataPolicy(second));

        AiAdvisoryConfigurationSnapshot left = AiAdvisoryConfigurationSnapshot.create(
            "snapshot",
            "4",
            forward,
            firstPolicies
        );
        AiAdvisoryConfigurationSnapshot right = AiAdvisoryConfigurationSnapshot.create(
            "snapshot",
            "4",
            reversed,
            secondPolicies
        );

        assertEquals(left.declaredContentHash(), right.declaredContentHash());
        assertEquals(64, left.declaredContentHash().length());
        assertTrue(left.contentHashMatches());
        assertEquals(
            AiAdvisoryConfigurationSnapshot.Stage.DRY_RUN_ONLY,
            left.stage()
        );
    }

    @Test
    void detectsTamperedHashAndRejectsAuthorityFlags() {
        AiVersionReferences versions = AiConfigurationTestFixtures.versions(
            "provider",
            "model"
        );
        AiProviderRoutingPolicy routing = new AiProviderRoutingPolicy(
            true,
            false,
            false,
            List.of(AiConfigurationTestFixtures.route("route", 1, versions))
        );
        AiAdvisoryConfigurationSnapshot valid = AiConfigurationTestFixtures.snapshot(
            routing,
            versions
        );
        AiAdvisoryConfigurationSnapshot tampered = new AiAdvisoryConfigurationSnapshot(
            valid.snapshotId(),
            valid.snapshotVersion(),
            "0".repeat(64),
            valid.routingPolicy(),
            valid.dataPolicies(),
            false,
            false
        );

        assertFalse(tampered.contentHashMatches());
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiAdvisoryConfigurationSnapshot(
                valid.snapshotId(),
                valid.snapshotVersion(),
                valid.declaredContentHash(),
                valid.routingPolicy(),
                valid.dataPolicies(),
                true,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiAdvisoryConfigurationSnapshot(
                valid.snapshotId(),
                valid.snapshotVersion(),
                valid.declaredContentHash(),
                valid.routingPolicy(),
                valid.dataPolicies(),
                false,
                true
            )
        );
    }
}
