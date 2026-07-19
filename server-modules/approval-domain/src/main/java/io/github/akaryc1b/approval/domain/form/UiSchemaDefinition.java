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
