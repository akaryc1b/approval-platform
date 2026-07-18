package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.CopyPublishedCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.CreateCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.PreviewCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.PreviewResult;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.PublishCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.PublishResult;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.RevisionCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.SaveMode;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.UpdateCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.ValidationReport;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore.DraftPage;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDesignDraft;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/form-design-drafts")
public class ApprovalFormDesignController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalFormDesignService service;

    public ApprovalFormDesignController(ApprovalFormDesignService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<FormDesignDraft> create(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestBody CreateRequest request
    ) {
        RequestContext context = context(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            traceId
        );
        CreateCommand command = new CreateCommand(
            context,
            request.formKey(),
            request.name(),
            request.formVersion(),
            request.uiSchemaVersion()
        );
        FormDesignDraft draft = request.source() == DraftSource.PURCHASE_PAYMENT_TEMPLATE
            ? service.createFromPurchasePaymentTemplate(command)
            : service.createBlank(command);
        return withRevision(draft);
    }

    @PostMapping("/from-published")
    public ResponseEntity<FormDesignDraft> createFromPublished(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestBody CopyPublishedRequest request
    ) {
        FormDesignDraft draft = service.createFromPublished(new CopyPublishedCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.formKey(),
            request.sourceFormVersion(),
            request.sourceUiSchemaVersion(),
            request.targetFormVersion(),
            request.targetUiSchemaVersion(),
            request.name()
        ));
        return withRevision(draft);
    }

    @GetMapping
    public DraftPage findDrafts(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) FormDesignDraft.Status status,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findDrafts(tenantId, keyword, status, limit, offset);
    }

    @GetMapping("/{draftId}")
    public ResponseEntity<FormDesignDraft> find(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID draftId
    ) {
        return service.find(tenantId, draftId)
            .map(ApprovalFormDesignController::withRevision)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{draftId}")
    public ResponseEntity<FormDesignDraft> update(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID draftId,
        @RequestBody UpdateRequest request
    ) {
        FormDesignDraft draft = service.update(new UpdateCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            draftId,
            request.expectedRevision(),
            request.name(),
            request.formDefinition(),
            request.uiSchemaDefinition(),
            request.saveMode()
        ));
        return withRevision(draft);
    }

    @PostMapping("/{draftId}/validate")
    public ValidationReport validate(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID draftId,
        @RequestBody RevisionRequest request
    ) {
        return service.validate(new RevisionCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            draftId,
            request.expectedRevision()
        ));
    }

    @PostMapping("/{draftId}/preview")
    public PreviewResult preview(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID draftId,
        @RequestBody PreviewRequest request
    ) {
        return service.preview(new PreviewCommand(
            tenantId,
            operatorId,
            draftId,
            request.contextKey()
        ));
    }

    @GetMapping("/{draftId}/contexts")
    public List<String> contexts(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID draftId
    ) {
        return service.contextKeys(tenantId, draftId);
    }

    @PostMapping("/{draftId}/publish")
    public PublishResult publish(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID draftId,
        @RequestBody PublishRequest request
    ) {
        return service.publish(new PublishCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            draftId,
            request.expectedRevision(),
            request.packageVersion()
        ));
    }

    @PostMapping("/{draftId}/archive")
    public ResponseEntity<FormDesignDraft> archive(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID draftId,
        @RequestBody RevisionRequest request
    ) {
        FormDesignDraft draft = service.archive(new RevisionCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            draftId,
            request.expectedRevision()
        ));
        return withRevision(draft);
    }

    private static RequestContext context(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey,
        String traceId
    ) {
        return new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId);
    }

    private static ResponseEntity<FormDesignDraft> withRevision(FormDesignDraft draft) {
        return ResponseEntity.ok()
            .eTag("\"revision-" + draft.revision() + "\"")
            .body(draft);
    }

    public enum DraftSource {
        BLANK,
        PURCHASE_PAYMENT_TEMPLATE
    }

    public record CreateRequest(
        DraftSource source,
        String formKey,
        String name,
        int formVersion,
        int uiSchemaVersion
    ) {
        public CreateRequest {
            source = source == null ? DraftSource.BLANK : source;
        }
    }

    public record CopyPublishedRequest(
        String formKey,
        int sourceFormVersion,
        int sourceUiSchemaVersion,
        int targetFormVersion,
        int targetUiSchemaVersion,
        String name
    ) {
    }

    public record UpdateRequest(
        long expectedRevision,
        String name,
        FormDefinition formDefinition,
        UiSchemaDefinition uiSchemaDefinition,
        SaveMode saveMode
    ) {
    }

    public record RevisionRequest(long expectedRevision) {
    }

    public record PreviewRequest(String contextKey) {
    }

    public record PublishRequest(long expectedRevision, int packageVersion) {
    }
}
