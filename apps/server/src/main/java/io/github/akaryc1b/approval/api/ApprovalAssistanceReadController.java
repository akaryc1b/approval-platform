package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.api.ApprovalAssistanceReadContracts.AssistanceView;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.OPERATOR_ID_HEADER;
import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.TENANT_ID_HEADER;

/** GET-only approval-assistance presentation for one authorized pending task. */
@RestController
@RequestMapping("/api/approval/tasks")
public final class ApprovalAssistanceReadController {

    private final ApprovalTaskQuery taskQuery;
    private final ApprovalAssistanceRuntimeAvailability runtimeAvailability;

    public ApprovalAssistanceReadController(
        ApprovalTaskQuery taskQuery,
        ApprovalAssistanceRuntimeAvailability runtimeAvailability
    ) {
        this.taskQuery = Objects.requireNonNull(taskQuery, "taskQuery must not be null");
        this.runtimeAvailability = Objects.requireNonNull(
            runtimeAvailability,
            "runtimeAvailability must not be null"
        );
    }

    @GetMapping("/{taskId}/assistance")
    public ResponseEntity<AssistanceView> findAssistance(
        @RequestHeader(TENANT_ID_HEADER) String trustedTenantId,
        @RequestHeader(OPERATOR_ID_HEADER) String trustedOperatorId,
        @PathVariable UUID taskId,
        @RequestParam(defaultValue = "SUMMARY") UseCase useCase
    ) {
        var task = taskQuery.findPendingTask(new PendingTaskIdentity(
            trustedTenantId,
            trustedOperatorId,
            taskId
        ));
        if (task.isEmpty()) {
            return ResponseEntity.notFound()
                .cacheControl(CacheControl.noStore())
                .build();
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(AssistanceView.current(
                task.orElseThrow(),
                useCase,
                runtimeAvailability.providerConfigured()
            ));
    }
}
