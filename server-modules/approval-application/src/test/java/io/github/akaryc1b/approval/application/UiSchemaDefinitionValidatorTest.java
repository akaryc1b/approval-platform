package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UiSchemaDefinitionValidatorTest {

    private final UiSchemaDefinitionValidator validator = new UiSchemaDefinitionValidator();

    @Test
    void purchasePaymentUiSchemaIsValidAndHashesDeterministically() {
        var form = PurchasePaymentTemplate.formDefinition();
        var schema = PurchasePaymentTemplate.uiSchemaDefinition();

        assertDoesNotThrow(() -> validator.validate(form, schema));
        UiSchemaHasher hasher = new UiSchemaHasher();
        assertEquals(hasher.hash(schema), hasher.hash(schema));
    }

    @Test
    void requiredStartFieldCannotBeReadonly() {
        var original = PurchasePaymentTemplate.uiSchemaDefinition();
        List<UiSchemaDefinition.NodePermissions> contexts = new ArrayList<>(
            original.nodePermissions()
        );
        UiSchemaDefinition.NodePermissions start = contexts.get(0);
        List<UiSchemaDefinition.FieldPermission> fields = new ArrayList<>(start.fields());
        fields.set(0, new UiSchemaDefinition.FieldPermission(
            "amount",
            UiSchemaDefinition.FieldAccess.READONLY
        ));
        contexts.set(0, new UiSchemaDefinition.NodePermissions(start.contextKey(), fields));
        var invalid = new UiSchemaDefinition(
            original.schemaVersion(),
            original.formKey(),
            original.formVersion(),
            original.version(),
            original.name(),
            original.sections(),
            contexts
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(PurchasePaymentTemplate.formDefinition(), invalid)
        );
    }

    @Test
    void everyFieldMustAppearExactlyOnceInLayout() {
        var original = PurchasePaymentTemplate.uiSchemaDefinition();
        var first = original.sections().get(0);
        List<UiSchemaDefinition.FieldLayout> fields = new ArrayList<>(first.fields());
        fields.remove(0);
        List<UiSchemaDefinition.Section> sections = new ArrayList<>(original.sections());
        sections.set(0, new UiSchemaDefinition.Section(
            first.key(),
            first.title(),
            first.helpText(),
            first.collapsed(),
            fields
        ));
        var invalid = new UiSchemaDefinition(
            original.schemaVersion(),
            original.formKey(),
            original.formVersion(),
            original.version(),
            original.name(),
            sections,
            original.nodePermissions()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(PurchasePaymentTemplate.formDefinition(), invalid)
        );
    }
}
