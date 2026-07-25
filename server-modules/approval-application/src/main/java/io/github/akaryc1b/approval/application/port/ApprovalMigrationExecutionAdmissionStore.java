package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic M5-D admission transaction from one authorized plan to one M5-B intent. */
public interface ApprovalMigrationExecutionAdmissionStore {

    AdmissionResult admit(AdmissionRequest request);

    Optional<ApprovalMigrationPlanConsumption> findConsumption(String tenantId, UUID planId);

    record AdmissionRequest(
        ApprovalMigrationPlan consumedPlan,
        long expectedRevision,
        ApprovalMigrationIntent intent,
        ApprovalMigrationIntentEvent intentEvent,
        ApprovalMigrationPlanConsumption consumption,
        ApprovalMigrationPlanEvent planEvent,
        AuditEvent auditEvent
    ) {
        public AdmissionRequest {
            consumedPlan = Objects.requireNonNull(consumedPlan, "consumedPlan must not be null");
            intent = Objects.requireNonNull(intent, "intent must not be null");
            intentEvent = Objects.requireNonNull(intentEvent, "intentEvent must not be null");
            consumption = Objects.requireNonNull(consumption, "consumption must not be null");
            planEvent = Objects.requireNonNull(planEvent, "planEvent must not be null");
            auditEvent = Objects.requireNonNull(auditEvent, "auditEvent must not be null");
            if (expectedRevision < 1 || consumedPlan.revision() != expectedRevision + 1) {
                throw new IllegalArgumentException("consumed plan revision is inconsistent");
            }
            requireSameIdentity(consumedPlan, intent, consumption, planEvent);
            if (!intent.intentId().equals(intentEvent.intentId())
                || !intent.tenantId().equals(intentEvent.tenantId())
                || intent.revision() != intentEvent.revision()
                || intent.status() != intentEvent.toStatus()) {
                throw new IllegalArgumentException("intent and initial event evidence do not match");
            }
            if (!auditEvent.tenantId().equals(consumedPlan.tenantId())) {
                throw new IllegalArgumentException("audit evidence does not match admission tenant");
            }
        }

        private static void requireSameIdentity(
            ApprovalMigrationPlan plan,
            ApprovalMigrationIntent intent,
            ApprovalMigrationPlanConsumption consumption,
            ApprovalMigrationPlanEvent event
        ) {
            if (!plan.tenantId().equals(intent.tenantId())
                || !plan.tenantId().equals(consumption.tenantId())
                || !plan.tenantId().equals(event.tenantId())
                || !plan.planId().equals(intent.planId())
                || !plan.planId().equals(consumption.planId())
                || !plan.planId().equals(event.planId())
                || !plan.planHash().equals(intent.planHash())
                || !plan.planHash().equals(consumption.planHash())
                || !plan.planHash().equals(event.planHash())
                || !intent.intentId().equals(consumption.intentId())
                || !intent.intentEvidenceHash().equals(consumption.intentEvidenceHash())
                || !plan.authorizationId().equals(consumption.authorizationId())
                || !plan.authorizationEvidenceHash().equals(
                    consumption.authorizationEvidenceHash()
                )) {
                throw new IllegalArgumentException("admission evidence identity is inconsistent");
            }
        }
    }

    record AdmissionResult(
        ApprovalMigrationPlan plan,
        ApprovalMigrationIntent intent,
        ApprovalMigrationPlanConsumption consumption,
        boolean replayedExistingAdmission
    ) {
        public AdmissionResult {
            plan = Objects.requireNonNull(plan, "plan must not be null");
            intent = Objects.requireNonNull(intent, "intent must not be null");
            consumption = Objects.requireNonNull(consumption, "consumption must not be null");
        }
    }

    final class MigrationExecutionAdmissionConflictException extends RuntimeException {
        public MigrationExecutionAdmissionConflictException(String message) {
            super(message);
        }

        public MigrationExecutionAdmissionConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
