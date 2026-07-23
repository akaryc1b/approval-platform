package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Dependency;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

class ProcessTemplateEcosystemTest {

    private final ProcessTemplateCanonicalHasher hasher = new ProcessTemplateCanonicalHasher();
    private final ProcessTemplatePackageValidator validator = new ProcessTemplatePackageValidator(hasher);
    private final ProcessTemplateImportPreviewService preview =
        new ProcessTemplateImportPreviewService(validator, hasher);

    @Test
    void validPackageProducesImportableDeterministicPreview() {
        TemplatePackage value = ProcessTemplateFixtures.validPackage();
        ImportPlan plan = preview.preview(value, 4096, ProcessTemplateFixtures.completeRequest(),
            ProcessTemplateFixtures.completeRegistry());
        assertTrue(plan.importable());
        assertEquals(64, plan.planHash().length());
        assertEquals("tenant-a", plan.draftTarget().tenantId());
        assertTrue(plan.findings().stream().anyMatch(finding ->
            finding.code().equals("PLATFORM_PROTOCOL_COMPATIBLE")));
    }

    @Test
    void missingDependencyProducesErrorWithoutBusinessWritePort() {
        TenantRegistrySnapshot registry = new TenantRegistrySnapshot(
            "tenant-a", "1.4.0", java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
            java.util.Set.of(), java.util.Set.of(), List.of());
        ImportPlan plan = preview.preview(ProcessTemplateFixtures.validPackage(), 4096,
            ProcessTemplateFixtures.completeRequest(), registry);
        assertFalse(plan.importable());
        assertTrue(plan.findings().stream().anyMatch(finding ->
            finding.code().equals("MISSING_DEPENDENCY")));
        assertEquals(2, ProcessTemplateImportPreviewService.class.getDeclaredConstructors()[0]
            .getParameterCount());
    }

    @Test
    void incompatibleVersionProducesError() {
        TenantRegistrySnapshot registry = new TenantRegistrySnapshot(
            "tenant-a", "2.0.0", java.util.Set.of("amount"), java.util.Set.of("finance.submit.local"),
            java.util.Set.of("invoice.local"), java.util.Set.of("finance.local"),
            java.util.Set.of("approver.local"), ProcessTemplateFixtures.completeRegistry().components());
        ImportPlan plan = preview.preview(ProcessTemplateFixtures.validPackage(), 4096,
            ProcessTemplateFixtures.completeRequest(), registry);
        assertTrue(plan.findings().stream().anyMatch(finding ->
            finding.code().equals("INCOMPATIBLE_PLATFORM_PROTOCOL")));
    }

    @Test
    void tamperedHashIsRejected() {
        TemplatePackage base = ProcessTemplateFixtures.validPackage();
        TemplatePackage tampered = new TemplatePackage(base.manifest(), base.dependencyManifest(),
            base.artifacts(), base.componentDescriptors(), "f".repeat(64));
        assertThrows(ProcessTemplateException.HashMismatch.class,
            () -> validator.validate(tampered, 4096));
    }

    @Test
    void duplicateDependencyIsRejected() {
        List<Dependency> dependencies = ProcessTemplateFixtures.dependencies();
        dependencies.add(dependencies.get(0));
        TemplatePackage value = ProcessTemplateFixtures.withDependencies(dependencies);
        assertThrows(ProcessTemplateException.class, () -> validator.validate(value, 4096));
    }

    @Test
    void crossTenantBindingIsRejected() {
        PreviewRequest request = ProcessTemplateFixtures.completeRequest();
        List<TenantBinding> bindings = new java.util.ArrayList<>(request.bindings());
        bindings.add(new TenantBinding(BindingKind.COMPONENT_IMPLEMENTATION, "customSelector",
            "tenant-b", "customSelector", 1));
        assertThrows(ProcessTemplateException.CrossTenantBinding.class,
            () -> preview.preview(ProcessTemplateFixtures.validPackage(), 4096,
                ProcessTemplateFixtures.requestWithBindings(bindings),
                ProcessTemplateFixtures.completeRegistry()));
    }

    @Test
    void scriptsAndDynamicImportsAreRejected() {
        TemplatePackage value = ProcessTemplateFixtures.withSummary("javascript: dynamic import(remoteModule)");
        assertThrows(ProcessTemplateException.class, () -> validator.validate(value, 4096));
    }

    @Test
    void unknownComponentUsesReadonlyFallback() {
        TenantRegistrySnapshot registry = new TenantRegistrySnapshot(
            "tenant-a", "1.4.0", java.util.Set.of("amount"), java.util.Set.of("finance.submit.local"),
            java.util.Set.of("invoice.local"), java.util.Set.of("finance.local"),
            java.util.Set.of("approver.local"), List.of());
        ImportPlan plan = preview.preview(ProcessTemplateFixtures.validPackage(), 4096,
            ProcessTemplateFixtures.completeRequest(), registry);
        assertTrue(plan.findings().stream().anyMatch(finding ->
            finding.code().equals("UNKNOWN_COMPONENT_READONLY_FALLBACK")));
    }

    @Test
    void planHashDoesNotDependOnBindingOrder() {
        PreviewRequest request = ProcessTemplateFixtures.completeRequest();
        List<TenantBinding> reversed = new java.util.ArrayList<>(request.bindings());
        Collections.reverse(reversed);
        ImportPlan first = preview.preview(ProcessTemplateFixtures.validPackage(), 4096, request,
            ProcessTemplateFixtures.completeRegistry());
        ImportPlan second = preview.preview(ProcessTemplateFixtures.validPackage(), 4096,
            ProcessTemplateFixtures.requestWithBindings(reversed), ProcessTemplateFixtures.completeRegistry());
        assertEquals(first.planHash(), second.planHash());
    }

    @Test
    void packageHashDoesNotDependOnDependencyOrder() {
        List<Dependency> reversed = ProcessTemplateFixtures.dependencies();
        Collections.reverse(reversed);
        assertEquals(ProcessTemplateFixtures.validPackage().contentHash(),
            ProcessTemplateFixtures.withDependencies(reversed).contentHash());
    }

    @Test
    void formPackageBindingMustMatchTargetDefinitionKey() {
        PreviewRequest request = ProcessTemplateFixtures.completeRequest();
        List<TenantBinding> bindings = request.bindings().stream()
            .map(binding -> binding.kind() == BindingKind.FORM_PACKAGE
                ? new TenantBinding(binding.kind(), binding.sourceKey(), binding.targetTenantId(),
                    "otherForm", binding.targetVersion())
                : binding)
            .toList();
        ImportPlan plan = preview.preview(ProcessTemplateFixtures.validPackage(), 4096,
            ProcessTemplateFixtures.requestWithBindings(bindings), ProcessTemplateFixtures.completeRegistry());
        assertFalse(plan.importable());
        assertTrue(plan.findings().stream().anyMatch(finding ->
            finding.code().equals("FORM_PACKAGE_KEY_MISMATCH")));
    }

    @Test
    void formPackageBindingRequiresImmutableVersion() {
        PreviewRequest request = ProcessTemplateFixtures.completeRequest();
        List<TenantBinding> bindings = request.bindings().stream()
            .map(binding -> binding.kind() == BindingKind.FORM_PACKAGE
                ? new TenantBinding(binding.kind(), binding.sourceKey(), binding.targetTenantId(),
                    binding.targetResourceKey(), null)
                : binding)
            .toList();
        ImportPlan plan = preview.preview(ProcessTemplateFixtures.validPackage(), 4096,
            ProcessTemplateFixtures.requestWithBindings(bindings), ProcessTemplateFixtures.completeRegistry());
        assertFalse(plan.importable());
        assertTrue(plan.findings().stream().anyMatch(finding ->
            finding.code().equals("FORM_PACKAGE_VERSION_REQUIRED")));
    }

    @Test
    void previewHasNoPublishDeployOrActivateMethod() {
        List<String> methods = java.util.Arrays.stream(ProcessTemplateImportPreviewService.class
            .getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        assertFalse(methods.contains("publish"));
        assertFalse(methods.contains("deploy"));
        assertFalse(methods.contains("activate"));
    }
}
