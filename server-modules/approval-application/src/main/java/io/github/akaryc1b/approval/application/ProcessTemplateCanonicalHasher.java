package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ComponentRegistryDescriptor;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Dependency;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DependencyManifest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DraftTarget;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Finding;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.IncludedArtifactReference;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PropertySchema;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.RebindingRequirement;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplateManifest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/** Length-prefixed SHA-256 protocol for packages and preview plans. */
public final class ProcessTemplateCanonicalHasher {

    public String packageHash(TemplatePackage value) {
        return packageHash(
            value.manifest(),
            value.dependencyManifest(),
            value.artifacts(),
            value.componentDescriptors()
        );
    }

    public String packageHash(
        TemplateManifest manifest,
        DependencyManifest dependencies,
        List<IncludedArtifactReference> artifacts,
        List<ComponentRegistryDescriptor> components
    ) {
        List<String> values = new ArrayList<>();
        values.add("process-template-package-v1");
        values.add(manifest.format());
        values.add(Integer.toString(manifest.formatVersion()));
        values.add(manifest.templateKey());
        values.add(Integer.toString(manifest.templateVersion()));
        values.add(manifest.title());
        values.add(manifest.summary());
        values.add(manifest.category());
        values.add(manifest.compatibility().minimum());
        values.add(manifest.compatibility().maximum());
        values.add(manifest.producer().product());
        values.add(manifest.producer().version());
        values.add(manifest.producer().protocol());
        values.add(String.join(",", new TreeSet<>(manifest.capabilityRequirements())));
        dependencies.dependencies().stream()
            .sorted(Comparator.comparing(Dependency::identity))
            .forEach(dependency -> values.add(String.join("|",
                "dependency", dependency.kind().name(), dependency.key(),
                nullToEmpty(dependency.minimumVersion()), Boolean.toString(dependency.required()))));
        artifacts.stream()
            .sorted(Comparator.comparing(IncludedArtifactReference::identity))
            .forEach(artifact -> values.add(String.join("|",
                "artifact", artifact.kind(), artifact.resourceName(), artifact.contentHash())));
        components.stream()
            .sorted(Comparator.comparing(ComponentRegistryDescriptor::identity))
            .forEach(component -> appendComponent(values, component));
        return sha256(lengthPrefix(values));
    }

    public String planHash(
        String packageHash,
        DraftTarget target,
        List<Finding> findings,
        List<RebindingRequirement> requirements,
        List<TenantBinding> bindings
    ) {
        List<String> values = new ArrayList<>();
        values.add("process-template-import-plan-v1");
        values.add(packageHash);
        values.add(target.tenantId());
        values.add(target.definitionKey());
        values.add(Integer.toString(target.definitionVersion()));
        values.add(target.draftName());
        findings.stream().map(Finding::canonical).sorted().forEach(values::add);
        requirements.stream().map(RebindingRequirement::canonical).sorted().forEach(values::add);
        bindings.stream().sorted(Comparator.comparing(TenantBinding::identity)).forEach(binding ->
            values.add(String.join("|", binding.kind().name(), binding.sourceKey(),
                binding.targetTenantId(), binding.targetResourceKey(),
                binding.targetVersion() == null ? "" : binding.targetVersion().toString())));
        return sha256(lengthPrefix(values));
    }

    private static void appendComponent(List<String> values, ComponentRegistryDescriptor component) {
        values.add(String.join("|", "component", component.componentKey(),
            Integer.toString(component.componentVersion()),
            String.join(",", new TreeSet<>(component.supportedFieldTypes())),
            String.join(",", new TreeSet<>(component.renderingSupport())),
            component.readonlyFallback(), component.unsupportedVersionBehavior().name()));
        component.propertySchema().stream().sorted(Comparator.comparing(PropertySchema::key))
            .forEach(property -> values.add(String.join("|", "property", component.identity(),
                property.key(), property.type().name(), Boolean.toString(property.required()),
                Integer.toString(property.maximumLength()))));
    }

    private static String lengthPrefix(List<String> values) {
        StringBuilder canonical = new StringBuilder(8192);
        for (String value : values) {
            String checked = nullToEmpty(value);
            canonical.append(checked.length()).append(':').append(checked).append('|');
        }
        return canonical.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
