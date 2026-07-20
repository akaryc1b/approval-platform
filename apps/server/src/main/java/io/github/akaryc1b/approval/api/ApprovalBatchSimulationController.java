package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService;
import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService.BatchCommand;
import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService.BatchReport;
import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService.FormValueDisclosure;
import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService.NamedScenario;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Server-authoritative named scenario batch simulation without engine deployment. */
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.DESIGN)
@RestController
@RequestMapping("/api/approval/process-design-drafts")
public class ApprovalBatchSimulationController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalBatchSimulationService service;

    public ApprovalBatchSimulationController(ApprovalBatchSimulationService service) {
        this.service = service;
    }

    @PostMapping("/{draftId}/batch-simulate")
    public BatchReport simulate(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID draftId,
        @RequestBody BatchSimulationRequest request
    ) {
        return service.simulate(request.toCommand(tenantId, draftId));
    }

    public record BatchSimulationRequest(
        long expectedRevision,
        List<NamedScenario> scenarios,
        FormValueDisclosure formValueDisclosure
    ) {
        private BatchCommand toCommand(String tenantId, UUID draftId) {
            return new BatchCommand(
                tenantId,
                draftId,
                expectedRevision,
                scenarios,
                formValueDisclosure
            );
        }
    }
}
