package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService.ParticipantSpec;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationMode;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationParticipant;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationProgress;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantDecision;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.PendingCollaborationTask;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.TaskCollaboration;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval")
public class ApprovalTaskCollaborationController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalTaskCollaborationService service;

    public ApprovalTaskCollaborationController(ApprovalTaskCollaborationService service) {
        this.service = service;
    }

    @PostMapping("/tasks/{taskId}/collaboration")
    public TaskCollaborationResponse create(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID taskId,
        @Valid @RequestBody CreateCollaborationRequest request
    ) {
        TaskCollaboration collaboration = service.create(
            new ApprovalTaskCollaborationService.CreateCollaborationCommand(
                context(tenantId, operatorId, requestId, idempotencyKey, traceId),
                taskId,
                request.connectorKey(),
                request.mode(),
                request.approvalThreshold(),
                request.approvalWeightThreshold(),
                request.participants().stream().map(ParticipantRequest::participant).toList(),
                request.reason()
            )
        );
        return TaskCollaborationResponse.from(collaboration);
    }

    @PostMapping("/tasks/{taskId}/collaboration/participants")
    public TaskCollaborationResponse addParticipants(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID taskId,
        @Valid @RequestBody AddParticipantsRequest request
    ) {
        TaskCollaboration collaboration = service.add(
            new ApprovalTaskCollaborationService.AddParticipantsCommand(
                context(tenantId, operatorId, requestId, idempotencyKey, traceId),
                taskId,
                request.connectorKey(),
                request.participants().stream().map(ParticipantRequest::participant).toList(),
                request.reason()
            )
        );
        return TaskCollaborationResponse.from(collaboration);
    }

    @GetMapping("/tasks/{taskId}/collaboration")
    public TaskCollaborationResponse findByTask(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID taskId
    ) {
        return TaskCollaborationResponse.from(service.findByTask(tenantId, operatorId, taskId));
    }

    @GetMapping("/collaboration/tasks/pending")
    public List<PendingCollaborationTask> findPending(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.findPending(tenantId, operatorId, limit);
    }

    @PostMapping("/collaboration/participants/{participantId}/remove")
    public TaskCollaborationResponse removeParticipant(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID participantId,
        @Valid @RequestBody RemoveParticipantRequest request
    ) {
        TaskCollaboration collaboration = service.remove(
            new ApprovalTaskCollaborationService.RemoveParticipantCommand(
                context(tenantId, operatorId, requestId, idempotencyKey, traceId),
                participantId,
                request.reason()
            )
        );
        return TaskCollaborationResponse.from(collaboration);
    }

    @PostMapping("/collaboration/participants/{participantId}/decide")
    public TaskCollaborationResponse decideParticipant(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID participantId,
        @Valid @RequestBody DecideParticipantRequest request
    ) {
        TaskCollaboration collaboration = service.decide(
            new ApprovalTaskCollaborationService.DecideParticipantCommand(
                context(tenantId, operatorId, requestId, idempotencyKey, traceId),
                participantId,
                request.decision(),
                request.comment()
            )
        );
        return TaskCollaborationResponse.from(collaboration);
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

    public record CreateCollaborationRequest(
        @NotBlank @Size(max = 128) String connectorKey,
        @NotNull CollaborationMode mode,
        @Min(1) @Max(20) Integer approvalThreshold,
        @Min(1) @Max(20000) Integer approvalWeightThreshold,
        @NotEmpty @Size(max = 20) List<@Valid ParticipantRequest> participants,
        @NotBlank @Size(max = 2000) String reason
    ) {
        public CreateCollaborationRequest {
            participants = participants == null ? List.of() : List.copyOf(participants);
        }
    }

    public record AddParticipantsRequest(
        @NotBlank @Size(max = 128) String connectorKey,
        @NotEmpty @Size(max = 20) List<@Valid ParticipantRequest> participants,
        @NotBlank @Size(max = 2000) String reason
    ) {
        public AddParticipantsRequest {
            participants = participants == null ? List.of() : List.copyOf(participants);
        }
    }

    public record ParticipantRequest(
        @NotBlank @Size(max = 128) String source,
        @NotBlank @Size(max = 128) String objectType,
        @NotBlank @Size(max = 256) String value,
        @Min(1) @Max(1000) Integer weight
    ) {
        ParticipantSpec participant() {
            return new ParticipantSpec(
                new IdentityReference(source, objectType, value),
                weight == null ? 1 : weight
            );
        }
    }

    public record RemoveParticipantRequest(
        @NotBlank @Size(max = 2000) String reason
    ) {
    }

    public record DecideParticipantRequest(
        @NotNull ParticipantDecision decision,
        @NotBlank @Size(max = 2000) String comment
    ) {
    }

    public record TaskCollaborationResponse(
        UUID policyId,
        String tenantId,
        UUID taskId,
        UUID instanceId,
        String engineTaskId,
        String engineInstanceId,
        String definitionKey,
        String taskDefinitionKey,
        String taskName,
        String ownerAssigneeId,
        CollaborationMode mode,
        Integer approvalThreshold,
        Integer approvalWeightThreshold,
        CollaborationStatus status,
        String reason,
        String createdBy,
        Instant createdAt,
        String terminalBy,
        Instant terminalAt,
        String terminalReason,
        long version,
        List<CollaborationParticipant> participants,
        CollaborationProgress progress
    ) {
        static TaskCollaborationResponse from(TaskCollaboration collaboration) {
            return new TaskCollaborationResponse(
                collaboration.policyId(),
                collaboration.tenantId(),
                collaboration.taskId(),
                collaboration.instanceId(),
                collaboration.engineTaskId(),
                collaboration.engineInstanceId(),
                collaboration.definitionKey(),
                collaboration.taskDefinitionKey(),
                collaboration.taskName(),
                collaboration.ownerAssigneeId(),
                collaboration.mode(),
                collaboration.approvalThreshold(),
                collaboration.approvalWeightThreshold(),
                collaboration.status(),
                collaboration.reason(),
                collaboration.createdBy(),
                collaboration.createdAt(),
                collaboration.terminalBy(),
                collaboration.terminalAt(),
                collaboration.terminalReason(),
                collaboration.version(),
                collaboration.participants(),
                collaboration.progress()
            );
        }
    }
}
