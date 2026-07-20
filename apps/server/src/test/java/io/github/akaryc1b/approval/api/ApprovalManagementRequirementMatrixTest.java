package io.github.akaryc1b.approval.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalManagementRequirementMatrixTest {

    @Test
    void authoritiesAndMetricTagsAreCanonicalAndUnique() {
        Set<String> authorities = new HashSet<>();
        Set<String> metricTags = new HashSet<>();

        for (ApprovalManagementPermission.Requirement requirement
            : ApprovalManagementPermission.Requirement.values()) {
            assertTrue(requirement.authority().startsWith("approval.management."));
            assertTrue(authorities.add(requirement.authority()), requirement.authority());
            assertTrue(metricTags.add(requirement.metricTag()), requirement.metricTag());
        }

        assertEquals(
            ApprovalManagementPermission.Requirement.values().length,
            authorities.size()
        );
        assertEquals(
            ApprovalManagementPermission.Requirement.values().length,
            metricTags.size()
        );
        assertTrue(authorities.contains(
            ApprovalManagementPermission.Requirement.AUDIT_READ.authority()
        ));
        assertTrue(authorities.contains(
            ApprovalManagementPermission.Requirement.CONSISTENCY_RUN.authority()
        ));
        assertTrue(authorities.contains(
            ApprovalManagementPermission.Requirement.OPERATIONAL_FAILURE_REPLAY.authority()
        ));
    }
}
