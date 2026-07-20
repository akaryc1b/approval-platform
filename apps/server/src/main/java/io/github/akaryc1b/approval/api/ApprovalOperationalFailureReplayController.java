package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.BatchReplayCommand;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.BatchReplayResult;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.ReplayCommand;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.ReplayItem;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService.ReplayItemResult;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.FailureCategory;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/management/operational-failures")
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.OPERATIONAL_FAILURE_REPLAY)
public class ApprovalOperationalFailureReplayController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String TRACE_ID = "X-Trace-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ApprovalOperationalFailureService service;

    public ApprovalOperationalFailureReplayController(ApprovalOperationalFailureService service) {
        this.service = service;
    }

    @PostMapping("/{category}/{sourceId}/replay")
    public ReplayItemResult replay(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable FailureCategory category,
        @PathVariable UUID sourceId
    ) {
        return service.replay(new ReplayCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            new ReplayItem(category, sourceId)
        ));
    }

    @PostMapping("/replay-batch")
    public BatchReplayResult replayBatch(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @Valid @RequestBody BatchReplayRequest request
    ) {
        return service.replayBatch(new BatchReplayCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.items().stream().map(ReplayRequest::item).toList()
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

    public record BatchReplayRequest(
        @NotNull @Size(min = 1, max = 50) List<@Valid ReplayRequest> items
    ) {
        public BatchReplayRequest {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ReplayRequest(
        @NotNull FailureCategory category,
        @NotNull UUID sourceId
    ) {
        ReplayItem item() {
            return new ReplayItem(category, sourceId);
        }
    }
}
