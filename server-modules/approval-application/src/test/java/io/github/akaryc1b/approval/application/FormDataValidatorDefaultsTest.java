package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.FormDefinition.SelectOption;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormDataValidatorDefaultsTest {

    private final FormDataValidator validator = new FormDataValidator();
    private final FormDefinition form = new FormDefinition("1.0", "opening-test", 1, "Opening", List.of(
        new FormField("amount", FieldType.MONEY, "Amount", true,
            FieldConstraints.money(2, new BigDecimal("0.01"))),
        new FormField("supplier", FieldType.TEXT, "Supplier", true, FieldConstraints.text(20)),
        new FormField("attachments", FieldType.ATTACHMENT, "Documents", true,
            FieldConstraints.attachments(2, true))
    ));

    @Test
    void opensAnEmptyFormWithoutInventingRequiredValuesOrAttachments() {
        var result = validator.validateDefaults(form, Map.of(), Map.of());
        assertTrue(result.values().isEmpty());
        assertTrue(result.attachmentIds().isEmpty());
        assertEquals(result, validator.validateDefaults(form, null, null));
        assertEquals(3, form.fields().size());
        assertTrue(form.fields().stream().allMatch(FormField::required));
        assertEquals(2, form.fields().get(2).constraints().minItems());
    }

    @Test
    void normalizesOnlyPresentDefaultsWithoutValidatingAbsentRequiredFields() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("amount", "12.50");
        input.put("supplier", "  Supplies  ");
        var result = validator.validateDefaults(form, input, Map.of());
        assertEquals(Map.of("amount", new BigDecimal("12.5"), "supplier", "Supplies"), result.values());
        assertTrue(result.attachmentIds().isEmpty());
        assertEquals("  Supplies  ", input.get("supplier"));
        input.clear();
        assertEquals(2, result.values().size());
        assertThrows(UnsupportedOperationException.class, () -> result.values().put("amount", 0));
    }

    @Test
    void rejectsMalformedPresentDefaultsInsteadOfSkippingValidation() {
        for (Map<String, Object> input : List.<Map<String, Object>>of(
            Map.of("amount", "bad"), Map.of("amount", "12.501"), Map.of("amount", "0"),
            Map.of("supplier", 42), Map.of("supplier", "x".repeat(21)),
            Map.of("attachments", List.of("not-a-uuid")), Map.of("unknown", "value")
        )) {
            assertThrows(FormDataValidator.FormDataValidationException.class,
                () -> validator.validateDefaults(form, input, Map.of()));
        }
    }

    @Test
    void keepsCardinalityConstraintsForExplicitDefaultsEvenWhenNotRequired() {
        for (List<String> ids : List.of(List.<String>of(),
            List.of("12345678-1234-4234-8234-123456789abc"))) {
            assertThrows(FormDataValidator.FormDataValidationException.class,
                () -> validator.validateDefaults(form, Map.of("attachments", ids), Map.of("attachments", false)));
        }
        List<String> ids = List.of("12345678-1234-4234-8234-123456789abc",
            "12345678-1234-4234-8234-123456789abd");
        var result = validator.validateDefaults(form, Map.of("attachments", ids), Map.of());
        assertEquals(ids, result.values().get("attachments"));
        assertEquals(2, result.attachmentIds().size());
    }

    @Test
    void preservesRequiredOverridesForPresentBlankDefaults() {
        assertThrows(FormDataValidator.FormDataValidationException.class,
            () -> validator.validateDefaults(form, Map.of("supplier", " "), Map.of()));
        assertTrue(validator.validateDefaults(form, Map.of("supplier", " "),
            Map.of("supplier", false)).values().isEmpty());
        assertThrows(FormDataValidator.FormDataValidationException.class,
            () -> validator.validateDefaults(form, Map.of("supplier", " "), Map.of("supplier", true)));
    }

    @Test
    void preservesDateBooleanAndSelectValidationForPresentDefaults() {
        FormDefinition typed = new FormDefinition("1.0", "typed-opening", 1, "Typed", List.of(
            new FormField("date", FieldType.DATE, "Date", true, FieldConstraints.none()),
            new FormField("confirmed", FieldType.BOOLEAN, "Confirmed", true, FieldConstraints.none()),
            new FormField("category", FieldType.SELECT, "Category", true, FieldConstraints.none(),
                FormDefinition.DefaultValue.none(), List.of(
                    new SelectOption("A", "Enabled", false), new SelectOption("B", "Disabled", true)))
        ));
        assertEquals(Map.of("date", "2026-09-11"),
            validator.validateDefaults(typed, Map.of("date", "2026-09-11"), Map.of()).values());
        for (Map<String, Object> input : List.<Map<String, Object>>of(
            Map.of("date", "bad-date"), Map.of("confirmed", "true"), Map.of("category", "B")
        )) {
            assertThrows(FormDataValidator.FormDataValidationException.class,
                () -> validator.validateDefaults(typed, input, Map.of()));
        }
    }

    @Test
    void openingDoesNotWeakenCompleteSubmissionValidation() {
        validator.validateDefaults(form, Map.of(), Map.of());
        Map<String, Object> complete = Map.of("amount", "12.50", "supplier", "Supplies",
            "attachments", List.of("12345678-1234-4234-8234-123456789abc",
                "12345678-1234-4234-8234-123456789abd"));
        for (String key : complete.keySet()) {
            Map<String, Object> incomplete = new LinkedHashMap<>(complete);
            incomplete.remove(key);
            assertThrows(FormDataValidator.FormDataValidationException.class,
                () -> validator.validate(form, incomplete));
        }
        assertEquals(3, validator.validate(form, complete).values().size());
        assertThrows(FormDataValidator.FormDataValidationException.class,
            () -> validator.validate(form, Map.of()));
    }
}
