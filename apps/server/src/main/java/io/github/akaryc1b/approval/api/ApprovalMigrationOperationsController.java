package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.InstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.OperationsSummary;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanDetail;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanPage;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only M5-E1 process-instance operations visibility. No command mapping exists here. */
@RestController
@RequestMapping("/api/approval/management/process-instance-operations")
@ApprovalManagementPermission(
    value = ApprovalManagementPermission.Requirement.MIGRATION_OPERATIONS_READ,
    resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
)
public class ApprovalMigrationOperationsController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalMigrationOperationsQuery query;

    public ApprovalMigrationOperationsController(ApprovalMigrationOperationsQuery query) {
        this.query = query;
    }

    @GetMapping("/summary")
    public OperationsSummary summary(@RequestHeader(TENANT_ID) String tenantId) {
        return query.summarize(tenantId);
    }

    @GetMapping("/plans")
    public PlanPage findPlans(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) @Size(min = 1, max = 64) String definitionKey,
        @RequestParam(required = false) PlanStatus planStatus,
        @RequestParam(required = false) AggregateStatus aggregateStatus,
        @RequestParam(required = false) Boolean paused,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return query.findPlans(new PlanCriteria(
            tenantId,
            definitionKey,
            planStatus,
            aggregateStatus,
            paused,
            limit,
            offset
        ));
    }

    @GetMapping("/plans/{planId}")
    public PlanDetail findPlan(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID planId
    ) {
        return query.findPlan(tenantId, planId)
            .orElseThrow(() -> new MigrationOperationsNotFoundException(
                "migration plan was not found"
            ));
    }

    @GetMapping("/plans/{planId}/instances")
    public InstancePage findInstances(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID planId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return query.findInstances(tenantId, planId, limit, offset);
    }
}
