package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalCommentService;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentCommand;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentItem;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentOptions;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentPage;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/approval/instances/{instanceId}/comments")
public class ApprovalCommentController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalCommentService service;

    public ApprovalCommentController(ApprovalCommentService service) {
        this.service = service;
    }

    @GetMapping("/options")
    public CommentOptions findOptions(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID instanceId
    ) {
        return service.findOptions(tenantId, operatorId, instanceId);
    }

    @GetMapping
    public CommentPage findComments(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID instanceId,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findComments(tenantId, operatorId, instanceId, limit, offset);
    }

    @PostMapping
    public CommentItem comment(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID instanceId,
        @Valid @RequestBody CommentRequest request
    ) {
        return service.comment(new CommentCommand(
            new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId),
            instanceId,
            request.parentCommentId(),
            request.body(),
            request.mentionIds(),
            request.attachmentIds()
        ));
    }

    public record CommentRequest(
        UUID parentCommentId,
        @NotBlank @Size(max = 4000) String body,
        @Size(max = 50) List<@NotBlank @Size(max = 512) String> mentionIds,
        @Size(max = 20) List<UUID> attachmentIds
    ) {
        public CommentRequest {
            mentionIds = mentionIds == null ? List.of() : List.copyOf(mentionIds);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }
}
