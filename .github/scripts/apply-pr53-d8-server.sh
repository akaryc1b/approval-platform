#!/usr/bin/env bash
set -euo pipefail

cat > server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/form/UiSchemaDefinition.java <<'JAVA'
package io.github.akaryc1b.approval.domain.form;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral layout, component and node field-access protocol for one immutable Form Schema.
 */
public record UiSchemaDefinition(
    String schemaVersion,
    String formKey,
    int formVersion,
    int version,
    String name,
    List<Section> sections,
    List<NodePermissions> nodePermissions
) {

    public static final String CURRENT_SCHEMA_VERSION = "1.0";
    public static final String START_CONTEXT = "$start";
    public static final int MAX_SECTION_DEPTH = 4;

    public UiSchemaDefinition {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        formKey = requireText(formKey, "formKey");
        if (formVersion < 1) {
            throw new IllegalArgumentException("formVersion must be positive");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        name = requireText(name, "name");
        sections = sections == null ? List.of() : List.copyOf(sections);
        nodePermissions = nodePermissions == null ? List.of() : List.copyOf(nodePermissions);
    }

    /** Compatibility alias for callers that name the immutable version explicitly. */
    public int uiSchemaVersion() {
        return version;
    }

    /**
     * Composite section protocol. The first five fields retain wire/source compatibility with the
     * original flat protocol; the remaining fields are additive and default safely when absent.
     */
    public record Section(
        String key,
        String title,
        String helpText,
        boolean collapsed,
        List<FieldLayout> fields,
        Integer order,
        Integer columns,
        Boolean collapsible,
        SectionVisibility visibility,
        boolean readonlySummary,
        List<Section> children
    ) {
        public Section(
            String key,
            String title,
            String helpText,
            boolean collapsed,
            List<FieldLayout> fields
        ) {
            this(
                key,
                title,
                helpText,
                collapsed,
                fields,
                null,
                1,
                true,
                SectionVisibility.always(),
                false,
                List.of()
            );
        }

        public Section {
            key = requireText(key, "section.key");
            title = requireText(title, "section.title");
            helpText = normalizeOptional(helpText);
            fields = fields == null ? List.of() : List.copyOf(fields);
            if (order != null && order < 0) {
                throw new IllegalArgumentException("section.order must not be negative");
            }
            columns = columns == null ? 1 : columns;
            if (columns < 1 || columns > 4) {
                throw new IllegalArgumentException("section.columns must be between 1 and 4");
            }
            collapsible = collapsible == null ? Boolean.TRUE : collapsible;
            visibility = visibility == null ? SectionVisibility.always() : visibility;
            children = children == null ? List.of() : List.copyOf(children);
        }

        public String sectionId() {
            return key;
        }

        public String description() {
            return helpText;
        }

        public boolean defaultCollapsed() {
            return collapsed;
        }
    }

    public record FieldLayout(
        String fieldKey,
        String placeholder,
        String helpText,
        int span,
        ComponentDefinition component
    ) {
        public FieldLayout(String fieldKey, String placeholder, String helpText, int span) {
            this(fieldKey, placeholder, helpText, span, null);
        }

        public FieldLayout {
            fieldKey = requireText(fieldKey, "fieldLayout.fieldKey");
            placeholder = normalizeOptional(placeholder);
            helpText = normalizeOptional(helpText);
            if (span < 1 || span > 24) {
                throw new IllegalArgumentException("fieldLayout.span must be between 1 and 24");
            }
        }
    }

    /** Closed component descriptor. It never contains a module path, URL, script or HTML. */
    public record ComponentDefinition(
        String componentType,
        int componentVersion,
        Map<String, Object> properties,
        FallbackRenderer fallbackRenderer
    ) {
        public ComponentDefinition {
            componentType = requireText(componentType, "component.componentType")
                .toUpperCase(Locale.ROOT);
            if (componentVersion < 1) {
                throw new IllegalArgumentException("componentVersion must be positive");
            }
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            fallbackRenderer = fallbackRenderer == null
                ? FallbackRenderer.READONLY_TEXT
                : fallbackRenderer;
        }
    }

    public enum FallbackRenderer {
        READONLY_TEXT,
        READONLY_JSON
    }

    /** Deliberately small conditional protocol; no expression or script is evaluated. */
    public record SectionVisibility(
        VisibilityMode mode,
        String fieldKey,
        Object expectedValue
    ) {
        public SectionVisibility {
            mode = mode == null ? VisibilityMode.ALWAYS : mode;
            fieldKey = normalizeOptional(fieldKey);
        }

        public static SectionVisibility always() {
            return new SectionVisibility(VisibilityMode.ALWAYS, null, null);
        }
    }

    public enum VisibilityMode {
        ALWAYS,
        FIELD_EQUALS,
        FIELD_NOT_EMPTY
    }

    public record NodePermissions(String contextKey, List<FieldPermission> fields) {
        public NodePermissions {
            contextKey = requireText(contextKey, "nodePermissions.contextKey");
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record FieldPermission(
        String fieldKey,
        FieldAccess access,
        RequiredOverride requiredOverride
    ) {
        public FieldPermission(String fieldKey, FieldAccess access) {
            this(fieldKey, access, RequiredOverride.INHERIT);
        }

        public FieldPermission {
            fieldKey = requireText(fieldKey, "fieldPermission.fieldKey");
            access = Objects.requireNonNull(access, "fieldPermission.access must not be null");
            requiredOverride = requiredOverride == null
                ? RequiredOverride.INHERIT
                : requiredOverride;
        }
    }

    public enum FieldAccess {
        EDITABLE,
        READONLY,
        HIDDEN
    }

    public enum RequiredOverride {
        INHERIT,
        REQUIRED,
        OPTIONAL
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
JAVA

cat > server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalFormComponentRegistry.java <<'JAVA'
package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.ComponentDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Host-owned whitelist for form components. No server value can select an arbitrary client module. */
public final class ApprovalFormComponentRegistry {

    private static final Pattern SAFE_PROPERTY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final int MAX_PROPERTIES = 20;
    private static final int MAX_PROPERTY_STRING = 4096;

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

    public record EffectiveComponent(
        String componentType,
        int componentVersion,
        Map<String, Object> properties,
        Object fallbackRenderer
    ) {
    }
}
JAVA

cat > server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/UiSchemaDefinitionValidator.java <<'JAVA'
package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldPermission;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.RequiredOverride;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.SectionVisibility;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.VisibilityMode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates a UI Schema against the exact immutable Form Schema it decorates. */
public final class UiSchemaDefinitionValidator {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_$.-]{1,128}");
    private static final int MAX_SECTIONS = 200;

    private final ApprovalFormComponentRegistry components;

    public UiSchemaDefinitionValidator() {
        this(new ApprovalFormComponentRegistry());
    }

    public UiSchemaDefinitionValidator(ApprovalFormComponentRegistry components) {
        this.components = Objects.requireNonNull(components);
    }

    public void validate(FormDefinition form, UiSchemaDefinition uiSchema) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(uiSchema, "uiSchema must not be null");
        if (!UiSchemaDefinition.CURRENT_SCHEMA_VERSION.equals(uiSchema.schemaVersion())) {
            throw new IllegalArgumentException("unsupported UI Schema version");
        }
        if (!form.formKey().equals(uiSchema.formKey()) || form.version() != uiSchema.formVersion()) {
            throw new IllegalArgumentException("UI Schema must bind to the exact Form Schema version");
        }

        Set<String> formFields = new LinkedHashSet<>();
        Map<String, FormField> fieldsByKey = new HashMap<>();
        form.fields().forEach(field -> {
            formFields.add(field.key());
            fieldsByKey.put(field.key(), field);
        });
        validateLayout(uiSchema, formFields, fieldsByKey);
        validatePermissions(uiSchema, formFields, fieldsByKey);
    }

    private void validateLayout(
        UiSchemaDefinition uiSchema,
        Set<String> formFields,
        Map<String, FormField> fieldsByKey
    ) {
        Set<String> sectionKeys = new HashSet<>();
        Set<String> layoutFields = new LinkedHashSet<>();
        IdentityHashMap<Section, Boolean> seenSections = new IdentityHashMap<>();
        int[] sectionCount = {0};
        validateSections(
            uiSchema.sections(),
            1,
            sectionKeys,
            layoutFields,
            formFields,
            fieldsByKey,
            seenSections,
            sectionCount
        );
        if (!layoutFields.equals(formFields)) {
            Set<String> missing = new LinkedHashSet<>(formFields);
            missing.removeAll(layoutFields);
            throw new IllegalArgumentException("layout is missing form fields: " + String.join(",", missing));
        }
    }

    private void validateSections(
        List<Section> sections,
        int depth,
        Set<String> sectionKeys,
        Set<String> layoutFields,
        Set<String> formFields,
        Map<String, FormField> fieldsByKey,
        IdentityHashMap<Section, Boolean> seenSections,
        int[] sectionCount
    ) {
        if (depth > UiSchemaDefinition.MAX_SECTION_DEPTH) {
            throw new IllegalArgumentException(
                "UI section nesting exceeds " + UiSchemaDefinition.MAX_SECTION_DEPTH
            );
        }
        validateSiblingOrder(sections);
        for (Section section : sections) {
            if (section == null) {
                throw new IllegalArgumentException("UI section must not be null");
            }
            if (seenSections.put(section, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("UI section graph cannot contain cycles or reused nodes");
            }
            if (++sectionCount[0] > MAX_SECTIONS) {
                throw new IllegalArgumentException("UI Schema exceeds 200 sections");
            }
            requireSafe(section.key(), "section key");
            if (!sectionKeys.add(section.key())) {
                throw new IllegalArgumentException("duplicate UI section: " + section.key());
            }
            validateVisibility(section.visibility(), formFields);
            for (FieldLayout layout : section.fields()) {
                if (layout == null) {
                    throw new IllegalArgumentException("UI field layout must not be null");
                }
                if (!formFields.contains(layout.fieldKey())) {
                    throw new IllegalArgumentException("unknown layout field: " + layout.fieldKey());
                }
                if (!layoutFields.add(layout.fieldKey())) {
                    throw new IllegalArgumentException("duplicate layout field: " + layout.fieldKey());
                }
                components.resolve(fieldsByKey.get(layout.fieldKey()), layout);
            }
            validateSections(
                section.children(),
                depth + 1,
                sectionKeys,
                layoutFields,
                formFields,
                fieldsByKey,
                seenSections,
                sectionCount
            );
        }
    }

    private static void validateSiblingOrder(List<Section> sections) {
        boolean explicit = sections.stream().filter(Objects::nonNull).anyMatch(section -> section.order() != null);
        if (!explicit) {
            return;
        }
        int previous = -1;
        Set<Integer> orders = new HashSet<>();
        for (Section section : sections) {
            if (section == null || section.order() == null) {
                throw new IllegalArgumentException(
                    "sibling sections must all define order when one order is explicit"
                );
            }
            if (!orders.add(section.order())) {
                throw new IllegalArgumentException("duplicate sibling section order: " + section.order());
            }
            if (section.order() <= previous) {
                throw new IllegalArgumentException("section list must be sorted by ascending order");
            }
            previous = section.order();
        }
    }

    private static void validateVisibility(SectionVisibility visibility, Set<String> formFields) {
        VisibilityMode mode = visibility.mode();
        if (mode == VisibilityMode.ALWAYS) {
            if (visibility.fieldKey() != null || visibility.expectedValue() != null) {
                throw new IllegalArgumentException("ALWAYS visibility cannot contain a field or value");
            }
            return;
        }
        if (visibility.fieldKey() == null || !formFields.contains(visibility.fieldKey())) {
            throw new IllegalArgumentException("section visibility references an unknown field");
        }
        if (mode == VisibilityMode.FIELD_NOT_EMPTY) {
            if (visibility.expectedValue() != null) {
                throw new IllegalArgumentException("FIELD_NOT_EMPTY cannot contain expectedValue");
            }
            return;
        }
        Object expected = visibility.expectedValue();
        if (!(expected instanceof String || expected instanceof Number || expected instanceof Boolean)) {
            throw new IllegalArgumentException("FIELD_EQUALS expectedValue must be a scalar");
        }
        if (expected instanceof String text && text.length() > 4096) {
            throw new IllegalArgumentException("section visibility expectedValue is too long");
        }
    }

    private static void validatePermissions(
        UiSchemaDefinition uiSchema,
        Set<String> formFields,
        Map<String, FormField> fieldsByKey
    ) {
        Set<String> contexts = new HashSet<>();
        boolean startFound = false;
        for (NodePermissions permissionSet : uiSchema.nodePermissions()) {
            requireSafe(permissionSet.contextKey(), "permission context key");
            if (!contexts.add(permissionSet.contextKey())) {
                throw new IllegalArgumentException(
                    "duplicate permission context: " + permissionSet.contextKey()
                );
            }
            if (UiSchemaDefinition.START_CONTEXT.equals(permissionSet.contextKey())) {
                startFound = true;
            }
            Set<String> permissionFields = new LinkedHashSet<>();
            for (FieldPermission permission : permissionSet.fields()) {
                if (!formFields.contains(permission.fieldKey())) {
                    throw new IllegalArgumentException(
                        "unknown permission field: " + permission.fieldKey()
                    );
                }
                if (!permissionFields.add(permission.fieldKey())) {
                    throw new IllegalArgumentException(
                        "duplicate permission field: " + permission.fieldKey()
                    );
                }
                validateRequiredCombination(
                    permissionSet.contextKey(),
                    fieldsByKey.get(permission.fieldKey()),
                    permission
                );
            }
            if (!permissionFields.equals(formFields)) {
                throw new IllegalArgumentException(
                    "permission context must explicitly cover every form field: "
                        + permissionSet.contextKey()
                );
            }
        }
        if (!startFound) {
            throw new IllegalArgumentException("UI Schema must define the $start permission context");
        }
    }

    private static void validateRequiredCombination(
        String contextKey,
        FormField field,
        FieldPermission permission
    ) {
        if (permission.access() == FieldAccess.HIDDEN
            && permission.requiredOverride() == RequiredOverride.REQUIRED) {
            throw new IllegalArgumentException(
                "hidden field cannot be explicitly required: " + permission.fieldKey()
            );
        }
        boolean required = effectiveRequired(field.required(), permission.requiredOverride());
        if (!UiSchemaDefinition.START_CONTEXT.equals(contextKey) || !required) {
            return;
        }
        boolean editable = permission.access() == FieldAccess.EDITABLE;
        boolean defaulted = field.defaultValue().type() != DefaultValueType.NONE;
        if (!editable && !defaulted) {
            throw new IllegalArgumentException(
                "required start field must be editable or have a server default: " + field.key()
            );
        }
    }

    public static boolean effectiveRequired(boolean baseRequired, RequiredOverride override) {
        return switch (override == null ? RequiredOverride.INHERIT : override) {
            case INHERIT -> baseRequired;
            case REQUIRED -> true;
            case OPTIONAL -> false;
        };
    }

    /** A section may only reduce editability; it can never reveal or unlock a protected field. */
    public static FieldAccess effectiveAccess(FieldAccess fieldAccess, boolean readonlySummary) {
        Objects.requireNonNull(fieldAccess, "fieldAccess must not be null");
        if (fieldAccess == FieldAccess.HIDDEN || fieldAccess == FieldAccess.READONLY) {
            return fieldAccess;
        }
        return readonlySummary ? FieldAccess.READONLY : FieldAccess.EDITABLE;
    }

    private static void requireSafe(String value, String name) {
        if (!SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains unsafe characters: " + value);
        }
    }
}
JAVA

cat > server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/UiSchemaHasher.java <<'JAVA'
package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.ComponentDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldPermission;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.RequiredOverride;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.SectionVisibility;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Produces a deterministic SHA-256 hash for immutable UI Schema versions. */
public final class UiSchemaHasher {

    public String hash(UiSchemaDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, definition.schemaVersion());
            update(digest, definition.formKey());
            update(digest, Integer.toString(definition.formVersion()));
            update(digest, Integer.toString(definition.version()));
            update(digest, definition.name());
            hashSections(digest, definition.sections());
            for (NodePermissions permissions : definition.nodePermissions()) {
                update(digest, permissions.contextKey());
                for (FieldPermission field : permissions.fields()) {
                    update(digest, field.fieldKey());
                    update(digest, field.access().name());
                    if (field.requiredOverride() != RequiredOverride.INHERIT) {
                        update(digest, "required-override-v1");
                        update(digest, field.requiredOverride().name());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void hashSections(MessageDigest digest, List<Section> sections) {
        update(digest, "sections:" + sections.size());
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            update(digest, "section-index:" + index);
            update(digest, section.key());
            update(digest, section.title());
            update(digest, optional(section.helpText()));
            update(digest, Boolean.toString(section.collapsed()));
            update(digest, optionalInteger(section.order()));
            update(digest, Integer.toString(section.columns()));
            update(digest, Boolean.toString(section.collapsible()));
            update(digest, Boolean.toString(section.readonlySummary()));
            hashVisibility(digest, section.visibility());
            for (FieldLayout field : section.fields()) {
                update(digest, field.fieldKey());
                update(digest, optional(field.placeholder()));
                update(digest, optional(field.helpText()));
                update(digest, Integer.toString(field.span()));
                hashComponent(digest, field.component());
            }
            hashSections(digest, section.children());
        }
    }

    private static void hashVisibility(MessageDigest digest, SectionVisibility visibility) {
        update(digest, visibility.mode().name());
        update(digest, optional(visibility.fieldKey()));
        hashValue(digest, visibility.expectedValue());
    }

    private static void hashComponent(MessageDigest digest, ComponentDefinition component) {
        if (component == null) {
            update(digest, "component:inferred");
            return;
        }
        update(digest, component.componentType());
        update(digest, Integer.toString(component.componentVersion()));
        update(digest, component.fallbackRenderer().name());
        hashMap(digest, component.properties());
    }

    private static void hashMap(MessageDigest digest, Map<String, ?> values) {
        update(digest, "map:" + values.size());
        values.keySet().stream().sorted().forEach(key -> {
            update(digest, key);
            hashValue(digest, values.get(key));
        });
    }

    private static void hashValue(MessageDigest digest, Object value) {
        if (value == null) {
            update(digest, "null");
        } else if (value instanceof Map<?, ?> map) {
            update(digest, "map-value");
            map.entrySet().stream()
                .map(entry -> Map.entry(String.valueOf(entry.getKey()), entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    update(digest, entry.getKey());
                    hashValue(digest, entry.getValue());
                });
        } else if (value instanceof List<?> list) {
            update(digest, "list:" + list.size());
            list.forEach(item -> hashValue(digest, item));
        } else {
            update(digest, value.getClass().getName());
            update(digest, String.valueOf(value));
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }

    private static String optionalInteger(Integer value) {
        return value == null ? "legacy-order" : Integer.toString(value);
    }
}
JAVA

cat > server-modules/approval-application/src/test/java/io/github/akaryc1b/approval/application/CompositeUiSchemaProtocolTest.java <<'JAVA'
package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.ComponentDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FallbackRenderer;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.SectionVisibility;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.VisibilityMode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeUiSchemaProtocolTest {

    private final FormDefinition form = new FormDefinition(
        FormDefinition.CURRENT_SCHEMA_VERSION,
        "composite-form",
        1,
        "Composite form",
        List.of(
            text("owner"),
            text("department"),
            text("reference")
        )
    );
    private final UiSchemaDefinitionValidator validator = new UiSchemaDefinitionValidator();
    private final UiSchemaHasher hasher = new UiSchemaHasher();

    @Test
    void producesStableHashAndMakesSectionOrderSemantic() {
        UiSchemaDefinition first = schema(List.of(
            section("identity", 0, List.of(layout("owner", "USER_SELECTOR", Map.of()))),
            section("business", 1, List.of(
                layout("department", "DEPARTMENT_SELECTOR", Map.of()),
                layout("reference", "BUSINESS_REFERENCE", Map.of())
            ))
        ));
        UiSchemaDefinition same = schema(first.sections());
        UiSchemaDefinition reordered = schema(List.of(
            section("business", 0, first.sections().get(1).fields()),
            section("identity", 1, first.sections().get(0).fields())
        ));

        validator.validate(form, first);
        assertEquals(hasher.hash(first), hasher.hash(same));
        assertNotEquals(hasher.hash(first), hasher.hash(reordered));
    }

    @Test
    void ignoresComponentPropertyMapInsertionOrder() {
        Map<String, Object> firstProperties = new LinkedHashMap<>();
        firstProperties.put("scope", "tenant");
        firstProperties.put("selectionMode", "single");
        Map<String, Object> secondProperties = new LinkedHashMap<>();
        secondProperties.put("selectionMode", "single");
        secondProperties.put("scope", "tenant");

        UiSchemaDefinition first = singleComponentSchema(firstProperties);
        UiSchemaDefinition second = singleComponentSchema(secondProperties);

        assertEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    void rejectsDuplicateSectionIdsAndDuplicateFieldOwnership() {
        Section one = section("duplicate", 0, List.of(layout("owner", "TEXT", Map.of())));
        Section two = section("duplicate", 1, List.of(
            layout("department", "TEXT", Map.of()),
            layout("reference", "TEXT", Map.of())
        ));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(form, schema(List.of(one, two))));

        Section duplicatedField = section("other", 1, List.of(
            layout("owner", "TEXT", Map.of()),
            layout("department", "TEXT", Map.of()),
            layout("reference", "TEXT", Map.of())
        ));
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(form, schema(List.of(one, duplicatedField)))
        );
    }

    @Test
    void rejectsExcessiveNestingAndReusedSectionNodes() {
        Section deepest = section("level-5", 0, List.of(
            layout("owner", "TEXT", Map.of()),
            layout("department", "TEXT", Map.of()),
            layout("reference", "TEXT", Map.of())
        ));
        Section nested = parent("level-4", deepest);
        nested = parent("level-3", nested);
        nested = parent("level-2", nested);
        nested = parent("level-1", nested);
        Section overDepth = nested;
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(form, schema(List.of(overDepth)))
        );

        Section reused = section("reused", 0, List.of());
        Section root = new Section(
            "root",
            "root",
            null,
            false,
            List.of(
                layout("owner", "TEXT", Map.of()),
                layout("department", "TEXT", Map.of()),
                layout("reference", "TEXT", Map.of())
            ),
            0,
            1,
            true,
            SectionVisibility.always(),
            false,
            List.of(reused, reused)
        );
        assertThrows(IllegalArgumentException.class, () -> validator.validate(form, schema(List.of(root))));
    }

    @Test
    void rejectsUnknownComponentsVersionsAndArbitraryProperties() {
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(form, singleComponentSchema(Map.of(), "REMOTE_SCRIPT", 1))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(form, singleComponentSchema(Map.of(), "USER_SELECTOR", 2))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(
                form,
                singleComponentSchema(Map.of("modulePath", "https://example.invalid/component.js"))
            )
        );
    }

    @Test
    void validatesControlledVisibilityAndCannotOverrideFieldPermissions() {
        Section conditional = new Section(
            "conditional",
            "Conditional",
            null,
            false,
            List.of(
                layout("owner", "USER_SELECTOR", Map.of()),
                layout("department", "DEPARTMENT_SELECTOR", Map.of()),
                layout("reference", "BUSINESS_REFERENCE", Map.of())
            ),
            0,
            2,
            true,
            new SectionVisibility(VisibilityMode.FIELD_EQUALS, "department", "finance"),
            true,
            List.of()
        );
        validator.validate(form, schema(List.of(conditional)));

        assertEquals(
            FieldAccess.HIDDEN,
            UiSchemaDefinitionValidator.effectiveAccess(FieldAccess.HIDDEN, false)
        );
        assertEquals(
            FieldAccess.READONLY,
            UiSchemaDefinitionValidator.effectiveAccess(FieldAccess.READONLY, false)
        );
        assertEquals(
            FieldAccess.READONLY,
            UiSchemaDefinitionValidator.effectiveAccess(FieldAccess.EDITABLE, true)
        );
        assertEquals(
            FieldAccess.EDITABLE,
            UiSchemaDefinitionValidator.effectiveAccess(FieldAccess.EDITABLE, false)
        );
    }

    private UiSchemaDefinition singleComponentSchema(Map<String, Object> properties) {
        return singleComponentSchema(properties, "USER_SELECTOR", 1);
    }

    private UiSchemaDefinition singleComponentSchema(
        Map<String, Object> properties,
        String componentType,
        int componentVersion
    ) {
        return schema(List.of(section("main", 0, List.of(
            layout("owner", componentType, componentVersion, properties),
            layout("department", "TEXT", Map.of()),
            layout("reference", "TEXT", Map.of())
        ))));
    }

    private UiSchemaDefinition schema(List<Section> sections) {
        return new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            form.formKey(),
            form.version(),
            1,
            "Composite UI",
            sections,
            List.of(new UiSchemaDefinition.NodePermissions(
                UiSchemaDefinition.START_CONTEXT,
                form.fields().stream()
                    .map(field -> new UiSchemaDefinition.FieldPermission(
                        field.key(),
                        FieldAccess.EDITABLE
                    ))
                    .toList()
            ))
        );
    }

    private static Section section(String key, int order, List<FieldLayout> fields) {
        return new Section(
            key,
            key,
            null,
            false,
            fields,
            order,
            2,
            true,
            SectionVisibility.always(),
            false,
            List.of()
        );
    }

    private static Section parent(String key, Section child) {
        return new Section(
            key,
            key,
            null,
            false,
            List.of(),
            0,
            1,
            true,
            SectionVisibility.always(),
            false,
            List.of(child)
        );
    }

    private static FieldLayout layout(
        String fieldKey,
        String componentType,
        Map<String, Object> properties
    ) {
        return layout(fieldKey, componentType, 1, properties);
    }

    private static FieldLayout layout(
        String fieldKey,
        String componentType,
        int componentVersion,
        Map<String, Object> properties
    ) {
        return new FieldLayout(
            fieldKey,
            null,
            null,
            24,
            new ComponentDefinition(
                componentType,
                componentVersion,
                properties,
                FallbackRenderer.READONLY_TEXT
            )
        );
    }

    private static FormDefinition.FormField text(String key) {
        return new FormDefinition.FormField(
            key,
            FormDefinition.FieldType.TEXT,
            key,
            false,
            FormDefinition.FieldConstraints.text(200)
        );
    }
}
JAVA

python3 <<'PY'
from pathlib import Path

path = Path(
    "server-modules/approval-persistence-jdbc/src/test/java/"
    "io/github/akaryc1b/approval/persistence/jdbc/"
    "JdbcApprovalFormDesignIntegrationTest.java"
)
text = path.read_text(encoding="utf-8")
text = text.replace(
    "import java.util.Optional;\nimport java.util.UUID;",
    "import java.util.List;\nimport java.util.Map;\nimport java.util.Optional;\nimport java.util.UUID;",
    1,
)
marker = "    @Test\n    void validatesAndPublishesIdempotentImmutablePackage() {"
test = '''    @Test
    void roundTripsCompositeSectionsAndWhitelistComponentsThroughPostgresql() {
        FormDesignDraft created = service.createBlank(
            createCommand("tenant-a", "composite-create", "composite-create-key", "composite-form")
        );
        FormDefinition form = templateForm("composite-form", 1, "Composite form");
        UiSchemaDefinition source = PurchasePaymentTemplate.uiSchemaDefinition();
        UiSchemaDefinition.Section child = new UiSchemaDefinition.Section(
            "materials",
            "Materials",
            "Nested materials",
            false,
            List.of(
                source.sections().get(0).fields().get(2),
                source.sections().get(1).fields().get(0)
            ),
            0,
            1,
            true,
            UiSchemaDefinition.SectionVisibility.always(),
            false,
            List.of()
        );
        UiSchemaDefinition.FieldLayout ownerLayout = new UiSchemaDefinition.FieldLayout(
            "supplier",
            "Select owner",
            null,
            12,
            new UiSchemaDefinition.ComponentDefinition(
                "USER_SELECTOR",
                1,
                Map.of("scope", "tenant", "selectionMode", "single"),
                UiSchemaDefinition.FallbackRenderer.READONLY_TEXT
            )
        );
        UiSchemaDefinition.Section root = new UiSchemaDefinition.Section(
            "request",
            "Request",
            "Composite root",
            false,
            List.of(source.sections().get(0).fields().get(0), ownerLayout),
            0,
            2,
            true,
            new UiSchemaDefinition.SectionVisibility(
                UiSchemaDefinition.VisibilityMode.FIELD_NOT_EMPTY,
                "supplier",
                null
            ),
            false,
            List.of(child)
        );
        UiSchemaDefinition uiSchema = new UiSchemaDefinition(
            source.schemaVersion(),
            form.formKey(),
            form.version(),
            1,
            "Composite UI",
            List.of(root),
            source.nodePermissions()
        );

        FormDesignDraft updated = service.update(new UpdateCommand(
            context("tenant-a", "composite-update", "composite-update-key"),
            created.draftId(),
            1,
            "Composite form",
            form,
            uiSchema,
            SaveMode.EXPLICIT
        ));
        FormDesignDraft roundTripped = service.find("tenant-a", updated.draftId()).orElseThrow();
        assertEquals(uiSchema, roundTripped.uiSchemaDefinition());
        assertEquals(new UiSchemaHasher().hash(uiSchema), new UiSchemaHasher().hash(
            roundTripped.uiSchemaDefinition()
        ));
        var validation = service.validate(new RevisionCommand(
            context("tenant-a", "composite-validate", "composite-validate-key"),
            updated.draftId(),
            updated.revision()
        ));
        assertTrue(validation.valid());
    }

''' + marker
if marker not in text:
    raise SystemExit("D8 JDBC insertion marker was not found")
path.write_text(text.replace(marker, test, 1), encoding="utf-8")
PY

rm -f .github/scripts/apply-pr53-d8-server.sh
