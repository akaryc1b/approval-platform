package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.FormDefinition.SelectOption;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualFormFieldProtocolTest {

    private final FormDefinitionValidator definitionValidator = new FormDefinitionValidator();
    private final FormDataValidator dataValidator = new FormDataValidator();

    @Test
    void validatesAndNormalizesEveryVisualDesignerFieldType() {
        FormDefinition definition = definition(selectOptions("A", "B"));
        definitionValidator.validate(definition);

        Map<String, Object> values = dataValidator.validate(
            definition,
            Map.of(
                "notes", "  multiline value  ",
                "quantity", "12.50",
                "deliveryDate", "2026-07-20",
                "meetingAt", "2026-07-20T10:30:00Z",
                "confirmed", true,
                "category", "A",
                "tags", List.of("B", "A", "B")
            )
        ).values();

        assertEquals("multiline value", values.get("notes"));
        assertEquals(new BigDecimal("12.5"), values.get("quantity"));
        assertEquals("2026-07-20", values.get("deliveryDate"));
        assertEquals("2026-07-20T10:30:00Z", values.get("meetingAt"));
        assertEquals(true, values.get("confirmed"));
        assertEquals("A", values.get("category"));
        assertEquals(List.of("B", "A"), values.get("tags"));
    }

    @Test
    void rejectsInvalidDateBooleanAndDisabledSelectValues() {
        FormDefinition definition = definition(List.of(
            new SelectOption("A", "Enabled", false),
            new SelectOption("B", "Disabled", true)
        ));
        definitionValidator.validate(definition);

        assertThrows(
            FormDataValidator.FormDataValidationException.class,
            () -> dataValidator.validate(definition, Map.of("deliveryDate", "20/07/2026"))
        );
        assertThrows(
            FormDataValidator.FormDataValidationException.class,
            () -> dataValidator.validate(definition, Map.of("confirmed", "true"))
        );
        assertThrows(
            FormDataValidator.FormDataValidationException.class,
            () -> dataValidator.validate(definition, Map.of("category", "B"))
        );
    }

    @Test
    void staticOptionsParticipateInDeterministicHash() {
        FormSchemaHasher hasher = new FormSchemaHasher();
        FormDefinition first = definition(selectOptions("A", "B"));
        FormDefinition same = definition(selectOptions("A", "B"));
        FormDefinition changedLabel = definition(List.of(
            new SelectOption("A", "Option A changed", false),
            new SelectOption("B", "Option B", false)
        ));

        assertEquals(hasher.hash(first), hasher.hash(same));
        assertNotEquals(hasher.hash(first), hasher.hash(changedLabel));
    }

    @Test
    void validatesTypedDefaultsForNewFields() {
        FormDefinition definition = definition(selectOptions("A", "B"));
        definitionValidator.validate(definition);
        assertTrue(definition.fields().stream().allMatch(field -> field.defaultValue() != null));

        FormDefinition invalid = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "visual-invalid",
            1,
            "Invalid",
            List.of(new FormField(
                "confirmed",
                FieldType.BOOLEAN,
                "Confirmed",
                false,
                FieldConstraints.none(),
                DefaultValue.literal("true")
            ))
        );
        assertThrows(IllegalArgumentException.class, () -> definitionValidator.validate(invalid));
    }

    @Test
    void allowsSingleSelectWithoutExplicitMinItems() {
        FormDefinition definition = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "select-default-minimum",
            1,
            "Select default minimum",
            List.of(new FormField(
                "category",
                FieldType.SELECT,
                "Category",
                false,
                FieldConstraints.none(),
                DefaultValue.none(),
                selectOptions("A", "B")
            ))
        );

        definitionValidator.validate(definition);
        assertEquals(Map.of(), dataValidator.validate(definition, Map.of()).values());
        assertEquals(
            Map.of("category", "A"),
            dataValidator.validate(definition, Map.of("category", "A")).values()
        );
    }

    private static FormDefinition definition(List<SelectOption> options) {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "visual-fields",
            1,
            "Visual fields",
            List.of(
                new FormField(
                    "notes",
                    FieldType.TEXTAREA,
                    "Notes",
                    false,
                    FieldConstraints.text(2000),
                    DefaultValue.literal("Initial notes")
                ),
                new FormField(
                    "quantity",
                    FieldType.NUMBER,
                    "Quantity",
                    false,
                    FieldConstraints.number(2, BigDecimal.ZERO),
                    DefaultValue.literal(new BigDecimal("1.5"))
                ),
                new FormField(
                    "deliveryDate",
                    FieldType.DATE,
                    "Delivery date",
                    false,
                    FieldConstraints.none(),
                    new DefaultValue(DefaultValueType.CURRENT_DATE, null)
                ),
                new FormField(
                    "meetingAt",
                    FieldType.DATETIME,
                    "Meeting at",
                    false,
                    FieldConstraints.none(),
                    new DefaultValue(DefaultValueType.CURRENT_DATETIME, null)
                ),
                new FormField(
                    "confirmed",
                    FieldType.BOOLEAN,
                    "Confirmed",
                    false,
                    FieldConstraints.none(),
                    DefaultValue.literal(true)
                ),
                new FormField(
                    "category",
                    FieldType.SELECT,
                    "Category",
                    false,
                    FieldConstraints.selection(0, false),
                    DefaultValue.literal("A"),
                    options
                ),
                new FormField(
                    "tags",
                    FieldType.SELECT,
                    "Tags",
                    false,
                    FieldConstraints.selection(0, true),
                    DefaultValue.literal(List.of("A")),
                    options
                )
            )
        );
    }

    private static List<SelectOption> selectOptions(String first, String second) {
        return List.of(
            new SelectOption(first, "Option " + first, false),
            new SelectOption(second, "Option " + second, false)
        );
    }
}
