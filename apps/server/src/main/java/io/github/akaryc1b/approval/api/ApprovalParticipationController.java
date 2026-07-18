package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalParticipationQueryService;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery.ProcessedTaskPage;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery.StartedInstancePage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval")
public class ApprovalParticipationController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";

    private final ApprovalParticipationQueryService service;

    public ApprovalParticipationController(ApprovalParticipationQueryService service) {
        this.service = service;
    }

    @GetMapping("/instances/started")
    public StartedInstancePage findStartedInstances(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findStartedInstances(
            tenantId,
            operatorId,
            keyword,
            limit,
            offset
        );
    }

    @GetMapping("/tasks/processed")
    public ProcessedTaskPage findProcessedTasks(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findProcessedTasks(
            tenantId,
            operatorId,
            keyword,
            limit,
            offset
        );
    }
}
