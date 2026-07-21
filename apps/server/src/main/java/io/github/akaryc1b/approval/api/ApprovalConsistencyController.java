package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalConsistencyService;
import io.github.akaryc1b.approval.application.ApprovalConsistencyService.RunCommand;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckStatus;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckType;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheck;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheckPage;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyFindingPage;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.Severity;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement.CONSISTENCY_READ;
import static io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement.CONSISTENCY_RUN;

@RestController
@RequestMapping("/api/approval/management/consistency")
@ApprovalManagementPermission(CONSISTENCY_READ)
public class ApprovalConsistencyController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalConsistencyService service;

    public ApprovalConsistencyController(ApprovalConsistencyService service) {
        this.service = service;
    }

    @PostMapping("/checks")
    @ApprovalManagementPermission(CONSISTENCY_RUN)
    public ConsistencyCheck run(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId
    ) {
        return service.run(new RunCommand(new RequestContext(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            traceId
        )));
    }

    @GetMapping("/checks")
    public ConsistencyCheckPage findChecks(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) CheckStatus status,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findChecks(tenantId, status, limit, offset);
    }

    @GetMapping("/checks/{checkId}/findings")
    public ConsistencyFindingPage findFindings(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID checkId,
        @RequestParam(required = false) CheckType checkType,
        @RequestParam(required = false) Severity severity,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findFindings(
            tenantId,
            checkId,
            checkType,
            severity,
            limit,
            offset
        );
    }
}
