package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsPage;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsSummary;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET-only tenant-scoped process-local Connector diagnostics. */
@RestController
@RequestMapping("/api/approval/management/connector-operations")
@ConditionalOnProperty(
    prefix = "approval.connector.operations-diagnostics",
    name = "enabled",
    havingValue = "true"
)
@ApprovalManagementPermission(
    value = ApprovalManagementPermission.Requirement.OPERATIONAL_FAILURE_READ,
    resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
)
public class ConnectorOperationsDiagnosticsController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private final ConnectorOperationsDiagnosticsQueryService queryService;

    public ConnectorOperationsDiagnosticsController(
        ConnectorOperationsDiagnosticsQueryService queryService
    ) {
        this.queryService = queryService;
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<DiagnosticsPage> findDiagnostics(
        @RequestHeader(TENANT_ID) String trustedTenantId,
        @RequestParam MultiValueMap<String, String> parameters
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(queryService.query(
                trustedTenantId,
                ConnectorOperationsDiagnosticsParameters.parse(parameters)
            ));
    }

    @GetMapping("/diagnostics/summary")
    public ResponseEntity<DiagnosticsSummary> summarize(
        @RequestHeader(TENANT_ID) String trustedTenantId
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(queryService.summarize(trustedTenantId));
    }
}
