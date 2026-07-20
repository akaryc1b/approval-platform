package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalReleaseStructuralDiff;
import io.github.akaryc1b.approval.application.ApprovalVersionManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Aggregated read API for the PC Approval version-management center. */
@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.READ)
@RestController
@RequestMapping("/api/approval/version-management")
public class ApprovalVersionManagementController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalVersionManagementService service;

    public ApprovalVersionManagementController(ApprovalVersionManagementService service) {
        this.service = service;
    }

    @GetMapping("/{definitionKey}")
    public ApprovalVersionManagementService.VersionCenter findVersionCenter(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findVersionCenter(tenantId, definitionKey, limit, offset);
    }

    @GetMapping("/{definitionKey}/definition-diff")
    public ResponseEntity<ApprovalReleaseStructuralDiff.Result> diffDefinitions(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @RequestParam int fromVersion,
        @RequestParam int toVersion
    ) {
        return service.diffDefinitions(tenantId, definitionKey, fromVersion, toVersion)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{definitionKey}/release-diff")
    public ResponseEntity<ApprovalReleaseStructuralDiff.Result> diffReleases(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @RequestParam int fromReleaseVersion,
        @RequestParam int toReleaseVersion
    ) {
        return service.diffReleases(
            tenantId,
            definitionKey,
            fromReleaseVersion,
            toReleaseVersion
        ).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
