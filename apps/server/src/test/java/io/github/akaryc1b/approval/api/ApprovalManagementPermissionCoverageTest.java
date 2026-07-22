package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApprovalManagementPermissionCoverageTest {

    @Test
    void managementControllersDeclareClosedCapabilities() {
        assertPermission(ApprovalFormDesignController.class, "findDrafts", Requirement.READ);
        assertPermission(ApprovalFormDesignController.class, "update", Requirement.DESIGN);
        assertPermission(ApprovalFormDesignController.class, "publish", Requirement.PUBLISH);
        assertPermission(ApprovalDesignController.class, "findDrafts", Requirement.READ);
        assertPermission(ApprovalDesignController.class, "simulate", Requirement.DESIGN);
        assertPermission(ApprovalDesignController.class, "publish", Requirement.PUBLISH);
        assertPermission(
            ApprovalBatchSimulationController.class,
            "simulate",
            Requirement.DESIGN
        );
        assertPermission(
            ApprovalReleasePreflightController.class,
            "publication",
            Requirement.PUBLISH
        );
        assertPermission(
            ApprovalReleasePreflightController.class,
            "deployment",
            Requirement.DEPLOY
        );
        assertPermission(
            ApprovalReleaseDeploymentController.class,
            "deploy",
            Requirement.DEPLOY
        );
        assertPermission(
            ApprovalReleaseDeploymentController.class,
            "find",
            Requirement.READ
        );
        assertPermission(
            ApprovalEffectiveReleaseController.class,
            "activate",
            Requirement.ACTIVATE
        );
        assertPermission(
            ApprovalEffectiveReleaseController.class,
            "rollback",
            Requirement.ACTIVATE
        );
        assertPermission(
            ApprovalProcessReleaseDispositionController.class,
            "deprecate",
            Requirement.RELEASE_LIFECYCLE
        );
        assertPermission(
            ApprovalProcessReleaseDispositionController.class,
            "retire",
            Requirement.RELEASE_LIFECYCLE
        );
        assertPermission(
            ApprovalArtifactTransferController.class,
            "importArtifact",
            Requirement.TRANSFER
        );
        assertPermission(
            ApprovalArtifactTransferController.class,
            "exportRelease",
            Requirement.TRANSFER
        );
        assertPermission(
            ApprovalVersionManagementController.class,
            "findVersionCenter",
            Requirement.READ
        );
    }

    private static void assertPermission(
        Class<?> controller,
        String methodName,
        Requirement expected
    ) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
        ApprovalManagementPermission permission = AnnotatedElementUtils.findMergedAnnotation(
            method,
            ApprovalManagementPermission.class
        );
        if (permission == null) {
            permission = AnnotatedElementUtils.findMergedAnnotation(
                controller,
                ApprovalManagementPermission.class
            );
        }
        assertNotNull(permission, controller.getSimpleName() + '.' + methodName);
        assertEquals(expected, permission.value(), controller.getSimpleName() + '.' + methodName);
    }
}
