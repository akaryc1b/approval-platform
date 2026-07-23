package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalProcessReleaseMigrationAssessmentEndpointContractTest {

    @Test
    void dryRunUsesClosedCapabilityAndHeaderOwnedReason() throws Exception {
        Field service = ApprovalProcessReleaseMigrationAssessmentController.class
            .getDeclaredField("assessment");
        assertEquals(ApprovalProcessReleaseMigrationAssessmentService.class, service.getType());

        Method method = Arrays.stream(
            ApprovalProcessReleaseMigrationAssessmentController.class.getDeclaredMethods()
        ).filter(candidate -> candidate.getName().equals("assess"))
            .findFirst()
            .orElseThrow();
        ApprovalManagementPermission permission = method.getAnnotation(
            ApprovalManagementPermission.class
        );
        assertNotNull(permission);
        assertEquals(
            ApprovalManagementPermission.Requirement.RELEASE_MIGRATION_ASSESS,
            permission.value()
        );
        assertTrue(permission.value().requiresReason());
        assertTrue(hasRequiredReasonHeader(method));

        Set<String> requestFields = Arrays.stream(
            ApprovalProcessReleaseMigrationAssessmentController.MigrationDryRunRequest.class
                .getRecordComponents()
        ).map(RecordComponent::getName).collect(Collectors.toSet());
        assertEquals(Set.of("targetReleaseVersion", "limit", "offset"), requestFields);
    }

    private static boolean hasRequiredReasonHeader(Method method) {
        for (Parameter parameter : method.getParameters()) {
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            if (header == null) {
                continue;
            }
            String name = header.name().isBlank() ? header.value() : header.name();
            if (ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER.equals(name)) {
                return header.required() && parameter.getType().equals(String.class);
            }
        }
        return false;
    }
}
