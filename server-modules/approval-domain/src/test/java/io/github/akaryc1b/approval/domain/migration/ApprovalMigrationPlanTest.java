package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan.SelectedInstance;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.ExpectedInstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationPlanTest {

    private static final Instant NOW = Instant.parse("2026-07-24T14:00:00Z");
    private static final UUID PLAN_ID = UUID.fromString("75000000-0000-0000-0000-000000000001");
    private static final UUID ASSESSMENT_ID = UUID.fromString(
        "75000000-0000-0000-0000-000000000002"
    );

    @Test
    void proposedPlanRequiresCanonicalSelectionAndNoAuthorizationEvidence() {
        ApprovalMigrationPlan plan = proposed();

        assertEquals(PlanStatus.PROPOSED, plan.status());
        assertEquals(2, plan.selectedInstanceCount());
        assertFalse(plan.authorizedAt(NOW.plusSeconds(10)));

        List<SelectedInstance> reversed = List.of(
            selected("75000000-0000-0000-0000-000000000012", '2'),
            selected("75000000-0000-0000-0000-000000000011", '1')
        );
        assertThrows(IllegalArgumentException.class, () -> copyWithSelection(reversed));
    }

    @Test
    void exactAuthorizationAdvancesOnceAndSeparatesRequesterFromAuthorizer() {
        ApprovalMigrationPlan current = proposed();
        ApprovalMigrationPlanAuthorization authorization = authorization("migration-approver");

        ApprovalMigrationPlan authorized = current.authorized(authorization);

        assertEquals(PlanStatus.AUTHORIZED, authorized.status());
        assertEquals(2, authorized.revision());
        assertEquals(authorization.authorizationId(), authorized.authorizationId());
        assertTrue(authorized.authorizedAt(NOW.plusSeconds(30)));
        assertFalse(authorized.authorizedAt(authorization.expiresAt()));
    }

    @Test
    void requesterCannotAuthorizeAndAuthorizationMustBindExactPlan() {
        ApprovalMigrationPlan plan = proposed();

        assertThrows(
            IllegalArgumentException.class,
            () -> plan.authorized(authorization("migration-requester"))
        );

        ApprovalMigrationPlanAuthorization wrongHash = new ApprovalMigrationPlanAuthorization(
            UUID.fromString("75000000-0000-0000-0000-000000000030"),
            plan.tenantId(),
            plan.planId(),
            hash('9'),
            plan.selectedInstanceCount(),
            plan.sourceReleaseVersion(),
            plan.sourcePackageHash(),
            plan.targetReleaseVersion(),
            plan.targetPackageHash(),
            "MIGRATION_PLAN_HIGH_RISK",
            "v1",
            hash('8'),
            "migration-approver",
            "Approve exact immutable migration plan",
            "authorization-key",
            NOW.plusSeconds(20),
            NOW.plusSeconds(120),
            "request-authorization",
            "trace-authorization",
            "audit-authorization"
        );
        assertThrows(IllegalArgumentException.class, () -> plan.authorized(wrongHash));
    }

    @Test
    void selectedTaskKeysMustBeNonemptyUniqueAndCanonical() {
        assertThrows(IllegalArgumentException.class, () -> new SelectedInstance(
            UUID.randomUUID(),
            ExpectedInstanceStatus.RUNNING,
            List.of("review", "approve"),
            hash('1'),
            hash('2')
        ));
        assertThrows(IllegalArgumentException.class, () -> new SelectedInstance(
            UUID.randomUUID(),
            ExpectedInstanceStatus.RUNNING,
            List.of("review", "review"),
            hash('1'),
            hash('2')
        ));
        assertThrows(IllegalArgumentException.class, () -> new SelectedInstance(
            UUID.randomUUID(),
            ExpectedInstanceStatus.RUNNING,
            List.of(),
            hash('1'),
            hash('2')
        ));
    }

    private static ApprovalMigrationPlan proposed() {
        return new ApprovalMigrationPlan(
            PLAN_ID,
            "tenant-migration-plan",
            ASSESSMENT_ID,
            hash('a'),
            "purchasePayment",
            1,
            hash('b'),
            2,
            hash('c'),
            UUID.fromString("75000000-0000-0000-0000-000000000030"),
            "engine-deployment-v2",
            "engine-definition-v2",
            2,
            List.of(
                selected("75000000-0000-0000-0000-000000000011", '1'),
                selected("75000000-0000-0000-0000-000000000012", '2')
            ),
            PlanStatus.PROPOSED,
            1,
            "plan-key",
            hash('d'),
            "migration-requester",
            "Create immutable migration plan from assessment",
            NOW.minusSeconds(60),
            NOW,
            NOW.plusSeconds(300),
            NOW,
            null,
            null,
            null,
            null,
            null,
            "request-plan",
            "trace-plan",
            "audit-plan"
        );
    }

    private static ApprovalMigrationPlan copyWithSelection(List<SelectedInstance> values) {
        ApprovalMigrationPlan plan = proposed();
        return new ApprovalMigrationPlan(
            plan.planId(), plan.tenantId(), plan.assessmentId(), plan.assessmentReportHash(),
            plan.definitionKey(), plan.sourceReleaseVersion(), plan.sourcePackageHash(),
            plan.targetReleaseVersion(), plan.targetPackageHash(),
            plan.targetDeploymentRecordId(), plan.targetEngineDeploymentId(),
            plan.targetEngineDefinitionId(), plan.targetEngineVersion(), values, plan.status(),
            plan.revision(), plan.idempotencyKey(), plan.planHash(), plan.requestedBy(),
            plan.operationReason(), plan.assessedAt(), plan.createdAt(), plan.expiresAt(),
            plan.updatedAt(), null, null, null, null, null, plan.requestId(), plan.traceId(),
            plan.auditChainReference()
        );
    }

    private static ApprovalMigrationPlanAuthorization authorization(String authorizedBy) {
        ApprovalMigrationPlan plan = proposed();
        return new ApprovalMigrationPlanAuthorization(
            UUID.fromString("75000000-0000-0000-0000-000000000020"),
            plan.tenantId(),
            plan.planId(),
            plan.planHash(),
            plan.selectedInstanceCount(),
            plan.sourceReleaseVersion(),
            plan.sourcePackageHash(),
            plan.targetReleaseVersion(),
            plan.targetPackageHash(),
            "MIGRATION_PLAN_HIGH_RISK",
            "v1",
            hash('e'),
            authorizedBy,
            "Approve exact immutable migration plan",
            "authorization-key",
            NOW.plusSeconds(20),
            NOW.plusSeconds(120),
            "request-authorization",
            "trace-authorization",
            "audit-authorization"
        );
    }

    private static SelectedInstance selected(String id, char value) {
        return new SelectedInstance(
            UUID.fromString(id),
            ExpectedInstanceStatus.RUNNING,
            List.of("approve", "review"),
            hash(value),
            hash((char) (value + 2))
        );
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
