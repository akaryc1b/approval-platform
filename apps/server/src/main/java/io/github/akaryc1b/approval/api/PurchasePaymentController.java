package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval")
public class PurchasePaymentController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final PurchasePaymentApplicationService service;

    public PurchasePaymentController(PurchasePaymentApplicationService service) {
        this.service = service;
    }

    @PostMapping("/definitions/purchase-payment/publish")
    public PurchasePaymentApplicationService.PublishResult publish(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId
    ) {
        return service.publish(new PurchasePaymentApplicationService.PublishCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId)
        ));
    }

    @PostMapping("/instances/purchase-payment")
    public PurchasePaymentApplicationService.StartResult start(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @Valid @RequestBody StartPurchasePaymentRequest request
    ) {
        return service.start(new PurchasePaymentApplicationService.StartCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.businessKey(),
            request.amount(),
            request.supplier(),
            request.purchaseOrderReference(),
            request.attachmentIds(),
            new AssigneeSnapshot(
                request.assignees().managerAssignee(),
                request.assignees().financeReviewer(),
                request.assignees().financeApprovers(),
                request.assignees().attributes()
            )
        ));
    }

    @GetMapping("/instances/{instanceId}")
    public ResponseEntity<PurchasePaymentApplicationService.InstanceDetails> findInstance(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID instanceId
    ) {
        return service.findInstance(tenantId, instanceId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks")
    public List<TaskProjection> findTasks(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam UUID instanceId
    ) {
        return service.findTasks(tenantId, instanceId);
    }

    @PostMapping("/tasks/{taskId}/approve")
    public PurchasePaymentApplicationService.ApproveResult approve(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID taskId,
        @Valid @RequestBody ApproveTaskRequest request
    ) {
        return service.approve(new PurchasePaymentApplicationService.ApproveCommand(
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

    public record StartPurchasePaymentRequest(
        @NotBlank String businessKey,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @Size(max = 200) String supplier,
        @NotBlank @Size(max = 100) String purchaseOrderReference,
        @NotEmpty List<@NotBlank String> attachmentIds,
        @NotNull @Valid AssigneeSnapshotRequest assignees
    ) {
        public StartPurchasePaymentRequest {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    public record AssigneeSnapshotRequest(
        @NotBlank String managerAssignee,
        @NotBlank String financeReviewer,
        @NotEmpty List<@NotBlank String> financeApprovers,
        Map<String, String> attributes
    ) {
        public AssigneeSnapshotRequest {
            financeApprovers = financeApprovers == null ? List.of() : List.copyOf(financeApprovers);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record ApproveTaskRequest(@Size(max = 2000) String comment) {
    }
}
