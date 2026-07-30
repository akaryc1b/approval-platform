package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialBindingContractsTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final CredentialReference REFERENCE = new CredentialReference(
        "dingtalk",
        "credential:test-only-one"
    );

    @Test
    void descriptorIsDeterministicAndSecretFree() {
        CredentialBindingDescriptor descriptor = descriptor(Map.of(
            "regionClass", "test",
            "sourceClass", "in-memory-fixture"
        ));
        CredentialBindingDescriptor reordered = descriptor(Map.of(
            "sourceClass", "in-memory-fixture",
            "regionClass", "test"
        ));

        assertEquals(descriptor.canonicalJson(), reordered.canonicalJson());
        assertEquals(descriptor.fingerprint(), reordered.fingerprint());
        assertFalse(descriptor.canonicalJson().contains(REFERENCE.referenceId()));
        assertFalse(descriptor.toString().toLowerCase().contains("secret-bytes"));
    }

    @Test
    void evidenceIsDeterministicAndCannotAuthorizeExecution() {
        CredentialBindingDescriptor descriptor = descriptor(Map.of());
        CredentialResolutionEvidence evidence = new CredentialResolutionEvidence(
            CredentialResolutionStatus.RESOLVED,
            descriptor.referenceHash(),
            descriptor.providerKey(),
            descriptor.keyId(),
            descriptor.versionId(),
            descriptor.credentialType(),
            ConnectorOperation.ORGANIZATION_READ,
            descriptor.policyVersion(),
            NOW,
            descriptor.expiresAt(),
            descriptor.fingerprint(),
            CanonicalPayloadHash.sha256Utf8("test-source-evidence"),
            Map.of("resolutionMode", "foundation")
        );

        assertEquals(evidence.evidenceHash(), evidence.evidenceHash());
        assertFalse(evidence.productionExecutionAuthorized());
        assertFalse(evidence.authorizationSatisfied());
        assertFalse(evidence.auditRecorded());
    }

    @Test
    void descriptorRejectsSensitiveOrUnboundedMetadata() {
        assertThrows(
            IllegalArgumentException.class,
            () -> descriptor(Map.of("accessToken", "test-value"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> descriptor(Map.ofEntries(
                Map.entry("k01", "v"), Map.entry("k02", "v"),
                Map.entry("k03", "v"), Map.entry("k04", "v"),
                Map.entry("k05", "v"), Map.entry("k06", "v"),
                Map.entry("k07", "v"), Map.entry("k08", "v"),
                Map.entry("k09", "v"), Map.entry("k10", "v"),
                Map.entry("k11", "v"), Map.entry("k12", "v"),
                Map.entry("k13", "v"), Map.entry("k14", "v"),
                Map.entry("k15", "v"), Map.entry("k16", "v"),
                Map.entry("k17", "v")
            ))
        );
    }

    @Test
    void descriptorRequiresExactProviderAndClosedOperationSet() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new CredentialBindingDescriptor(
                REFERENCE,
                "tenant-test",
                "another-provider",
                CredentialMaterialType.APP_KEY_SECRET,
                "key-test",
                "version-1",
                CredentialBindingState.ACTIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(300),
                Set.of(ConnectorOperation.ORGANIZATION_READ),
                "policy-1",
                Map.of()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new CredentialBindingDescriptor(
                REFERENCE,
                "tenant-test",
                "dingtalk",
                CredentialMaterialType.APP_KEY_SECRET,
                "key-test",
                "version-1",
                CredentialBindingState.ACTIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(300),
                Set.of(),
                "policy-1",
                Map.of()
            )
        );
    }

    @Test
    void evidenceRecordsOnlyHashesAndNonSecretIdentifiers() {
        Set<String> components = Arrays.stream(
            CredentialResolutionEvidence.class.getRecordComponents()
        ).map(RecordComponent::getName).collect(Collectors.toSet());

        for (String forbidden : Set.of(
            "secret",
            "secretBytes",
            "token",
            "password",
            "authorizationHeader",
            "privateKey"
        )) {
            assertFalse(components.contains(forbidden));
        }
        assertTrue(components.contains("credentialReferenceHash"));
        assertTrue(components.contains("descriptorFingerprint"));
        assertTrue(components.contains("sourceEvidenceHash"));
    }

    @Test
    void resolutionRequestBindsTrustedContextOperationKeyAndVersion() {
        TrustedConnectorExecutionContext context = new TrustedConnectorExecutionContext(
            "tenant-test",
            "dingtalk",
            REFERENCE,
            NOW
        );
        CredentialResolutionRequest request = new CredentialResolutionRequest(
            context,
            ConnectorOperation.ORGANIZATION_READ,
            CredentialMaterialType.APP_KEY_SECRET,
            "key-test",
            "version-1"
        );
        CredentialResolutionRequest anotherVersion = new CredentialResolutionRequest(
            context,
            ConnectorOperation.ORGANIZATION_READ,
            CredentialMaterialType.APP_KEY_SECRET,
            "key-test",
            "version-2"
        );

        assertEquals("version-1", request.expectedVersionId());
        assertNotEquals(request, anotherVersion);
    }

    private static CredentialBindingDescriptor descriptor(Map<String, String> metadata) {
        return new CredentialBindingDescriptor(
            REFERENCE,
            "tenant-test",
            "dingtalk",
            CredentialMaterialType.APP_KEY_SECRET,
            "key-test",
            "version-1",
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(300),
            Set.of(
                ConnectorOperation.IDENTITY_RESOLVE,
                ConnectorOperation.ORGANIZATION_READ
            ),
            "policy-1",
            metadata
        );
    }
}
