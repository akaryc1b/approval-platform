package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Single-use-at-a-time, explicitly closed and zeroized credential material lease.
 */
public final class CredentialMaterialLease implements AutoCloseable {

    private static final int MAXIMUM_MATERIAL_BYTES = 65_536;

    private final CredentialMaterialRequest request;
    private final CredentialMaterialDescriptor descriptor;
    private final ByteBuffer ownedMaterial;
    private final LongSupplier ordinalSource;
    private final CredentialMaterialRelease release;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicBoolean releaseAttempted = new AtomicBoolean();
    private final AtomicBoolean releaseFailed = new AtomicBoolean();
    private final AtomicLong closeOrdinal = new AtomicLong(-1);

    private CredentialMaterialLease(
        CredentialMaterialRequest request,
        CredentialMaterialDescriptor descriptor,
        byte[] material,
        LongSupplier ordinalSource,
        CredentialMaterialRelease release
    ) {
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.descriptor = requireExactDescriptor(request, descriptor);
        Objects.requireNonNull(material, "material must not be null");
        if (material.length == 0 || material.length > MAXIMUM_MATERIAL_BYTES) {
            throw new CredentialMaterialSourceException(CredentialMaterialFailure.MATERIAL_MALFORMED);
        }
        this.ordinalSource = Objects.requireNonNull(ordinalSource, "ordinalSource must not be null");
        this.release = Objects.requireNonNull(release, "release must not be null");
        this.ownedMaterial = ByteBuffer.allocateDirect(material.length);
        this.ownedMaterial.put(material).flip();
        Arrays.fill(material, (byte) 0);
    }

    public static CredentialMaterialLease takeOwnership(
        CredentialMaterialRequest request,
        CredentialMaterialDescriptor descriptor,
        byte[] material,
        LongSupplier ordinalSource,
        CredentialMaterialRelease release
    ) {
        return new CredentialMaterialLease(
            request,
            descriptor,
            material,
            ordinalSource,
            release
        );
    }

    public CredentialMaterialDescriptor descriptor() {
        return descriptor;
    }

    public boolean active() {
        State current = state.get();
        return current == State.OPEN || current == State.IN_USE;
    }

    public boolean closed() {
        return state.get() == State.CLOSED;
    }

    public void useMaterial(CredentialMaterialUse use) {
        Objects.requireNonNull(use, "use must not be null");
        if (!state.compareAndSet(State.OPEN, State.IN_USE)) {
            State current = state.get();
            CredentialMaterialFailure failure = current == State.IN_USE
                ? CredentialMaterialFailure.CONCURRENT_ACCESS_REJECTED
                : CredentialMaterialFailure.LEASE_CLOSED;
            throw new CredentialMaterialLeaseException(failure);
        }

        byte[] scopedCopy = copyMaterial();
        Throwable callbackFailure = null;
        try {
            use.accept(scopedCopy);
        } catch (RuntimeException | Error failure) {
            callbackFailure = failure;
            throw failure;
        } finally {
            Arrays.fill(scopedCopy, (byte) 0);
            completeUse(callbackFailure);
        }
    }

    @Override
    public void close() {
        while (true) {
            State current = state.get();
            if (current == State.CLOSED || current == State.CLOSE_REQUESTED) {
                return;
            }
            if (current == State.IN_USE) {
                if (state.compareAndSet(State.IN_USE, State.CLOSE_REQUESTED)) {
                    return;
                }
                continue;
            }
            if (state.compareAndSet(State.OPEN, State.CLOSED)) {
                releaseNow();
                return;
            }
        }
    }

    public CredentialMaterialAuditEvidence auditEvidence() {
        State current = state.get();
        CredentialMaterialFailure failure = releaseFailed.get()
            ? CredentialMaterialFailure.RELEASE_FAILED
            : CredentialMaterialFailure.NONE;
        String releaseEvidenceHash = CanonicalPayloadHash.sha256Utf8(
            descriptor.descriptorHash() + "\n" + current.name() + "\n"
                + closeOrdinal.get() + "\n" + failure.stableCode()
        );
        return new CredentialMaterialAuditEvidence(
            request.evidenceHash(),
            descriptor.descriptorHash(),
            descriptor.sourceEvidenceHash(),
            failure,
            true,
            current == State.IN_USE,
            current == State.CLOSE_REQUESTED,
            current == State.CLOSED,
            releaseFailed.get(),
            descriptor.acquisitionOrdinal(),
            closeOrdinal.get(),
            releaseEvidenceHash
        );
    }

    @Override
    public String toString() {
        return "CredentialMaterialLease[descriptorHash=" + descriptor.descriptorHash()
            + ", state=" + state.get() + "]";
    }

    private byte[] copyMaterial() {
        ByteBuffer view = ownedMaterial.asReadOnlyBuffer();
        view.position(0);
        byte[] copy = new byte[view.remaining()];
        view.get(copy);
        return copy;
    }

    private void completeUse(Throwable callbackFailure) {
        if (state.compareAndSet(State.IN_USE, State.OPEN)) {
            return;
        }
        if (state.compareAndSet(State.CLOSE_REQUESTED, State.CLOSED)) {
            try {
                releaseNow();
            } catch (RuntimeException releaseFailure) {
                if (callbackFailure != null) {
                    callbackFailure.addSuppressed(releaseFailure);
                    return;
                }
                throw releaseFailure;
            }
        }
    }

    private void releaseNow() {
        zeroOwnedMaterial();
        if (!releaseAttempted.compareAndSet(false, true)) {
            return;
        }
        closeOrdinal.set(nextOrdinal());
        try {
            release.release();
        } catch (RuntimeException failure) {
            releaseFailed.set(true);
            throw new CredentialMaterialLeaseException(CredentialMaterialFailure.RELEASE_FAILED);
        }
    }

    private long nextOrdinal() {
        long value = ordinalSource.getAsLong();
        if (value < descriptor.acquisitionOrdinal()) {
            throw new CredentialMaterialLeaseException(CredentialMaterialFailure.UNKNOWN);
        }
        return value;
    }

    private void zeroOwnedMaterial() {
        ByteBuffer view = ownedMaterial.duplicate();
        view.position(0);
        while (view.hasRemaining()) {
            view.put((byte) 0);
        }
    }

    private static CredentialMaterialDescriptor requireExactDescriptor(
        CredentialMaterialRequest request,
        CredentialMaterialDescriptor descriptor
    ) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        boolean mismatch = descriptor.classification() != CredentialMaterialLoadClassification.LOADED
            || descriptor.failure() != CredentialMaterialFailure.NONE
            || !descriptor.providerKey().equals(request.providerKey())
            || !descriptor.credentialReferenceHash().equals(request.credentialReferenceHash())
            || !descriptor.versionReference().equals(request.expectedVersion().versionReference())
            || descriptor.materialType() != request.materialType()
            || !descriptor.requestEvidenceHash().equals(request.evidenceHash());
        if (mismatch) {
            throw new CredentialMaterialSourceException(CredentialMaterialFailure.MATERIAL_MALFORMED);
        }
        return descriptor;
    }

    private enum State {
        OPEN,
        IN_USE,
        CLOSE_REQUESTED,
        CLOSED
    }

    @FunctionalInterface
    public interface CredentialMaterialUse {
        void accept(byte[] material);
    }

    @FunctionalInterface
    public interface CredentialMaterialRelease {
        void release();
    }
}
