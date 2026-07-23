package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentCommand;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Detect-only API for governed in-flight release migration assessment. */
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.READ)
@RestController
@RequestMapping("/api/approval/version-management")
public class ApprovalProcessReleaseMigrationAssessmentController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalProcessReleaseMigrationAssessmentService assessment;

    public ApprovalProcessReleaseMigrationAssessmentController(
        ApprovalProcessReleaseMigrationAssessmentService assessment
    ) {
        this.assessment = assessment;
    }

    @ApprovalManagementPermission(
        ApprovalManagementPermission.Requirement.RELEASE_MIGRATION_ASSESS
    )
    @PostMapping("/{definitionKey}/releases/{sourceReleaseVersion}/migration-dry-run")
    public AssessmentResult assess(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER)
        String reason,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable String definitionKey,
        @PathVariable int sourceReleaseVersion,
        @RequestBody MigrationDryRunRequest request
    ) {
        int limit = request.limit() == null ? 100 : request.limit();
        int offset = request.offset() == null ? 0 : request.offset();
        return assessment.assess(new AssessmentCommand(
            new RequestContext(
                tenantId,
                operatorId,
                requestId,
                idempotencyKey,
                traceId
            ),
            definitionKey,
            sourceReleaseVersion,
            request.targetReleaseVersion(),
            limit,
            offset,
            reason
        ));
    }

    public record MigrationDryRunRequest(
        int targetReleaseVersion,
        Integer limit,
        Integer offset
    ) {
    }
}
