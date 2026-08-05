package io.github.akaryc1b.approval.ai.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Framework-free P4 store and retention CAS boundary. */
public interface ApprovalAssistanceDurableEvidenceStore {

    StoreResult store(ApprovalAssistanceDurableEvidence evidence);

    TombstoneResult tombstone(TombstoneCommand command);

    Optional<EvidenceSnapshot> find(String tenantId, UUID evidenceId);

    enum StoreDisposition {
        STORED,
        REPLAYED,
        CONFLICT
    }

    enum TombstoneDisposition {
        TOMBSTONED,
        REPLAYED,
        NOT_FOUND,
        REVISION_CONFLICT,
        RETENTION_BLOCKED,
        CONFLICT
    }

    enum EvidenceState {
        ACTIVE,
        TOMBSTONED
    }

    enum DeleteReason {
        RETENTION_EXPIRED(false),
        DATA_SUBJECT_REQUEST(true),
        TENANT_POLICY(true),
        SECURITY_INCIDENT(true),
        LEGAL_REQUIREMENT(true);

        private final boolean permitsEarlyDeletion;

        DeleteReason(boolean permitsEarlyDeletion) {
            this.permitsEarlyDeletion = permitsEarlyDeletion;
        }

        public boolean permitsEarlyDeletion() {
            return permitsEarlyDeletion;
        }
    }

    record StoreResult(
        StoreDisposition disposition,
        UUID evidenceId,
        long revision,
        EvidenceState state,
        String evidenceHash,
        String eventHash
    ) {
        public StoreResult {
            disposition = Objects.requireNonNull(
                disposition,
                "disposition must not be null"
            );
            evidenceId = Objects.requireNonNull(evidenceId, "evidenceId must not be null");
            state = Objects.requireNonNull(state, "state must not be null");
            evidenceHash = Hashes.requireSha256(evidenceHash, "evidenceHash");
            eventHash = Hashes.requireSha256(eventHash, "eventHash");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            boolean accepted = disposition == StoreDisposition.STORED
                || disposition == StoreDisposition.REPLAYED;
            if (accepted && (state != EvidenceState.ACTIVE || revision != 1)) {
                throw new IllegalArgumentException(
                    "stored or replayed evidence must return active revision one"
                );
            }
        }
    }

    record TombstoneCommand(
        String tenantId,
        UUID evidenceId,
        long expectedRevision,
        DeleteReason reason,
        Instant requestedAt,
        String deletionRequestHash
    ) {
        public TombstoneCommand {
            tenantId = Hashes.requireText(tenantId, "tenantId", 128);
            evidenceId = Objects.requireNonNull(evidenceId, "evidenceId must not be null");
            reason = Objects.requireNonNull(reason, "reason must not be null");
            requestedAt = Objects.requireNonNull(
                requestedAt,
                "requestedAt must not be null"
            );
            deletionRequestHash = Hashes.requireSha256(
                deletionRequestHash,
                "deletionRequestHash"
            );
            if (expectedRevision < 1) {
                throw new IllegalArgumentException(
                    "expectedRevision must be positive"
                );
            }
        }
    }

    record TombstoneResult(
        TombstoneDisposition disposition,
        UUID evidenceId,
        long revision,
        EvidenceState state,
        DeleteReason deleteReason,
        Instant tombstonedAt,
        String deletionRequestHash,
        String tombstoneHash,
        String eventHash
    ) {
        public TombstoneResult {
            disposition = Objects.requireNonNull(
                disposition,
                "disposition must not be null"
            );
            evidenceId = Objects.requireNonNull(evidenceId, "evidenceId must not be null");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            boolean notFound = disposition == TombstoneDisposition.NOT_FOUND;
            boolean completed = disposition == TombstoneDisposition.TOMBSTONED
                || disposition == TombstoneDisposition.REPLAYED;
            if (notFound) {
                if (revision != 0
                    || state != null
                    || deleteReason != null
                    || tombstonedAt != null
                    || deletionRequestHash != null
                    || tombstoneHash != null
                    || eventHash != null) {
                    throw new IllegalArgumentException(
                        "not-found tombstone result must contain no stored evidence"
                    );
                }
            } else {
                state = Objects.requireNonNull(
                    state,
                    "stored tombstone result requires state"
                );
                if (revision < 1) {
                    throw new IllegalArgumentException(
                        "stored tombstone result requires a positive revision"
                    );
                }
                if (completed) {
                    deleteReason = Objects.requireNonNull(
                        deleteReason,
                        "completed tombstone requires deleteReason"
                    );
                    tombstonedAt = Objects.requireNonNull(
                        tombstonedAt,
                        "completed tombstone requires tombstonedAt"
                    );
                    deletionRequestHash = Hashes.requireSha256(
                        deletionRequestHash,
                        "deletionRequestHash"
                    );
                    tombstoneHash = Hashes.requireSha256(
                        tombstoneHash,
                        "tombstoneHash"
                    );
                    eventHash = Hashes.requireSha256(eventHash, "eventHash");
                    if (state != EvidenceState.TOMBSTONED || revision != 2) {
                        throw new IllegalArgumentException(
                            "completed tombstone must return tombstoned revision two"
                        );
                    }
                } else if (deleteReason != null
                    || tombstonedAt != null
                    || deletionRequestHash != null
                    || tombstoneHash != null
                    || eventHash != null) {
                    throw new IllegalArgumentException(
                        "non-completed tombstone result must not manufacture deletion evidence"
                    );
                }
            }
        }
    }

    record EvidenceSnapshot(
        ApprovalAssistanceDurableEvidence evidence,
        long revision,
        EvidenceState state,
        DeleteReason deleteReason,
        Instant tombstonedAt,
        String deletionRequestHash,
        String tombstoneHash
    ) {
        public EvidenceSnapshot {
            evidence = Objects.requireNonNull(evidence, "evidence must not be null");
            state = Objects.requireNonNull(state, "state must not be null");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            if (state == EvidenceState.ACTIVE) {
                if (revision != 1
                    || deleteReason != null
                    || tombstonedAt != null
                    || deletionRequestHash != null
                    || tombstoneHash != null) {
                    throw new IllegalArgumentException(
                        "active evidence must remain at revision one without tombstone metadata"
                    );
                }
            } else {
                deleteReason = Objects.requireNonNull(
                    deleteReason,
                    "tombstoned evidence requires deleteReason"
                );
                tombstonedAt = Objects.requireNonNull(
                    tombstonedAt,
                    "tombstoned evidence requires tombstonedAt"
                );
                deletionRequestHash = Hashes.requireSha256(
                    deletionRequestHash,
                    "deletionRequestHash"
                );
                tombstoneHash = Hashes.requireSha256(tombstoneHash, "tombstoneHash");
                if (revision != 2) {
                    throw new IllegalArgumentException(
                        "tombstoned evidence must use revision two"
                    );
                }
            }
        }
    }

    final class Hashes {

        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

        private Hashes() {
        }

        static String requireSha256(String value, String name) {
            String normalized = requireText(value, name, 64).toLowerCase();
            if (!SHA256.matcher(normalized).matches()) {
                throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
            }
            return normalized;
        }

        static String requireText(String value, String name, int maximumLength) {
            Objects.requireNonNull(value, name + " must not be null");
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(name + " must be non-blank and bounded");
            }
            return normalized;
        }
    }
}
