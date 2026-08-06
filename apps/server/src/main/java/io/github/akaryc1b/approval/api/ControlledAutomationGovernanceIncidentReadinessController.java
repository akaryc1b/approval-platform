package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** GET-only tenant-scoped composite incident-readiness view for governed AI. */
@RestController
@RequestMapping("/api/approval/management/ai-governance")
@ApprovalManagementPermission(
    value = ApprovalManagementPermission.Requirement.READ,
    resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
)
public class ControlledAutomationGovernanceIncidentReadinessController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ControlledAutomationGovernanceIncidentReadinessSource source;

    public ControlledAutomationGovernanceIncidentReadinessController(
        ControlledAutomationGovernanceIncidentReadinessSource source
    ) {
        this.source = source;
    }

    @GetMapping("/incident-readiness")
    public ResponseEntity<IncidentReadinessView> incidentReadiness(
        @RequestHeader(TENANT_ID) String trustedTenantId,
        @RequestParam("from") String fromInclusive,
        @RequestParam("to") String toExclusive
    ) {
        requireCanonicalTenant(trustedTenantId);
        IncidentReadinessView view = source.readiness(
            trustedTenantId,
            parseCanonicalInstant(fromInclusive, "from"),
            parseCanonicalInstant(toExclusive, "to")
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(view);
    }

    private static Instant parseCanonicalInstant(String value, String name) {
        if (value == null
            || value.isBlank()
            || !value.equals(value.trim())
            || value.length() > 40) {
            throw new IllegalArgumentException(name + " must be a canonical UTC instant");
        }
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException(
                    name + " must use canonical UTC instant formatting"
                );
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                name + " must be a canonical UTC instant",
                invalid
            );
        }
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
