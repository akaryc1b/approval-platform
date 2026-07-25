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

class ServerOwnedCredentialResolverTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final CredentialReference REFERENCE = new CredentialReference(
        "dingtalk",
        "credential:test-only-one"
    );

    @Test
    void exactResolutionClosesEveryScopeAndZeroizesEveryTemporaryCopy() {
        MutableCatalog catalog = new MutableCatalog(descriptor(REFERENCE, "tenant-test", "version-1"));
        FixtureMaterialSource source = new FixtureMaterialSource("key-test", "version-1");
        ServerOwnedCredentialResolver resolver = resolver(catalog, source);
        AtomicReference<ResolvedScopedCredential> capturedScope = new AtomicReference<>();
        AtomicReference<byte[]> capturedBytes = new AtomicReference<>();

        CredentialResolutionEvidence evidence = resolver.useCredential(request(), credential -> {
            capturedScope.set(credential);
            credential.useSecretBytes(bytes -> {
                capturedBytes.set(bytes);
                assertFalse(allZero(bytes));
            });
            assertTrue(credential.active());
        });

        assertEquals(CredentialResolutionStatus.RESOLVED, evidence.status());
        assertFalse(evidence.productionExecutionAuthorized());
        assertFalse(capturedScope.get().active());
        assertThrows(IllegalStateException.class, capturedScope.get()::keyId);
        assertTrue(allZero(capturedBytes.get()));
        assertTrue(source.lastScopeClosed);
        assertTrue(source.lastSourceCopyZeroized);
        assertEquals(1, source.openCount);
    }

    @Test
    void callbackExceptionStillClosesAndZeroizes() {
        FixtureMaterialSource source = new FixtureMaterialSource("key-test", "version-1");
        AtomicReference<byte[]> capturedBytes = new AtomicReference<>();

        assertThrows(
            IllegalArgumentException.class,
            () -> resolver(
                new MutableCatalog(descriptor(REFERENCE, "tenant-test", "version-1")),
                source
            ).useCredential(request(), credential -> credential.useSecretBytes(bytes -> {
                capturedBytes.set(bytes);
                throw new IllegalArgumentException("test callback failure");
            }))
        );

        assertTrue(allZero(capturedBytes.get()));
        assertTrue(source.lastScopeClosed);
        assertTrue(source.lastSourceCopyZeroized);
    }

    @Test
    void providerReferenceTenantOperationAndVersionMustMatchExactly() {
        assertFailure(
            descriptor(new CredentialReference("feishu", "credential:test-only-two"),
                "tenant-test", "version-1"),
            request(),
            CredentialResolutionStatus.PROVIDER_MISMATCH
        );
        assertFailure(
            descriptor(new CredentialReference("dingtalk", "credential:test-only-two"),
                "tenant-test", "version-1"),
            request(),
            CredentialResolutionStatus.REFERENCE_MISMATCH
        );
        assertFailure(
            descriptor(REFERENCE, "tenant-other", "version-1"),
            request(),
            CredentialResolutionStatus.TENANT_MISMATCH
        );
        assertFailure(
            descriptor(REFERENCE, "tenant-test", "version-1"),
            new CredentialResolutionRequest(
                context(),
                ConnectorOperation.NOTIFICATION_SEND,
                CredentialMaterialType.ACCESS_TOKEN,
                "key-test",
                "version-1"
            ),
            CredentialResolutionStatus.OPERATION_NOT_ALLOWED
        );
        assertFailure(
            descriptor(REFERENCE, "tenant-test", "version-1"),
            new CredentialResolutionRequest(
                context(),
                ConnectorOperation.ORGANIZATION_READ,
                CredentialMaterialType.ACCESS_TOKEN,
                "key-test",
                "version-2"
            ),
            CredentialResolutionStatus.VERSION_MISMATCH
        );
    }

    @Test
    void missingOrUnavailableMaterialDoesNotFallback() {
        FixtureMaterialSource unused = new FixtureMaterialSource("key-test", "version-1");
        CredentialResolutionException missing = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(reference -> Optional.empty(), unused)
                .useCredential(request(), credential -> { })
        );
        assertEquals(CredentialResolutionStatus.REFERENCE_NOT_FOUND, missing.evidence().status());
        assertEquals(0, unused.openCount);

        FixtureMaterialSource unavailable = new FixtureMaterialSource("key-test", "version-1");
        unavailable.unavailable = true;
        CredentialResolutionException failure = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(
                new MutableCatalog(descriptor(REFERENCE, "tenant-test", "version-1")),
                unavailable
            ).useCredential(request(), credential -> { })
        );
        assertEquals(
            CredentialResolutionStatus.MATERIAL_SOURCE_UNAVAILABLE,
            failure.evidence().status()
        );
        assertEquals(1, unavailable.openCount);
    }

    @Test
    void sourceVersionAndMidResolutionDescriptorMustRemainStable() {
        FixtureMaterialSource oldVersion = new FixtureMaterialSource("key-test", "version-0");
        CredentialResolutionException sourceMismatch = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(
                new MutableCatalog(descriptor(REFERENCE, "tenant-test", "version-1")),
                oldVersion
            ).useCredential(request(), credential -> { })
        );
        assertEquals(
            CredentialResolutionStatus.VERSION_MISMATCH,
            sourceMismatch.evidence().status()
        );
        assertEquals(1, oldVersion.openCount);

        MutableCatalog changing = new MutableCatalog(
            descriptor(REFERENCE, "tenant-test", "version-1")
        );
        changing.next = descriptor(REFERENCE, "tenant-test", "version-2");
        CredentialResolutionException changed = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(changing, new FixtureMaterialSource("key-test", "version-1"))
                .useCredential(request(), credential -> { })
        );
        assertEquals(CredentialResolutionStatus.VERSION_MISMATCH, changed.evidence().status());
    }

    @Test
    void closeIsIdempotentAndLegacyGenericEscapePathsAreRejected() {
        ServerOwnedCredentialResolver resolver = resolver(
            new MutableCatalog(descriptor(REFERENCE, "tenant-test", "version-1")),
            new FixtureMaterialSource("key-test", "version-1")
        );
        resolver.useCredential(request(), credential -> {
            credential.close();
            credential.close();
            assertFalse(credential.active());
            assertThrows(IllegalStateException.class, credential::versionId);
            assertThrows(
                UnsupportedOperationException.class,
                () -> credential.withSecretBytes(bytes -> bytes)
            );
        });
        resolver.close();
        resolver.close();

        assertThrows(
            IllegalStateException.class,
            () -> resolver.useCredential(request(), credential -> { })
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> resolver.withCredential(context(), credential -> credential)
        );
        assertFalse(resolver.toString().contains(FixtureMaterialSource.FIXTURE_TEXT));
    }

    @Test
    void exceptionAndScopeRenderingNeverContainMaterial() {
        FixtureMaterialSource source = new FixtureMaterialSource("key-test", "version-1");
        source.unavailable = true;
        CredentialResolutionException exception = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(
                new MutableCatalog(descriptor(REFERENCE, "tenant-test", "version-1")),
                source
            ).useCredential(request(), credential -> { })
        );

        assertFalse(exception.getMessage().contains(REFERENCE.referenceId()));
        assertFalse(exception.getMessage().contains(FixtureMaterialSource.FIXTURE_TEXT));
        assertFalse(exception.evidence().canonicalJson().contains(FixtureMaterialSource.FIXTURE_TEXT));
    }

    private static void assertFailure(
        CredentialBindingDescriptor descriptor,
        CredentialResolutionRequest request,
        CredentialResolutionStatus expected
    ) {
        FixtureMaterialSource source = new FixtureMaterialSource("key-test", "version-1");
        CredentialResolutionException exception = assertThrows(
            CredentialResolutionException.class,
            () -> resolver(new MutableCatalog(descriptor), source)
                .useCredential(request, credential -> { })
        );
        assertEquals(expected, exception.evidence().status());
        assertEquals(0, source.openCount);
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
            context(),
            ConnectorOperation.ORGANIZATION_READ,
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            "version-1"
        );
    }

    private static TrustedConnectorExecutionContext context() {
        return new TrustedConnectorExecutionContext(
            "tenant-test",
            "dingtalk",
            REFERENCE,
            NOW
        );
    }

    private static CredentialBindingDescriptor descriptor(
        CredentialReference reference,
        String tenantId,
        String versionId
    ) {
        return new CredentialBindingDescriptor(
            reference,
            tenantId,
            reference.providerKey(),
            CredentialMaterialType.ACCESS_TOKEN,
            "key-test",
            versionId,
            CredentialBindingState.ACTIVE,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600),
            Set.of(ConnectorOperation.IDENTITY_RESOLVE, ConnectorOperation.ORGANIZATION_READ),
            "policy-1",
            Map.of("ownerClass", "platform-security", "purposeClass", "captured-transport")
        );
    }

    private static boolean allZero(byte[] value) {
        return value != null && Arrays.equals(value, new byte[value.length]);
    }

    private static final class MutableCatalog implements CredentialBindingCatalog {
        private CredentialBindingDescriptor current;
        private CredentialBindingDescriptor next;
        private int findCount;

        private MutableCatalog(CredentialBindingDescriptor current) {
            this.current = current;
        }

        @Override
        public Optional<CredentialBindingDescriptor> find(CredentialReference reference) {
            findCount++;
            if (findCount > 1 && next != null) {
                current = next;
            }
            return Optional.ofNullable(current);
        }
    }

    private static final class FixtureMaterialSource implements CredentialMaterialSource {
        private static final String FIXTURE_TEXT = "test-material-not-a-token";
        private static final byte[] FIXTURE = FIXTURE_TEXT.getBytes();

        private final String keyId;
        private final String versionId;
        private int openCount;
        private boolean unavailable;
        private boolean lastScopeClosed;
        private boolean lastSourceCopyZeroized;

        private FixtureMaterialSource(String keyId, String versionId) {
            this.keyId = keyId;
            this.versionId = versionId;
        }

        @Override
        public MaterialScope openMaterial(
            CredentialReference reference,
            String expectedKeyId,
            String expectedVersionId
        ) {
            openCount++;
            if (unavailable) {
                throw new SourceUnavailableException();
            }
            return new Scope(keyId, versionId, FIXTURE.clone());
        }

        private final class Scope implements MaterialScope {
            private final String scopeKeyId;
            private final String scopeVersionId;
            private final byte[] material;
            private boolean active = true;

            private Scope(String scopeKeyId, String scopeVersionId, byte[] material) {
                this.scopeKeyId = scopeKeyId;
                this.scopeVersionId = scopeVersionId;
                this.material = material;
            }

            @Override
            public String keyId() {
                requireActive();
                return scopeKeyId;
            }

            @Override
            public String versionId() {
                requireActive();
                return scopeVersionId;
            }

            @Override
            public String sourceEvidenceHash() {
                requireActive();
                return CanonicalPayloadHash.sha256Utf8("test-source:" + scopeVersionId);
            }

            @Override
            public void useSecretBytes(SecretBytesUse use) {
                requireActive();
                byte[] sourceCopy = material.clone();
                try {
                    use.accept(sourceCopy);
                } finally {
                    Arrays.fill(sourceCopy, (byte) 0);
                    lastSourceCopyZeroized = allZero(sourceCopy);
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
                    lastScopeClosed = true;
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
