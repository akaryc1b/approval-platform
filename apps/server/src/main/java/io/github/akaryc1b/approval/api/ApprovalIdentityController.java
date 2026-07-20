package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalIdentityService;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityCandidate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/approval/identities")
public class ApprovalIdentityController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalIdentityService service;

    public ApprovalIdentityController(ApprovalIdentityService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public List<IdentityCandidate> findCandidates(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestParam String connectorKey,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "20") int limit
    ) {
        requireOperator(operatorId);
        return service.search(
            tenantId,
            connectorKey,
            requestId,
            traceId,
            keyword,
            limit
        );
    }

    private static void requireOperator(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId must not be blank");
        }
    }
}
