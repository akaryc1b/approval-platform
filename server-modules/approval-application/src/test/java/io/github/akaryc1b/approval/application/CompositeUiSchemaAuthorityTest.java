package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeUiSchemaAuthorityTest {

    @Test
    void readonlySummaryIsServerAuthoritativeForRuntimePermissions() {
        FormDefinition form = form(false);
        UiSchemaDefinition schema = schema(form, true, false);

        ApprovalFormRuntimeService.RuntimePermissions permissions =
            ApprovalFormRuntimeService.permissions(
                form,
                new ApprovalFormRuntimeService.UiSelection(schema, "hash", false),
                UiSchemaDefinition.START_CONTEXT
            );

        assertEquals(FieldAccess.READONLY, permissions.fieldAccess().get("owner"));
        assertEquals(FieldAccess.HIDDEN, permissions.fieldAccess().get("secret"));
        assertEquals(FieldAccess.READONLY, permissions.fieldAccess().get("childValue"));
        assertThrows(
            ApprovalFormRuntimeService.FieldPermissionException.class,
            () -> ApprovalFormRuntimeService.rejectNonEditable(
                Map.of("owner", "changed"),
                permissions.fieldAccess(),
                Set.of()
            )
        );
    }

    @Test
    void requiredEditableFieldCannotBecomeReadonlyThroughItsSection() {
        FormDefinition form = form(true);
        UiSchemaDefinition schema = schema(form, true, false);

        assertThrows(
            IllegalArgumentException.class,
            () -> new UiSchemaDefinitionValidator().validate(form, schema)
        );
    }

    @Test
    void sectionAccessOnlyReducesExistingFieldPermissions() {
        FormDefinition form = form(false);
        UiSchemaDefinition schema = schema(form, true, false);
        Map<String, FieldAccess> effective = UiSchemaDefinitionValidator.applySectionAccess(
            schema,
            Map.of(
                "owner", FieldAccess.EDITABLE,
                "secret", FieldAccess.HIDDEN,
                "childValue", FieldAccess.READONLY
            )
        );

        assertEquals(FieldAccess.READONLY, effective.get("owner"));
        assertEquals(FieldAccess.HIDDEN, effective.get("secret"));
        assertEquals(FieldAccess.READONLY, effective.get("childValue"));
    }

    private static FormDefinition form(boolean requiredOwner) {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "authority-form",
            1,
            "Authority form",
            List.of(
                field("owner", requiredOwner),
                field("secret", false),
                field("childValue", false)
            )
        );
    }

    private static FormDefinition.FormField field(String key, boolean required) {
        return new FormDefinition.FormField(
            key,
            FormDefinition.FieldType.TEXT,
            key,
            required,
            FormDefinition.FieldConstraints.text(100)
        );
    }

    private static UiSchemaDefinition schema(
        FormDefinition form,
        boolean readonlyRoot,
        boolean readonlyChild
    ) {
        Section child = new Section(
            "child",
            "Child",
            null,
            false,
            List.of(new UiSchemaDefinition.FieldLayout("childValue", null, null, 24)),
            0,
            1,
            true,
            UiSchemaDefinition.SectionVisibility.always(),
            readonlyChild,
            List.of()
        );
        Section root = new Section(
            "root",
            "Root",
            null,
            false,
            List.of(
                new UiSchemaDefinition.FieldLayout("owner", null, null, 24),
                new UiSchemaDefinition.FieldLayout("secret", null, null, 24)
            ),
            0,
            1,
            true,
            UiSchemaDefinition.SectionVisibility.always(),
            readonlyRoot,
            List.of(child)
        );
        return new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            form.formKey(),
            form.version(),
            1,
            "Authority UI",
            List.of(root),
            List.of(new UiSchemaDefinition.NodePermissions(
                UiSchemaDefinition.START_CONTEXT,
                List.of(
                    new UiSchemaDefinition.FieldPermission("owner", FieldAccess.EDITABLE),
                    new UiSchemaDefinition.FieldPermission("secret", FieldAccess.HIDDEN),
                    new UiSchemaDefinition.FieldPermission("childValue", FieldAccess.EDITABLE)
                )
            ))
        );
    }
}
