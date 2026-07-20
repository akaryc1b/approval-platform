package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore.VersionPage;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore.ReleasePage;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.READ)
@RestController
@RequestMapping("/api/approval")
public class ApprovalReleasePackageController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalDesignService service;

    public ApprovalReleasePackageController(ApprovalDesignService service) {
        this.service = service;
    }

    @GetMapping("/definition-versions")
    public VersionPage findDefinitions(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) String definitionKey,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findDefinitions(tenantId, definitionKey, limit, offset);
    }

    @GetMapping("/definition-versions/{definitionKey}/{version}")
    public ResponseEntity<ApprovalDefinitionVersion> findDefinition(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @PathVariable int version
    ) {
        return service.findDefinition(tenantId, definitionKey, version)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/definition-versions/{definitionKey}/latest")
    public ResponseEntity<ApprovalDefinitionVersion> findLatestDefinition(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey
    ) {
        return service.findLatestDefinition(tenantId, definitionKey)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/release-packages")
    public ReleasePage findReleases(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) String definitionKey,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.findReleases(tenantId, definitionKey, limit, offset);
    }

    @GetMapping("/release-packages/{definitionKey}/{releaseVersion}")
    public ResponseEntity<ApprovalReleasePackage> findRelease(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @PathVariable int releaseVersion
    ) {
        return service.findRelease(tenantId, definitionKey, releaseVersion)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/release-packages/{definitionKey}/latest")
    public ResponseEntity<ApprovalReleasePackage> findLatestRelease(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey
    ) {
        return service.findLatestRelease(tenantId, definitionKey)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
