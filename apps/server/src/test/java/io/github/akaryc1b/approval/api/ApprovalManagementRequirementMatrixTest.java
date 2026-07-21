package io.github.akaryc1b.approval.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalManagementRequirementMatrixTest {

    @Test
    void authoritiesMetricTagsAndHighRiskSetAreCanonicalAndUnique() {
        Set<String> authorities = new HashSet<>();
        Set<String> metricTags = new HashSet<>();
        Set<ApprovalManagementPermission.Requirement> highRisk =
            EnumSet.noneOf(ApprovalManagementPermission.Requirement.class);

        for (ApprovalManagementPermission.Requirement requirement
            : ApprovalManagementPermission.Requirement.values()) {
            assertTrue(requirement.authority().startsWith("approval.management."));
            assertTrue(authorities.add(requirement.authority()), requirement.authority());
            assertTrue(metricTags.add(requirement.metricTag()), requirement.metricTag());
            if (requirement.requiresReason()) {
                highRisk.add(requirement);
            }
        }

        assertEquals(
            ApprovalManagementPermission.Requirement.values().length,
            authorities.size()
        );
        assertEquals(
            ApprovalManagementPermission.Requirement.values().length,
            metricTags.size()
        );
        assertEquals(
            EnumSet.of(
                ApprovalManagementPermission.Requirement.PUBLISH,
                ApprovalManagementPermission.Requirement.DEPLOY,
                ApprovalManagementPermission.Requirement.ACTIVATE,
                ApprovalManagementPermission.Requirement.TRANSFER,
                ApprovalManagementPermission.Requirement.AUDIT_EXPORT,
                ApprovalManagementPermission.Requirement.AUDIT_VERIFY,
                ApprovalManagementPermission.Requirement.CONSISTENCY_RUN,
                ApprovalManagementPermission.Requirement.OPERATIONAL_FAILURE_REPLAY
            ),
            highRisk
        );
    }
}
