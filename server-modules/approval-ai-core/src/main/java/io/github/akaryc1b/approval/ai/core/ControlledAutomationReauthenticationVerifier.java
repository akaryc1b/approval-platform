package io.github.akaryc1b.approval.ai.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-owned step-up verification boundary for one controlled-automation confirmation.
 *
 * <p>The contract carries no password, session credential, bearer token, Secret or reusable
 * authority material. The current repository has no production verifier; the safe default is
 * unavailable and blocks confirmation.</p>
 */
public interface ControlledAutomationReauthenticationVerifier {

    Verification verify(
        AiServerRequestContext context,
        ControlledAutomationProposal proposal,
        ReauthenticationChallenge challenge
    );

    static ControlledAutomationReauthenticationVerifier unavailable() {
        return (context, proposal, challenge) -> Verification.unavailable();
    }

    enum ReauthenticationMethod {
        HOST_STEP_UP,
        PASSWORD_REENTRY,
        SECURITY_KEY,
        TOTP
    }

    enum VerificationStatus {
        ACCEPTED,
        EXPIRED,
        FAILED,
        UNAVAILABLE
    }

    record ReauthenticationChallenge(
        UUID challengeId,
        String bindingHash,
        ReauthenticationMethod method,
        Instant issuedAt,
        Instant expiresAt
    ) {
        public ReauthenticationChallenge {
            challengeId = Objects.requireNonNull(challengeId, "challengeId must not be null");
            bindingHash = ControlledAutomationProposal.requireSha256(
                bindingHash,
                "reauthenticationBindingHash"
            );
            method = Objects.requireNonNull(method, "method must not be null");
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException(
                    "reauthentication challenge expiry must be after issuance"
                );
            }
        }
    }

    record Verification(
        VerificationStatus status,
        String evidenceHash,
        Instant verifiedAt
    ) {
        public Verification {
            status = Objects.requireNonNull(status, "status must not be null");
            if (status == VerificationStatus.ACCEPTED) {
                evidenceHash = ControlledAutomationProposal.requireSha256(
                    evidenceHash,
                    "reauthenticationEvidenceHash"
                );
                verifiedAt = Objects.requireNonNull(
                    verifiedAt,
                    "verifiedAt must not be null for accepted verification"
                );
            } else if (evidenceHash != null || verifiedAt != null) {
                throw new IllegalArgumentException(
                    "failed or unavailable reauthentication cannot carry reusable evidence"
                );
            }
        }

        public static Verification accepted(String evidenceHash, Instant verifiedAt) {
            return new Verification(VerificationStatus.ACCEPTED, evidenceHash, verifiedAt);
        }

        public static Verification unavailable() {
            return new Verification(VerificationStatus.UNAVAILABLE, null, null);
        }

        public static Verification failed() {
            return new Verification(VerificationStatus.FAILED, null, null);
        }

        public static Verification expired() {
            return new Verification(VerificationStatus.EXPIRED, null, null);
        }
    }
}
