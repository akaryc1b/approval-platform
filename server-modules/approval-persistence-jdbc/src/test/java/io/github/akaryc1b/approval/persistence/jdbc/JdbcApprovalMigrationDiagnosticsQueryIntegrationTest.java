package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.AttemptStatusFilter;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.FailureClass;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceSort;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.ReconciliationState;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
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

class JdbcApprovalMigrationDiagnosticsQueryIntegrationTest
    extends JdbcApprovalMigrationPlanStoreIntegrationTestSupport {

    @Test
    void readsOnlyTenantOwnedPlanAndUnprovisionedInstanceEvidence() {
        ApprovalMigrationPlan tenantPlan = proposed(
            TENANT,
            PLAN_ID,
            "diagnostics-tenant-plan",
            hash('d')
        );
        plans.createPlan(tenantPlan, initialEvent(tenantPlan, "diagnostics-tenant-event"));
        ApprovalMigrationPlan otherPlan = proposed(
            OTHER_TENANT,
            UUID.fromString("78000000-0000-0000-0000-000000000099"),
            "diagnostics-other-plan",
            hash('e')
        );
        plans.createPlan(otherPlan, initialEvent(otherPlan, "diagnostics-other-event"));

        JdbcApprovalMigrationDiagnosticsQuery query = query();

        var diagnostics = query.findPlanDiagnostics(TENANT, tenantPlan.planId()).orElseThrow();
        assertEquals(tenantPlan.planId(), diagnostics.planId());
        assertEquals("PROPOSED", diagnostics.planStatus());
        assertEquals(tenantPlan.selectedInstances().size(), diagnostics.selectedCount());
        assertEquals(tenantPlan.selectedInstances().size(), diagnostics.unresolvedCount());
        assertEquals("NOT_STARTED", diagnostics.aggregateStatus());
        assertEquals("NOT_OBSERVED", diagnostics.killSwitchStatus());
        assertEquals(NOW, diagnostics.observedAt());

        var page = query.findInstances(criteria(tenantPlan.planId(), 1, 1));
        assertEquals(2, page.total());
        assertEquals(1, page.items().size());
        assertEquals(2, page.totalPages());
        assertTrue(page.hasMore());
        assertEquals("UNPROVISIONED", page.items().getFirst().attemptStatus());
        assertEquals(FailureClass.NONE, page.items().getFirst().failureClass());
        assertEquals(ReconciliationState.NONE, page.items().getFirst().reconciliationState());
        assertEquals("NOT_RECORDED", page.items().getFirst().bindingResult());
        assertFalse(page.items().getFirst().canary());

        UUID instanceId = page.items().getFirst().approvalInstanceId();
        var instance = query.findInstance(TENANT, tenantPlan.planId(), instanceId)
            .orElseThrow();
        assertEquals(instanceId, instance.instance().approvalInstanceId());
        assertEquals(1, instance.timeline().size());
        assertEquals("PLAN_SELECTION", instance.timeline().getFirst().stage());
        assertEquals(NOW, instance.observedAt());

        assertTrue(query.findPlanDiagnostics(TENANT, otherPlan.planId()).isEmpty());
        assertTrue(query.findInstance(TENANT, tenantPlan.planId(), UUID.randomUUID()).isEmpty());
        assertThrows(
            JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException.class,
            () -> query.findInstances(criteria(otherPlan.planId(), 1, 50))
        );
    }

    @Test
    void filtersAndPagingStayClosedBoundedAndDeterministic() {
        ApprovalMigrationPlan plan = proposed(
            TENANT,
            PLAN_ID,
            "diagnostics-filter-plan",
            hash('d')
        );
        plans.createPlan(plan, initialEvent(plan, "diagnostics-filter-event"));

        JdbcApprovalMigrationDiagnosticsQuery query = query();
        var first = query.findInstances(criteria(plan.planId(), 1, 1));
        var second = query.findInstances(criteria(plan.planId(), 2, 1));

        assertEquals(2, first.total());
        assertEquals(2, second.total());
        assertTrue(first.hasMore());
        assertFalse(second.hasMore());
        assertFalse(first.items().getFirst().approvalInstanceId().equals(
            second.items().getFirst().approvalInstanceId()
        ));

        InstanceCriteria filtered = new InstanceCriteria(
            TENANT,
            plan.planId(),
            first.items().getFirst().approvalInstanceId(),
            AttemptStatusFilter.UNPROVISIONED,
            FailureClass.NONE,
            ReconciliationState.NONE,
            NOW.minusSeconds(1),
            NOW.plusSeconds(1),
            InstanceSort.LATEST_EVIDENCE_DESC,
            1,
            10
        );
        var filteredPage = query.findInstances(filtered);
        assertEquals(1, filteredPage.total());
        assertEquals(
            first.items().getFirst().approvalInstanceId(),
            filteredPage.items().getFirst().approvalInstanceId()
        );
    }

    private static InstanceCriteria criteria(UUID planId, int page, int pageSize) {
        return new InstanceCriteria(
            TENANT,
            planId,
            null,
            AttemptStatusFilter.UNPROVISIONED,
            null,
            null,
            null,
            null,
            InstanceSort.SEQUENCE_ASC,
            page,
            pageSize
        );
    }

    private static JdbcApprovalMigrationDiagnosticsQuery query() {
        return new JdbcApprovalMigrationDiagnosticsQuery(
            dataSource,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
