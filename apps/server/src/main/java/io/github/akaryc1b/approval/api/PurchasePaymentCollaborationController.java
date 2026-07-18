package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.PurchasePaymentCollaborationService;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/approval")
public class PurchasePaymentCollaborationController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final PurchasePaymentCollaborationService service;

    public PurchasePaymentCollaborationController(PurchasePaymentCollaborationService service) {
        this.service = service;
    }

    @PostMapping("/instances/{instanceId}/withdraw")
    public PurchasePaymentCollaborationService.WithdrawResult withdraw(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID instanceId,
        @Valid @RequestBody CollaborationCommentRequest request
    ) {
        return service.withdraw(new PurchasePaymentCollaborationService.WithdrawCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            instanceId,
            request.comment()
        ));
    }

    @PostMapping("/tasks/{taskId}/transfer")
    public PurchasePaymentCollaborationService.TransferResult transfer(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID taskId,
        @Valid @RequestBody TransferTaskRequest request
    ) {
        return service.transfer(new PurchasePaymentCollaborationService.TransferCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            taskId,
            request.targetUserId(),
            request.comment()
        ));
    }

    @PostMapping("/tasks/{taskId}/retrieve")
    public PurchasePaymentCollaborationService.RetrieveResult retrieve(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID taskId,
        @Valid @RequestBody CollaborationCommentRequest request
    ) {
        return service.retrieve(new PurchasePaymentCollaborationService.RetrieveCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            taskId,
            request.comment()
        ));
    }

    private static RequestContext context(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey,
        String traceId
    ) {
        return new RequestContext(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            traceId
        );
    }

    public record CollaborationCommentRequest(@Size(max = 2000) String comment) {
    }

    public record TransferTaskRequest(
        @NotBlank @Size(max = 256) String targetUserId,
        @NotBlank @Size(max = 2000) String comment
    ) {
    }
}
