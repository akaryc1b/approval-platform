package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportResult;
import io.github.akaryc1b.approval.application.ProcessTemplateException;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator.CreateDraftCommand;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator.GovernedDraftCreationResult;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator.GovernedPreview;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator.PreviewCommand;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;

/** Management-only API for local preview and exact tenant-local DRAFT import. */
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.READ)
@RestController
@RequestMapping("/api/approval/management/process-template-imports")
public class ProcessTemplateManagementController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ProcessTemplateGovernedImportCoordinator coordinator;
    private final ProcessTemplateManagementJsonCodec codec;

    public ProcessTemplateManagementController(
        ProcessTemplateGovernedImportCoordinator coordinator,
        ProcessTemplateManagementJsonCodec codec
    ) {
        this.coordinator = coordinator;
        this.codec = codec;
    }

    @ApprovalManagementPermission(ApprovalManagementPermission.Requirement.DESIGN)
    @PostMapping(
        path = "/previews",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GovernedPreview preview(
        @RequestHeader(TENANT_ID) String tenantId,
        HttpServletRequest servletRequest
    ) {
        var request = codec.decodePreview(readBody(servletRequest), tenantId);
        return coordinator.preview(new PreviewCommand(
            request.templatePackage(),
            request.packageBytes(),
            request.previewRequest()
        ));
    }

    @ApprovalManagementPermission(ApprovalManagementPermission.Requirement.TRANSFER)
    @PostMapping(
        path = "/drafts",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<GovernedDraftCreationResult> createDraft(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER)
        String reason,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        HttpServletRequest servletRequest
    ) {
        var request = codec.decodeCreateDraft(readBody(servletRequest), tenantId);
        GovernedDraftCreationResult result = coordinator.createDraft(new CreateDraftCommand(
            new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.templatePackage(),
            request.packageBytes(),
            request.previewRequest(),
            request.expectedGovernedPreviewHash(),
            request.artifactEnvelope()
        ));
        ImportResult imported = result.draft().draft();
        return ResponseEntity.created(URI.create(
            "/api/approval/process-design-drafts/" + imported.draftId()
        )).eTag("\"revision-" + imported.revision() + "\"").body(result);
    }

    static byte[] readBody(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(
                ProcessTemplateManagementJsonCodec.MAX_REQUEST_BYTES + 1
            );
            if (body.length > ProcessTemplateManagementJsonCodec.MAX_REQUEST_BYTES) {
                throw new ProcessTemplateException.PackageTooLarge(
                    "management import request exceeds the 4 MiB maximum"
                );
            }
            return body;
        } catch (IOException exception) {
            throw new ProcessTemplateException(
                "management import request could not be read safely",
                exception
            );
        }
    }
}
