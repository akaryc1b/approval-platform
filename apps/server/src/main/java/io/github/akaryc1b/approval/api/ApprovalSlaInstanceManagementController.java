package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalSlaService;
import io.github.akaryc1b.approval.application.ApprovalSlaService.PauseCommand;
import io.github.akaryc1b.approval.application.ApprovalSlaService.ResumeCommand;
import io.github.akaryc1b.approval.application.port.ApprovalSlaManagementQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChange;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaNotFoundException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/management/sla/instances")
public class ApprovalSlaInstanceManagementController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalSlaService service;
    private final ApprovalSlaManagementQuery query;
    private final Clock clock;

    public ApprovalSlaInstanceManagementController(
        ApprovalSlaService service,
        ApprovalSlaManagementQuery query,
        Clock approvalClock
    ) {
        this.service = service;
        this.query = query;
        this.clock = approvalClock;
    }

    @GetMapping
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaInstancePage findInstances(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) SlaStatus status,
        @RequestParam(required = false) String responsibleUserId,
        @RequestParam(required = false) Instant dueBefore,
        @RequestParam(required = false) Instant dueAfter,
        @RequestParam(required = false) String requestId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return service.findInstances(new SlaInstanceCriteria(
            tenantId,
            status,
            responsibleUserId,
            dueBefore,
            dueAfter,
            requestId,
            limit,
            offset
        ));
    }

    @GetMapping("/{slaInstanceId}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaInstance findInstance(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID slaInstanceId
    ) {
        return service.findInstance(tenantId, slaInstanceId)
            .orElseThrow(() -> notFound());
    }

    @GetMapping("/upcoming")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaInstancePage findUpcoming(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam Instant dueBefore,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        Instant observedAt = clock.instant();
        if (!dueBefore.isAfter(observedAt)) {
            throw new IllegalArgumentException("dueBefore must be after the observation time");
        }
        return query.findUpcoming(tenantId, observedAt, dueBefore, limit, offset);
    }

    @GetMapping("/overdue")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaInstancePage findOverdue(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return query.findOverdue(tenantId, clock.instant(), limit, offset);
    }

    @GetMapping("/by-request-id/{requestId}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaInstancePage findByRequestId(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable @Size(min = 1, max = 128) String requestId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return query.findByRequestId(tenantId, requestId, limit, offset);
    }

    @GetMapping("/{slaInstanceId}/responsibility-changes")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public List<ResponsibilityChange> findResponsibilityChanges(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID slaInstanceId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit
    ) {
        findInstance(tenantId, slaInstanceId);
        return query.findResponsibilityChanges(tenantId, slaInstanceId, limit);
    }

    @PostMapping("/{slaInstanceId}/pause")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_DESIGN,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaInstance pause(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID slaInstanceId,
        @Valid @RequestBody PauseRequest request
    ) {
        return service.pause(new PauseCommand(
            tenantId,
            slaInstanceId,
            request.expectedVersion(),
            request.reason()
        ));
    }

    @PostMapping("/{slaInstanceId}/resume")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_DESIGN,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaInstance resume(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID slaInstanceId,
        @Valid @RequestBody ResumeRequest request
    ) {
        return service.resume(new ResumeCommand(
            tenantId,
            slaInstanceId,
            request.expectedVersion()
        ));
    }

    private static SlaNotFoundException notFound() {
        return new SlaNotFoundException(
            "APPROVAL_SLA_INSTANCE_NOT_FOUND",
            "approval SLA was not found"
        );
    }

    public record PauseRequest(
        @Min(1) long expectedVersion,
        @NotBlank @Size(min = 8, max = 512) String reason
    ) {
    }

    public record ResumeRequest(@Min(1) long expectedVersion) {
    }
}
