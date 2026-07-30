package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable M5-C immutable-plan and exact-authorization evidence without execution. */
public interface ApprovalMigrationPlanStore {

    PlanCreationResult createPlan(
        ApprovalMigrationPlan plan,
        ApprovalMigrationPlanEvent initialEvent
    );

    Optional<ApprovalMigrationPlan> findPlan(String tenantId, UUID planId);

    Optional<ApprovalMigrationPlan> findPlanByHash(String tenantId, String planHash);

    Optional<ApprovalMigrationPlan> findPlanByIdempotencyKey(
        String tenantId,
        String idempotencyKey
    );

    AuthorizationResult authorizePlan(
        ApprovalMigrationPlan next,
        long expectedRevision,
        ApprovalMigrationPlanAuthorization authorization,
        ApprovalMigrationPlanEvent event
    );

    Optional<ApprovalMigrationPlanAuthorization> findAuthorization(
        String tenantId,
        UUID planId
    );

    Optional<ApprovalMigrationPlan> findAuthorizedPlan(
        String tenantId,
        UUID planId,
        String planHash,
        Instant validAt
    );

    List<ApprovalMigrationPlanEvent> findEvents(String tenantId, UUID planId);

    record PlanCreationResult(
        ApprovalMigrationPlan plan,
        boolean replayedExistingPlan
    ) {
        public PlanCreationResult {
            if (plan == null) {
                throw new IllegalArgumentException("plan must not be null");
            }
        }
    }

    record AuthorizationResult(
        ApprovalMigrationPlan plan,
        ApprovalMigrationPlanAuthorization authorization,
        boolean replayedExistingAuthorization
    ) {
        public AuthorizationResult {
            if (plan == null || authorization == null) {
                throw new IllegalArgumentException(
                    "plan and authorization must not be null"
                );
            }
        }
    }

    final class MigrationPlanConflictException extends RuntimeException {
        public MigrationPlanConflictException(String message) {
            super(message);
        }

        public MigrationPlanConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
