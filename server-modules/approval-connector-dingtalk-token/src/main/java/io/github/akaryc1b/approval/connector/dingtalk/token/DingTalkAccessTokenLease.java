package io.github.akaryc1b.approval.connector.dingtalk.token;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class DingTalkAccessTokenLease implements AutoCloseable {

    private static final int MAXIMUM_TOKEN_BYTES = 65_536;

    private final DingTalkTokenEvidence evidence;
    private final ByteBuffer ownedMaterial;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    private DingTalkAccessTokenLease(DingTalkTokenEvidence evidence, byte[] material) {
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(material, "material must not be null");
        if (material.length == 0 || material.length > MAXIMUM_TOKEN_BYTES) {
            Arrays.fill(material, (byte) 0);
            throw new DingTalkTokenLifecycleException(
                DingTalkTokenFailure.ENDPOINT_MALFORMED
            );
        }
        ownedMaterial = ByteBuffer.allocateDirect(material.length);
        ownedMaterial.put(material).flip();
        Arrays.fill(material, (byte) 0);
    }

    static DingTalkAccessTokenLease takeOwnership(
        DingTalkTokenEvidence evidence,
        byte[] material
    ) {
        return new DingTalkAccessTokenLease(evidence, material);
    }

    public DingTalkTokenEvidence evidence() {
        return evidence;
    }

    public boolean active() {
        State current = state.get();
        return current == State.OPEN || current == State.IN_USE;
    }

    public boolean closed() {
        return state.get() == State.CLOSED;
    }

    public void use(TokenUse use) {
        Objects.requireNonNull(use, "use must not be null");
        if (!state.compareAndSet(State.OPEN, State.IN_USE)) {
            DingTalkTokenFailure failure = state.get() == State.IN_USE
                ? DingTalkTokenFailure.CONCURRENT_ACCESS_REJECTED
                : DingTalkTokenFailure.LEASE_CLOSED;
            throw new DingTalkTokenLifecycleException(failure);
        }
        byte[] scoped = new byte[ownedMaterial.capacity()];
        copyInto(scoped);
        try {
            use.accept(scoped);
        } finally {
            Arrays.fill(scoped, (byte) 0);
            completeUse();
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
                zeroOwnedMaterial();
                return;
            }
        }
    }

    @Override
    public String toString() {
        return "DingTalkAccessTokenLease[tokenVersionReference="
            + evidence.tokenVersionReference() + ", state=" + state.get() + "]";
    }

    private void completeUse() {
        if (state.compareAndSet(State.IN_USE, State.OPEN)) {
            return;
        }
        if (state.compareAndSet(State.CLOSE_REQUESTED, State.CLOSED)) {
            zeroOwnedMaterial();
        }
    }

    private void copyInto(byte[] target) {
        ByteBuffer view = ownedMaterial.asReadOnlyBuffer();
        view.position(0);
        view.get(target);
    }

    private void zeroOwnedMaterial() {
        ByteBuffer view = ownedMaterial.duplicate();
        view.position(0);
        while (view.hasRemaining()) {
            view.put((byte) 0);
        }
    }

    private enum State {
        OPEN,
        IN_USE,
        CLOSE_REQUESTED,
        CLOSED
    }

    @FunctionalInterface
    public interface TokenUse {
        void accept(byte[] material);
    }
}
