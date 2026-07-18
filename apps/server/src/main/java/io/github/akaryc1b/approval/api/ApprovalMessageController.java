package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalMessageService;
import io.github.akaryc1b.approval.application.ApprovalMessageService.CollaborationOptions;
import io.github.akaryc1b.approval.application.ApprovalMessageService.CopyCommand;
import io.github.akaryc1b.approval.application.ApprovalMessageService.MessageActionResult;
import io.github.akaryc1b.approval.application.ApprovalMessageService.ReadAllResult;
import io.github.akaryc1b.approval.application.ApprovalMessageService.ReadResult;
import io.github.akaryc1b.approval.application.ApprovalMessageService.UnreadCount;
import io.github.akaryc1b.approval.application.ApprovalMessageService.UrgeCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessagePage;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageReceipt;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval")
public class ApprovalMessageController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalMessageService service;

    public ApprovalMessageController(ApprovalMessageService service) {
        this.service = service;
    }

    @GetMapping("/instances/{instanceId}/collaboration-options")
    public CollaborationOptions findOptions(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID instanceId
    ) {
        return service.findOptions(tenantId, operatorId, instanceId);
    }

    @PostMapping("/instances/{instanceId}/urge")
    public MessageActionResult urge(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID instanceId,
        @Valid @RequestBody UrgeRequest request
    ) {
        return service.urge(new UrgeCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            instanceId,
            request.comment()
        ));
    }

    @PostMapping("/instances/{instanceId}/copy")
    public MessageActionResult copy(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID instanceId,
        @Valid @RequestBody CopyRequest request
    ) {
        return service.copy(new CopyCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            instanceId,
            request.recipientIds(),
            request.comment()
        ));
    }

    @GetMapping("/instances/{instanceId}/receipts")
    public List<MessageReceipt> findReceipts(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID instanceId
    ) {
        return service.findReceipts(tenantId, operatorId, instanceId);
    }

    @GetMapping("/messages")
    public MessagePage findMessages(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestParam(defaultValue = "false") boolean unreadOnly,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findMessages(tenantId, operatorId, unreadOnly, limit, offset);
    }

    @GetMapping("/messages/unread-count")
    public UnreadCount unreadCount(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId
    ) {
        return service.unreadCount(tenantId, operatorId);
    }

    @PostMapping("/messages/{messageId}/read")
    public ResponseEntity<ReadResult> markRead(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID messageId
    ) {
        return service.markRead(tenantId, operatorId, messageId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/messages/read-all")
    public ReadAllResult markAllRead(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId
    ) {
        return service.markAllRead(tenantId, operatorId);
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

    public record UrgeRequest(@Size(max = 500) String comment) {
    }

    public record CopyRequest(
        @NotEmpty @Size(max = 50) List<@NotBlank String> recipientIds,
        @Size(max = 2000) String comment
    ) {
        public CopyRequest {
            recipientIds = recipientIds == null ? List.of() : List.copyOf(recipientIds);
        }
    }
}
