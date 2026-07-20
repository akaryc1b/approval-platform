package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalTaskDelegationQueryService;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore.DelegatedTaskAssignment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/approval/tasks")
public class ApprovalTaskDelegationController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";

    private final ApprovalTaskDelegationQueryService service;

    public ApprovalTaskDelegationController(ApprovalTaskDelegationQueryService service) {
        this.service = service;
    }

    @GetMapping("/{taskId}/delegation")
    public ResponseEntity<DelegatedTaskAssignment> findDelegation(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID taskId
    ) {
        return service.findForTask(tenantId, operatorId, taskId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
