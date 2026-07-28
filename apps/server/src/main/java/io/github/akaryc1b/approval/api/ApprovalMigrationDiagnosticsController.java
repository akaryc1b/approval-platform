package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.DiagnosticInstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceDiagnostics;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.PlanDiagnostics;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** GET-only M5-E2 diagnostics. This controller has no command or request-body mapping. */
@RestController
@RequestMapping({
    "/api/approval/management/process-instance-operations",
    "/api/approval/mobile/process-instance-operations"
})
@ApprovalManagementPermission(
    value = ApprovalManagementPermission.Requirement.MIGRATION_OPERATIONS_READ,
    resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
)
public class ApprovalMigrationDiagnosticsController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalMigrationDiagnosticsQuery query;

    public ApprovalMigrationDiagnosticsController(ApprovalMigrationDiagnosticsQuery query) {
        this.query = query;
    }

    @GetMapping("/plans/{planId}/diagnostics")
    public PlanDiagnostics findPlanDiagnostics(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID planId
    ) {
        return query.findPlanDiagnostics(tenantId, planId)
            .orElseThrow(() -> new MigrationOperationsNotFoundException(
                "migration plan was not found"
            ));
    }

    @GetMapping("/plans/{planId}/diagnostics/instances")
    public DiagnosticInstancePage findDiagnosticInstances(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID planId,
        @RequestParam MultiValueMap<String, String> parameters
    ) {
        return query.findInstances(
            ApprovalMigrationDiagnosticsParameters.parse(tenantId, planId, parameters)
        );
    }

    @GetMapping("/plans/{planId}/instances/{instanceId}/diagnostics")
    public InstanceDiagnostics findInstanceDiagnostics(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID planId,
        @PathVariable UUID instanceId
    ) {
        return query.findInstance(tenantId, planId, instanceId)
            .orElseThrow(() -> new MigrationOperationsNotFoundException(
                "migration instance evidence was not found"
            ));
    }
}
