package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore.MigrationPlanConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Atomic immutable-plan creation and authorization writers. */
final class JdbcApprovalMigrationPlanWriter {

    private final JdbcApprovalMigrationPlanRepository repository;
    private final TransactionTemplate transactions;

    JdbcApprovalMigrationPlanWriter(
        JdbcApprovalMigrationPlanRepository repository,
        PlatformTransactionManager transactionManager
    ) {
        this.repository = Objects.requireNonNull(repository);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    ApprovalMigrationPlanStore.PlanCreationResult create(
        ApprovalMigrationPlan plan,
        ApprovalMigrationPlanEvent initialEvent
    ) {
        requireInitialEvidence(plan, initialEvent);
        try {
            return transactions.execute(status -> {
                if (repository.insertPlan(plan) == 0) {
                    ApprovalMigrationPlan existing = repository.findPlanByIdempotencyKey(
                        plan.tenantId(),
                        plan.idempotencyKey()
                    ).orElseThrow(() -> conflict("migration plan replay disappeared"));
                    if (!existing.equals(plan)) {
                        throw conflict("migration plan idempotency key reused with different evidence");
                    }
                    return new ApprovalMigrationPlanStore.PlanCreationResult(existing, true);
                }
                repository.insertSelections(plan);
                repository.appendEvent(initialEvent);
                return new ApprovalMigrationPlanStore.PlanCreationResult(plan, false);
            });
        } catch (DataAccessException exception) {
            ApprovalMigrationPlan replay = repository.findPlanByIdempotencyKey(
                plan.tenantId(),
                plan.idempotencyKey()
            ).orElse(null);
            if (plan.equals(replay)) {
                return new ApprovalMigrationPlanStore.PlanCreationResult(replay, true);
            }
            throw new MigrationPlanConflictException(
                "migration plan persistence conflict",
                exception
            );
        }
    }

    ApprovalMigrationPlanStore.AuthorizationResult authorize(
        ApprovalMigrationPlan next,
        long expectedRevision,
        ApprovalMigrationPlanAuthorization authorization,
        ApprovalMigrationPlanEvent event
    ) {
        requireAuthorizationEvidence(next, expectedRevision, authorization, event);
        try {
            return transactions.execute(status -> {
                if (repository.insertAuthorization(authorization) == 0) {
                    ApprovalMigrationPlanAuthorization existing = repository.findAuthorization(
                        authorization.tenantId(),
                        authorization.planId()
                    ).orElseThrow(() -> conflict("migration plan authorization replay disappeared"));
                    ApprovalMigrationPlan current = repository.findPlan(
                        authorization.tenantId(),
                        authorization.planId()
                    ).orElseThrow(() -> conflict("migration plan disappeared during authorization replay"));
                    if (!existing.equals(authorization) || !current.equals(next)) {
                        throw conflict("migration plan already has different authorization evidence");
                    }
                    return new ApprovalMigrationPlanStore.AuthorizationResult(current, existing, true);
                }
                if (repository.updatePlan(next, expectedRevision, event) != 1) {
                    throw conflict("migration plan authorization lost revision compare-and-set");
                }
                repository.appendEvent(event);
                return new ApprovalMigrationPlanStore.AuthorizationResult(next, authorization, false);
            });
        } catch (DataAccessException exception) {
            ApprovalMigrationPlanAuthorization existing = repository.findAuthorization(
                authorization.tenantId(),
                authorization.planId()
            ).orElse(null);
            ApprovalMigrationPlan current = repository.findPlan(
                authorization.tenantId(),
                authorization.planId()
            ).orElse(null);
            if (authorization.equals(existing) && next.equals(current)) {
                return new ApprovalMigrationPlanStore.AuthorizationResult(current, existing, true);
            }
            throw new MigrationPlanConflictException(
                "migration plan authorization conflict",
                exception
            );
        }
    }

    private static void requireInitialEvidence(
        ApprovalMigrationPlan plan,
        ApprovalMigrationPlanEvent event
    ) {
        if (plan.revision() != 1
            || event.revision() != 1
            || !plan.tenantId().equals(event.tenantId())
            || !plan.planId().equals(event.planId())
            || !plan.planHash().equals(event.planHash())
            || event.toStatus() != plan.status()) {
            throw new IllegalArgumentException("initial migration plan and event evidence do not match");
        }
    }

    private static void requireAuthorizationEvidence(
        ApprovalMigrationPlan next,
        long expectedRevision,
        ApprovalMigrationPlanAuthorization authorization,
        ApprovalMigrationPlanEvent event
    ) {
        if (next.revision() != expectedRevision + 1
            || !next.tenantId().equals(authorization.tenantId())
            || !next.planId().equals(authorization.planId())
            || !next.planHash().equals(authorization.planHash())
            || !next.authorizationId().equals(authorization.authorizationId())
            || !next.authorizationEvidenceHash().equals(
                authorization.authorizationEvidenceHash()
            )
            || event.revision() != next.revision()
            || event.toStatus() != next.status()
            || !event.authorizationId().equals(authorization.authorizationId())) {
            throw new IllegalArgumentException(
                "migration plan authorization current, event and decision evidence do not match"
            );
        }
    }

    private static MigrationPlanConflictException conflict(String message) {
        return new MigrationPlanConflictException(message);
    }
}
