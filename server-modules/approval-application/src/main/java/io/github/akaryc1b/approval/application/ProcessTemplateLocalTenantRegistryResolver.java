package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.RegisteredComponent;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ProcessTemplateTenantRegistryResolver;
import io.github.akaryc1b.approval.application.port.ProcessTemplateTenantRegistryResolver.RegistryResolutionRequest;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Resolves current import capabilities only from local immutable stores and host allowlists. */
public final class ProcessTemplateLocalTenantRegistryResolver
    implements ProcessTemplateTenantRegistryResolver {

    private final ApprovalFormPackageResolver formPackages;
    private final ApprovalFormComponentRegistry componentRegistry;
    private final String platformProtocolVersion;
    private final Set<String> connectorCapabilities;
    private final Set<String> businessReferenceTypes;
    private final Set<String> organizationPlaceholders;
    private final Set<String> identityPlaceholders;

    public ProcessTemplateLocalTenantRegistryResolver(
        ApprovalFormPackageStore packages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ApprovalFormComponentRegistry componentRegistry,
        String platformProtocolVersion,
        Set<String> connectorCapabilities,
        Set<String> businessReferenceTypes,
        Set<String> organizationPlaceholders,
        Set<String> identityPlaceholders
    ) {
        this.formPackages = new ApprovalFormPackageResolver(packages, forms, uiSchemas);
        this.componentRegistry = Objects.requireNonNull(componentRegistry, "componentRegistry");
        this.platformProtocolVersion = ProcessTemplateSecurity.version(
            platformProtocolVersion,
            "platformProtocolVersion"
        );
        this.connectorCapabilities = safeKeys(
            connectorCapabilities,
            "connectorCapabilities"
        );
        this.businessReferenceTypes = safeKeys(
            businessReferenceTypes,
            "businessReferenceTypes"
        );
        this.organizationPlaceholders = safeKeys(
            organizationPlaceholders,
            "organizationPlaceholders"
        );
        this.identityPlaceholders = safeKeys(identityPlaceholders, "identityPlaceholders");
    }

    @Override
    public TenantRegistrySnapshot resolve(RegistryResolutionRequest request) {
        Objects.requireNonNull(request, "request");
        return new TenantRegistrySnapshot(
            request.tenantId(),
            platformProtocolVersion,
            resolveFormFields(request),
            connectorCapabilities,
            businessReferenceTypes,
            organizationPlaceholders,
            identityPlaceholders,
            registeredComponents()
        );
    }

    private Set<String> resolveFormFields(RegistryResolutionRequest request) {
        List<TenantBinding> bindings = request.previewRequest().bindings().stream()
            .filter(binding -> binding.kind() == BindingKind.FORM_PACKAGE)
            .toList();
        if (bindings.isEmpty()) {
            return Set.of();
        }
        if (bindings.size() != 1) {
            throw new ProcessTemplateException(
                "tenant registry resolution requires at most one Form Package binding"
            );
        }
        TenantBinding binding = bindings.get(0);
        if (!request.tenantId().equals(binding.targetTenantId())) {
            throw new ProcessTemplateException.CrossTenantBinding(
                "Form Package binding tenant does not match registry tenant"
            );
        }
        if (binding.targetVersion() == null
            || binding.targetVersion() < 1
            || !request.previewRequest().targetDefinitionKey()
                .equals(binding.targetResourceKey())) {
            return Set.of();
        }
        ApprovalFormPackageResolver.ExactFormPackage exact;
        try {
            exact = formPackages.resolve(
                request.tenantId(),
                binding.targetResourceKey(),
                binding.targetVersion()
            );
        } catch (RuntimeException exception) {
            throw new ProcessTemplateException.RegistryResolutionFailed(
                "target tenant Form Package registry could not be resolved safely",
                exception
            );
        }
        TreeSet<String> fields = new TreeSet<>();
        exact.form().definition().fields().forEach(field -> fields.add(
            ProcessTemplateSecurity.key(field.key(), "formField")
        ));
        return Set.copyOf(fields);
    }

    private List<RegisteredComponent> registeredComponents() {
        return componentRegistry.registeredDescriptors().stream()
            .map(descriptor -> new RegisteredComponent(
                descriptor.componentType(),
                descriptor.componentVersion(),
                descriptor.supportedFieldTypes(),
                descriptor.propertyKeys(),
                descriptor.renderingSupport(),
                descriptor.readonlyFallback()
            ))
            .toList();
    }

    private static Set<String> safeKeys(Set<String> values, String name) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            normalized.add(ProcessTemplateSecurity.key(value, name));
        }
        return Set.copyOf(normalized);
    }
}
