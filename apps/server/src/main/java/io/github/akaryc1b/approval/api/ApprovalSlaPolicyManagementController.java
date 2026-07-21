package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalSlaService;
import io.github.akaryc1b.approval.application.ApprovalSlaService.ActivatePolicyCommand;
import io.github.akaryc1b.approval.application.ApprovalSlaService.CreatePolicyCommand;
import io.github.akaryc1b.approval.application.ApprovalSlaService.PolicyValidation;
import io.github.akaryc1b.approval.application.ApprovalSlaService.PublishPolicyCommand;
import io.github.akaryc1b.approval.application.ApprovalSlaService.SavePolicyVersionCommand;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.EscalationTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaNotFoundException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyPage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/management/sla/policies")
public class ApprovalSlaPolicyManagementController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";

    private final ApprovalSlaService service;

    public ApprovalSlaPolicyManagementController(ApprovalSlaService service) {
        this.service = service;
    }

    @GetMapping
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaPolicyPage findPolicies(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return service.findPolicies(tenantId, limit, offset);
    }

    @GetMapping("/{policyId}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaPolicyIdentity findPolicy(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID policyId
    ) {
        return service.findPolicy(tenantId, policyId)
            .orElseThrow(() -> new SlaNotFoundException(
                "APPROVAL_SLA_POLICY_NOT_FOUND",
                "SLA policy was not found"
            ));
    }

    @GetMapping("/{policyId}/versions/{policyVersion}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaPolicyVersion findPolicyVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID policyId,
        @PathVariable @Min(1) int policyVersion
    ) {
        return service.findPolicyVersion(tenantId, policyId, policyVersion)
            .orElseThrow(() -> new SlaNotFoundException(
                "APPROVAL_SLA_POLICY_NOT_FOUND",
                "SLA policy version was not found"
            ));
    }

    @PostMapping
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_DESIGN,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaPolicyIdentity createPolicy(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @Valid @RequestBody CreatePolicyRequest request
    ) {
        return service.createPolicy(new CreatePolicyCommand(
            tenantId,
            request.policyKey(),
            request.displayName(),
            operatorId
        ));
    }

    @PutMapping("/{policyId}/versions/{policyVersion}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_DESIGN,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaPolicyVersion savePolicyVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID policyId,
        @PathVariable @Min(1) int policyVersion,
        @Valid @RequestBody PolicyVersionRequest request
    ) {
        return service.savePolicyVersion(command(
            tenantId,
            policyId,
            policyVersion,
            request
        ));
    }

    @PostMapping("/{policyId}/versions/{policyVersion}/validate")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_DESIGN,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public PolicyValidation validatePolicyVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID policyId,
        @PathVariable @Min(1) int policyVersion,
        @Valid @RequestBody PolicyVersionRequest request
    ) {
        return service.validatePolicy(command(
            tenantId,
            policyId,
            policyVersion,
            request
        ));
    }

    @PostMapping("/{policyId}/versions/{policyVersion}/publish")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_PUBLISH,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaPolicyVersion publishPolicyVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID policyId,
        @PathVariable @Min(1) int policyVersion,
        @Valid @RequestBody VersionTransitionRequest request
    ) {
        return service.publishPolicyVersion(new PublishPolicyCommand(
            tenantId,
            policyId,
            policyVersion,
            operatorId,
            request.expectedIdentityVersion()
        ));
    }

    @PostMapping("/{policyId}/versions/{policyVersion}/activate")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_ACTIVATE,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public SlaPolicyIdentity activatePolicyVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID policyId,
        @PathVariable @Min(1) int policyVersion,
        @Valid @RequestBody VersionTransitionRequest request
    ) {
        return service.activatePolicyVersion(new ActivatePolicyCommand(
            tenantId,
            policyId,
            policyVersion,
            operatorId,
            request.expectedIdentityVersion()
        ));
    }

    private static SavePolicyVersionCommand command(
        String tenantId,
        UUID policyId,
        int policyVersion,
        PolicyVersionRequest request
    ) {
        return new SavePolicyVersionCommand(
            tenantId,
            policyId,
            policyVersion,
            request.definitionKey(),
            request.releaseVersion(),
            normalize(request.taskDefinitionKey()),
            request.targetType(),
            request.durationMode(),
            request.duration(),
            request.calendarId(),
            request.calendarVersion(),
            request.firstReminderOffset(),
            request.repeatReminderInterval(),
            request.maximumReminderCount(),
            request.overdueOffset(),
            request.escalationTargetType(),
            normalize(request.escalationTarget()),
            request.automaticAction(),
            request.naturalTimePauses(),
            request.expectedIdentityVersion()
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreatePolicyRequest(
        @NotBlank @Size(max = 100) String policyKey,
        @NotBlank @Size(max = 200) String displayName
    ) {
    }

    public record PolicyVersionRequest(
        @NotBlank @Size(max = 100) String definitionKey,
        @Min(1) Integer releaseVersion,
        @Size(max = 100) String taskDefinitionKey,
        @NotNull SlaTargetType targetType,
        @NotNull SlaDurationMode durationMode,
        @NotNull Duration duration,
        UUID calendarId,
        @Min(1) Integer calendarVersion,
        Duration firstReminderOffset,
        Duration repeatReminderInterval,
        @Min(0) @Max(100) int maximumReminderCount,
        Duration overdueOffset,
        EscalationTargetType escalationTargetType,
        @Size(max = 256) String escalationTarget,
        AutomaticAction automaticAction,
        boolean naturalTimePauses,
        @Min(1) long expectedIdentityVersion
    ) {
    }

    public record VersionTransitionRequest(@Min(1) long expectedIdentityVersion) {
    }
}
