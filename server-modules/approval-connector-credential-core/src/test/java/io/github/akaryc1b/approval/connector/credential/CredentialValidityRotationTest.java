package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver.SecretBytesUse;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialValidityRotationTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final CredentialReference REFERENCE = new CredentialReference(
        "dingtalk",
        "credential:test-only-one"
    );

    @Test
    void closedBindingStatesAreRejectedBeforeMaterialResolution() {
        assertStateFailure(CredentialBindingState.DISABLED, CredentialResolutionStatus.CREDENTIAL_DISABLED);
        assertStateFailure(CredentialBindingState.REVOKED, CredentialResolutionStatus.CREDENTIAL_REVOKED);
        assertStateFailure(CredentialBindingState.NOT_YET_VALID, CredentialResolutionStatus.NOT_YET_VALID);
        assertStateFailure(CredentialBindingState.EXPIRED, CredentialResolutionStatus.EXPIRED);
        assertStateFailure(CredentialBindingState.ROTATION_PENDING, CredentialResolutionStatus.ROTATION_PENDING);
    }

    @Test
    void clockBoundariesAreDeterministicAndFailClosed() {
        assertDescriptorFailure(
            descriptor(
                CredentialBindingState.ACTIVE,
                "version-1",
                NOW.plusSeconds(1),
                NOW.plusSeconds(600)
            ),
            CredentialResolutionStatus.NOT_YET_VALID
        );
        assertDescriptorFailure(
            descriptor(
                CredentialBindingState.ACTIVE,
                "version-1",
                NOW.minusSeconds(600),
                NOW
            ),
            CredentialResolutionStatus.EXPIRED
        );
        CountingMaterialSource source = new CountingMaterialSource("version-1");
        CredentialResolutionEvidence evidence = resolver(
            descriptor(CredentialBindingState.ACTIVE, "version-1", NOW, NOW.plusSeconds(1)),
            source
        ).useCredential(request("version-1"), credential -> credential.useSecretBytes(bytes -> { }));

        assertEquals(CredentialResolutionStatus.RESOLVED, evidence.status());
        assertEquals(1, source.openCount);
    }

    @Test
    void rotationCompletesOnlyForOneExactBindingAndNewSourceVersion() {
        CredentialBindingDescriptor pending = descriptor(
            CredentialBindingState.ROTATION_PENDING,
            "version-1",
            NOW.minusSeconds(600),
            NOW.plusSeconds(600)
        );
        CredentialBindingDescriptor active = descriptor(
            CredentialBindingState.ACTIVE,
            "version-2",
            NOW,
            NOW.plusSeconds(600)
        );
        CredentialRotationEvidence evidence = evaluator().complete(
            pending,
            active,
            "version-2",
            CanonicalPayloadHash.sha256Utf8("test-source-version-2")
        );

        assertEquals(CredentialRotationStatus.COMPLETED, evidence.status());
        assertEquals("version-1", evidence.previousVersionId());
        assertEquals("version-2", evidence.activeVersionId());
        assertFalse(evidence.previousVersionFallbackAllowed());
        assertFalse(evidence.productionExecutionAuthorized());
        assertEquals(evidence.evidenceHash(), evidence.evidenceHash());
    }

    @Test
    void rotationRejectsSameVersionSourceMismatchAndDifferentBinding() {
        CredentialBindingDescriptor pending = descriptor(
            CredentialBindingState.ROTATION_PENDING,
            "version-1",
            NOW.minusSeconds(600),
            NOW.plusSeconds(600)
        );
        CredentialBindingDescriptor active = descriptor(
            CredentialBindingState.ACTIVE,
            "version-2",
            NOW,
            NOW.plusSeconds(600)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> evaluator().complete(
                pending,
                descriptor(CredentialBindingState.ACTIVE, "version-1", NOW, NOW.plusSeconds(600)),
                "version-1",
                CanonicalPayloadHash.sha256Utf8("test-same-version")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> evaluator().complete(
                pending,
                active,
                "version-0",
                CanonicalPayloadHash.sha256Utf8("test-old-source-version")
            )
        );
        CredentialBindingDescriptor anotherReference = new CredentialBindingDescriptor(
            new CredentialReference("dingtalk", "credential:test-only-two"),
            "tenant-test",
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            "version-2",
            CredentialBindingState.ACTIVE,
            NOW,
            NOW.plusSeconds(600),
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            "policy-1",
            Map.of()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> evaluator().complete(
                pending,
                anotherReference,
                "version-2",
                CanonicalPayloadHash.sha256Utf8("test-other-reference")
            )
        );
    }

    @Test
    void rotationRejectsNotYetValidOrExpiredActiveVersion() {
        CredentialBindingDescriptor pending = descriptor(
            CredentialBindingState.ROTATION_PENDING,
            "version-1",
            NOW.minusSeconds(600),
            NOW.plusSeconds(600)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> evaluator().complete(
                pending,
                descriptor(
                    CredentialBindingState.ACTIVE,
                    "version-2",
                    NOW.plusSeconds(1),
                    NOW.plusSeconds(600)
                ),
                "version-2",
                CanonicalPayloadHash.sha256Utf8("test-not-yet-valid")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> evaluator().complete(
                pending,
                descriptor(
                    CredentialBindingState.ACTIVE,
                    "version-2",
                    NOW.minusSeconds(600),
                    NOW
                ),
                "version-2",
                CanonicalPayloadHash.sha256Utf8("test-expired")
            )
        );
    }

    private static void assertStateFailure(
        CredentialBindingState state,
        CredentialResolutionStatus expected
    ) {
        assertDescriptorFailure(
            descriptor(state, "version-1", NOW.minusSeconds(600), NOW.plusSeconds(600)),
            expected
        );
    }

    private static void assertDescriptorFailure(
        CredentialBindingDescriptor descriptor,
        CredentialResolutionStatus expected
    ) {
        CountingMaterialSource source = new CountingMaterialSource("version-1");
        CredentialResolutionException exception = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(descriptor, source).useCredential(
                request("version-1"),
                credential -> { }
            )
        );
        assertEquals(expected, exception.evidence().status());
        assertEquals(0, source.openCount);
    }

    private static CredentialRotationEvaluator evaluator() {
        return new CredentialRotationEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ServerOwnedCredentialResolver resolver(
        CredentialBindingDescriptor descriptor,
        CredentialMaterialSource source
    ) {
        CredentialBindingCatalog catalog = reference -> Optional.of(descriptor);
        return new ServerOwnedCredentialResolver(
            catalog,
            source,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static CredentialResolutionRequest request(String versionId) {
        return new CredentialResolutionRequest(
            new TrustedConnectorExecutionContext("tenant-test", "dingtalk", REFERENCE, NOW),
            ConnectorOperation.ORGANIZATION_READ,
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            versionId
        );
    }

    private static CredentialBindingDescriptor descriptor(
        CredentialBindingState state,
        String versionId,
        Instant notBefore,
        Instant expiresAt
    ) {
        return new CredentialBindingDescriptor(
            REFERENCE,
            "tenant-test",
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            versionId,
            state,
            notBefore,
            expiresAt,
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            "policy-1",
            Map.of("lifecycleClass", "deterministic-test")
        );
    }

    private static final class CountingMaterialSource implements CredentialMaterialSource {
        private static final byte[] FIXTURE = "test-material-not-production".getBytes();
        private final String versionId;
        private int openCount;

        private CountingMaterialSource(String versionId) {
            this.versionId = versionId;
        }

        @Override
        public MaterialScope openMaterial(
            CredentialReference reference,
            String expectedKeyId,
            String expectedVersionId
        ) {
            openCount++;
            return new Scope(versionId, FIXTURE.clone());
        }

        private static final class Scope implements MaterialScope {
            private final String versionId;
            private final byte[] material;
            private boolean active = true;

            private Scope(String versionId, byte[] material) {
                this.versionId = versionId;
                this.material = material;
            }

            @Override
            public String keyId() {
                requireActive();
                return "key-test";
            }

            @Override
            public String versionId() {
                requireActive();
                return versionId;
            }

            @Override
            public String sourceEvidenceHash() {
                requireActive();
                return CanonicalPayloadHash.sha256Utf8("test-source:" + versionId);
            }

            @Override
            public void useSecretBytes(SecretBytesUse use) {
                requireActive();
                byte[] copy = material.clone();
                try {
                    use.accept(copy);
                } finally {
                    Arrays.fill(copy, (byte) 0);
                }
            }

            @Override
            public boolean active() {
                return active;
            }

            @Override
            public void close() {
                if (active) {
                    Arrays.fill(material, (byte) 0);
                    active = false;
                }
            }

            private void requireActive() {
                if (!active) {
                    throw new IllegalStateException("material scope is closed");
                }
            }
        }
    }
}
