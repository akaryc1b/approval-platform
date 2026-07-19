#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected D8 authority block not found in {path}: {old[:100]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

validator = Path(
    'server-modules/approval-application/src/main/java/'
    'io/github/akaryc1b/approval/application/UiSchemaDefinitionValidator.java'
)
replace_once(
    validator,
    '''        validateLayout(uiSchema, formFields, fieldsByKey);
        validatePermissions(uiSchema, formFields, fieldsByKey);''',
    '''        validateLayout(uiSchema, formFields, fieldsByKey);
        Set<String> readonlySummaryFields = readonlySummaryFields(uiSchema);
        validatePermissions(uiSchema, formFields, fieldsByKey, readonlySummaryFields);''',
)
replace_once(
    validator,
    '''    private static void validatePermissions(
        UiSchemaDefinition uiSchema,
        Set<String> formFields,
        Map<String, FormField> fieldsByKey
    ) {''',
    '''    private static void validatePermissions(
        UiSchemaDefinition uiSchema,
        Set<String> formFields,
        Map<String, FormField> fieldsByKey,
        Set<String> readonlySummaryFields
    ) {''',
)
replace_once(
    validator,
    '''                    fieldsByKey.get(permission.fieldKey()),
                    permission
                );''',
    '''                    fieldsByKey.get(permission.fieldKey()),
                    permission,
                    readonlySummaryFields.contains(permission.fieldKey())
                );''',
)
replace_once(
    validator,
    '''        FormField field,
        FieldPermission permission
    ) {
        if (permission.access() == FieldAccess.HIDDEN''',
    '''        FormField field,
        FieldPermission permission,
        boolean readonlySummary
    ) {
        FieldAccess effectiveAccess = effectiveAccess(permission.access(), readonlySummary);
        if (effectiveAccess == FieldAccess.HIDDEN''',
)
replace_once(
    validator,
    '''        boolean editable = permission.access() == FieldAccess.EDITABLE;''',
    '''        boolean editable = effectiveAccess == FieldAccess.EDITABLE;''',
)
marker = '''    public static boolean effectiveRequired(boolean baseRequired, RequiredOverride override) {'''
addition = '''    public static Map<String, FieldAccess> applySectionAccess(
        UiSchemaDefinition uiSchema,
        Map<String, FieldAccess> fieldAccess
    ) {
        Objects.requireNonNull(uiSchema, "uiSchema must not be null");
        Objects.requireNonNull(fieldAccess, "fieldAccess must not be null");
        Set<String> readonlyFields = readonlySummaryFields(uiSchema);
        Map<String, FieldAccess> effective = new java.util.LinkedHashMap<>();
        fieldAccess.forEach((fieldKey, access) -> effective.put(
            fieldKey,
            effectiveAccess(access, readonlyFields.contains(fieldKey))
        ));
        return Map.copyOf(effective);
    }

    public static Set<String> readonlySummaryFields(UiSchemaDefinition uiSchema) {
        Objects.requireNonNull(uiSchema, "uiSchema must not be null");
        Set<String> fields = new LinkedHashSet<>();
        collectReadonlySummaryFields(uiSchema.sections(), false, fields);
        return Set.copyOf(fields);
    }

    private static void collectReadonlySummaryFields(
        List<Section> sections,
        boolean inheritedReadonly,
        Set<String> fields
    ) {
        for (Section section : sections) {
            boolean readonly = inheritedReadonly || section.readonlySummary();
            if (readonly) {
                section.fields().forEach(layout -> fields.add(layout.fieldKey()));
            }
            collectReadonlySummaryFields(section.children(), readonly, fields);
        }
    }

''' + marker
replace_once(validator, marker, addition)

runtime = Path(
    'server-modules/approval-application/src/main/java/'
    'io/github/akaryc1b/approval/application/ApprovalFormRuntimeService.java'
)
replace_once(
    runtime,
    '''    private static RuntimePermissions permissions(
        FormDefinition form,''',
    '''    static RuntimePermissions permissions(
        FormDefinition form,''',
)
replace_once(
    runtime,
    '''        return new RuntimePermissions(Map.copyOf(access), Map.copyOf(required));''',
    '''        Map<String, FieldAccess> effectiveAccess = UiSchemaDefinitionValidator.applySectionAccess(
            selection.definition(),
            access
        );
        return new RuntimePermissions(effectiveAccess, Map.copyOf(required));''',
)

design = Path(
    'server-modules/approval-application/src/main/java/'
    'io/github/akaryc1b/approval/application/ApprovalFormDesignService.java'
)
replace_once(
    design,
    '''        Map<String, Object> values = defaultValues.resolve(
            draft.formDefinition(),''',
    '''        access = new LinkedHashMap<>(UiSchemaDefinitionValidator.applySectionAccess(
            draft.uiSchemaDefinition(),
            access
        ));
        Map<String, Object> values = defaultValues.resolve(
            draft.formDefinition(),''',
)
replace_once(
    design,
    '''            draft.uiSchemaDefinition().sections().size(),''',
    '''            sectionCount(draft.uiSchemaDefinition().sections()),''',
)
marker = '''    private void appendAudit('''
addition = '''    private static int sectionCount(List<UiSchemaDefinition.Section> sections) {
        return sections.stream()
            .mapToInt(section -> 1 + sectionCount(section.children()))
            .sum();
    }

''' + marker
replace_once(design, marker, addition)

jdbc = Path(
    'server-modules/approval-persistence-jdbc/src/test/java/'
    'io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalFormDesignIntegrationTest.java'
)
replace_once(
    jdbc,
    '''        assertTrue(validation.valid());
    }

    @Test
    void validatesAndPublishesIdempotentImmutablePackage() {''',
    '''        assertTrue(validation.valid());
        assertEquals(2, validation.sectionCount());
    }

    @Test
    void validatesAndPublishesIdempotentImmutablePackage() {''',
)
PY

cat > server-modules/approval-application/src/test/java/io/github/akaryc1b/approval/application/CompositeUiSchemaAuthorityTest.java <<'JAVA'
package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeUiSchemaAuthorityTest {

    @Test
    void readonlySummaryIsServerAuthoritativeForRuntimePermissions() {
        FormDefinition form = form(false);
        UiSchemaDefinition schema = schema(form, false, true);

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
            () -> invokeRejectNonEditable(permissions.fieldAccess())
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
        UiSchemaDefinition schema = schema(form, false, true);
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

    private static void invokeRejectNonEditable(Map<String, FieldAccess> access) {
        if (access.get("owner") != FieldAccess.EDITABLE) {
            throw new ApprovalFormRuntimeService.FieldPermissionException(
                "fields are not editable in the current context: owner"
            );
        }
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
JAVA

rm -f .github/scripts/apply-pr53-d8-authority.sh
