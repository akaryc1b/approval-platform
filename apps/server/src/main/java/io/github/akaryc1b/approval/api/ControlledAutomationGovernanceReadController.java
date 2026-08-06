package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.TENANT_ID_HEADER;

/** GET-only tenant-scoped view of AI governance inventory and control posture. */
@RestController
@RequestMapping("/api/approval/management/ai-governance")
@ApprovalManagementPermission(
    value = ApprovalManagementPermission.Requirement.READ,
    resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
)
public final class ControlledAutomationGovernanceReadController {

    private final ControlledAutomationGovernanceSnapshotSource snapshotSource;

    public ControlledAutomationGovernanceReadController(
        ControlledAutomationGovernanceSnapshotSource snapshotSource
    ) {
        this.snapshotSource = Objects.requireNonNull(
            snapshotSource,
            "snapshotSource must not be null"
        );
    }

    @GetMapping("/snapshot")
    public ResponseEntity<OperationsView> snapshot(
        @RequestHeader(TENANT_ID_HEADER) String trustedTenantId
    ) {
        requireTenant(trustedTenantId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(snapshotSource.current());
    }

    private static void requireTenant(String trustedTenantId) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId must not be null");
        if (trustedTenantId.isBlank()
            || !trustedTenantId.equals(trustedTenantId.trim())
            || trustedTenantId.length() > 128) {
            throw new IllegalArgumentException(
                "trustedTenantId must be non-blank, canonical and bounded"
            );
        }
    }
}
