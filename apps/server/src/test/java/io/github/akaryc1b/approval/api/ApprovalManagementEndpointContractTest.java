package io.github.akaryc1b.approval.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalManagementEndpointContractTest {

    @Test
    void allManagementHandlersHaveCapabilitiesAndValidResourceDeclarations()
        throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> missing = new ArrayList<>();
        List<String> invalidScopes = new ArrayList<>();
        int handlers = 0;
        for (BeanDefinition bean : scanner.findCandidateComponents(
            "io.github.akaryc1b.approval.api"
        )) {
            Class<?> controller = Class.forName(bean.getBeanClassName());
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(
                controller,
                RequestMapping.class
            );
            if (root == null || !managementRoot(root)) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                    method,
                    RequestMapping.class
                );
                if (mapping == null) {
                    continue;
                }
                handlers++;
                ApprovalManagementPermission capability = capability(controller, method);
                String handler = controller.getSimpleName() + '.' + method.getName();
                if (capability == null) {
                    missing.add(handler);
                } else if (!validResourceDeclaration(capability)) {
                    invalidScopes.add(handler);
                }
            }
        }

        assertTrue(handlers > 0, "management endpoint scan unexpectedly empty");
        assertEquals(List.of(), missing, "management endpoints without capabilities");
        assertEquals(
            List.of(),
            invalidScopes,
            "management endpoints with invalid resource declarations"
        );
    }

    private static ApprovalManagementPermission capability(
        Class<?> controller,
        Method method
    ) {
        ApprovalManagementPermission capability =
            AnnotatedElementUtils.findMergedAnnotation(
                method,
                ApprovalManagementPermission.class
            );
        if (capability != null) {
            return capability;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
            controller,
            ApprovalManagementPermission.class
        );
    }

    private static boolean validResourceDeclaration(
        ApprovalManagementPermission capability
    ) {
        boolean departmentVariableDeclared =
            !capability.departmentPathVariable().trim().isEmpty();
        return switch (capability.resourceScope()) {
            case TENANT -> !departmentVariableDeclared;
            case DEPARTMENT -> departmentVariableDeclared;
        };
    }

    private static boolean managementRoot(RequestMapping mapping) {
        String[] paths = mapping.path().length == 0 ? mapping.value() : mapping.path();
        for (String path : paths) {
            if (path.equals("/api/approval/management")
                || path.startsWith("/api/approval/management/")) {
                return true;
            }
        }
        return false;
    }
}
