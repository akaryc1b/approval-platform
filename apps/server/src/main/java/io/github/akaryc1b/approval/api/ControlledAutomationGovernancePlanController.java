package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.ReviewPlan;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** GET-only P6-B review plan for Canary, Rollout and Rollback governance. */
@RestController
@RequestMapping("/api/approval/management/ai-governance")
@ApprovalManagementPermission(
    value = ApprovalManagementPermission.Requirement.READ,
    resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
)
public final class ControlledAutomationGovernancePlanController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ControlledAutomationGovernanceSnapshotSource snapshotSource;

    public ControlledAutomationGovernancePlanController(
        ControlledAutomationGovernanceSnapshotSource snapshotSource
    ) {
        this.snapshotSource = Objects.requireNonNull(
            snapshotSource,
            "snapshotSource must not be null"
        );
    }

    @GetMapping("/change-plan")
    public ResponseEntity<ReviewPlan> preview(
        @RequestHeader(TENANT_ID) String trustedTenantId,
        @RequestParam Operation operation
    ) {
        requireCanonicalTenant(trustedTenantId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ReviewPlan.preview(operation, snapshotSource.current()));
    }

    private static String requireCanonicalTenant(String value) {
        Objects.requireNonNull(value, "trustedTenantId must not be null");
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > 128) {
            throw new IllegalArgumentException(
                "trustedTenantId must be canonical, non-blank and bounded"
            );
        }
        return value;
    }
}
