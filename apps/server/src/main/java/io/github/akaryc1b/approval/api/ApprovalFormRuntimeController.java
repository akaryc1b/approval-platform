package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService.RuntimeView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/approval")
public class ApprovalFormRuntimeController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";

    private final ApprovalFormRuntimeService service;

    public ApprovalFormRuntimeController(ApprovalFormRuntimeService service) {
        this.service = service;
    }

    @GetMapping("/forms/{formKey}/versions/{formVersion}/runtime")
    public RuntimeView startRuntime(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable String formKey,
        @PathVariable int formVersion
    ) {
        return service.startRuntime(tenantId, operatorId, formKey, formVersion);
    }

    @GetMapping("/tasks/{taskId}/form-runtime")
    public RuntimeView taskRuntime(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID taskId
    ) {
        return service.taskRuntime(tenantId, operatorId, taskId);
    }
}
