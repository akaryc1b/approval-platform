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
