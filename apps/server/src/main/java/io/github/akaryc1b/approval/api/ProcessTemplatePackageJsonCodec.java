package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.CompatibilityRange;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ComponentRegistryDescriptor;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.Dependency;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DependencyKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.DependencyManifest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.IncludedArtifactReference;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ProducerMetadata;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PropertySchema;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PropertyType;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplateManifest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.UnsupportedVersionBehavior;
import io.github.akaryc1b.approval.application.ProcessTemplateException;

import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_ARTIFACTS;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_COMPONENT_PROPERTIES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_DEPENDENCIES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_JSON_DEPTH;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_JSON_ELEMENTS;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_PACKAGE_BYTES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_STRING_LENGTH;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict decoder for untrusted process template package JSON. */
@Component
final class ProcessTemplatePackageJsonCodec {

    private static final Set<String> ROOT_FIELDS = Set.of(
        "manifest", "dependencyManifest", "artifacts", "componentDescriptors", "contentHash");
    private final ObjectMapper objectMapper;

    ProcessTemplatePackageJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.objectMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(MAX_JSON_DEPTH)
            .maxStringLength(MAX_STRING_LENGTH)
            .maxNumberLength(100)
            .build());
    }

    TemplatePackage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalid("template package is empty");
        }
        if (bytes.length > MAX_PACKAGE_BYTES) {
            throw tooLarge("package bytes exceed " + MAX_PACKAGE_BYTES);
        }
        try {
            JsonNode root = objectMapper.readTree(bytes);
            object(root, "root", ROOT_FIELDS);
            int elements = countElements(root, 0);
            if (elements > MAX_JSON_ELEMENTS) {
                throw tooLarge("JSON element count exceeds " + MAX_JSON_ELEMENTS);
            }
            return new TemplatePackage(
                manifest(required(root, "manifest")),
                dependencies(required(root, "dependencyManifest")),
                artifacts(required(root, "artifacts")),
                components(required(root, "componentDescriptors")),
                string(root, "contentHash")
            );
        } catch (ProcessTemplateException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("template package JSON is invalid: " + exception.getMessage());
        }
    }

    private static TemplateManifest manifest(JsonNode node) {
        object(node, "manifest", Set.of("format", "formatVersion", "templateKey", "templateVersion",
            "title", "summary", "category", "compatibility", "producer", "capabilityRequirements"));
        JsonNode compatibility = required(node, "compatibility");
        object(compatibility, "compatibility", Set.of("minimum", "maximum"));
        JsonNode producer = required(node, "producer");
        object(producer, "producer", Set.of("product", "version", "protocol"));
        return new TemplateManifest(
            string(node, "format"), integer(node, "formatVersion"), string(node, "templateKey"),
            integer(node, "templateVersion"), string(node, "title"), string(node, "summary"),
            string(node, "category"),
            new CompatibilityRange(string(compatibility, "minimum"), string(compatibility, "maximum")),
            new ProducerMetadata(string(producer, "product"), string(producer, "version"),
                string(producer, "protocol")),
            stringSet(required(node, "capabilityRequirements"), "capabilityRequirements")
        );
    }

    private static DependencyManifest dependencies(JsonNode node) {
        object(node, "dependencyManifest", Set.of("dependencies"));
        JsonNode values = required(node, "dependencies");
        array(values, "dependencies");
        if (values.size() > MAX_DEPENDENCIES) {
            throw tooLarge("dependency count exceeds " + MAX_DEPENDENCIES);
        }
        List<Dependency> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            object(value, "dependency", Set.of("kind", "key", "minimumVersion", "required"));
            result.add(new Dependency(
                enumValue(value, "kind", DependencyKind.class),
                string(value, "key"), nullableString(value, "minimumVersion"),
                bool(value, "required")
            ));
        }
        return new DependencyManifest(result);
    }

    private static List<IncludedArtifactReference> artifacts(JsonNode node) {
        array(node, "artifacts");
        if (node.size() > MAX_ARTIFACTS) {
            throw tooLarge("artifact count exceeds " + MAX_ARTIFACTS);
        }
        List<IncludedArtifactReference> result = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            object(value, "artifact", Set.of("kind", "resourceName", "contentHash"));
            result.add(new IncludedArtifactReference(
                string(value, "kind"), string(value, "resourceName"), string(value, "contentHash")));
        }
        return result;
    }

    private static List<ComponentRegistryDescriptor> components(JsonNode node) {
        array(node, "componentDescriptors");
        List<ComponentRegistryDescriptor> result = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            object(value, "componentDescriptor", Set.of("componentKey", "componentVersion",
                "supportedFieldTypes", "propertySchema", "renderingSupport", "readonlyFallback",
                "unsupportedVersionBehavior"));
            JsonNode properties = required(value, "propertySchema");
            array(properties, "propertySchema");
            if (properties.size() > MAX_COMPONENT_PROPERTIES) {
                throw tooLarge("component property count exceeds " + MAX_COMPONENT_PROPERTIES);
            }
            List<PropertySchema> propertySchema = new ArrayList<>(properties.size());
            for (JsonNode property : properties) {
                object(property, "propertySchema", Set.of("key", "type", "required", "maximumLength"));
                propertySchema.add(new PropertySchema(
                    string(property, "key"), enumValue(property, "type", PropertyType.class),
                    bool(property, "required"), integer(property, "maximumLength")));
            }
            result.add(new ComponentRegistryDescriptor(
                string(value, "componentKey"), integer(value, "componentVersion"),
                stringSet(required(value, "supportedFieldTypes"), "supportedFieldTypes"),
                propertySchema,
                stringSet(required(value, "renderingSupport"), "renderingSupport"),
                string(value, "readonlyFallback"),
                enumValue(value, "unsupportedVersionBehavior", UnsupportedVersionBehavior.class)
            ));
        }
        return result;
    }

    private static int countElements(JsonNode node, int depth) {
        if (depth > MAX_JSON_DEPTH) {
            throw tooLarge("JSON depth exceeds " + MAX_JSON_DEPTH);
        }
        int count = 1;
        if (node.isContainerNode()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                count = Math.addExact(count, countElements(children.next(), depth + 1));
                if (count > MAX_JSON_ELEMENTS) {
                    return count;
                }
            }
        }
        return count;
    }

    private static void object(JsonNode node, String name, Set<String> allowedFields) {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be an object");
        }
        Set<String> unknown = new HashSet<>();
        node.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            throw invalid(name + " contains unknown fields " + unknown);
        }
    }

    private static void array(JsonNode node, String name) {
        if (node == null || !node.isArray()) {
            throw invalid(name + " must be an array");
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw invalid(field + " is required");
        }
        return value;
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return value.textValue();
    }

    private static String nullableString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string or null");
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) {
            throw invalid(field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static Set<String> stringSet(JsonNode node, String name) {
        array(node, name);
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw invalid(name + " must contain only strings");
            }
            if (!result.add(value.textValue())) {
                throw invalid(name + " contains a duplicate value");
            }
        }
        return result;
    }

    private static <T extends Enum<T>> T enumValue(JsonNode node, String field, Class<T> type) {
        try {
            return Enum.valueOf(type, string(node, field));
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " has an unsupported value");
        }
    }

    private static ProcessTemplateException invalid(String message) {
        return new ProcessTemplateException(message);
    }

    private static ProcessTemplateException.PackageTooLarge tooLarge(String message) {
        return new ProcessTemplateException.PackageTooLarge(message);
    }
}
