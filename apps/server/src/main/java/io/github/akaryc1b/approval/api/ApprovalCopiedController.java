package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalCopiedQueryService;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.CopiedInstancePage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval/instances/copied")
public class ApprovalCopiedController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";

    private final ApprovalCopiedQueryService service;

    public ApprovalCopiedController(ApprovalCopiedQueryService service) {
        this.service = service;
    }

    @GetMapping
    public CopiedInstancePage findCopiedInstances(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findCopiedInstances(tenantId, operatorId, keyword, limit, offset);
    }
}
