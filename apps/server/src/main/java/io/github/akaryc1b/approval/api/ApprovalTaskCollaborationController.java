package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationMode;
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
    public TaskCollaboration create(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID taskId,
        @Valid @RequestBody CreateCollaborationRequest request
    ) {
        return service.create(new ApprovalTaskCollaborationService.CreateCollaborationCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            taskId,
            request.connectorKey(),
            request.mode(),
            request.participants().stream().map(IdentityReferenceRequest::identity).toList(),
            request.reason()
        ));
    }

    @GetMapping("/tasks/{taskId}/collaboration")
    public TaskCollaboration findByTask(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID taskId
    ) {
        return service.findByTask(tenantId, operatorId, taskId);
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
    public TaskCollaboration removeParticipant(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID participantId,
        @Valid @RequestBody RemoveParticipantRequest request
    ) {
        return service.remove(new ApprovalTaskCollaborationService.RemoveParticipantCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            participantId,
            request.reason()
        ));
    }

    @PostMapping("/collaboration/participants/{participantId}/decide")
    public TaskCollaboration decideParticipant(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID participantId,
        @Valid @RequestBody DecideParticipantRequest request
    ) {
        return service.decide(new ApprovalTaskCollaborationService.DecideParticipantCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            participantId,
            request.decision(),
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

    public record CreateCollaborationRequest(
        @NotBlank @Size(max = 128) String connectorKey,
        @NotNull CollaborationMode mode,
        @NotEmpty @Size(max = 20) List<@Valid IdentityReferenceRequest> participants,
        @NotBlank @Size(max = 2000) String reason
    ) {
        public CreateCollaborationRequest {
            participants = participants == null ? List.of() : List.copyOf(participants);
        }
    }

    public record IdentityReferenceRequest(
        @NotBlank @Size(max = 128) String source,
        @NotBlank @Size(max = 128) String objectType,
        @NotBlank @Size(max = 256) String value
    ) {
        IdentityReference identity() {
            return new IdentityReference(source, objectType, value);
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
}
