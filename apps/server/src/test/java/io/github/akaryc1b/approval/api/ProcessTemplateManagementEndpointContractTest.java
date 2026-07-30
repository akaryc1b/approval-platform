package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTemplateManagementEndpointContractTest {

    @Test
    void controllerUsesExistingManagementPermissionBoundary() throws Exception {
        RequestMapping mapping = ProcessTemplateManagementController.class.getAnnotation(
            RequestMapping.class
        );
        assertNotNull(mapping);
        assertEquals(
            Set.of("/api/approval/management/process-template-imports"),
            Set.of(mapping.value())
        );

        Field coordinator = ProcessTemplateManagementController.class.getDeclaredField(
            "coordinator"
        );
        assertEquals(ProcessTemplateGovernedImportCoordinator.class, coordinator.getType());

        Method preview = method("preview");
        Method create = method("createDraft");
        assertPermission(preview, ApprovalManagementPermission.Requirement.DESIGN);
        assertPermission(create, ApprovalManagementPermission.Requirement.TRANSFER);
        assertTrue(hasRequiredHeader(
            create,
            ApprovalManagementPermissionInterceptor.OPERATION_REASON_HEADER
        ));
        assertTrue(hasRequiredHeader(
            create,
            ApprovalManagementPermissionInterceptor.IDEMPOTENCY_KEY_HEADER
        ));
    }

    @Test
    void publicSurfaceCannotPublishDeployActivateOrAcceptEvidence() {
        Set<String> publicMethods = Arrays.stream(
            ProcessTemplateManagementController.class.getDeclaredMethods()
        ).filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertEquals(Set.of("preview", "createDraft"), publicMethods);
        assertFalse(publicMethods.stream().anyMatch(name ->
            name.contains("publish")
                || name.contains("deploy")
                || name.contains("activate")
                || name.contains("accept")));
    }

    @Test
    void managementDecoderContractsContainNoCallerOwnedAuthorityFields() {
        Set<String> previewFields = recordFields(
            ProcessTemplateManagementJsonCodec.DecodedPreview.class
        );
        Set<String> createFields = recordFields(
            ProcessTemplateManagementJsonCodec.DecodedCreateDraft.class
        );

        assertEquals(Set.of("templatePackage", "packageBytes", "previewRequest"), previewFields);
        assertEquals(Set.of(
            "templatePackage",
            "packageBytes",
            "previewRequest",
            "expectedGovernedPreviewHash",
            "artifactEnvelope"
        ), createFields);
        assertFalse(createFields.contains("tenantRegistry"));
        assertFalse(createFields.contains("formPackageEvidence"));
        assertFalse(createFields.contains("operatorId"));
        assertFalse(createFields.contains("permissions"));
    }

    private static Method method(String name) {
        return Arrays.stream(ProcessTemplateManagementController.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static void assertPermission(
        Method method,
        ApprovalManagementPermission.Requirement requirement
    ) {
        ApprovalManagementPermission permission = method.getAnnotation(
            ApprovalManagementPermission.class
        );
        assertNotNull(permission);
        assertEquals(requirement, permission.value());
    }

    private static boolean hasRequiredHeader(Method method, String headerName) {
        for (Parameter parameter : method.getParameters()) {
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            if (header == null) {
                continue;
            }
            String name = header.name().isBlank() ? header.value() : header.name();
            if (headerName.equals(name)) {
                return header.required() && parameter.getType().equals(String.class);
            }
        }
        return false;
    }

    private static Set<String> recordFields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(Collectors.toSet());
    }
}
