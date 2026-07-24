package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.ComponentDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Host-owned whitelist for form components. No server value can select an arbitrary client module. */
public final class ApprovalFormComponentRegistry {

    private static final Pattern SAFE_PROPERTY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final int MAX_PROPERTIES = 20;
    private static final int MAX_PROPERTY_STRING = 4096;
    private static final Set<String> RENDERING_SUPPORT = Set.of("WEB", "H5", "WECHAT");
    private static final String READONLY_FALLBACK = "READONLY_TEXT";

    private static final Map<String, Descriptor> DESCRIPTORS = Map.ofEntries(
        entry("TEXT", Set.of(FieldType.TEXT), Set.of("mask", "trim")),
        entry("TEXTAREA", Set.of(FieldType.TEXTAREA), Set.of("autosize", "trim")),
        entry("MONEY", Set.of(FieldType.MONEY), Set.of("prefix", "suffix")),
        entry("ATTACHMENT", Set.of(FieldType.ATTACHMENT), Set.of("accept")),
        entry("NUMBER", Set.of(FieldType.NUMBER), Set.of("prefix", "suffix")),
        entry("DATE", Set.of(FieldType.DATE), Set.of("displayFormat")),
        entry("DATETIME", Set.of(FieldType.DATETIME), Set.of("displayFormat")),
        entry("BOOLEAN", Set.of(FieldType.BOOLEAN), Set.of("activeLabel", "inactiveLabel")),
        entry("SELECT", Set.of(FieldType.SELECT), Set.of("displayMode")),
        entry("BUSINESS_REFERENCE", Set.of(FieldType.TEXT), Set.of("referenceType")),
        entry("USER_SELECTOR", Set.of(FieldType.TEXT), Set.of("scope", "selectionMode")),
        entry("DEPARTMENT_SELECTOR", Set.of(FieldType.TEXT), Set.of("scope", "selectionMode"))
    );

    public EffectiveComponent resolve(FormField field, FieldLayout layout) {
        ComponentDefinition configured = layout.component();
        if (configured == null) {
            return new EffectiveComponent(field.type().name(), 1, Map.of(), null);
        }
        Descriptor descriptor = DESCRIPTORS.get(configured.componentType());
        if (descriptor == null) {
            throw new IllegalArgumentException(
                "unregistered form component: " + configured.componentType()
            );
        }
        if (configured.componentVersion() != descriptor.version()) {
            throw new IllegalArgumentException(
                "unsupported component version: " + configured.componentType()
                    + '@' + configured.componentVersion()
            );
        }
        if (!descriptor.fieldTypes().contains(field.type())) {
            throw new IllegalArgumentException(
                "component " + configured.componentType() + " is incompatible with field "
                    + field.key() + " of type " + field.type()
            );
        }
        validateProperties(configured, descriptor);
        return new EffectiveComponent(
            configured.componentType(),
            configured.componentVersion(),
            configured.properties(),
            configured.fallbackRenderer()
        );
    }

    public boolean isRegistered(String componentType, int componentVersion) {
        Descriptor descriptor = DESCRIPTORS.get(componentType);
        return descriptor != null && descriptor.version() == componentVersion;
    }

    public Set<String> componentTypes() {
        return DESCRIPTORS.keySet();
    }

    /** Immutable data-only view used by server-authoritative template registry resolution. */
    public List<RegisteredDescriptor> registeredDescriptors() {
        return DESCRIPTORS.entrySet().stream()
            .map(entry -> new RegisteredDescriptor(
                entry.getKey(),
                entry.getValue().version(),
                entry.getValue().fieldTypes().stream()
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                new TreeSet<>(entry.getValue().propertyKeys()),
                RENDERING_SUPPORT,
                READONLY_FALLBACK
            ))
            .sorted(Comparator.comparing(RegisteredDescriptor::componentType))
            .toList();
    }

    private static void validateProperties(ComponentDefinition component, Descriptor descriptor) {
        if (component.properties().size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("component properties exceed the maximum of 20");
        }
        component.properties().forEach((key, value) -> {
            if (!SAFE_PROPERTY.matcher(key).matches()) {
                throw new IllegalArgumentException("unsafe component property: " + key);
            }
            if (!descriptor.propertyKeys().contains(key)) {
                throw new IllegalArgumentException(
                    "unsupported property for " + component.componentType() + ": " + key
                );
            }
            validatePropertyValue(value, key);
        });
    }

    private static void validatePropertyValue(Object value, String key) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return;
        }
        if (value instanceof String text) {
            if (text.length() > MAX_PROPERTY_STRING) {
                throw new IllegalArgumentException("component property is too long: " + key);
            }
            return;
        }
        if (value instanceof List<?> values) {
            if (values.size() > 100) {
                throw new IllegalArgumentException("component property list is too large: " + key);
            }
            values.forEach(item -> validatePropertyValue(item, key));
            return;
        }
        throw new IllegalArgumentException(
            "component property must be a scalar or scalar list: " + key
        );
    }

    private static Map.Entry<String, Descriptor> entry(
        String type,
        Set<FieldType> fieldTypes,
        Set<String> propertyKeys
    ) {
        return Map.entry(type, new Descriptor(1, fieldTypes, propertyKeys));
    }

    private record Descriptor(int version, Set<FieldType> fieldTypes, Set<String> propertyKeys) {
    }

    public record RegisteredDescriptor(
        String componentType,
        int componentVersion,
        Set<String> supportedFieldTypes,
        Set<String> propertyKeys,
        Set<String> renderingSupport,
        String readonlyFallback
    ) {
        public RegisteredDescriptor {
            supportedFieldTypes = Set.copyOf(supportedFieldTypes);
            propertyKeys = Set.copyOf(propertyKeys);
            renderingSupport = Set.copyOf(renderingSupport);
        }
    }

    public record EffectiveComponent(
        String componentType,
        int componentVersion,
        Map<String, Object> properties,
        Object fallbackRenderer
    ) {
    }
}
