package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore.MigrationPlanConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.DEFINITION_KEY;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.OTHER_TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationPlanSecurityIntegrationTest
    extends JdbcApprovalMigrationPlanStoreIntegrationTestSupport {

    @Test
    void concurrentAuthorizationProducesOneRevisionOwner() throws Exception {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        plans.createPlan(plan, initialEvent(plan, "initial-plan"));
        ApprovalMigrationPlanAuthorization first = authorization(
            plan,
            "migration-approver-one",
            "authorization-key-one",
            hash('e')
        );
        ApprovalMigrationPlanAuthorization second = authorization(
            plan,
            "migration-approver-two",
            "authorization-key-two",
            hash('f')
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstResult = executor.submit(() -> authorizeConcurrently(
                newStore(),
                plan,
                first,
                ready,
                start,
                "first-concurrent-event"
            ));
            Future<Boolean> secondResult = executor.submit(() -> authorizeConcurrently(
                newStore(),
                plan,
                second,
                ready,
                start,
                "second-concurrent-event"
            ));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int successes = (firstResult.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (secondResult.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(PlanStatus.AUTHORIZED, plans.findPlan(TENANT, PLAN_ID).orElseThrow().status());
        assertEquals(1, count("ap_process_migration_plan_authorization"));
        assertEquals(2, count("ap_process_migration_plan_event"));
    }

    @Test
    void targetDeploymentIdentityDriftRejectsAuthorizationAtDatabaseBoundary() {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        plans.createPlan(plan, initialEvent(plan, "initial-plan"));
        jdbc.update("""
            update ap_approval_release_deployment
            set engine_deployment_id=?,engine_definition_id=?,engine_version=?,attempt_count=2,
                updated_at=?,deployed_at=?
            where tenant_id=? and definition_key=? and release_version=2
            """,
            "engine-deployment-v2-replaced",
            "engine-definition-v2-replaced",
            3,
            offset(NOW.plusSeconds(10)),
            offset(NOW.plusSeconds(10)),
            TENANT,
            DEFINITION_KEY
        );
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-key",
            hash('e')
        );
        ApprovalMigrationPlan next = plan.authorized(authorization);

        assertThrows(MigrationPlanConflictException.class, () -> plans.authorizePlan(
            next,
            1,
            authorization,
            authorizationEvent(plan, next, authorization, "authorization-event")
        ));
    }

    @Test
    void directCurrentAndAppendOnlyEvidenceTamperingIsRejected() {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        plans.createPlan(plan, initialEvent(plan, "initial-plan"));
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-key",
            hash('e')
        );
        ApprovalMigrationPlan next = plan.authorized(authorization);
        plans.authorizePlan(
            next,
            1,
            authorization,
            authorizationEvent(plan, next, authorization, "authorization-event")
        );

        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_plan set plan_hash=? where tenant_id=? and plan_id=?",
            hash('9'), TENANT, PLAN_ID
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_plan_instance set instance_evidence_hash=? "
                + "where tenant_id=? and plan_id=? and approval_instance_id=?",
            hash('9'), TENANT, PLAN_ID, FIRST_INSTANCE
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_plan_authorization where tenant_id=? and plan_id=?",
            TENANT, PLAN_ID
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_plan_event where tenant_id=? and plan_id=?",
            TENANT, PLAN_ID
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_plan where tenant_id=? and plan_id=?",
            TENANT, PLAN_ID
        ));
    }

    @Test
    void crossTenantAuthorizationReferenceCannotBindAnotherTenantPlan() {
        ApprovalMigrationPlan plan = proposed(TENANT, PLAN_ID, "plan-key", hash('d'));
        plans.createPlan(plan, initialEvent(plan, "initial-plan"));
        ApprovalMigrationPlanAuthorization otherTenantAuthorization = new ApprovalMigrationPlanAuthorization(
            UUID.fromString("77000000-0000-0000-0000-000000000090"),
            OTHER_TENANT,
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
            "migration-approver",
            "Approve exact immutable migration plan hash",
            "other-tenant-authorization-key",
            NOW.plusSeconds(20),
            NOW.plusSeconds(100),
            "other-tenant-request",
            "other-tenant-trace",
            "other-tenant-audit"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> plan.authorized(otherTenantAuthorization)
        );
    }

}
