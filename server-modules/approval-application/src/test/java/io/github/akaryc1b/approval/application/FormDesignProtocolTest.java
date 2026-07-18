package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.RequiredOverride;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormDesignProtocolTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:34:56Z");

    @Test
    void defaultsAndRequiredOverridesChangeHashesOnlyWhenConfigured() {
        FormDefinition legacy = form(DefaultValue.none());
        FormDefinition explicitNone = form(new DefaultValue(
            FormDefinition.DefaultValueType.NONE,
            null
        ));
        FormDefinition currentUser = form(new DefaultValue(
            FormDefinition.DefaultValueType.CURRENT_USER,
            null
        ));
        FormSchemaHasher formHasher = new FormSchemaHasher();
        assertEquals(formHasher.hash(legacy), formHasher.hash(explicitNone));
        assertNotEquals(formHasher.hash(legacy), formHasher.hash(currentUser));

        UiSchemaDefinition inherited = ui(RequiredOverride.INHERIT, FieldAccess.EDITABLE);
        UiSchemaDefinition explicitInherited = ui(null, FieldAccess.EDITABLE);
        UiSchemaDefinition required = ui(RequiredOverride.REQUIRED, FieldAccess.EDITABLE);
        UiSchemaHasher uiHasher = new UiSchemaHasher();
        assertEquals(uiHasher.hash(inherited), uiHasher.hash(explicitInherited));
        assertNotEquals(uiHasher.hash(inherited), uiHasher.hash(required));
    }

    @Test
    void rejectsHiddenExplicitRequiredAndUnsafeStartRequirement() {
        FormDefinition form = form(DefaultValue.none());
        UiSchemaDefinitionValidator validator = new UiSchemaDefinitionValidator();
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(form, ui(RequiredOverride.REQUIRED, FieldAccess.HIDDEN))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(form, ui(RequiredOverride.REQUIRED, FieldAccess.READONLY))
        );

        FormDefinition defaulted = form(new DefaultValue(
            FormDefinition.DefaultValueType.CURRENT_USER,
            null
        ));
        validator.validate(defaulted, ui(RequiredOverride.REQUIRED, FieldAccess.READONLY));
    }

    @Test
    void resolvesDefaultsWithInjectedClockAndValidatesEffectiveRequired() {
        FormDefaultValueResolver resolver = new FormDefaultValueResolver(
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        FormDefinition definition = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "default-form",
            1,
            "Default form",
            List.of(
                text("owner", DefaultValueType.CURRENT_USER),
                text("date", DefaultValueType.CURRENT_DATE),
                text("time", DefaultValueType.CURRENT_DATETIME)
            )
        );
        Map<String, Object> values = resolver.resolve(definition, "user-1");
        assertEquals("user-1", values.get("owner"));
        assertEquals("2026-07-18", values.get("date"));
        assertEquals(NOW.toString(), values.get("time"));

        FormDataValidator dataValidator = new FormDataValidator();
        assertThrows(
            FormDataValidator.FormDataValidationException.class,
            () -> dataValidator.validate(definition, Map.of(), Map.of("owner", true))
        );
        assertTrue(dataValidator.validate(
            definition,
            values,
            Map.of("owner", true, "date", false, "time", false)
        ).values().containsKey("owner"));
    }

    private static FormDefinition form(DefaultValue defaultValue) {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "default-form",
            1,
            "Default form",
            List.of(new FormDefinition.FormField(
                "owner",
                FormDefinition.FieldType.TEXT,
                "Owner",
                false,
                FormDefinition.FieldConstraints.text(100),
                defaultValue
            ))
        );
    }

    private static FormDefinition.FormField text(String key, DefaultValueType type) {
        return new FormDefinition.FormField(
            key,
            FormDefinition.FieldType.TEXT,
            key,
            false,
            FormDefinition.FieldConstraints.text(100),
            new DefaultValue(type, null)
        );
    }

    private static UiSchemaDefinition ui(
        RequiredOverride requiredOverride,
        FieldAccess access
    ) {
        return new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            "default-form",
            1,
            1,
            "Default UI",
            List.of(new UiSchemaDefinition.Section(
                "main",
                "Main",
                null,
                false,
                List.of(new UiSchemaDefinition.FieldLayout("owner", null, null, 24))
            )),
            List.of(new UiSchemaDefinition.NodePermissions(
                UiSchemaDefinition.START_CONTEXT,
                List.of(new UiSchemaDefinition.FieldPermission(
                    "owner",
                    access,
                    requiredOverride
                ))
            ))
        );
    }

    private enum DefaultValueType {
        CURRENT_USER,
        CURRENT_DATE,
        CURRENT_DATETIME
    }
}
