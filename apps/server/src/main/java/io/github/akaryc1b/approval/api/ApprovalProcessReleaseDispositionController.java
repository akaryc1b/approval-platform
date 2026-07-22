package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService.DispositionCommand;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService.DispositionResult;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Governed explicit deprecation and retirement for immutable process releases. */
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.READ)
@RestController
@RequestMapping("/api/approval/version-management")
public class ApprovalProcessReleaseDispositionController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalProcessReleaseDispositionService disposition;

    public ApprovalProcessReleaseDispositionController(
        ApprovalProcessReleaseDispositionService disposition
    ) {
        this.disposition = disposition;
    }

    @ApprovalManagementPermission(
        ApprovalManagementPermission.Requirement.RELEASE_LIFECYCLE
    )
    @PostMapping("/{definitionKey}/releases/{releaseVersion}/deprecate")
    public DispositionResult deprecate(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER)
        String reason,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable String definitionKey,
        @PathVariable int releaseVersion,
        @RequestBody DispositionRequest request
    ) {
        return disposition.deprecate(command(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            traceId,
            definitionKey,
            releaseVersion,
            request,
            reason
        ));
    }

    @ApprovalManagementPermission(
        ApprovalManagementPermission.Requirement.RELEASE_LIFECYCLE
    )
    @PostMapping("/{definitionKey}/releases/{releaseVersion}/retire")
    public DispositionResult retire(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER)
        String reason,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable String definitionKey,
        @PathVariable int releaseVersion,
        @RequestBody DispositionRequest request
    ) {
        return disposition.retire(command(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            traceId,
            definitionKey,
            releaseVersion,
            request,
            reason
        ));
    }

    private static DispositionCommand command(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey,
        String traceId,
        String definitionKey,
        int releaseVersion,
        DispositionRequest request,
        String reason
    ) {
        return new DispositionCommand(
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

    public record DispositionRequest(long expectedRevision) {
    }
}
