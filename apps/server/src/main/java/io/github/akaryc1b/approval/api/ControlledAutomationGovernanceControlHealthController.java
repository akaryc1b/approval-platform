package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET-only tenant-scoped health view for the shared production AI runtime. */
@RestController
@RequestMapping("/api/approval/management/ai-governance")
@ApprovalManagementPermission(
    value = ApprovalManagementPermission.Requirement.READ,
    resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
)
public class ControlledAutomationGovernanceControlHealthController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ControlledAutomationGovernanceControlHealthSource source;

    public ControlledAutomationGovernanceControlHealthController(
        ControlledAutomationGovernanceControlHealthSource source
    ) {
        this.source = source;
    }

    @GetMapping("/control-health")
    public ResponseEntity<ControlHealthView> controlHealth(
        @RequestHeader(TENANT_ID) String trustedTenantId
    ) {
        requireCanonicalTenant(trustedTenantId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(source.current());
    }

    private static void requireCanonicalTenant(String trustedTenantId) {
        if (trustedTenantId == null
            || trustedTenantId.isBlank()
            || !trustedTenantId.equals(trustedTenantId.trim())
            || trustedTenantId.length() > 128) {
            throw new IllegalArgumentException(
                "trusted tenant id must be canonical and bounded"
            );
        }
    }
}
