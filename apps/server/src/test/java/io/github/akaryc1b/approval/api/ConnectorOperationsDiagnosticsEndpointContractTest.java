package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConnectorOperationsDiagnosticsEndpointContractTest {

    @Test
    void surfaceContainsExactlyTwoTenantScopedGetHandlers() {
        RequestMapping mapping = ConnectorOperationsDiagnosticsController.class.getAnnotation(
            RequestMapping.class
        );
        assertEquals("/api/approval/management/connector-operations", mapping.value()[0]);
        Method[] handlers = Arrays.stream(
            ConnectorOperationsDiagnosticsController.class.getDeclaredMethods()
        ).filter(method -> method.isAnnotationPresent(GetMapping.class)).toArray(Method[]::new);
        assertEquals(2, handlers.length);
        assertEquals(
            Set.of("findDiagnostics", "summarize"),
            Arrays.stream(handlers).map(Method::getName).collect(Collectors.toSet())
        );
        for (Method method : handlers) {
            for (Parameter parameter : method.getParameters()) {
                assertFalse(parameter.isAnnotationPresent(RequestBody.class));
            }
            Set<String> headers = Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestHeader.class))
                .filter(java.util.Objects::nonNull)
                .map(RequestHeader::value)
                .collect(Collectors.toSet());
            assertEquals(Set.of("X-Tenant-Id"), headers);
        }
    }

    @Test
    void controllerContainsNoMutationMapping() {
        for (Method method : ConnectorOperationsDiagnosticsController.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(PostMapping.class));
            assertFalse(method.isAnnotationPresent(PutMapping.class));
            assertFalse(method.isAnnotationPresent(PatchMapping.class));
            assertFalse(method.isAnnotationPresent(DeleteMapping.class));
            assertFalse(method.getName().matches(
                ".*(refresh|invalidate|rotate|retry|replay|recover|clear|enable|disable).*"
            ));
        }
    }

    @Test
    void existingReadOnlyManagementPermissionIsReused() {
        ApprovalManagementPermission permission = AnnotatedElementUtils.findMergedAnnotation(
            ConnectorOperationsDiagnosticsController.class,
            ApprovalManagementPermission.class
        );
        assertNotNull(permission);
        assertEquals(Requirement.OPERATIONAL_FAILURE_READ, permission.value());
        assertEquals(ApprovalManagementPermission.ResourceScope.TENANT, permission.resourceScope());
        assertFalse(permission.value().requiresReason());
    }
}
