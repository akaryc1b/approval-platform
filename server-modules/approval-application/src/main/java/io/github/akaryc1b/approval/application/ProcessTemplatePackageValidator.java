package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ComponentRegistryDescriptor;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Dependency;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DependencyKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DependencyManifest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.IncludedArtifactReference;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PropertySchema;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplateManifest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;

import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.FORMAT;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.FORMAT_VERSION;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_ARTIFACTS;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_COMPONENT_PROPERTIES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_DEPENDENCIES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_PACKAGE_BYTES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_STRING_LENGTH;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.compareVersions;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.hash;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.key;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.resourceName;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.text;
import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.version;

import java.util.HashSet;
import java.util.Set;

/** Validates package contracts before dependency resolution or tenant rebinding. */
public final class ProcessTemplatePackageValidator {

    private final ProcessTemplateCanonicalHasher hasher;

    public ProcessTemplatePackageValidator(ProcessTemplateCanonicalHasher hasher) {
        this.hasher = java.util.Objects.requireNonNull(hasher, "hasher");
    }

    public void validate(TemplatePackage value, int packageBytes) {
        java.util.Objects.requireNonNull(value, "template package");
        if (packageBytes < 0 || packageBytes > MAX_PACKAGE_BYTES) {
            throw new ProcessTemplateException.PackageTooLarge("package bytes exceed " + MAX_PACKAGE_BYTES);
        }
        validateManifest(value.manifest());
        validateDependencies(value.dependencyManifest());
        validateArtifacts(value.artifacts());
        validateComponents(value.componentDescriptors());
        hash(value.contentHash(), "contentHash");
        String actual = hasher.packageHash(value);
        if (!actual.equals(value.contentHash())) {
            throw new ProcessTemplateException.HashMismatch("template package content hash mismatch");
        }
    }

    private static void validateManifest(TemplateManifest manifest) {
        if (manifest == null || !FORMAT.equals(manifest.format())
            || manifest.formatVersion() != FORMAT_VERSION || manifest.templateVersion() < 1) {
            throw new ProcessTemplateException("unsupported template manifest");
        }
        key(manifest.templateKey(), "manifest.templateKey");
        text(manifest.title(), "manifest.title");
        text(manifest.summary(), "manifest.summary");
        key(manifest.category(), "manifest.category");
        if (manifest.compatibility() == null || manifest.producer() == null) {
            throw new ProcessTemplateException("compatibility and producer are required");
        }
        version(manifest.compatibility().minimum(), "compatibility.minimum");
        version(manifest.compatibility().maximum(), "compatibility.maximum");
        if (compareVersions(manifest.compatibility().minimum(), manifest.compatibility().maximum()) > 0) {
            throw new ProcessTemplateException("compatibility minimum exceeds maximum");
        }
        key(manifest.producer().product(), "producer.product");
        version(manifest.producer().version(), "producer.version");
        key(manifest.producer().protocol(), "producer.protocol");
        manifest.capabilityRequirements().forEach(value -> key(value, "capabilityRequirement"));
    }

    private static void validateDependencies(DependencyManifest manifest) {
        if (manifest == null || manifest.dependencies().size() > MAX_DEPENDENCIES) {
            throw new ProcessTemplateException.PackageTooLarge("dependency count exceeds " + MAX_DEPENDENCIES);
        }
        Set<String> identities = new HashSet<>();
        for (Dependency dependency : manifest.dependencies()) {
            if (dependency == null || dependency.kind() == null) {
                throw new ProcessTemplateException("dependency kind is required");
            }
            key(dependency.key(), "dependency.key");
            if (dependency.minimumVersion() != null) {
                version(dependency.minimumVersion(), "dependency.minimumVersion");
            }
            if (dependency.kind() == DependencyKind.PLATFORM_PROTOCOL
                && dependency.minimumVersion() == null) {
                throw new ProcessTemplateException("platform protocol requires minimumVersion");
            }
            if (!identities.add(dependency.identity())) {
                throw new ProcessTemplateException("duplicate dependency " + dependency.identity());
            }
        }
    }

    private static void validateArtifacts(java.util.List<IncludedArtifactReference> artifacts) {
        if (artifacts.size() > MAX_ARTIFACTS) {
            throw new ProcessTemplateException.PackageTooLarge("artifact count exceeds " + MAX_ARTIFACTS);
        }
        Set<String> identities = new HashSet<>();
        for (IncludedArtifactReference artifact : artifacts) {
            key(artifact.kind(), "artifact.kind");
            resourceName(artifact.resourceName(), "artifact.resourceName");
            hash(artifact.contentHash(), "artifact.contentHash");
            if (!identities.add(artifact.identity())) {
                throw new ProcessTemplateException("duplicate artifact " + artifact.identity());
            }
        }
    }

    private static void validateComponents(java.util.List<ComponentRegistryDescriptor> components) {
        Set<String> identities = new HashSet<>();
        for (ComponentRegistryDescriptor component : components) {
            if (component.componentVersion() < 1 || component.unsupportedVersionBehavior() == null) {
                throw new ProcessTemplateException("invalid component version or behavior");
            }
            key(component.componentKey(), "component.componentKey");
            key(component.readonlyFallback(), "component.readonlyFallback");
            if (component.supportedFieldTypes().isEmpty() || component.renderingSupport().isEmpty()) {
                throw new ProcessTemplateException("component support whitelist must not be empty");
            }
            component.supportedFieldTypes().forEach(value -> key(value, "component.supportedFieldType"));
            component.renderingSupport().forEach(value -> key(value, "component.renderingSupport"));
            if (component.propertySchema().size() > MAX_COMPONENT_PROPERTIES) {
                throw new ProcessTemplateException.PackageTooLarge(
                    "component property count exceeds " + MAX_COMPONENT_PROPERTIES);
            }
            Set<String> properties = new HashSet<>();
            for (PropertySchema property : component.propertySchema()) {
                key(property.key(), "property.key");
                if (property.type() == null || property.maximumLength() < 0
                    || property.maximumLength() > MAX_STRING_LENGTH) {
                    throw new ProcessTemplateException("invalid component property schema");
                }
                if (!properties.add(property.key())) {
                    throw new ProcessTemplateException("duplicate component property " + property.key());
                }
            }
            if (!identities.add(component.identity())) {
                throw new ProcessTemplateException("duplicate component descriptor " + component.identity());
            }
        }
    }
}
