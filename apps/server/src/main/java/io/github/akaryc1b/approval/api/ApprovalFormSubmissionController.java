package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService.SubmissionCommand;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService.SubmissionResult;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService.SubmissionSnapshot;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval")
public class ApprovalFormSubmissionController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalFormSubmissionService service;

    public ApprovalFormSubmissionController(ApprovalFormSubmissionService service) {
        this.service = service;
    }

    @PostMapping("/forms/{formKey}/versions/{version}/submissions")
    public SubmissionResult submit(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable String formKey,
        @PathVariable int version,
        @Valid @RequestBody SubmissionRequest request
    ) {
        return service.submit(new SubmissionCommand(
            new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId),
            formKey,
            version,
            request.businessKey(),
            request.values(),
            request.startParameters()
        ));
    }

    @GetMapping("/instances/{instanceId}/form-snapshot")
    public ResponseEntity<SubmissionSnapshot> findSnapshot(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID instanceId
    ) {
        return service.findByInstance(tenantId, operatorId, instanceId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record SubmissionRequest(
        @NotBlank String businessKey,
        @NotNull Map<String, Object> values,
        Map<String, Object> startParameters
    ) {
        public SubmissionRequest {
            values = values == null ? Map.of() : Map.copyOf(values);
            startParameters = startParameters == null ? Map.of() : Map.copyOf(startParameters);
        }
    }
}
