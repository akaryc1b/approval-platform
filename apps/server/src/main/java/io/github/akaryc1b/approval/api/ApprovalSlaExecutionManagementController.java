package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionAttempt;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntentPage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionNotFoundException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.QueueSummary;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ReplayRequest;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ReplayResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/management/sla/executions")
public class ApprovalSlaExecutionManagementController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String TRACE_ID = "X-Trace-Id";
    private static final String REASON = "X-Approval-Operation-Reason";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ApprovalSlaExecutionStore executions;
    private final Clock clock;

    public ApprovalSlaExecutionManagementController(
        ApprovalSlaExecutionStore executions,
        Clock approvalClock
    ) {
        this.executions = executions;
        this.clock = approvalClock;
    }

    @GetMapping("/summary")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public QueueSummary summary(@RequestHeader(TENANT_ID) String tenantId) {
        return executions.summarize(tenantId, clock.instant());
    }

    @GetMapping
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public ExecutionIntentPage findIntents(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) Set<IntentStatus> statuses,
        @RequestParam(required = false) Set<ActionType> actionTypes,
        @RequestParam(required = false) Instant scheduledFrom,
        @RequestParam(required = false) Instant scheduledTo,
        @RequestParam(required = false) @Size(max = 128) String requestId,
        @RequestParam(required = false) @Size(max = 200) String responsibleUserId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return executions.findIntents(new ExecutionIntentCriteria(
            tenantId,
            statuses,
            actionTypes,
            scheduledFrom,
            scheduledTo,
            requestId,
            responsibleUserId,
            limit,
            offset
        ));
    }

    @GetMapping("/{intentId}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public ExecutionIntentDetail findIntent(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID intentId
    ) {
        ExecutionIntent intent = executions.findIntent(tenantId, intentId)
            .orElseThrow(() -> new ExecutionNotFoundException(
                "SLA execution intent was not found"
            ));
        List<ExecutionAttempt> attempts = executions.findAttempts(tenantId, intentId);
        return new ExecutionIntentDetail(intent, attempts);
    }

    @PostMapping("/{intentId}/replay")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.OPERATIONAL_FAILURE_REPLAY,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public ReplayResult replay(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(TRACE_ID) String traceId,
        @RequestHeader(REASON) @Size(min = 8, max = 512) String reason,
        @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 200) String idempotencyKey,
        @PathVariable UUID intentId
    ) {
        UUID replayId = deterministicId("replay", tenantId, idempotencyKey);
        UUID newIntentId = deterministicId("intent", tenantId, idempotencyKey);
        String evidenceKey = deterministicId("audit", tenantId, idempotencyKey).toString();
        return executions.replayDead(new ReplayRequest(
            replayId,
            newIntentId,
            tenantId,
            intentId,
            reason,
            idempotencyKey,
            "sla-replay:" + intentId + ':' + newIntentId,
            operatorId,
            clock.instant(),
            "management-high-risk:" + requestId + ':' + evidenceKey,
            requestId,
            traceId
        ));
    }

    private static UUID deterministicId(String purpose, String tenantId, String key) {
        return UUID.nameUUIDFromBytes(
            (purpose + ':' + tenantId + ':' + key).getBytes(StandardCharsets.UTF_8)
        );
    }

    public record ExecutionIntentDetail(
        ExecutionIntent intent,
        List<ExecutionAttempt> attempts
    ) {
        public ExecutionIntentDetail {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }
    }
}
