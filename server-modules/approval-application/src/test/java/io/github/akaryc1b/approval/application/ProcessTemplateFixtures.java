package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.CompatibilityRange;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ComponentRegistryDescriptor;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Dependency;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DependencyKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DependencyManifest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.IncludedArtifactReference;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ProducerMetadata;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PropertySchema;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PropertyType;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.RegisteredComponent;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplateManifest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.UnsupportedVersionBehavior;

import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.FORMAT;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.FORMAT_VERSION;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ProcessTemplateFixtures {

    private ProcessTemplateFixtures() {
    }

    static TemplatePackage validPackage() {
        TemplateManifest manifest = new TemplateManifest(
            FORMAT, FORMAT_VERSION, "expenseApproval", 1, "Expense approval",
            "Tenant-safe expense approval template", "FINANCE",
            new CompatibilityRange("1.0.0", "1.9.0"),
            new ProducerMetadata("approval-platform", "1.0.0", "template-v1"),
            Set.of("approval-dsl", "form-schema", "ui-schema")
        );
        DependencyManifest dependencies = new DependencyManifest(List.of(
            dependency(DependencyKind.FORM_FIELD, "amount", null),
            dependency(DependencyKind.CONNECTOR_CAPABILITY, "finance.submit", "1.0.0"),
            dependency(DependencyKind.BUSINESS_REFERENCE_TYPE, "invoice", null),
            dependency(DependencyKind.ORGANIZATION_PLACEHOLDER, "financeDepartment", null),
            dependency(DependencyKind.IDENTITY_PLACEHOLDER, "approver", null),
            dependency(DependencyKind.PLATFORM_PROTOCOL, "approval-template", "1.0.0"),
            dependency(DependencyKind.COMPONENT, "customSelector", "1.0.0")
        ));
        List<IncludedArtifactReference> artifacts = List.of(
            new IncludedArtifactReference("APPROVAL_DSL", "approval/expense.dsl.json", "1".repeat(64)),
            new IncludedArtifactReference("UI_SCHEMA", "forms/expense.ui.json", "2".repeat(64))
        );
        List<ComponentRegistryDescriptor> components = List.of(new ComponentRegistryDescriptor(
            "customSelector", 1, Set.of("TEXT"),
            List.of(new PropertySchema("selectionMode", PropertyType.STRING, false, 32)),
            Set.of("PC", "H5", "WECHAT"), "TEXT_READONLY",
            UnsupportedVersionBehavior.READONLY_FALLBACK
        ));
        ProcessTemplateCanonicalHasher hasher = new ProcessTemplateCanonicalHasher();
        String hash = hasher.packageHash(manifest, dependencies, artifacts, components);
        return new TemplatePackage(manifest, dependencies, artifacts, components, hash);
    }

    static TemplatePackage withDependencies(List<Dependency> dependencies) {
        TemplatePackage base = validPackage();
        DependencyManifest manifest = new DependencyManifest(dependencies);
        ProcessTemplateCanonicalHasher hasher = new ProcessTemplateCanonicalHasher();
        return new TemplatePackage(base.manifest(), manifest, base.artifacts(), base.componentDescriptors(),
            hasher.packageHash(base.manifest(), manifest, base.artifacts(), base.componentDescriptors()));
    }

    static TemplatePackage withSummary(String summary) {
        TemplatePackage base = validPackage();
        TemplateManifest manifest = new TemplateManifest(
            base.manifest().format(), base.manifest().formatVersion(), base.manifest().templateKey(),
            base.manifest().templateVersion(), base.manifest().title(), summary, base.manifest().category(),
            base.manifest().compatibility(), base.manifest().producer(),
            base.manifest().capabilityRequirements());
        ProcessTemplateCanonicalHasher hasher = new ProcessTemplateCanonicalHasher();
        return new TemplatePackage(manifest, base.dependencyManifest(), base.artifacts(),
            base.componentDescriptors(), hasher.packageHash(manifest, base.dependencyManifest(),
                base.artifacts(), base.componentDescriptors()));
    }

    static TemplatePackage withSourceArtifactHash(String artifactKind, String contentHash) {
        TemplatePackage base = validPackage();
        List<IncludedArtifactReference> artifacts = base.artifacts().stream()
            .map(artifact -> artifact.kind().equals(artifactKind)
                ? new IncludedArtifactReference(artifact.kind(), artifact.resourceName(), contentHash)
                : artifact)
            .toList();
        ProcessTemplateCanonicalHasher hasher = new ProcessTemplateCanonicalHasher();
        return new TemplatePackage(base.manifest(), base.dependencyManifest(), artifacts,
            base.componentDescriptors(), hasher.packageHash(base.manifest(), base.dependencyManifest(),
                artifacts, base.componentDescriptors()));
    }

    static TenantRegistrySnapshot completeRegistry() {
        return new TenantRegistrySnapshot(
            "tenant-a", "1.4.0", Set.of("amount"), Set.of("finance.submit.local"),
            Set.of("invoice.local"), Set.of("finance.local"), Set.of("approver.local"),
            List.of(new RegisteredComponent("customSelector", 1, Set.of("TEXT"),
                Set.of("selectionMode"), Set.of("PC", "H5", "WECHAT"), "TEXT_READONLY"))
        );
    }

    static PreviewRequest completeRequest() {
        return new PreviewRequest("tenant-a", "expenseImported", 1, "Expense imported draft", List.of(
            binding(BindingKind.FORM_PACKAGE, "form-package", "expenseImported", 3),
            binding(BindingKind.CONNECTOR, "finance.submit", "finance.submit.local", 1),
            binding(BindingKind.BUSINESS_REFERENCE, "invoice", "invoice.local", 1),
            binding(BindingKind.ORGANIZATION_IDENTITY, "financeDepartment", "finance.local", null),
            binding(BindingKind.ORGANIZATION_IDENTITY, "approver", "approver.local", null)
        ));
    }

    static PreviewRequest requestWithBindings(List<TenantBinding> bindings) {
        return new PreviewRequest("tenant-a", "expenseImported", 1, "Expense imported draft", bindings);
    }

    static List<Dependency> dependencies() {
        return new ArrayList<>(validPackage().dependencyManifest().dependencies());
    }

    private static Dependency dependency(DependencyKind kind, String key, String minimumVersion) {
        return new Dependency(kind, key, minimumVersion, true);
    }

    private static TenantBinding binding(
        BindingKind kind,
        String source,
        String target,
        Integer version
    ) {
        return new TenantBinding(kind, source, "tenant-a", target, version);
    }
}
