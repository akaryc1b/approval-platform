package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.FailureCategory;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.FailureKind;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailureAttempt;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailurePage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/management/operational-failures")
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.OPERATIONAL_FAILURE_READ)
public class ApprovalOperationalFailureQueryController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalOperationalFailureService service;

    public ApprovalOperationalFailureQueryController(ApprovalOperationalFailureService service) {
        this.service = service;
    }

    @GetMapping
    public OperationalFailurePage findFailures(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) FailureCategory category,
        @RequestParam(required = false) FailureKind failureKind,
        @RequestParam(required = false) String connectorKey,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return service.findFailures(tenantId, category, failureKind, connectorKey, limit, offset);
    }

    @GetMapping("/{category}/{sourceId}/attempts")
    public List<OperationalFailureAttempt> findAttempts(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable FailureCategory category,
        @PathVariable UUID sourceId
    ) {
        return service.findAttempts(tenantId, category, sourceId);
    }
}
