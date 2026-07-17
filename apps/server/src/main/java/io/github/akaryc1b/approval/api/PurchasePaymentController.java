package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalTaskQueryService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskDetails;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskPage;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver.AssigneeRules;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    private final ApprovalTaskQueryService taskQueryService;

    public PurchasePaymentController(
        PurchasePaymentApplicationService service,
        ApprovalTaskQueryService taskQueryService
    ) {
        this.service = service;
        this.taskQueryService = taskQueryService;
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
            new AssigneeRules(
                request.assigneeRules().connectorKey(),
                new ExternalId(
                    request.assigneeRules().initiatorUserId().source(),
                    request.assigneeRules().initiatorUserId().objectType(),
                    request.assigneeRules().initiatorUserId().value()
                ),
                request.assigneeRules().financeReviewerRoleCode(),
                request.assigneeRules().financeApproverPositionCode(),
                request.assigneeRules().maximumFinanceApprovers()
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

    @GetMapping("/tasks/pending")
    public PendingTaskPage findPendingTasks(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return taskQueryService.findPendingTasks(
            tenantId,
            operatorId,
            keyword,
            limit,
            offset
        );
    }

    @GetMapping("/tasks/pending/{taskId}")
    public ResponseEntity<PendingTaskDetails> findPendingTask(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID taskId
    ) {
        return taskQueryService.findPendingTask(tenantId, operatorId, taskId)
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
        @NotNull @Valid AssigneeRulesRequest assigneeRules
    ) {
        public StartPurchasePaymentRequest {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    public record AssigneeRulesRequest(
        @NotBlank String connectorKey,
        @NotNull @Valid ExternalIdRequest initiatorUserId,
        @NotBlank String financeReviewerRoleCode,
        @NotBlank String financeApproverPositionCode,
        @Min(1) @Max(100) int maximumFinanceApprovers
    ) {
    }

    public record ExternalIdRequest(
        @NotBlank String source,
        @NotBlank String objectType,
        @NotBlank String value
    ) {
    }

    public record ApproveTaskRequest(@Size(max = 2000) String comment) {
    }
}
