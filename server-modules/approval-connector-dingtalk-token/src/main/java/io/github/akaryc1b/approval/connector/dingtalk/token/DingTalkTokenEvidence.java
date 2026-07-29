package io.github.akaryc1b.approval.connector.dingtalk.token;

import java.time.Instant;
import java.util.Objects;

public record DingTalkTokenEvidence(
    DingTalkTokenOutcome outcome,
    DingTalkTokenFailure failure,
    String requestEvidenceHash,
    String routePlanHash,
    String credentialRequestHash,
    String tokenVersionReference,
    Instant issuedAt,
    Instant refreshAt,
    Instant expiresAt,
    boolean endpointAttempted,
    boolean credentialLeaseOpened,
    boolean singleFlightLeader,
    long generationOrdinal,
    String evidenceHash
) {

    public DingTalkTokenEvidence {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        failure = Objects.requireNonNull(failure, "failure must not be null");
        if (failure != DingTalkTokenFailure.NONE) {
            throw new IllegalArgumentException("successful Token evidence must have NONE failure");
        }
        requestEvidenceHash = DingTalkTokenSupport.sha256(
            requestEvidenceHash,
            "requestEvidenceHash"
        );
        routePlanHash = DingTalkTokenSupport.sha256(routePlanHash, "routePlanHash");
        credentialRequestHash = DingTalkTokenSupport.sha256(
            credentialRequestHash,
            "credentialRequestHash"
        );
        tokenVersionReference = DingTalkTokenSupport.sha256(
            tokenVersionReference,
            "tokenVersionReference"
        );
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        refreshAt = Objects.requireNonNull(refreshAt, "refreshAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (refreshAt.isBefore(issuedAt) || expiresAt.isBefore(refreshAt)
            || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Token evidence time range is invalid");
        }
        if (generationOrdinal < 0) {
            throw new IllegalArgumentException("generationOrdinal must not be negative");
        }
        evidenceHash = DingTalkTokenSupport.sha256(evidenceHash, "evidenceHash");
    }

    public static DingTalkTokenEvidence create(
        DingTalkTokenOutcome outcome,
        DingTalkTokenRequest request,
        TokenEntryView entry,
        boolean singleFlightLeader
    ) {
        String computed = DingTalkTokenSupport.hash(
            outcome.name() + "\n" + request.evidenceHash() + "\n"
                + entry.tokenVersionReference() + "\n" + entry.issuedAt() + "\n"
                + entry.refreshAt() + "\n" + entry.expiresAt() + "\n"
                + entry.generationOrdinal() + "\n" + singleFlightLeader
        );
        return new DingTalkTokenEvidence(
            outcome,
            DingTalkTokenFailure.NONE,
            request.evidenceHash(),
            request.routePlan().planHash(),
            request.applicationCredentialRequest().evidenceHash(),
            entry.tokenVersionReference(),
            entry.issuedAt(),
            entry.refreshAt(),
            entry.expiresAt(),
            outcome == DingTalkTokenOutcome.ACQUIRED
                || outcome == DingTalkTokenOutcome.REFRESHED,
            outcome == DingTalkTokenOutcome.ACQUIRED
                || outcome == DingTalkTokenOutcome.REFRESHED,
            singleFlightLeader,
            entry.generationOrdinal(),
            computed
        );
    }

    public String canonicalJson() {
        return new StringBuilder(1_024)
            .append('{')
            .append("\"outcome\":").append(DingTalkTokenSupport.json(outcome.name()))
            .append(",\"failure\":")
            .append(DingTalkTokenSupport.json(failure.stableCode()))
            .append(",\"requestEvidenceHash\":")
            .append(DingTalkTokenSupport.json(requestEvidenceHash))
            .append(",\"routePlanHash\":")
            .append(DingTalkTokenSupport.json(routePlanHash))
            .append(",\"credentialRequestHash\":")
            .append(DingTalkTokenSupport.json(credentialRequestHash))
            .append(",\"tokenVersionReference\":")
            .append(DingTalkTokenSupport.json(tokenVersionReference))
            .append(",\"issuedAt\":").append(DingTalkTokenSupport.instant(issuedAt))
            .append(",\"refreshAt\":").append(DingTalkTokenSupport.instant(refreshAt))
            .append(",\"expiresAt\":").append(DingTalkTokenSupport.instant(expiresAt))
            .append(",\"endpointAttempted\":").append(endpointAttempted)
            .append(",\"credentialLeaseOpened\":").append(credentialLeaseOpened)
            .append(",\"singleFlightLeader\":").append(singleFlightLeader)
            .append(",\"generationOrdinal\":").append(generationOrdinal)
            .append(",\"evidenceHash\":")
            .append(DingTalkTokenSupport.json(evidenceHash))
            .append('}')
            .toString();
    }

    public interface TokenEntryView {
        String tokenVersionReference();
        Instant issuedAt();
        Instant refreshAt();
        Instant expiresAt();
        long generationOrdinal();
    }
}
