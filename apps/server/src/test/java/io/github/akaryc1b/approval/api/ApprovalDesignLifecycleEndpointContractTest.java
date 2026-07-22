package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalDesignResults;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalDesignLifecycleEndpointContractTest {

    @Test
    void publishUsesLifecycleFacadeAndRequiresExplicitGovernanceReason() throws Exception {
        Field lifecycle = ApprovalDesignController.class.getDeclaredField("releaseLifecycle");
        assertEquals(ApprovalProcessReleaseLifecycleService.class, lifecycle.getType());

        Method publish = Arrays.stream(ApprovalDesignController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("publish"))
            .findFirst()
            .orElseThrow();
        assertEquals(ApprovalDesignResults.Publish.class, publish.getReturnType());

        ApprovalManagementPermission permission = publish.getAnnotation(
            ApprovalManagementPermission.class
        );
        assertNotNull(permission);
        assertEquals(ApprovalManagementPermission.Requirement.PUBLISH, permission.value());
        assertTrue(permission.value().requiresReason());

        boolean reasonHeader = false;
        for (Parameter parameter : publish.getParameters()) {
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            if (header == null) {
                continue;
            }
            String name = header.name().isBlank() ? header.value() : header.name();
            if (ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER.equals(name)) {
                reasonHeader = true;
                assertTrue(header.required());
                assertEquals(String.class, parameter.getType());
            }
        }
        assertTrue(reasonHeader, "publish endpoint must require the governed operation reason");
    }
}
