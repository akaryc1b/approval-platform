package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore.MigrationPlanConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.OTHER_TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationPlanStoreIntegrationTest
    extends JdbcApprovalMigrationPlanStoreIntegrationTestSupport {

    @Test
    void createsImmutablePlanWithSelectionAndExactReplay() {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        ApprovalMigrationPlanEvent event = initialEvent(plan, "initial-plan");

        var created = plans.createPlan(plan, event);
        var replayed = plans.createPlan(plan, event);

        assertFalse(created.replayedExistingPlan());
        assertTrue(replayed.replayedExistingPlan());
        assertEquals(plan, plans.findPlan(TENANT, PLAN_ID).orElseThrow());
        assertEquals(plan, plans.findPlanByHash(TENANT, plan.planHash()).orElseThrow());
        assertEquals(1, count("ap_process_migration_plan"));
        assertEquals(2, count("ap_process_migration_plan_instance"));
        assertEquals(1, count("ap_process_migration_plan_event"));
    }

    @Test
    void idempotencyAndCanonicalPlanHashConflictsFailClosed() {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        plans.createPlan(plan, initialEvent(plan, "initial-plan"));

        ApprovalMigrationPlan changed = proposed(
            TENANT,
            UUID.fromString("77000000-0000-0000-0000-000000000003"),
            "plan-key",
            hash('e')
        );
        assertThrows(
            MigrationPlanConflictException.class,
            () -> plans.createPlan(changed, initialEvent(changed, "changed-plan"))
        );

        ApprovalMigrationPlan duplicateHash = proposed(
            TENANT,
            UUID.fromString("77000000-0000-0000-0000-000000000004"),
            "another-key",
            plan.planHash()
        );
        assertThrows(
            MigrationPlanConflictException.class,
            () -> plans.createPlan(
                duplicateHash,
                initialEvent(duplicateHash, "duplicate-hash-plan")
            )
        );
        assertEquals(1, count("ap_process_migration_plan"));
    }

    @Test
    void sameStableIdentitiesCoexistAcrossTenantsAndReadsStayScoped() {
        ApprovalMigrationPlan tenantPlan = proposed(TENANT, PLAN_ID, "tenant-key", hash('d'));
        ApprovalMigrationPlan otherPlan = proposed(
            OTHER_TENANT,
            PLAN_ID,
            "tenant-key",
            hash('d')
        );

        plans.createPlan(tenantPlan, initialEvent(tenantPlan, "tenant-event"));
        plans.createPlan(otherPlan, initialEvent(otherPlan, "other-event"));

        assertEquals(tenantPlan, plans.findPlan(TENANT, PLAN_ID).orElseThrow());
        assertEquals(otherPlan, plans.findPlan(OTHER_TENANT, PLAN_ID).orElseThrow());
        assertNotEquals(
            plans.findPlan(TENANT, PLAN_ID).orElseThrow().tenantId(),
            plans.findPlan(OTHER_TENANT, PLAN_ID).orElseThrow().tenantId()
        );
        assertEquals(2, count("ap_process_migration_plan"));
    }

    @Test
    void exactIndependentAuthorizationOpensAuthorizedReadGate() {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        plans.createPlan(plan, initialEvent(plan, "initial-plan"));
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-key",
            hash('e')
        );
        ApprovalMigrationPlan next = plan.authorized(authorization);
        ApprovalMigrationPlanEvent event = authorizationEvent(
            plan,
            next,
            authorization,
            "authorization-event"
        );

        var result = plans.authorizePlan(next, 1, authorization, event);
        var replay = plans.authorizePlan(next, 1, authorization, event);

        assertFalse(result.replayedExistingAuthorization());
        assertTrue(replay.replayedExistingAuthorization());
        assertEquals(next, plans.findAuthorizedPlan(
            TENANT,
            PLAN_ID,
            plan.planHash(),
            authorization.decidedAt().plusSeconds(1)
        ).orElseThrow());
        assertTrue(plans.findAuthorizedPlan(
            TENANT,
            PLAN_ID,
            hash('9'),
            authorization.decidedAt()
        ).isEmpty());
        assertTrue(plans.findAuthorizedPlan(
            TENANT,
            PLAN_ID,
            plan.planHash(),
            authorization.expiresAt()
        ).isEmpty());
        assertEquals(1, count("ap_process_migration_plan_authorization"));
        assertEquals(2, count("ap_process_migration_plan_event"));
    }

    @Test
    void mismatchedAuthorizationAndStaleRevisionFailClosed() {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        plans.createPlan(plan, initialEvent(plan, "initial-plan"));
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-key",
            hash('e')
        );
        ApprovalMigrationPlan next = plan.authorized(authorization);
        ApprovalMigrationPlanEvent event = authorizationEvent(
            plan,
            next,
            authorization,
            "authorization-event"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> plans.authorizePlan(next, 2, authorization, event)
        );

        ApprovalMigrationPlanAuthorization wrong = authorization(
            plan,
            "migration-approver-two",
            "authorization-key-two",
            hash('f')
        );
        ApprovalMigrationPlan wrongNext = plan.authorized(wrong);
        assertThrows(
            IllegalArgumentException.class,
            () -> plans.authorizePlan(wrongNext, 1, wrong, event)
        );
    }

}
