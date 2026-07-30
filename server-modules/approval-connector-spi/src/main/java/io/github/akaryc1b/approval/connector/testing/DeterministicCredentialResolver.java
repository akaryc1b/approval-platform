package io.github.akaryc1b.approval.connector.testing;

import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only credential resolver with scoped copies and deterministic zeroization.
 */
public final class DeterministicCredentialResolver
    implements ConnectorCredentialResolver, AutoCloseable {

    private final String expectedTenantId;
    private final CredentialReference expectedReference;
    private final String keyId;
    private final byte[] fixtureMaterial;
    private final AtomicBoolean lastScopeClosed = new AtomicBoolean(true);
    private boolean closed;

    public DeterministicCredentialResolver(
        String expectedTenantId,
        CredentialReference expectedReference,
        String keyId,
        byte[] fixtureMaterial
    ) {
        this.expectedTenantId = requireText(expectedTenantId, "expectedTenantId");
        this.expectedReference = Objects.requireNonNull(
            expectedReference,
            "expectedReference must not be null"
        );
        this.keyId = requireText(keyId, "keyId");
        Objects.requireNonNull(fixtureMaterial, "fixtureMaterial must not be null");
        if (fixtureMaterial.length < 16 || fixtureMaterial.length > 4096) {
            throw new IllegalArgumentException(
                "fixtureMaterial must contain between 16 and 4096 bytes"
            );
        }
        this.fixtureMaterial = fixtureMaterial.clone();
    }

    @Override
    public synchronized <T> T withCredential(
        TrustedConnectorExecutionContext context,
        CredentialUse<T> use
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(use, "use must not be null");
        if (closed) {
            throw new IllegalStateException("credential resolver is closed");
        }
        if (!expectedTenantId.equals(context.tenantId())) {
            throw new IllegalArgumentException("trusted tenant is not assigned to credential");
        }
        if (!expectedReference.equals(context.credentialReference())) {
            throw new IllegalArgumentException("credential reference does not match context");
        }
        ScopedMaterial material = new ScopedMaterial(
            expectedReference,
            keyId,
            fixtureMaterial.clone()
        );
        lastScopeClosed.set(false);
        try {
            return use.apply(material);
        } finally {
            material.close();
            lastScopeClosed.set(true);
        }
    }

    public boolean lastScopeClosed() {
        return lastScopeClosed.get();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(fixtureMaterial, (byte) 0);
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "DeterministicCredentialResolver[credential=<redacted>]";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1 to 128 characters");
        }
        return value;
    }

    private static final class ScopedMaterial implements ScopedCredential {

        private final CredentialReference reference;
        private final String keyId;
        private final byte[] material;
        private boolean active = true;

        private ScopedMaterial(
            CredentialReference reference,
            String keyId,
            byte[] material
        ) {
            this.reference = reference;
            this.keyId = keyId;
            this.material = material;
        }

        @Override
        public CredentialReference reference() {
            requireActive();
            return reference;
        }

        @Override
        public String keyId() {
            requireActive();
            return keyId;
        }

        @Override
        public <T> T withSecretBytes(SecretUse<T> use) {
            requireActive();
            Objects.requireNonNull(use, "use must not be null");
            byte[] scopedCopy = material.clone();
            try {
                return use.apply(scopedCopy);
            } finally {
                Arrays.fill(scopedCopy, (byte) 0);
            }
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public String toString() {
            return "ScopedCredential[reference=" + reference + ", material=<redacted>]";
        }

        private void close() {
            if (active) {
                Arrays.fill(material, (byte) 0);
                active = false;
            }
        }

        private void requireActive() {
            if (!active) {
                throw new IllegalStateException("credential scope is closed");
            }
        }
    }
}
