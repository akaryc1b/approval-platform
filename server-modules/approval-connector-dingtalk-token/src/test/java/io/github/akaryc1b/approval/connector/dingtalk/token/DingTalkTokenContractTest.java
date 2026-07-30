package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkTokenContractTest {

    @Test
    void requestEvidenceIsDeterministicHashOnlyAndNonProduction() {
        var fixture = DingTalkTokenTestFixtures.fixture();
        DingTalkTokenRequest request = fixture.request();

        assertEquals(request.evidenceHash(), fixture.request().evidenceHash());
        assertTrue(request.canonicalEvidenceJson().contains(request.familyHash()));
        assertTrue(request.canonicalEvidenceJson().contains(request.cacheKeyHash()));
        assertFalse(request.canonicalEvidenceJson().contains(request.trustedTenantId()));
        assertFalse(request.canonicalEvidenceJson().contains(
            request.applicationCredentialRequest().credentialReference().referenceId()
        ));
        assertFalse(request.toString().contains(request.trustedTenantId()));
    }

    @Test
    void productionMaterialRequestIsRejectedBeforeAnyEndpointAccess() {
        var fixture = DingTalkTokenTestFixtures.fixture();
        CredentialMaterialRequest source = fixture.request().applicationCredentialRequest();
        CredentialMaterialRequest production = new CredentialMaterialRequest(
            source.credentialReference(),
            source.tenantId(),
            source.providerKey(),
            source.routePlanHash(),
            source.credentialBindingHash(),
            source.expectedVersion(),
            source.materialType(),
            source.operation(),
            source.protocolProfile(),
            source.capability(),
            CredentialMaterialEnvironment.PRODUCTION,
            source.policyRevision()
        );

        DingTalkTokenLifecycleException problem = assertThrows(
            DingTalkTokenLifecycleException.class,
            () -> new DingTalkTokenRequest(
                source.tenantId(),
                fixture.request().routePlan(),
                production,
                fixture.request().killSwitchRevision(),
                fixture.request().tokenPolicyVersion()
            )
        );
        assertEquals(DingTalkTokenFailure.PRODUCTION_NOT_AUTHORIZED, problem.failure());
    }

    @Test
    void endpointRequestUsesOnlyHashedAndVersionedEvidence() {
        var fixture = DingTalkTokenTestFixtures.fixture();
        DingTalkTokenEndpointRequest first = DingTalkTokenEndpointRequest.create(
            fixture.request(),
            7
        );
        DingTalkTokenEndpointRequest second = DingTalkTokenEndpointRequest.create(
            fixture.request(),
            7
        );
        DingTalkTokenEndpointRequest changed = DingTalkTokenEndpointRequest.create(
            fixture.request(),
            8
        );

        assertEquals(first, second);
        assertNotEquals(first.evidenceHash(), changed.evidenceHash());
        assertFalse(first.toString().contains(fixture.request().trustedTenantId()));
        assertFalse(first.toString().contains(
            fixture.request().applicationCredentialRequest().credentialReference().referenceId()
        ));
    }

    @Test
    void policyBoundsAreClosedAndFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new DingTalkTokenPolicy(
            "policy-v1",
            Duration.ofMinutes(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(5),
            Duration.ofSeconds(1),
            16
        ));
        assertThrows(IllegalArgumentException.class, () -> new DingTalkTokenPolicy(
            "policy-v1",
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(5),
            Duration.ofSeconds(31),
            16
        ));
        assertThrows(IllegalArgumentException.class, () -> new DingTalkTokenPolicy(
            "policy-v1",
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(5),
            Duration.ofSeconds(1),
            0
        ));
    }

    @Test
    void failuresExposeOnlyStableLowCardinalityCodes() {
        for (DingTalkTokenFailure failure : DingTalkTokenFailure.values()) {
            assertTrue(failure.stableCode().matches("[a-z0-9_]+"));
            assertTrue(failure.stableCode().length() <= 32);
        }
        DingTalkTokenLifecycleException problem = new DingTalkTokenLifecycleException(
            DingTalkTokenFailure.ENDPOINT_UNAVAILABLE
        );
        assertEquals(DingTalkTokenFailure.ENDPOINT_UNAVAILABLE, problem.failure());
        assertFalse(problem.getMessage().contains("tenant"));
        assertFalse(problem.getMessage().contains("app-secret"));
    }
}
