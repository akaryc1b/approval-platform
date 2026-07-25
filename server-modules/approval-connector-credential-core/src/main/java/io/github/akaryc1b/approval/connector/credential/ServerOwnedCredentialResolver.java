package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.io.Serial;
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
        Optional<CredentialBindingDescriptor> found;
        try {
            found = Objects.requireNonNull(
                bindingCatalog.find(reference),
                "binding catalog returned null"
            );
        } catch (RuntimeException exception) {
            throw failure(request, now, CredentialResolutionStatus.UNKNOWN, null, null);
        }
        if (found.isEmpty()) {
            throw failure(request, now, CredentialResolutionStatus.REFERENCE_NOT_FOUND, null, null);
        }
        CredentialBindingDescriptor descriptor = found.orElseThrow();
        validateDescriptor(request, descriptor, now);

        CredentialMaterialSource.MaterialScope openedScope;
        try {
            openedScope = Objects.requireNonNull(
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

        String sourceEvidenceHash = null;
        try (MaterialScopeGuard materialScope = new MaterialScopeGuard(openedScope)) {
            if (!materialScope.active()) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_INVALID,
                    null
                );
            }
            if (!descriptor.keyId().equals(materialScope.keyId())
                || !descriptor.versionId().equals(materialScope.versionId())) {
                throw failure(
                    request,
                    now,
                    CredentialResolutionStatus.VERSION_MISMATCH,
                    descriptor,
                    null
                );
            }
            try {
                sourceEvidenceHash = CredentialContractSupport.requireSha256(
                    materialScope.sourceEvidenceHash(),
                    "sourceEvidenceHash"
                );
            } catch (MaterialAccessException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_INVALID,
                    exception
                );
            }

            CredentialBindingDescriptor currentDescriptor;
            try {
                currentDescriptor = bindingCatalog.find(reference).orElse(null);
            } catch (RuntimeException exception) {
                throw failure(
                    request,
                    now,
                    CredentialResolutionStatus.UNKNOWN,
                    descriptor,
                    sourceEvidenceHash
                );
            }
            if (currentDescriptor == null
                || !descriptor.fingerprint().equals(currentDescriptor.fingerprint())) {
                throw failure(
                    request,
                    now,
                    CredentialResolutionStatus.VERSION_MISMATCH,
                    descriptor,
                    sourceEvidenceHash
                );
            }

            CredentialResolutionEvidence evidence = evidence(
                request,
                now,
                CredentialResolutionStatus.RESOLVED,
                descriptor,
                sourceEvidenceHash
            );
            try (ScopedCredentialImpl credential = new ScopedCredentialImpl(
                descriptor,
                evidence,
                materialScope
            )) {
                use.accept(credential);
                return evidence;
            }
        } catch (MaterialAccessException exception) {
            throw failure(
                request,
                now,
                exception.status(),
                descriptor,
                sourceEvidenceHash
            );
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
        private final MaterialScopeGuard materialScope;
        private boolean active = true;

        private ScopedCredentialImpl(
            CredentialBindingDescriptor descriptor,
            CredentialResolutionEvidence evidence,
            MaterialScopeGuard materialScope
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
            try {
                materialScope.useSecretBytes(sourceBytes -> {
                    Objects.requireNonNull(sourceBytes, "material source returned null bytes");
                    if (sourceBytes.length == 0 || sourceBytes.length > 16_384) {
                        throw new CredentialMaterialSource.MaterialInvalidException();
                    }
                    byte[] scopedCopy = sourceBytes.clone();
                    try {
                        try {
                            use.accept(scopedCopy);
                        } catch (RuntimeException exception) {
                            throw new SecretCallbackException(exception);
                        }
                    } finally {
                        Arrays.fill(scopedCopy, (byte) 0);
                    }
                });
            } catch (SecretCallbackException exception) {
                throw exception.callbackFailure();
            }
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
                materialScope.close();
            }
        }

        private void requireActive() {
            if (!active) {
                throw new IllegalStateException("credential scope is closed");
            }
        }
    }

    private static final class MaterialScopeGuard implements AutoCloseable {

        private final CredentialMaterialSource.MaterialScope delegate;
        private boolean closed;

        private MaterialScopeGuard(CredentialMaterialSource.MaterialScope delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        }

        private boolean active() {
            requireOpen();
            try {
                return delegate.active();
            } catch (CredentialMaterialSource.SourceUnavailableException exception) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_SOURCE_UNAVAILABLE,
                    exception
                );
            } catch (RuntimeException exception) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_INVALID,
                    exception
                );
            }
        }

        private String keyId() {
            return metadata(delegate::keyId);
        }

        private String versionId() {
            return metadata(delegate::versionId);
        }

        private String sourceEvidenceHash() {
            return metadata(delegate::sourceEvidenceHash);
        }

        private void useSecretBytes(SecretBytesUse use) {
            requireOpen();
            try {
                delegate.useSecretBytes(use);
            } catch (SecretCallbackException exception) {
                throw exception;
            } catch (CredentialMaterialSource.SourceUnavailableException exception) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_SOURCE_UNAVAILABLE,
                    exception
                );
            } catch (CredentialMaterialSource.MaterialInvalidException exception) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_INVALID,
                    exception
                );
            } catch (RuntimeException exception) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_INVALID,
                    exception
                );
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    delegate.close();
                } catch (CredentialMaterialSource.SourceUnavailableException exception) {
                    throw new MaterialAccessException(
                        CredentialResolutionStatus.MATERIAL_SOURCE_UNAVAILABLE,
                        exception
                    );
                } catch (RuntimeException exception) {
                    throw new MaterialAccessException(
                        CredentialResolutionStatus.MATERIAL_INVALID,
                        exception
                    );
                }
            }
        }

        private String metadata(MetadataUse use) {
            requireOpen();
            try {
                return use.get();
            } catch (CredentialMaterialSource.SourceUnavailableException exception) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_SOURCE_UNAVAILABLE,
                    exception
                );
            } catch (RuntimeException exception) {
                throw new MaterialAccessException(
                    CredentialResolutionStatus.MATERIAL_INVALID,
                    exception
                );
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("material scope is closed");
            }
        }

        @FunctionalInterface
        private interface MetadataUse {

            String get();
        }
    }

    private static final class MaterialAccessException extends IllegalStateException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final CredentialResolutionStatus status;

        private MaterialAccessException(
            CredentialResolutionStatus status,
            RuntimeException cause
        ) {
            super("credential material access failed", cause);
            this.status = Objects.requireNonNull(status, "status must not be null");
        }

        private CredentialResolutionStatus status() {
            return status;
        }
    }

    private static final class SecretCallbackException extends IllegalStateException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final RuntimeException callbackFailure;

        private SecretCallbackException(RuntimeException callbackFailure) {
            super("credential callback failed", callbackFailure);
            this.callbackFailure = callbackFailure;
        }

        private RuntimeException callbackFailure() {
            return callbackFailure;
        }
    }
}
