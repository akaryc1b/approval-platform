package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
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

class ApprovalMigrationDiagnosticsEndpointContractTest {

    @Test
    void exposesExactlyThreeTenantScopedGetOnlyDiagnosticsHandlers() {
        RequestMapping mapping = ApprovalMigrationDiagnosticsController.class.getAnnotation(
            RequestMapping.class
        );
        assertEquals(
            Set.of(
                "/api/approval/management/process-instance-operations",
                "/api/approval/mobile/process-instance-operations"
            ),
            Set.of(mapping.value())
        );
        Method[] handlers = Arrays.stream(
            ApprovalMigrationDiagnosticsController.class.getDeclaredMethods()
        ).filter(method -> method.isAnnotationPresent(GetMapping.class)).toArray(Method[]::new);
        assertEquals(3, handlers.length);
        assertEquals(
            Set.of(
                "findPlanDiagnostics",
                "findDiagnosticInstances",
                "findInstanceDiagnostics"
            ),
            Arrays.stream(handlers).map(Method::getName).collect(Collectors.toSet())
        );
        for (Method handler : handlers) {
            for (Parameter parameter : handler.getParameters()) {
                assertFalse(parameter.isAnnotationPresent(RequestBody.class), handler.getName());
            }
            Set<String> headers = Arrays.stream(handler.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestHeader.class))
                .filter(java.util.Objects::nonNull)
                .map(RequestHeader::value)
                .collect(Collectors.toSet());
            assertEquals(Set.of("X-Tenant-Id"), headers, handler.getName());
            assertFalse(handler.getName().matches(
                ".*(execute|retry|rollback|reconcile|force|cancel|switch|enable|disable).*"
            ));
        }
    }

    @Test
    void diagnosticsUsesTheExistingReadOnlyCapability() {
        ApprovalManagementPermission permission = AnnotatedElementUtils.findMergedAnnotation(
            ApprovalMigrationDiagnosticsController.class,
            ApprovalManagementPermission.class
        );
        assertNotNull(permission);
        assertEquals(Requirement.MIGRATION_OPERATIONS_READ, permission.value());
        assertEquals(
            ApprovalManagementPermission.ResourceScope.TENANT,
            permission.resourceScope()
        );
        assertFalse(permission.value().requiresReason());
    }
}
