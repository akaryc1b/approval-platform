package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalDelegationIdentityService;
import io.github.akaryc1b.approval.application.ApprovalDelegationService;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationRule;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationScope;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/delegations")
public class ApprovalDelegationController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalDelegationIdentityService service;

    public ApprovalDelegationController(ApprovalDelegationIdentityService service) {
        this.service = service;
    }

    @PostMapping
    public DelegationRule create(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @Valid @RequestBody CreateDelegationRequest request
    ) {
        return service.create(
            new ApprovalDelegationIdentityService.CreateGovernedDelegationCommand(
                context(tenantId, operatorId, requestId, idempotencyKey, traceId),
                request.connectorKey(),
                reference(request.delegateIdentity()),
                request.scope(),
                request.definitionKey(),
                request.validFrom(),
                request.validUntil(),
                request.reason()
            )
        );
    }

    @GetMapping
    public List<DelegationRule> findMine(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestParam(defaultValue = "false") boolean includeRevoked
    ) {
        return service.findMine(tenantId, operatorId, includeRevoked);
    }

    @PostMapping("/{ruleId}/revoke")
    public DelegationRule revoke(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable UUID ruleId,
        @Valid @RequestBody RevokeDelegationRequest request
    ) {
        return service.revoke(new ApprovalDelegationService.RevokeDelegationCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            ruleId,
            request.reason()
        ));
    }

    private static IdentityReference reference(IdentityReferenceRequest request) {
        return new IdentityReference(
            request.source(),
            request.objectType(),
            request.value()
        );
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

    public record IdentityReferenceRequest(
        @NotBlank @Size(max = 128) String source,
        @NotBlank @Size(max = 128) String objectType,
        @NotBlank @Size(max = 256) String value
    ) {
    }

    public record CreateDelegationRequest(
        @NotBlank @Size(max = 128) String connectorKey,
        @NotNull @Valid IdentityReferenceRequest delegateIdentity,
        @NotNull DelegationScope scope,
        @Size(max = 256) String definitionKey,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil,
        @NotBlank @Size(max = 2000) String reason
    ) {
    }

    public record RevokeDelegationRequest(
        @NotBlank @Size(max = 2000) String reason
    ) {
    }
}
