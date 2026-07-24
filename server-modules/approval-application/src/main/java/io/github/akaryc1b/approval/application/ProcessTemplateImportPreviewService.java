package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ComponentRegistryDescriptor;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Dependency;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DraftTarget;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Finding;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.RebindingRequirement;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.RegisteredComponent;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.RegistryEvidence;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Severity;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.UnsupportedVersionBehavior;

import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_REGISTERED_COMPONENTS;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_REGISTERED_COMPONENT_PROPERTIES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_REGISTRY_VALUES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.bindingsByIdentity;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.compareVersions;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.key;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.text;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.version;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Side-effect-free import preview. It has no persistence, publication, deployment or activation port. */
public final class ProcessTemplateImportPreviewService {

    private final ProcessTemplatePackageValidator validator;
    private final ProcessTemplateCanonicalHasher hasher;

    public ProcessTemplateImportPreviewService(
        ProcessTemplatePackageValidator validator,
        ProcessTemplateCanonicalHasher hasher
    ) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.hasher = java.util.Objects.requireNonNull(hasher, "hasher");
    }

    public ImportPlan preview(
        TemplatePackage templatePackage,
        int packageBytes,
        PreviewRequest request,
        TenantRegistrySnapshot registry
    ) {
        validator.validate(templatePackage, packageBytes);
        validateTarget(request, registry);
        validateRegistry(registry);
        RegistryEvidence registryEvidence = new RegistryEvidence(
            registry.tenantId(),
            registry.platformProtocolVersion(),
            hasher.registryHash(registry)
        );
        Map<String, TenantBinding> bindings = bindingsByIdentity(request.bindings());
        List<Finding> findings = new ArrayList<>();
        List<RebindingRequirement> requirements = new ArrayList<>();
        checkCompatibility(templatePackage, registry, findings);
        for (Dependency dependency : templatePackage.dependencyManifest().dependencies()) {
            resolve(dependency, templatePackage, registry, bindings, findings, requirements);
        }
        requireFormPackage(bindings, request.targetDefinitionKey(), findings, requirements);
        DraftTarget target = new DraftTarget(
            request.targetTenantId(),
            key(request.targetDefinitionKey(), "targetDefinitionKey"),
            positive(request.targetDefinitionVersion(), "targetDefinitionVersion"),
            text(request.targetDraftName(), "targetDraftName")
        );
        List<Finding> sortedFindings = findings.stream()
            .sorted(Comparator.comparing(Finding::canonical)).toList();
        List<RebindingRequirement> sortedRequirements = requirements.stream()
            .sorted(Comparator.comparing(RebindingRequirement::canonical)).toList();
        List<TenantBinding> acceptedBindings = bindings.values().stream()
            .sorted(Comparator.comparing(TenantBinding::identity)).toList();
        boolean importable = sortedFindings.stream()
            .noneMatch(finding -> finding.severity() == Severity.ERROR)
            && sortedRequirements.stream().noneMatch(RebindingRequirement::required);
        String planHash = hasher.planHash(
            templatePackage.contentHash(),
            registryEvidence,
            target,
            sortedFindings,
            sortedRequirements,
            acceptedBindings
        );
        return new ImportPlan(
            templatePackage.contentHash(),
            registryEvidence,
            target,
            sortedFindings,
            sortedRequirements,
            acceptedBindings,
            importable,
            planHash
        );
    }

    private static void validateTarget(PreviewRequest request, TenantRegistrySnapshot registry) {
        if (request == null || registry == null) {
            throw new ProcessTemplateException("preview request and tenant registry are required");
        }
        String targetTenant = key(request.targetTenantId(), "targetTenantId");
        if (!targetTenant.equals(key(registry.tenantId(), "registry.tenantId"))) {
            throw new ProcessTemplateException.CrossTenantBinding(
                "registry tenant does not match preview target tenant");
        }
        for (TenantBinding binding : request.bindings()) {
            if (binding == null || binding.kind() == null) {
                throw new ProcessTemplateException("binding kind is required");
            }
            key(binding.sourceKey(), "binding.sourceKey");
            key(binding.targetResourceKey(), "binding.targetResourceKey");
            if (!targetTenant.equals(key(binding.targetTenantId(), "binding.targetTenantId"))) {
                throw new ProcessTemplateException.CrossTenantBinding(
                    "package import cannot select a resource from another tenant");
            }
            if (binding.targetVersion() != null) {
                positive(binding.targetVersion(), "binding.targetVersion");
            }
        }
    }

    private static void validateRegistry(TenantRegistrySnapshot registry) {
        key(registry.tenantId(), "registry.tenantId");
        version(registry.platformProtocolVersion(), "registry.platformProtocolVersion");
        int registryValues = registry.formFields().size()
            + registry.connectorCapabilities().size()
            + registry.businessReferenceTypes().size()
            + registry.organizationPlaceholders().size()
            + registry.identityPlaceholders().size();
        if (registryValues > MAX_REGISTRY_VALUES) {
            throw new ProcessTemplateException.PackageTooLarge(
                "tenant registry value count exceeds " + MAX_REGISTRY_VALUES
            );
        }
        if (registry.components().size() > MAX_REGISTERED_COMPONENTS) {
            throw new ProcessTemplateException.PackageTooLarge(
                "registered component count exceeds " + MAX_REGISTERED_COMPONENTS
            );
        }
        registry.formFields().forEach(value -> key(value, "registry.formField"));
        registry.connectorCapabilities().forEach(value ->
            key(value, "registry.connectorCapability"));
        registry.businessReferenceTypes().forEach(value ->
            key(value, "registry.businessReferenceType"));
        registry.organizationPlaceholders().forEach(value ->
            key(value, "registry.organizationPlaceholder"));
        registry.identityPlaceholders().forEach(value ->
            key(value, "registry.identityPlaceholder"));
        Set<String> identities = new HashSet<>();
        for (RegisteredComponent component : registry.components()) {
            validateRegisteredComponent(component, identities);
        }
    }

    private static void validateRegisteredComponent(
        RegisteredComponent component,
        Set<String> identities
    ) {
        if (component == null || component.componentVersion() < 1) {
            throw new ProcessTemplateException("registered component version is invalid");
        }
        key(component.componentKey(), "registry.componentKey");
        key(component.readonlyFallback(), "registry.componentReadonlyFallback");
        if (component.supportedFieldTypes().isEmpty()
            || component.renderingSupport().isEmpty()) {
            throw new ProcessTemplateException(
                "registered component support whitelist must not be empty"
            );
        }
        if (component.propertyKeys().size() > MAX_REGISTERED_COMPONENT_PROPERTIES
            || component.supportedFieldTypes().size() > MAX_REGISTERED_COMPONENT_PROPERTIES
            || component.renderingSupport().size() > MAX_REGISTERED_COMPONENT_PROPERTIES) {
            throw new ProcessTemplateException.PackageTooLarge(
                "registered component contract count exceeds "
                    + MAX_REGISTERED_COMPONENT_PROPERTIES
            );
        }
        component.supportedFieldTypes().forEach(value ->
            key(value, "registry.componentSupportedFieldType"));
        component.propertyKeys().forEach(value ->
            key(value, "registry.componentPropertyKey"));
        component.renderingSupport().forEach(value ->
            key(value, "registry.componentRenderingSupport"));
        if (!identities.add(component.identity())) {
            throw new ProcessTemplateException(
                "duplicate registered component " + component.identity()
            );
        }
    }

    private static void checkCompatibility(
        TemplatePackage value,
        TenantRegistrySnapshot registry,
        List<Finding> findings
    ) {
        String target = registry.platformProtocolVersion();
        if (value.manifest().compatibility().supports(target)) {
            findings.add(info("PLATFORM_PROTOCOL_COMPATIBLE", target,
                "platform protocol is compatible"));
        } else {
            findings.add(error("INCOMPATIBLE_PLATFORM_PROTOCOL", target,
                "platform protocol is outside package compatibility range"));
        }
    }

    private static void resolve(
        Dependency dependency,
        TemplatePackage templatePackage,
        TenantRegistrySnapshot registry,
        Map<String, TenantBinding> bindings,
        List<Finding> findings,
        List<RebindingRequirement> requirements
    ) {
        switch (dependency.kind()) {
            case FORM_FIELD -> setDependency(dependency, registry.formFields(), findings);
            case CONNECTOR_CAPABILITY -> bindingDependency(dependency, BindingKind.CONNECTOR,
                registry.connectorCapabilities(), bindings, findings, requirements);
            case BUSINESS_REFERENCE_TYPE -> bindingDependency(dependency,
                BindingKind.BUSINESS_REFERENCE, registry.businessReferenceTypes(), bindings,
                findings, requirements);
            case ORGANIZATION_PLACEHOLDER -> bindingDependency(dependency,
                BindingKind.ORGANIZATION_IDENTITY, registry.organizationPlaceholders(), bindings,
                findings, requirements);
            case IDENTITY_PLACEHOLDER -> bindingDependency(dependency,
                BindingKind.ORGANIZATION_IDENTITY, registry.identityPlaceholders(), bindings,
                findings, requirements);
            case PLATFORM_PROTOCOL -> protocolDependency(dependency, registry, findings);
            case COMPONENT -> componentDependency(dependency, templatePackage, registry,
                bindings, findings, requirements);
            default -> throw new ProcessTemplateException("unsupported dependency kind");
        }
    }

    private static void setDependency(
        Dependency dependency,
        Set<String> available,
        List<Finding> findings
    ) {
        findings.add(available.contains(dependency.key()) ? resolved(dependency) : missing(dependency));
    }

    private static void bindingDependency(
        Dependency dependency,
        BindingKind kind,
        Set<String> available,
        Map<String, TenantBinding> bindings,
        List<Finding> findings,
        List<RebindingRequirement> requirements
    ) {
        TenantBinding binding = bindings.get(kind + ":" + dependency.key());
        if (binding != null && available.contains(binding.targetResourceKey())) {
            findings.add(resolved(dependency));
            return;
        }
        requirements.add(new RebindingRequirement(kind, dependency.key(), dependency.required(),
            "target tenant service must select a local resource"));
        findings.add(new Finding(dependency.required() ? Severity.ERROR : Severity.WARNING,
            "TENANT_REBINDING_REQUIRED", dependency.identity(),
            "package identifiers are not trusted target bindings"));
    }

    private static void protocolDependency(
        Dependency dependency,
        TenantRegistrySnapshot registry,
        List<Finding> findings
    ) {
        if (compareVersions(registry.platformProtocolVersion(), dependency.minimumVersion()) >= 0) {
            findings.add(resolved(dependency));
        } else {
            findings.add(missing(dependency));
        }
    }

    private static void componentDependency(
        Dependency dependency,
        TemplatePackage templatePackage,
        TenantRegistrySnapshot registry,
        Map<String, TenantBinding> bindings,
        List<Finding> findings,
        List<RebindingRequirement> requirements
    ) {
        int requestedVersion = dependency.minimumVersion() == null
            ? 1 : Integer.parseInt(dependency.minimumVersion().split("\\.")[0]);
        Optional<RegisteredComponent> exact = registry.components().stream()
            .filter(component -> component.componentKey().equals(dependency.key()))
            .filter(component -> component.componentVersion() == requestedVersion)
            .findFirst();
        if (exact.isPresent()) {
            findings.add(resolved(dependency));
            return;
        }
        TenantBinding binding = bindings.get(
            BindingKind.COMPONENT_IMPLEMENTATION + ":" + dependency.key()
        );
        if (binding != null && registry.components().stream().anyMatch(component ->
            component.componentKey().equals(binding.targetResourceKey())
                && (binding.targetVersion() == null
                    || component.componentVersion() == binding.targetVersion()))) {
            findings.add(info("COMPONENT_REBOUND", dependency.identity(),
                "target tenant selected a registered component implementation"));
            return;
        }
        ComponentRegistryDescriptor descriptor = templatePackage.componentDescriptors().stream()
            .filter(component -> component.componentKey().equals(dependency.key()))
            .findFirst()
            .orElse(null);
        boolean fallback = descriptor != null
            && descriptor.unsupportedVersionBehavior()
                == UnsupportedVersionBehavior.READONLY_FALLBACK;
        findings.add(new Finding(fallback ? Severity.WARNING : Severity.ERROR,
            fallback ? "UNKNOWN_COMPONENT_READONLY_FALLBACK" : "MISSING_COMPONENT",
            dependency.identity(), fallback
                ? "unknown component will render with declared readonly fallback"
                : "required component implementation is unavailable"));
        requirements.add(new RebindingRequirement(BindingKind.COMPONENT_IMPLEMENTATION,
            dependency.key(), dependency.required() && !fallback, fallback
                ? "readonly fallback is safe; tenant binding is optional"
                : "target tenant must select a registered implementation"));
    }

    private static void requireFormPackage(
        Map<String, TenantBinding> bindings,
        String targetDefinitionKey,
        List<Finding> findings,
        List<RebindingRequirement> requirements
    ) {
        List<TenantBinding> formPackages = bindings.values().stream()
            .filter(binding -> binding.kind() == BindingKind.FORM_PACKAGE)
            .toList();
        if (formPackages.size() != 1) {
            findings.add(error("FORM_PACKAGE_BINDING_REQUIRED", "form-package",
                "exactly one tenant-local Form Package binding is required"));
            requirements.add(new RebindingRequirement(BindingKind.FORM_PACKAGE, "form-package", true,
                "target tenant must select exactly one immutable local Form Package"));
            return;
        }
        TenantBinding formPackage = formPackages.get(0);
        if (formPackage.targetVersion() == null || formPackage.targetVersion() < 1) {
            findings.add(error("FORM_PACKAGE_VERSION_REQUIRED", formPackage.identity(),
                "tenant-local Form Package binding requires a positive immutable version"));
            requirements.add(new RebindingRequirement(BindingKind.FORM_PACKAGE,
                formPackage.sourceKey(), true,
                "target tenant must select an immutable Form Package version"));
        }
        if (!formPackage.targetResourceKey().equals(targetDefinitionKey)) {
            findings.add(error("FORM_PACKAGE_KEY_MISMATCH", formPackage.identity(),
                "target Form Package key must match the target Approval DSL definition key"));
            requirements.add(new RebindingRequirement(BindingKind.FORM_PACKAGE,
                formPackage.sourceKey(), true,
                "target Form Package key must match target definition key"));
        }
    }

    private static Finding resolved(Dependency dependency) {
        return info("DEPENDENCY_RESOLVED", dependency.identity(),
            "dependency is available in target tenant");
    }

    private static Finding missing(Dependency dependency) {
        return new Finding(dependency.required() ? Severity.ERROR : Severity.WARNING,
            "MISSING_DEPENDENCY", dependency.identity(),
            "dependency is unavailable in target tenant");
    }

    private static Finding info(String code, String subject, String message) {
        return new Finding(Severity.INFO, code, subject, message);
    }

    private static Finding error(String code, String subject, String message) {
        return new Finding(Severity.ERROR, code, subject, message);
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new ProcessTemplateException(name + " must be positive");
        }
        return value;
    }
}
