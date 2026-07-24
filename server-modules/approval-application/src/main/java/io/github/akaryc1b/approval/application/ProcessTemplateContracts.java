package io.github.akaryc1b.approval.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Public, data-only contracts for tenant-safe template package preview. */
public final class ProcessTemplateContracts {

    public static final String FORMAT = "PROCESS_TEMPLATE_PACKAGE";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_PACKAGE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_JSON_DEPTH = 64;
    public static final int MAX_JSON_ELEMENTS = 30_000;
    public static final int MAX_STRING_LENGTH = 64 * 1024;
    public static final int MAX_DEPENDENCIES = 500;
    public static final int MAX_COMPONENT_PROPERTIES = 50;
    public static final int MAX_ARTIFACTS = 100;
    public static final int MAX_REGISTRY_VALUES = 10_000;
    public static final int MAX_REGISTERED_COMPONENTS = 1_000;
    public static final int MAX_REGISTERED_COMPONENT_PROPERTIES = 100;

    private ProcessTemplateContracts() {
    }

    public enum DependencyKind {
        FORM_FIELD,
        COMPONENT,
        CONNECTOR_CAPABILITY,
        BUSINESS_REFERENCE_TYPE,
        ORGANIZATION_PLACEHOLDER,
        IDENTITY_PLACEHOLDER,
        PLATFORM_PROTOCOL
    }

    public enum BindingKind {
        FORM_PACKAGE,
        CONNECTOR,
        ORGANIZATION_IDENTITY,
        BUSINESS_REFERENCE,
        COMPONENT_IMPLEMENTATION
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public enum UnsupportedVersionBehavior {
        READONLY_FALLBACK,
        REJECT
    }

    public enum PropertyType {
        STRING,
        BOOLEAN,
        INTEGER,
        DECIMAL,
        STRING_LIST
    }

    public record CompatibilityRange(String minimum, String maximum) {
        public CompatibilityRange {
            minimum = Objects.requireNonNull(minimum, "minimum");
            maximum = Objects.requireNonNull(maximum, "maximum");
        }

        public boolean supports(String version) {
            return ProcessTemplateSecurity.compareVersions(version, minimum) >= 0
                && ProcessTemplateSecurity.compareVersions(version, maximum) <= 0;
        }
    }

    public record ProducerMetadata(String product, String version, String protocol) {
    }

    public record TemplateManifest(
        String format,
        int formatVersion,
        String templateKey,
        int templateVersion,
        String title,
        String summary,
        String category,
        CompatibilityRange compatibility,
        ProducerMetadata producer,
        Set<String> capabilityRequirements
    ) {
        public TemplateManifest {
            capabilityRequirements = immutableSet(capabilityRequirements);
        }
    }

    public record Dependency(
        DependencyKind kind,
        String key,
        String minimumVersion,
        boolean required
    ) {
        public String identity() {
            return kind + ":" + key;
        }
    }

    public record DependencyManifest(List<Dependency> dependencies) {
        public DependencyManifest {
            dependencies = immutableList(dependencies);
        }
    }

    public record IncludedArtifactReference(
        String kind,
        String resourceName,
        String contentHash
    ) {
        public String identity() {
            return kind + ":" + resourceName;
        }
    }

    public record PropertySchema(
        String key,
        PropertyType type,
        boolean required,
        int maximumLength
    ) {
    }

    public record ComponentRegistryDescriptor(
        String componentKey,
        int componentVersion,
        Set<String> supportedFieldTypes,
        List<PropertySchema> propertySchema,
        Set<String> renderingSupport,
        String readonlyFallback,
        UnsupportedVersionBehavior unsupportedVersionBehavior
    ) {
        public ComponentRegistryDescriptor {
            supportedFieldTypes = immutableSet(supportedFieldTypes);
            propertySchema = immutableList(propertySchema);
            renderingSupport = immutableSet(renderingSupport);
        }

        public String identity() {
            return componentKey + "@" + componentVersion;
        }
    }

    public record TemplatePackage(
        TemplateManifest manifest,
        DependencyManifest dependencyManifest,
        List<IncludedArtifactReference> artifacts,
        List<ComponentRegistryDescriptor> componentDescriptors,
        String contentHash
    ) {
        public TemplatePackage {
            artifacts = immutableList(artifacts);
            componentDescriptors = immutableList(componentDescriptors);
        }
    }

    public record RegisteredComponent(
        String componentKey,
        int componentVersion,
        Set<String> supportedFieldTypes,
        Set<String> propertyKeys,
        Set<String> renderingSupport,
        String readonlyFallback
    ) {
        public RegisteredComponent {
            supportedFieldTypes = immutableSet(supportedFieldTypes);
            propertyKeys = immutableSet(propertyKeys);
            renderingSupport = immutableSet(renderingSupport);
        }

        public String identity() {
            return componentKey + "@" + componentVersion;
        }
    }

    public record TenantRegistrySnapshot(
        String tenantId,
        String platformProtocolVersion,
        Set<String> formFields,
        Set<String> connectorCapabilities,
        Set<String> businessReferenceTypes,
        Set<String> organizationPlaceholders,
        Set<String> identityPlaceholders,
        List<RegisteredComponent> components
    ) {
        public TenantRegistrySnapshot {
            formFields = immutableSet(formFields);
            connectorCapabilities = immutableSet(connectorCapabilities);
            businessReferenceTypes = immutableSet(businessReferenceTypes);
            organizationPlaceholders = immutableSet(organizationPlaceholders);
            identityPlaceholders = immutableSet(identityPlaceholders);
            components = immutableList(components);
        }
    }

    public record RegistryEvidence(
        String tenantId,
        String platformProtocolVersion,
        String contentHash
    ) {
        public RegistryEvidence {
            tenantId = ProcessTemplateSecurity.key(tenantId, "registryEvidence.tenantId");
            platformProtocolVersion = ProcessTemplateSecurity.version(
                platformProtocolVersion,
                "registryEvidence.platformProtocolVersion"
            );
            contentHash = ProcessTemplateSecurity.hash(
                contentHash,
                "registryEvidence.contentHash"
            );
        }
    }

    public record TenantBinding(
        BindingKind kind,
        String sourceKey,
        String targetTenantId,
        String targetResourceKey,
        Integer targetVersion
    ) {
        public String identity() {
            return kind + ":" + sourceKey;
        }
    }

    public record PreviewRequest(
        String targetTenantId,
        String targetDefinitionKey,
        int targetDefinitionVersion,
        String targetDraftName,
        List<TenantBinding> bindings
    ) {
        public PreviewRequest {
            bindings = immutableList(bindings);
        }
    }

    public record Finding(Severity severity, String code, String subject, String message) {
        public String canonical() {
            return severity + "|" + code + "|" + subject + "|" + message;
        }
    }

    public record RebindingRequirement(
        BindingKind kind,
        String sourceKey,
        boolean required,
        String reason
    ) {
        public String canonical() {
            return kind + "|" + sourceKey + "|" + required + "|" + reason;
        }
    }

    public record DraftTarget(
        String tenantId,
        String definitionKey,
        int definitionVersion,
        String draftName
    ) {
    }

    public record ImportPlan(
        String packageContentHash,
        RegistryEvidence registryEvidence,
        DraftTarget draftTarget,
        List<Finding> findings,
        List<RebindingRequirement> rebindingRequirements,
        List<TenantBinding> acceptedBindings,
        boolean importable,
        String planHash
    ) {
        public ImportPlan {
            registryEvidence = Objects.requireNonNull(registryEvidence, "registryEvidence");
            findings = immutableList(findings);
            rebindingRequirements = immutableList(rebindingRequirements);
            acceptedBindings = immutableList(acceptedBindings);
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    public static Map<String, TenantBinding> bindingsByIdentity(List<TenantBinding> bindings) {
        java.util.TreeMap<String, TenantBinding> result = new java.util.TreeMap<>();
        for (TenantBinding binding : bindings) {
            if (result.putIfAbsent(binding.identity(), binding) != null) {
                throw new ProcessTemplateException("duplicate tenant-local binding " + binding.identity());
            }
        }
        return Map.copyOf(result);
    }
}
