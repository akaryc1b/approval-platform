package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormService;
import io.github.akaryc1b.approval.application.ApprovalFormService.PublishCommand;
import io.github.akaryc1b.approval.application.ApprovalFormService.PublishResult;
import io.github.akaryc1b.approval.application.ApprovalFormService.ValidationResult;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.FormPage;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval/forms")
public class ApprovalFormController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalFormService service;

    public ApprovalFormController(ApprovalFormService service) {
        this.service = service;
    }

    @GetMapping
    public FormPage findForms(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findForms(tenantId, keyword, limit, offset);
    }

    @GetMapping("/{formKey}/versions/{version}")
    public ResponseEntity<PublishedForm> findForm(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String formKey,
        @PathVariable int version
    ) {
        return service.find(tenantId, formKey, version)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/templates/purchase-payment")
    public FormDefinition purchasePaymentTemplate() {
        return service.purchasePaymentTemplate();
    }

    @PostMapping("/validate")
    public ValidationResult validate(@RequestBody FormDefinition definition) {
        return service.validate(definition);
    }

    @PostMapping("/publish")
    public PublishResult publish(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestBody FormDefinition definition
    ) {
        return service.publish(new PublishCommand(
            new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId),
            definition
        ));
    }
}
