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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerOwnedCredentialResolverHardeningTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final CredentialReference REFERENCE = new CredentialReference(
        "dingtalk",
        "credential:test-only-hardening"
    );

    @Test
    void explicitCredentialCloseImmediatelyClosesTheMaterialScopeExactlyOnce() {
        FixtureMaterialSource source = new FixtureMaterialSource();
        ServerOwnedCredentialResolver resolver = resolver(reference -> Optional.of(descriptor()), source);

        resolver.useCredential(request(), credential -> {
            assertEquals(0, source.closeCount);
            credential.close();
            assertFalse(credential.active());
            assertEquals(1, source.closeCount);
            credential.close();
            assertEquals(1, source.closeCount);
        });

        assertEquals(1, source.closeCount);
        assertTrue(source.materialZeroized);
    }

    @Test
    void sourceUnavailableDuringSecretUseReturnsDeterministicEvidence() {
        FixtureMaterialSource source = new FixtureMaterialSource();
        source.useFailure = new CredentialMaterialSource.SourceUnavailableException();

        CredentialResolutionException failure = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(reference -> Optional.of(descriptor()), source)
                .useCredential(request(), credential -> credential.useSecretBytes(bytes -> { }))
        );

        assertEquals(
            CredentialResolutionStatus.MATERIAL_SOURCE_UNAVAILABLE,
            failure.evidence().status()
        );
        assertEquals(1, source.closeCount);
        assertTrue(source.materialZeroized);
    }

    @Test
    void invalidSecretBytesAndSourceEvidenceHashReturnMaterialInvalidEvidence() {
        FixtureMaterialSource empty = new FixtureMaterialSource();
        empty.material = new byte[0];
        CredentialResolutionException emptyFailure = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(reference -> Optional.of(descriptor()), empty)
                .useCredential(request(), credential -> credential.useSecretBytes(bytes -> { }))
        );
        assertEquals(CredentialResolutionStatus.MATERIAL_INVALID, emptyFailure.evidence().status());
        assertEquals(1, empty.closeCount);

        FixtureMaterialSource invalidHash = new FixtureMaterialSource();
        invalidHash.sourceEvidenceHash = "not-a-sha-256";
        CredentialResolutionException hashFailure = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(reference -> Optional.of(descriptor()), invalidHash)
                .useCredential(request(), credential -> { })
        );
        assertEquals(CredentialResolutionStatus.MATERIAL_INVALID, hashFailure.evidence().status());
        assertEquals(1, invalidHash.closeCount);
    }

    @Test
    void materialCloseFailureReturnsDeterministicEvidence() {
        FixtureMaterialSource source = new FixtureMaterialSource();
        source.closeFailure = new CredentialMaterialSource.SourceUnavailableException();

        CredentialResolutionException failure = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(reference -> Optional.of(descriptor()), source)
                .useCredential(request(), credential -> { })
        );

        assertEquals(
            CredentialResolutionStatus.MATERIAL_SOURCE_UNAVAILABLE,
            failure.evidence().status()
        );
        assertEquals(1, source.closeCount);
    }

    @Test
    void catalogInfrastructureFailureReturnsUnknownWithoutOpeningMaterial() {
        FixtureMaterialSource source = new FixtureMaterialSource();

        CredentialResolutionException failure = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(reference -> {
                throw new IllegalStateException("test catalog unavailable");
            }, source).useCredential(request(), credential -> { })
        );

        assertEquals(CredentialResolutionStatus.UNKNOWN, failure.evidence().status());
        assertEquals(0, source.openCount);
    }

    @Test
    void trustedCallbackFailureIsPreservedAndCannotBeMisclassifiedAsSourceFailure() {
        FixtureMaterialSource source = new FixtureMaterialSource();
        AtomicReference<byte[]> callbackBytes = new AtomicReference<>();

        CredentialMaterialSource.SourceUnavailableException callbackFailure = assertThrows(
            CredentialMaterialSource.SourceUnavailableException.class,
            () -> resolver(reference -> Optional.of(descriptor()), source)
                .useCredential(request(), credential -> credential.useSecretBytes(bytes -> {
                    callbackBytes.set(bytes);
                    throw new CredentialMaterialSource.SourceUnavailableException();
                }))
        );

        assertEquals("credential material source is unavailable", callbackFailure.getMessage());
        assertTrue(allZero(callbackBytes.get()));
        assertEquals(1, source.closeCount);
        assertTrue(source.materialZeroized);
    }

    private static ServerOwnedCredentialResolver resolver(
        CredentialBindingCatalog catalog,
        CredentialMaterialSource source
    ) {
        return new ServerOwnedCredentialResolver(
            catalog,
            source,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static CredentialResolutionRequest request() {
        return new CredentialResolutionRequest(
            new TrustedConnectorExecutionContext("tenant-test", "dingtalk", REFERENCE, NOW),
            ConnectorOperation.ORGANIZATION_READ,
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            "version-1"
        );
    }

    private static CredentialBindingDescriptor descriptor() {
        return new CredentialBindingDescriptor(
            REFERENCE,
            "tenant-test",
            "dingtalk",
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            "version-1",
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600),
            Set.of(ConnectorOperation.ORGANIZATION_READ),
            "policy-1",
            Map.of("ownerClass", "platform-security")
        );
    }

    private static boolean allZero(byte[] value) {
        return value != null && Arrays.equals(value, new byte[value.length]);
    }

    private static final class FixtureMaterialSource implements CredentialMaterialSource {

        private byte[] material = "test-hardening-material".getBytes();
        private String sourceEvidenceHash = CanonicalPayloadHash.sha256Utf8("test-source:version-1");
        private RuntimeException useFailure;
        private RuntimeException closeFailure;
        private int openCount;
        private int closeCount;
        private boolean materialZeroized;

        @Override
        public MaterialScope openMaterial(
            CredentialReference reference,
            String expectedKeyId,
            String expectedVersionId
        ) {
            openCount++;
            return new Scope(material.clone());
        }

        private final class Scope implements MaterialScope {

            private final byte[] scopedMaterial;
            private boolean active = true;

            private Scope(byte[] scopedMaterial) {
                this.scopedMaterial = scopedMaterial;
            }

            @Override
            public String keyId() {
                requireActive();
                return "key-test";
            }

            @Override
            public String versionId() {
                requireActive();
                return "version-1";
            }

            @Override
            public String sourceEvidenceHash() {
                requireActive();
                return sourceEvidenceHash;
            }

            @Override
            public void useSecretBytes(SecretBytesUse use) {
                requireActive();
                if (useFailure != null) {
                    throw useFailure;
                }
                byte[] sourceCopy = scopedMaterial.clone();
                try {
                    use.accept(sourceCopy);
                } finally {
                    Arrays.fill(sourceCopy, (byte) 0);
                }
            }

            @Override
            public boolean active() {
                return active;
            }

            @Override
            public void close() {
                if (!active) {
                    return;
                }
                closeCount++;
                Arrays.fill(scopedMaterial, (byte) 0);
                materialZeroized = allZero(scopedMaterial);
                active = false;
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }

            private void requireActive() {
                if (!active) {
                    throw new IllegalStateException("test material scope is closed");
                }
            }
        }
    }
}
