package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalUiSchemaService;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService.PublishCommand;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService.PublishResult;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService.ValidationResult;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval/ui-schemas")
public class ApprovalUiSchemaController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalUiSchemaService service;

    public ApprovalUiSchemaController(ApprovalUiSchemaService service) {
        this.service = service;
    }

    @GetMapping("/templates/purchase-payment")
    public UiSchemaDefinition purchasePaymentTemplate() {
        return service.purchasePaymentTemplate();
    }

    @GetMapping("/forms/{formKey}/versions/{formVersion}/latest")
    public ResponseEntity<PublishedUiSchema> findLatest(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String formKey,
        @PathVariable int formVersion
    ) {
        return service.findLatest(tenantId, formKey, formVersion)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/forms/{formKey}/versions/{formVersion}/ui-versions/{uiSchemaVersion}")
    public ResponseEntity<PublishedUiSchema> find(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String formKey,
        @PathVariable int formVersion,
        @PathVariable int uiSchemaVersion
    ) {
        return service.find(tenantId, formKey, formVersion, uiSchemaVersion)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/validate")
    public ValidationResult validate(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestBody UiSchemaDefinition definition
    ) {
        return service.validate(tenantId, definition);
    }

    @PostMapping("/publish")
    public PublishResult publish(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestBody UiSchemaDefinition definition
    ) {
        return service.publish(new PublishCommand(
            new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId),
            definition
        ));
    }
}
