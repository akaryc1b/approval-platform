package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalDesignCommands.CopyPublished;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands.Create;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands.Publish;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands.Revision;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands.SaveMode;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands.Simulation;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands.StableIdentitySnapshot;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands.Update;
import io.github.akaryc1b.approval.application.ApprovalDesignResults;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore.DraftPage;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/process-design-drafts")
public class ApprovalDesignController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalDesignService service;

    public ApprovalDesignController(ApprovalDesignService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApprovalDesignDraft> create(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestBody CreateRequest request
    ) {
        Create command = new Create(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.definitionKey(),
            request.name(),
            request.definitionVersion(),
            request.formPackageVersion()
        );
        ApprovalDesignDraft draft = request.source() == DraftSource.PURCHASE_PAYMENT_TEMPLATE
            ? service.createFromPurchasePaymentTemplate(command)
            : service.createBlank(command);
        return withRevision(draft);
    }

    @PostMapping("/from-published")
    public ResponseEntity<ApprovalDesignDraft> createFromPublished(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestBody CopyPublishedRequest request
    ) {
        ApprovalDesignDraft draft = service.createFromPublished(new CopyPublished(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.definitionKey(),
            request.sourceDefinitionVersion(),
            request.targetDefinitionVersion(),
            request.formPackageVersion(),
            request.name()
        ));
        return withRevision(draft);
    }

    @GetMapping
    public DraftPage findDrafts(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) ApprovalDesignDraft.Status status,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findDrafts(tenantId, keyword, status, limit, offset);
    }

    @GetMapping("/{draftId}")
    public ResponseEntity<ApprovalDesignDraft> find(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID draftId
    ) {
        return service.find(tenantId, draftId)
            .map(ApprovalDesignController::withRevision)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{draftId}")
    public ResponseEntity<ApprovalDesignDraft> update(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID draftId,
        @RequestBody UpdateRequest request
    ) {
        ApprovalDesignDraft draft = service.update(new Update(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            draftId,
            request.expectedRevision(),
            request.name(),
            request.definition(),
            request.formPackageVersion(),
            request.saveMode()
        ));
        return withRevision(draft);
    }

    @PostMapping("/{draftId}/validate")
    public ApprovalDesignResults.Validation validate(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID draftId,
        @RequestBody RevisionRequest request
    ) {
        return service.validate(new Revision(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            draftId,
            request.expectedRevision()
        ));
    }

    @PostMapping("/{draftId}/simulate")
    public ApprovalDesignResults.Simulation simulate(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID draftId,
        @RequestBody SimulationRequest request
    ) {
        return service.simulate(new Simulation(
            tenantId,
            draftId,
            request.expectedRevision(),
            request.scenario(),
            request.identityInputs()
        ));
    }

    @PostMapping("/{draftId}/archive")
    public ResponseEntity<ApprovalDesignDraft> archive(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID draftId,
        @RequestBody RevisionRequest request
    ) {
        ApprovalDesignDraft draft = service.archive(new Revision(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            draftId,
            request.expectedRevision()
        ));
        return withRevision(draft);
    }

    @PostMapping("/{draftId}/publish")
    public ApprovalDesignResults.Publish publish(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID draftId,
        @RequestBody PublishRequest request
    ) {
        return service.publish(new Publish(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            draftId,
            request.expectedRevision(),
            request.definitionVersion(),
            request.releaseVersion(),
            request.deploymentTarget(),
            request.preflightHash(),
            request.acknowledgedWarningCodes(),
            request.preflightScenario()
        ));
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

    private static ResponseEntity<ApprovalDesignDraft> withRevision(
        ApprovalDesignDraft draft
    ) {
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
        String definitionKey,
        String name,
        int definitionVersion,
        int formPackageVersion
    ) {
        public CreateRequest {
            source = source == null ? DraftSource.BLANK : source;
        }
    }

    public record CopyPublishedRequest(
        String definitionKey,
        int sourceDefinitionVersion,
        int targetDefinitionVersion,
        int formPackageVersion,
        String name
    ) {
    }

    public record UpdateRequest(
        long expectedRevision,
        String name,
        ApprovalDefinition definition,
        int formPackageVersion,
        SaveMode saveMode
    ) {
    }

    public record RevisionRequest(long expectedRevision) {
    }

    public record SimulationRequest(
        long expectedRevision,
        ApprovalDefinitionSimulator.Scenario scenario,
        Map<String, List<StableIdentitySnapshot>> identityInputs
    ) {
    }

    public record PublishRequest(
        long expectedRevision,
        int definitionVersion,
        int releaseVersion,
        String deploymentTarget,
        String preflightHash,
        List<String> acknowledgedWarningCodes,
        ApprovalDefinitionSimulator.Scenario preflightScenario
    ) {
    }
}
