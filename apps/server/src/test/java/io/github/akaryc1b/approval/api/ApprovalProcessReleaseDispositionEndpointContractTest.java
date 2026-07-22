package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService;
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

class ApprovalProcessReleaseDispositionEndpointContractTest {

    @Test
    void dispositionEndpointsUseClosedCapabilityAndHeaderOwnedReason() throws Exception {
        Field service = ApprovalProcessReleaseDispositionController.class.getDeclaredField(
            "disposition"
        );
        assertEquals(ApprovalProcessReleaseDispositionService.class, service.getType());

        for (String methodName : Set.of("deprecate", "retire")) {
            Method method = Arrays.stream(
                ApprovalProcessReleaseDispositionController.class.getDeclaredMethods()
            ).filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
            ApprovalManagementPermission permission = method.getAnnotation(
                ApprovalManagementPermission.class
            );
            assertNotNull(permission);
            assertEquals(
                ApprovalManagementPermission.Requirement.RELEASE_LIFECYCLE,
                permission.value()
            );
            assertTrue(permission.value().requiresReason());
            assertTrue(hasRequiredReasonHeader(method));
        }

        Set<String> requestFields = Arrays.stream(
            ApprovalProcessReleaseDispositionController.DispositionRequest.class
                .getRecordComponents()
        ).map(RecordComponent::getName).collect(Collectors.toSet());
        assertEquals(Set.of("expectedRevision"), requestFields);
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
