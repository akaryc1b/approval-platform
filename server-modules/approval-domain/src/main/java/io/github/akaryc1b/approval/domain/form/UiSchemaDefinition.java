package io.github.akaryc1b.approval.domain.form;

import java.util.List;
import java.util.Objects;

/**
 * Framework-neutral layout and node field-access protocol for one immutable Form Schema version.
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

    public record Section(
        String key,
        String title,
        String helpText,
        boolean collapsed,
        List<FieldLayout> fields
    ) {
        public Section {
            key = requireText(key, "section.key");
            title = requireText(title, "section.title");
            helpText = normalizeOptional(helpText);
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record FieldLayout(
        String fieldKey,
        String placeholder,
        String helpText,
        int span
    ) {
        public FieldLayout {
            fieldKey = requireText(fieldKey, "fieldLayout.fieldKey");
            placeholder = normalizeOptional(placeholder);
            helpText = normalizeOptional(helpText);
            if (span < 1 || span > 24) {
                throw new IllegalArgumentException("fieldLayout.span must be between 1 and 24");
            }
        }
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
