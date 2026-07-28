package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.InstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanPage;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.OTHER_TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationOperationsQueryIntegrationTest
    extends JdbcApprovalMigrationPlanStoreIntegrationTestSupport {

    @Test
    void readsTenantScopedSummaryPlansDetailsAndCanonicalInstances() {
        ApprovalMigrationPlan tenantPlan = proposed(
            TENANT,
            PLAN_ID,
            "operations-tenant-plan",
            hash('d')
        );
        plans.createPlan(tenantPlan, initialEvent(tenantPlan, "operations-tenant-event"));
        ApprovalMigrationPlan otherPlan = proposed(
            OTHER_TENANT,
            UUID.fromString("77000000-0000-0000-0000-000000000099"),
            "operations-other-plan",
            hash('e')
        );
        plans.createPlan(otherPlan, initialEvent(otherPlan, "operations-other-event"));

        JdbcApprovalMigrationOperationsQuery query = query();

        var summary = query.summarize(TENANT);
        assertEquals(TENANT, summary.tenantId());
        assertEquals(1, summary.totalPlans());
        assertEquals(0, summary.consumedPlans());
        assertEquals(NOW, summary.observedAt());

        PlanPage page = query.findPlans(new PlanCriteria(
            TENANT,
            tenantPlan.definitionKey(),
            tenantPlan.status(),
            null,
            false,
            1,
            0
        ));
        assertEquals(1, page.total());
        assertFalse(page.hasMore());
        assertEquals(tenantPlan.planId(), page.items().getFirst().planId());
        assertEquals(2, page.items().getFirst().selectedInstanceCount());
        assertEquals(2, page.items().getFirst().unresolvedCount());

        var detail = query.findPlan(TENANT, tenantPlan.planId()).orElseThrow();
        assertEquals(tenantPlan.planHash(), detail.plan().planHash());
        assertEquals(tenantPlan.sourcePackageHash(), detail.sourcePackageHash());
        assertEquals(tenantPlan.targetPackageHash(), detail.targetPackageHash());

        InstancePage instances = query.findInstances(TENANT, tenantPlan.planId(), 1, 0);
        assertEquals(2, instances.total());
        assertEquals(1, instances.items().size());
        assertTrue(instances.hasMore());
        assertEquals(1, instances.items().getFirst().sequenceNo());
        assertEquals(
            tenantPlan.selectedInstances().getFirst().instanceEvidenceHash(),
            instances.items().getFirst().selectedInstanceEvidenceHash()
        );
        assertFalse(instances.items().getFirst().exactCompletion());
        assertFalse(instances.items().getFirst().bindingConflict());

        assertTrue(query.findPlan(TENANT, otherPlan.planId()).isEmpty());
        assertThrows(
            MigrationOperationsNotFoundException.class,
            () -> query.findInstances(TENANT, otherPlan.planId(), 50, 0)
        );
    }

    @Test
    void pagingAndFiltersRemainBoundedAndStable() {
        ApprovalMigrationPlan first = proposed(
            TENANT,
            PLAN_ID,
            "operations-first",
            hash('d')
        );
        ApprovalMigrationPlan second = proposed(
            TENANT,
            UUID.fromString("77000000-0000-0000-0000-000000000098"),
            "operations-second",
            hash('e')
        );
        plans.createPlan(first, initialEvent(first, "operations-first-event"));
        plans.createPlan(second, initialEvent(second, "operations-second-event"));

        JdbcApprovalMigrationOperationsQuery query = query();
        PlanPage firstPage = query.findPlans(new PlanCriteria(
            TENANT,
            null,
            null,
            null,
            null,
            1,
            0
        ));
        PlanPage secondPage = query.findPlans(new PlanCriteria(
            TENANT,
            null,
            null,
            null,
            null,
            1,
            1
        ));

        assertEquals(2, firstPage.total());
        assertTrue(firstPage.hasMore());
        assertEquals(2, secondPage.total());
        assertFalse(secondPage.hasMore());
        assertFalse(firstPage.items().getFirst().planId().equals(
            secondPage.items().getFirst().planId()
        ));
        assertThrows(IllegalArgumentException.class, () -> query.findPlans(new PlanCriteria(
            TENANT,
            null,
            null,
            null,
            null,
            201,
            0
        )));
    }

    private static JdbcApprovalMigrationOperationsQuery query() {
        return new JdbcApprovalMigrationOperationsQuery(
            dataSource,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}