package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationOperationsEndpointContractTest {

    @Test
    void controllerExposesOnlyFourTenantScopedGetHandlers() {
        RequestMapping mapping = ApprovalMigrationOperationsController.class.getAnnotation(
            RequestMapping.class
        );
        assertEquals(
            "/api/approval/management/migrations",
            mapping.value()[0]
        );
        Method[] handlers = Arrays.stream(
            ApprovalMigrationOperationsController.class.getDeclaredMethods()
        ).filter(method -> method.isAnnotationPresent(GetMapping.class)).toArray(Method[]::new);
        assertEquals(4, handlers.length);
        assertEquals(
            Set.of("summary", "findPlans", "findPlan", "findInstances"),
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
        }
    }

    @Test
    void operationsVisibilityUsesDedicatedReadOnlyCapability() {
        ApprovalManagementPermission permission = AnnotatedElementUtils.findMergedAnnotation(
            ApprovalMigrationOperationsController.class,
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

    @Test
    void planAndInstanceHandlersAcceptOnlyIdentityFiltersAndBoundedPaging() {
        Method findPlans = method("findPlans");
        assertTrue(Arrays.stream(findPlans.getParameters()).anyMatch(
            parameter -> parameter.isAnnotationPresent(RequestParam.class)
        ));
        assertFalse(Arrays.stream(findPlans.getParameters()).anyMatch(
            parameter -> parameter.isAnnotationPresent(PathVariable.class)
        ));

        for (String name : Set.of("findPlan", "findInstances")) {
            Method method = method(name);
            assertTrue(Arrays.stream(method.getParameters()).anyMatch(
                parameter -> parameter.isAnnotationPresent(PathVariable.class)
            ));
        }
        for (Method method : ApprovalMigrationOperationsController.class.getDeclaredMethods()) {
            assertFalse(method.getName().matches("execute|retry|rollback|reconcile|force|cancel"));
        }
    }

    private static Method method(String name) {
        return Arrays.stream(ApprovalMigrationOperationsController.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
