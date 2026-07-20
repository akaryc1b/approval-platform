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
