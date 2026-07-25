package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ServerOwnedCredentialResolver
    implements ConnectorCredentialResolver, AutoCloseable {

    private final CredentialBindingCatalog bindingCatalog;
    private final CredentialMaterialSource materialSource;
    private final Clock clock;
    private boolean closed;

    public ServerOwnedCredentialResolver(
        CredentialBindingCatalog bindingCatalog,
        CredentialMaterialSource materialSource,
        Clock clock
    ) {
        this.bindingCatalog = Objects.requireNonNull(
            bindingCatalog,
            "bindingCatalog must not be null"
        );
        this.materialSource = Objects.requireNonNull(
            materialSource,
            "materialSource must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public <T> T withCredential(
        TrustedConnectorExecutionContext context,
        CredentialUse<T> use
    ) {
        throw new UnsupportedOperationException(
            "production credential resolution requires an operation-bound request"
        );
    }

    @Override
    public void useCredential(
        TrustedConnectorExecutionContext context,
        ScopedCredentialUse use
    ) {
        throw new UnsupportedOperationException(
            "production credential resolution requires an operation-bound request"
        );
    }

    public synchronized CredentialResolutionEvidence useCredential(
        CredentialResolutionRequest request,
        ResolvedCredentialUse use
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(use, "use must not be null");
        requireOpen();

        Instant now = clock.instant();
        CredentialReference reference = request.context().credentialReference();
        Optional<CredentialBindingDescriptor> found = bindingCatalog.find(reference);
        if (found.isEmpty()) {
            throw failure(request, now, CredentialResolutionStatus.REFERENCE_NOT_FOUND, null, null);
        }
        CredentialBindingDescriptor descriptor = found.orElseThrow();
        validateDescriptor(request, descriptor, now);

        CredentialMaterialSource.MaterialScope materialScope;
        try {
            materialScope = Objects.requireNonNull(
                materialSource.openMaterial(reference, descriptor.keyId(), descriptor.versionId()),
                "material source returned null scope"
            );
        } catch (CredentialMaterialSource.SourceUnavailableException exception) {
            throw failure(
                request,
                now,
                CredentialResolutionStatus.MATERIAL_SOURCE_UNAVAILABLE,
                descriptor,
                null
            );
        } catch (RuntimeException exception) {
            throw failure(
                request,
                now,
                CredentialResolutionStatus.MATERIAL_INVALID,
                descriptor,
                null
            );
        }

        try {
            if (!materialScope.active()
                || !descriptor.keyId().equals(materialScope.keyId())
                || !descriptor.versionId().equals(materialScope.versionId())) {
                throw failure(
                    request,
                    now,
                    CredentialResolutionStatus.VERSION_MISMATCH,
                    descriptor,
                    materialScope.sourceEvidenceHash()
                );
            }
            CredentialBindingDescriptor currentDescriptor = bindingCatalog.find(reference).orElse(null);
            if (currentDescriptor == null
                || !descriptor.fingerprint().equals(currentDescriptor.fingerprint())) {
                throw failure(
                    request,
                    now,
                    CredentialResolutionStatus.VERSION_MISMATCH,
                    descriptor,
                    materialScope.sourceEvidenceHash()
                );
            }

            CredentialResolutionEvidence evidence = evidence(
                request,
                now,
                CredentialResolutionStatus.RESOLVED,
                descriptor,
                materialScope.sourceEvidenceHash()
            );
            ScopedCredentialImpl credential = new ScopedCredentialImpl(
                descriptor,
                evidence,
                materialScope
            );
            try {
                use.accept(credential);
                return evidence;
            } finally {
                credential.close();
            }
        } finally {
            materialScope.close();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    @Override
    public String toString() {
        return "ServerOwnedCredentialResolver[material=<redacted>]";
    }

    private void validateDescriptor(
        CredentialResolutionRequest request,
        CredentialBindingDescriptor descriptor,
        Instant now
    ) {
        TrustedConnectorExecutionContext context = request.context();
        if (!context.providerKey().equals(descriptor.providerKey())) {
            throw failure(request, now, CredentialResolutionStatus.PROVIDER_MISMATCH, descriptor, null);
        }
        if (!context.credentialReference().equals(descriptor.reference())) {
            throw failure(request, now, CredentialResolutionStatus.REFERENCE_MISMATCH, descriptor, null);
        }
        if (!context.tenantId().equals(descriptor.tenantId())) {
            throw failure(request, now, CredentialResolutionStatus.TENANT_MISMATCH, descriptor, null);
        }
        CredentialResolutionStatus stateFailure = stateFailure(descriptor.state());
        if (stateFailure != null) {
            throw failure(request, now, stateFailure, descriptor, null);
        }
        if (descriptor.notBefore() != null && now.isBefore(descriptor.notBefore())) {
            throw failure(request, now, CredentialResolutionStatus.NOT_YET_VALID, descriptor, null);
        }
        if (descriptor.expiresAt() != null && !now.isBefore(descriptor.expiresAt())) {
            throw failure(request, now, CredentialResolutionStatus.EXPIRED, descriptor, null);
        }
        if (!descriptor.allowedOperations().contains(request.operation())) {
            throw failure(
                request,
                now,
                CredentialResolutionStatus.OPERATION_NOT_ALLOWED,
                descriptor,
                null
            );
        }
        if (descriptor.credentialType() != request.expectedCredentialType()
            || !descriptor.keyId().equals(request.expectedKeyId())
            || !descriptor.versionId().equals(request.expectedVersionId())) {
            throw failure(request, now, CredentialResolutionStatus.VERSION_MISMATCH, descriptor, null);
        }
    }

    private CredentialResolutionException failure(
        CredentialResolutionRequest request,
        Instant now,
        CredentialResolutionStatus status,
        CredentialBindingDescriptor descriptor,
        String sourceEvidenceHash
    ) {
        return new CredentialResolutionException(
            evidence(request, now, status, descriptor, sourceEvidenceHash)
        );
    }

    private CredentialResolutionEvidence evidence(
        CredentialResolutionRequest request,
        Instant now,
        CredentialResolutionStatus status,
        CredentialBindingDescriptor descriptor,
        String sourceEvidenceHash
    ) {
        CredentialReference reference = request.context().credentialReference();
        return new CredentialResolutionEvidence(
            status,
            referenceHash(reference),
            request.context().providerKey(),
            descriptor == null ? request.expectedKeyId() : descriptor.keyId(),
            descriptor == null ? request.expectedVersionId() : descriptor.versionId(),
            descriptor == null ? request.expectedCredentialType() : descriptor.credentialType(),
            request.operation(),
            descriptor == null ? null : descriptor.policyVersion(),
            now,
            descriptor == null ? null : descriptor.expiresAt(),
            descriptor == null ? null : descriptor.fingerprint(),
            sourceEvidenceHash,
            Map.of("backendMode", "foundation-only")
        );
    }

    private static CredentialResolutionStatus stateFailure(CredentialBindingState state) {
        return switch (state) {
            case ACTIVE -> null;
            case DISABLED -> CredentialResolutionStatus.CREDENTIAL_DISABLED;
            case NOT_YET_VALID -> CredentialResolutionStatus.NOT_YET_VALID;
            case EXPIRED -> CredentialResolutionStatus.EXPIRED;
            case REVOKED -> CredentialResolutionStatus.CREDENTIAL_REVOKED;
            case ROTATION_PENDING -> CredentialResolutionStatus.ROTATION_PENDING;
        };
    }

    private static String referenceHash(CredentialReference reference) {
        return CanonicalPayloadHash.sha256Utf8(
            reference.providerKey() + "\n" + reference.referenceId()
        );
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("credential resolver is closed");
        }
    }

    @FunctionalInterface
    public interface ResolvedCredentialUse {

        void accept(ResolvedScopedCredential credential);
    }

    private static final class ScopedCredentialImpl implements ResolvedScopedCredential {

        private final CredentialBindingDescriptor descriptor;
        private final CredentialResolutionEvidence evidence;
        private final CredentialMaterialSource.MaterialScope materialScope;
        private boolean active = true;

        private ScopedCredentialImpl(
            CredentialBindingDescriptor descriptor,
            CredentialResolutionEvidence evidence,
            CredentialMaterialSource.MaterialScope materialScope
        ) {
            this.descriptor = descriptor;
            this.evidence = evidence;
            this.materialScope = materialScope;
        }

        @Override
        public CredentialReference reference() {
            requireActive();
            return descriptor.reference();
        }

        @Override
        public String keyId() {
            requireActive();
            return descriptor.keyId();
        }

        @Override
        public String versionId() {
            requireActive();
            return descriptor.versionId();
        }

        @Override
        public CredentialMaterialType credentialType() {
            requireActive();
            return descriptor.credentialType();
        }

        @Override
        public CredentialResolutionEvidence evidence() {
            requireActive();
            return evidence;
        }

        @Override
        public <T> T withSecretBytes(SecretUse<T> use) {
            throw new UnsupportedOperationException(
                "production secret access requires the non-returning useSecretBytes callback"
            );
        }

        @Override
        public void useSecretBytes(SecretBytesUse use) {
            requireActive();
            Objects.requireNonNull(use, "use must not be null");
            materialScope.useSecretBytes(sourceBytes -> {
                Objects.requireNonNull(sourceBytes, "material source returned null bytes");
                if (sourceBytes.length == 0 || sourceBytes.length > 16_384) {
                    throw new CredentialMaterialSource.MaterialInvalidException();
                }
                byte[] scopedCopy = sourceBytes.clone();
                try {
                    use.accept(scopedCopy);
                } finally {
                    Arrays.fill(scopedCopy, (byte) 0);
                }
            });
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public String toString() {
            return "ResolvedScopedCredential[reference=" + descriptor.reference()
                + ", material=<redacted>]";
        }

        @Override
        public void close() {
            if (active) {
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
