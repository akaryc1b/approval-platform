package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalHandoverService;
import io.github.akaryc1b.approval.application.ApprovalHandoverService.CreateHandoverResult;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore.PrincipalHandover;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/approval/handovers")
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.TRANSFER)
public class ApprovalHandoverController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalHandoverService service;

    public ApprovalHandoverController(ApprovalHandoverService service) {
        this.service = service;
    }

    @PostMapping
    public CreateHandoverResult create(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @Valid @RequestBody CreateHandoverRequest request
    ) {
        return service.create(new ApprovalHandoverService.CreateHandoverCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.connectorKey(),
            reference(request.principalIdentity()),
            reference(request.successorIdentity()),
            request.reason()
        ));
    }

    @GetMapping
    public List<PrincipalHandover> findByPrincipal(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam String principalId,
        @RequestParam(defaultValue = "false") boolean includeRevoked
    ) {
        return service.findByPrincipal(tenantId, principalId, includeRevoked);
    }

    @PostMapping("/{handoverId}/revoke")
    public PrincipalHandover revoke(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID handoverId,
        @Valid @RequestBody RevokeHandoverRequest request
    ) {
        return service.revoke(new ApprovalHandoverService.RevokeHandoverCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            handoverId,
            request.reason()
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

    private static IdentityReference reference(IdentityReferenceRequest request) {
        return new IdentityReference(
            request.source(),
            request.objectType(),
            request.value()
        );
    }

    public record IdentityReferenceRequest(
        @NotBlank @Size(max = 128) String source,
        @NotBlank @Size(max = 128) String objectType,
        @NotBlank @Size(max = 256) String value
    ) {
    }

    public record CreateHandoverRequest(
        @NotBlank @Size(max = 128) String connectorKey,
        @NotNull @Valid IdentityReferenceRequest principalIdentity,
        @NotNull @Valid IdentityReferenceRequest successorIdentity,
        @NotBlank @Size(max = 2000) String reason
    ) {
    }

    public record RevokeHandoverRequest(
        @NotBlank @Size(max = 2000) String reason
    ) {
    }
}
