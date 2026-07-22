package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService.ActivationCommand;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService.ActivationResult;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore.ActivationPage;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative API for exact effective release activation and rollback. */
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.READ)
@RestController
@RequestMapping("/api/approval/version-management")
public class ApprovalEffectiveReleaseController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalEffectiveReleaseService service;
    private final ApprovalProcessReleaseActivationService releaseActivation;

    public ApprovalEffectiveReleaseController(
        ApprovalEffectiveReleaseService service,
        ApprovalProcessReleaseActivationService releaseActivation
    ) {
        this.service = service;
        this.releaseActivation = releaseActivation;
    }

    @GetMapping("/{definitionKey}/effective")
    public ResponseEntity<ApprovalEffectiveRelease> findCurrent(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey
    ) {
        return service.findCurrent(tenantId, definitionKey)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{definitionKey}/effective/history")
    public ActivationPage findHistory(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findHistory(tenantId, definitionKey, limit, offset);
    }

    @ApprovalManagementPermission(ApprovalManagementPermission.Requirement.ACTIVATE)
    @PostMapping("/{definitionKey}/releases/{releaseVersion}/activate")
    public ActivationResult activate(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER)
        String reason,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable String definitionKey,
        @PathVariable int releaseVersion,
        @RequestBody ActivationRequest request
    ) {
        return releaseActivation.activate(command(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            traceId,
            definitionKey,
            releaseVersion,
            request,
            reason
        )).effective();
    }

    @ApprovalManagementPermission(ApprovalManagementPermission.Requirement.ACTIVATE)
    @PostMapping("/{definitionKey}/releases/{releaseVersion}/rollback")
    public ActivationResult rollback(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER)
        String reason,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable String definitionKey,
        @PathVariable int releaseVersion,
        @RequestBody ActivationRequest request
    ) {
        return releaseActivation.rollback(command(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            traceId,
            definitionKey,
            releaseVersion,
            request,
            reason
        )).effective();
    }

    private static ActivationCommand command(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey,
        String traceId,
        String definitionKey,
        int releaseVersion,
        ActivationRequest request,
        String reason
    ) {
        return new ActivationCommand(
            new RequestContext(
                tenantId,
                operatorId,
                requestId,
                idempotencyKey,
                traceId
            ),
            definitionKey,
            releaseVersion,
            request.expectedRevision(),
            reason
        );
    }

    public record ActivationRequest(Long expectedRevision) {
    }
}
