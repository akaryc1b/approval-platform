package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialMaterialContractTest {

    private static final Instant NOW = Instant.parse("2026-07-28T13:00:00Z");

    @Test
    void requestAndDescriptorEvidenceAreDeterministicAndRedacted() {
        Fixture fixture = fixture(
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );
        CredentialMaterialRequest request = fixture.request();
        CredentialMaterialDescriptor descriptor = CredentialMaterialDescriptor.loaded(
            request,
            hash("source"),
            1
        );

        assertEquals(request.evidenceHash(), fixture.request().evidenceHash());
        assertTrue(request.canonicalEvidenceJson().contains(request.tenantHash()));
        assertTrue(request.canonicalEvidenceJson().contains(request.credentialReferenceHash()));
        assertFalse(request.canonicalEvidenceJson().contains(request.tenantId()));
        assertFalse(request.canonicalEvidenceJson().contains(
            request.credentialReference().referenceId()
        ));
        assertFalse(descriptor.canonicalJson().contains(request.tenantId()));
        assertFalse(descriptor.toString().contains(request.credentialReference().referenceId()));
    }

    @Test
    void unselectedBackendAndExactDriftFailClosedWithoutFallback() {
        Fixture fixture = activeFixture();
        CredentialMaterialSource source = (reference, key, version) -> {
            throw new CredentialMaterialSource.SourceUnavailableException();
        };
        CredentialMaterialSourceException unselected = assertThrows(
            CredentialMaterialSourceException.class,
            () -> source.openLease(fixture.request())
        );
        assertEquals(CredentialMaterialFailure.BACKEND_NOT_SELECTED, unselected.failure());

        CredentialMaterialRequest routeDrift = copy(
            fixture.request(),
            hash("different-route"),
            fixture.request().expectedVersion()
        );
        CredentialMaterialSourceException drift = assertThrows(
            CredentialMaterialSourceException.class,
            () -> fixture.source().openLease(routeDrift)
        );
        assertEquals(CredentialMaterialFailure.ROUTE_DRIFT, drift.failure());

        CredentialMaterialVersion next = new CredentialMaterialVersion(
            "version-2",
            NOW.minusSeconds(1),
            NOW.plusSeconds(600),
            hash("version-2")
        );
        CredentialMaterialRequest rotated = copy(
            fixture.request(),
            fixture.request().routePlanHash(),
            next
        );
        assertNotEquals(fixture.request().evidenceHash(), rotated.evidenceHash());
        CredentialMaterialSourceException versionFailure = assertThrows(
            CredentialMaterialSourceException.class,
            () -> fixture.source().openLease(rotated)
        );
        assertEquals(CredentialMaterialFailure.VERSION_DRIFT, versionFailure.failure());
    }

    @Test
    void bindingStateAndValidityAreRejectedBeforeMaterialUse() {
        assertAdmissionFailure(
            fixture(CredentialBindingState.DISABLED, NOW.minusSeconds(60), NOW.plusSeconds(600)),
            CredentialMaterialFailure.CREDENTIAL_DISABLED
        );
        assertAdmissionFailure(
            fixture(CredentialBindingState.REVOKED, NOW.minusSeconds(60), NOW.plusSeconds(600)),
            CredentialMaterialFailure.CREDENTIAL_REVOKED
        );
        assertAdmissionFailure(
            fixture(
                CredentialBindingState.ROTATION_PENDING,
                NOW.minusSeconds(60),
                NOW.plusSeconds(600)
            ),
            CredentialMaterialFailure.AMBIGUOUS_ACTIVE_VERSIONS
        );
        assertAdmissionFailure(
            fixture(CredentialBindingState.ACTIVE, NOW.plusSeconds(60), NOW.plusSeconds(600)),
            CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID
        );
        assertAdmissionFailure(
            fixture(CredentialBindingState.ACTIVE, NOW.minusSeconds(600), NOW),
            CredentialMaterialFailure.CREDENTIAL_EXPIRED
        );
    }

    @Test
    void exceptionsAndFailureCodesRemainStableAndLowCardinality() {
        CredentialMaterialSourceException source = new CredentialMaterialSourceException(
            CredentialMaterialFailure.SOURCE_UNAVAILABLE
        );
        CredentialMaterialLeaseException lease = new CredentialMaterialLeaseException(
            CredentialMaterialFailure.LEASE_CLOSED
        );
        assertEquals(
            "credential material source rejected request: source_unavailable",
            source.getMessage()
        );
        assertEquals("credential material lease failure: lease_closed", lease.getMessage());
        assertFalse(source.getMessage().contains("tenant"));
        assertFalse(lease.getMessage().contains("secret"));
        for (CredentialMaterialFailure failure : CredentialMaterialFailure.values()) {
            assertTrue(failure.stableCode().matches("[a-z0-9_]+"));
            assertTrue(failure.stableCode().length() <= 32);
        }
    }

    private static Fixture activeFixture() {
        return fixture(
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );
    }

    private static Fixture fixture(
        CredentialBindingState state,
        Instant notBefore,
        Instant expiresAt
    ) {
        CredentialReference reference = new CredentialReference(
            "dingtalk",
            "credential-fixture"
        );
        CredentialBindingDescriptor descriptor = new CredentialBindingDescriptor(
            reference,
            "tenant-fixture",
            "dingtalk",
            CredentialMaterialType.APP_KEY_SECRET,
            "key-fixture",
            "version-1",
            state,
            notBefore,
            expiresAt,
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            "policy-1",
            Map.of("ownerClass", "platform-security")
        );
        CredentialMaterialVersion version = new CredentialMaterialVersion(
            descriptor.versionId(),
            notBefore,
            expiresAt,
            hash(descriptor.versionId())
        );
        CredentialMaterialRequest request = new CredentialMaterialRequest(
            reference,
            descriptor.tenantId(),
            descriptor.providerKey(),
            hash("route-plan"),
            descriptor.fingerprint(),
            version,
            descriptor.credentialType(),
            ConnectorOperation.ORGANIZATION_READ,
            "DINGTALK_JAVA21_FIXED_HTTPS_V1",
            "ORGANIZATION",
            CredentialMaterialEnvironment.NON_PRODUCTION,
            descriptor.policyVersion()
        );
        return new Fixture(
            request,
            descriptor,
            new DeterministicCredentialMaterialSource(request, descriptor, NOW)
        );
    }

    private static CredentialMaterialRequest copy(
        CredentialMaterialRequest source,
        String routePlanHash,
        CredentialMaterialVersion version
    ) {
        return new CredentialMaterialRequest(
            source.credentialReference(),
            source.tenantId(),
            source.providerKey(),
            routePlanHash,
            source.credentialBindingHash(),
            version,
            source.materialType(),
            source.operation(),
            source.protocolProfile(),
            source.capability(),
            source.environment(),
            source.policyRevision()
        );
    }

    private static void assertAdmissionFailure(
        Fixture fixture,
        CredentialMaterialFailure expected
    ) {
        CredentialMaterialSourceException failure = assertThrows(
            CredentialMaterialSourceException.class,
            () -> CredentialMaterialAdmission.requireAdmitted(
                fixture.request(),
                fixture.descriptor(),
                NOW
            )
        );
        assertEquals(expected, failure.failure());
    }

    private static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    private record Fixture(
        CredentialMaterialRequest request,
        CredentialBindingDescriptor descriptor,
        DeterministicCredentialMaterialSource source
    ) {
    }
}
