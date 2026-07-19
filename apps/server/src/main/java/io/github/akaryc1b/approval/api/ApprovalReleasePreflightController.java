package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService.DeploymentRequest;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService.PreflightReport;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService.PublicationRequest;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only server-authoritative readiness checks for publication and deployment. */
@RestController
@RequestMapping("/api/approval/preflight")
public class ApprovalReleasePreflightController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalReleasePreflightService service;

    public ApprovalReleasePreflightController(ApprovalReleasePreflightService service) {
        this.service = service;
    }

    @ApprovalManagementPermission(ApprovalManagementPermission.Requirement.PUBLISH)
    @PostMapping("/publication")
    public PreflightReport publication(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestBody PublicationPreflightRequest request
    ) {
        return service.preflightPublication(new PublicationRequest(
            tenantId,
            request.draftId(),
            request.expectedRevision(),
            request.definitionKey(),
            request.targetDefinitionVersion(),
            request.targetReleaseVersion(),
            request.deploymentTarget(),
            request.scenario()
        ));
    }

    @ApprovalManagementPermission(ApprovalManagementPermission.Requirement.DEPLOY)
    @PostMapping("/deployment")
    public PreflightReport deployment(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestBody DeploymentPreflightRequest request
    ) {
        return service.preflightDeployment(new DeploymentRequest(
            tenantId,
            request.definitionKey(),
            request.releaseVersion(),
            request.deploymentTarget()
        ));
    }

    public record PublicationPreflightRequest(
        UUID draftId,
        long expectedRevision,
        String definitionKey,
        int targetDefinitionVersion,
        int targetReleaseVersion,
        String deploymentTarget,
        ApprovalDefinitionSimulator.Scenario scenario
    ) {
    }

    public record DeploymentPreflightRequest(
        String definitionKey,
        int releaseVersion,
        String deploymentTarget
    ) {
    }
}
