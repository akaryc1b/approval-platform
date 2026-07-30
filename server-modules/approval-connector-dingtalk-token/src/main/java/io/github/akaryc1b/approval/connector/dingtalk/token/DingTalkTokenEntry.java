package io.github.akaryc1b.approval.connector.dingtalk.token;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

final class DingTalkTokenEntry
    implements DingTalkTokenEvidence.TokenEntryView, AutoCloseable {

    private final String familyHash;
    private final String cacheKeyHash;
    private final String tokenVersionReference;
    private final Instant issuedAt;
    private final Instant refreshAt;
    private final Instant expiresAt;
    private final long generationOrdinal;
    private final ByteBuffer ownedMaterial;
    private boolean active = true;

    private DingTalkTokenEntry(
        String familyHash,
        String cacheKeyHash,
        String tokenVersionReference,
        Instant issuedAt,
        Instant refreshAt,
        Instant expiresAt,
        long generationOrdinal,
        byte[] material
    ) {
        this.familyHash = DingTalkTokenSupport.sha256(familyHash, "familyHash");
        this.cacheKeyHash = DingTalkTokenSupport.sha256(cacheKeyHash, "cacheKeyHash");
        this.tokenVersionReference = DingTalkTokenSupport.sha256(
            tokenVersionReference,
            "tokenVersionReference"
        );
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        this.refreshAt = Objects.requireNonNull(refreshAt, "refreshAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.generationOrdinal = generationOrdinal;
        Objects.requireNonNull(material, "material must not be null");
        if (material.length == 0 || material.length > 65_536) {
            Arrays.fill(material, (byte) 0);
            throw failure(DingTalkTokenFailure.ENDPOINT_MALFORMED);
        }
        ownedMaterial = ByteBuffer.allocateDirect(material.length);
        ownedMaterial.put(material).flip();
        Arrays.fill(material, (byte) 0);
    }

    static DingTalkTokenEntry takeOwnership(
        String familyHash,
        String cacheKeyHash,
        String tokenVersionReference,
        Instant issuedAt,
        Instant refreshAt,
        Instant expiresAt,
        long generationOrdinal,
        byte[] material
    ) {
        return new DingTalkTokenEntry(
            familyHash,
            cacheKeyHash,
            tokenVersionReference,
            issuedAt,
            refreshAt,
            expiresAt,
            generationOrdinal,
            material
        );
    }

    synchronized boolean usableAt(Instant evaluatedAt) {
        return active && evaluatedAt.isBefore(expiresAt);
    }

    synchronized boolean usableWithoutRefreshAt(Instant evaluatedAt) {
        return usableAt(evaluatedAt) && evaluatedAt.isBefore(refreshAt);
    }

    synchronized DingTalkAccessTokenLease issueLease(
        DingTalkTokenOutcome outcome,
        DingTalkTokenRequest request,
        boolean singleFlightLeader,
        Instant evaluatedAt
    ) {
        if (!active || !evaluatedAt.isBefore(expiresAt)) {
            throw failure(DingTalkTokenFailure.TOKEN_EXPIRED);
        }
        byte[] copy = new byte[ownedMaterial.capacity()];
        ByteBuffer view = ownedMaterial.asReadOnlyBuffer();
        view.position(0);
        view.get(copy);
        DingTalkTokenEvidence evidence = DingTalkTokenEvidence.create(
            outcome,
            request,
            this,
            singleFlightLeader
        );
        return DingTalkAccessTokenLease.takeOwnership(evidence, copy);
    }

    String familyHash() {
        return familyHash;
    }

    String cacheKeyHash() {
        return cacheKeyHash;
    }

    @Override
    public String tokenVersionReference() {
        return tokenVersionReference;
    }

    @Override
    public Instant issuedAt() {
        return issuedAt;
    }

    @Override
    public Instant refreshAt() {
        return refreshAt;
    }

    @Override
    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public long generationOrdinal() {
        return generationOrdinal;
    }

    @Override
    public synchronized void close() {
        if (!active) {
            return;
        }
        ByteBuffer view = ownedMaterial.duplicate();
        view.position(0);
        while (view.hasRemaining()) {
            view.put((byte) 0);
        }
        active = false;
    }

    private static DingTalkTokenLifecycleException failure(DingTalkTokenFailure failure) {
        return new DingTalkTokenLifecycleException(failure);
    }
}
