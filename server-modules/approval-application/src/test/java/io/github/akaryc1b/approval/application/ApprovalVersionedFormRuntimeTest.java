package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real normalization and hashing; only immutable stores are strict test doubles. */
class ApprovalVersionedFormRuntimeTest {
    private static final String TENANT = "versioned-tenant";
    private static final String KEY = "purchase-payment";
    private static final String FORM_HASH = "a".repeat(64);
    private static final String UI_HASH = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Test
    void boundStartUsesExactPublishedUiEvenWhenNewerUiExists() {
        Fixture fixture = new Fixture(List.of(field("supplier", FieldType.TEXT, DefaultValue.literal("A"))), Map.of());
        var service = fixture.service();
        var runtime = service.startRuntime(TENANT, "employee", KEY, 2);
        assertEquals(2, runtime.uiSchema().version());
        assertEquals(UI_HASH, runtime.uiSchemaHash());
        var start = service.validateStartBound(TENANT, "employee", KEY, 2, Map.of(), release(TENANT, KEY, 2, FORM_HASH, UI_HASH));
        assertEquals(2, start.uiSchema().definition().version());
        assertEquals(Map.of("supplier", "A"), start.data().values());
        assertEquals(0, fixture.latestReads);
        assertEquals(2, fixture.pinnedReads);
    }

    @Test
    void boundStartRejectsWrongIdentityVersionHashesAndMissingUi() {
        Fixture fixture = new Fixture(List.of(field("supplier", FieldType.TEXT, DefaultValue.literal("A"))), Map.of());
        var service = fixture.service();
        for (ApprovalEffectiveRelease invalid : List.of(
            release("other", KEY, 2, FORM_HASH, UI_HASH), release(TENANT, "another", 2, FORM_HASH, UI_HASH),
            release(TENANT, KEY, 3, FORM_HASH, UI_HASH), release(TENANT, KEY, 2, "c".repeat(64), UI_HASH))) {
            assertThrows(IllegalArgumentException.class, () -> service.validateStartBound(TENANT, "employee", KEY, 2, Map.of(), invalid));
        }
        assertThrows(IllegalStateException.class, () -> service.validateStartBound(TENANT, "employee", KEY, 2, Map.of(),
            release(TENANT, KEY, 2, FORM_HASH, "c".repeat(64))));
        fixture.missingUi = true;
        assertThrows(IllegalStateException.class, () -> service.validateStartBound(TENANT, "employee", KEY, 2, Map.of(),
            release(TENANT, KEY, 2, FORM_HASH, UI_HASH)));
        assertEquals(0, fixture.latestReads);
    }

    @Test
    void replayDoesNotTurnPriorEditableOverrideIntoLiteralDefault() {
        Fixture fixture = new Fixture(List.of(field("supplier", FieldType.TEXT, DefaultValue.literal("A"))), Map.of());
        var service = fixture.service();
        var original = submission(Map.of("supplier", "B"), FORM_HASH, UI_HASH);
        var same = service.validateStartReplay("employee", original, Map.of("supplier", "B"));
        var omitted = service.validateStartReplay("employee", original, Map.of());
        assertEquals(Map.of("supplier", "B"), same.data().values());
        assertEquals(Map.of("supplier", "A"), omitted.data().values());
        assertEquals(hash(original.values()), hash(same.data().values()));
        assertNotEquals(hash(original.values()), hash(omitted.data().values()));
        assertEquals(0, fixture.latestReads);
    }

    @Test
    void replayFreezesOnlyNoneditableClockDefaultsAndPreservesEditableProvenanceBoundary() {
        var fields = List.of(
            field("readonlyDate", FieldType.DATE, new DefaultValue(DefaultValueType.CURRENT_DATE, null)),
            field("readonlyTime", FieldType.DATETIME, new DefaultValue(DefaultValueType.CURRENT_DATETIME, null)),
            field("editableTime", FieldType.DATETIME, new DefaultValue(DefaultValueType.CURRENT_DATETIME, null)),
            field("currentUser", FieldType.TEXT, new DefaultValue(DefaultValueType.CURRENT_USER, null)),
            field("literal", FieldType.TEXT, DefaultValue.literal("A")));
        Fixture fixture = new Fixture(fields, Map.of("readonlyDate", FieldAccess.READONLY, "readonlyTime", FieldAccess.READONLY));
        var previous = submission(Map.of("readonlyDate", "2026-10-25", "readonlyTime", "2026-10-25T10:00:00Z",
            "editableTime", "2026-10-25T12:00:00Z", "currentUser", "prior-override", "literal", "B"), FORM_HASH, UI_HASH);
        var actual = fixture.service().validateStartReplay("employee", previous, Map.of()).data().values();
        assertEquals("2026-10-25", actual.get("readonlyDate"));
        assertEquals("2026-10-25T10:00:00Z", actual.get("readonlyTime"));
        assertEquals(NOW.toString(), actual.get("editableTime"));
        assertEquals("employee", actual.get("currentUser"));
        assertEquals("A", actual.get("literal"));
        assertNotEquals(hash(previous.values()), hash(actual));
    }

    @Test
    void replayRejectsPinnedSchemaOrUiHashDriftRatherThanUsingLatest() {
        Fixture fixture = new Fixture(List.of(field("supplier", FieldType.TEXT, DefaultValue.literal("A"))), Map.of());
        var service = fixture.service();
        assertThrows(IllegalStateException.class, () -> service.validateStartReplay("employee",
            submission(Map.of(), "c".repeat(64), UI_HASH), Map.of()));
        assertThrows(IllegalStateException.class, () -> service.validateStartReplay("employee",
            submission(Map.of(), FORM_HASH, "c".repeat(64)), Map.of()));
        fixture.missingUi = true;
        assertThrows(IllegalStateException.class, () -> service.validateStartReplay("employee",
            submission(Map.of(), FORM_HASH, UI_HASH), Map.of()));
        assertEquals(0, fixture.latestReads);
    }

    private static FormField field(String key, FieldType type, DefaultValue value) {
        return new FormField(key, type, key, false, FormDefinition.FieldConstraints.none(), value);
    }
    private static String hash(Map<String, Object> values) {
        return new FormSubmissionHasher().hash(KEY, 2, FORM_HASH, "business", values, Map.of());
    }
    private static ApprovalFormSubmissionStore.FormSubmission submission(Map<String, Object> values, String formHash, String uiHash) {
        return new ApprovalFormSubmissionStore.FormSubmission(UUID.randomUUID(), TENANT, KEY, 2, formHash, 2, uiHash,
            "business", values, Map.of(), UUID.randomUUID(), "employee", NOW.minusSeconds(86400), hash(values));
    }
    private static ApprovalEffectiveRelease release(String tenant, String key, int version, String formHash, String uiHash) {
        String hash = "d".repeat(64);
        return new ApprovalEffectiveRelease(tenant, key, 2, 1, hash, 2, hash, 2, hash, version, formHash, 2, uiHash,
            "compiler", hash, hash, hash, "deployment-2", "definition-2", 2, ApprovalEffectiveRelease.Status.ACTIVE,
            2, "activator", NOW, "isolated test activation", "request", "trace");
    }
    private static final class Fixture {
        private final FormDefinition form;
        private final UiSchemaDefinition ui;
        private int latestReads;
        private int pinnedReads;
        private boolean missingUi;
        Fixture(List<FormField> fields, Map<String, FieldAccess> access) {
            form = new FormDefinition("1.0", KEY, 2, "Versioned purchase", fields);
            ui = new UiSchemaDefinition("1.0", KEY, 2, 2, "Bound layout",
                List.of(new UiSchemaDefinition.Section("main", "Main", null, false,
                    fields.stream().map(field -> new UiSchemaDefinition.FieldLayout(field.key(), null, null, 24)).toList())),
                List.of(new UiSchemaDefinition.NodePermissions("$start", fields.stream()
                    .map(field -> new UiSchemaDefinition.FieldPermission(field.key(), access.getOrDefault(field.key(), FieldAccess.EDITABLE))).toList())));
        }
        ApprovalFormRuntimeService service() {
            return new ApprovalFormRuntimeService(
                port(ApprovalFormStore.class, (method, args) -> {
                    assertEquals("find", method); assertEquals(TENANT, args[0]); assertEquals(KEY, args[1]); assertEquals(2, args[2]);
                    return Optional.of(new ApprovalFormStore.PublishedForm(TENANT, form, FORM_HASH, "publisher", NOW));
                }),
                port(ApprovalUiSchemaStore.class, (method, args) -> {
                    if (method.equals("findLatest")) {
                        latestReads++;
                        var latest = new UiSchemaDefinition("1.0", KEY, 2, 99, "Unbound layout", ui.sections(), ui.nodePermissions());
                        return Optional.of(new ApprovalUiSchemaStore.PublishedUiSchema(TENANT, latest, "e".repeat(64), "publisher", NOW));
                    }
                    assertEquals("find", method); assertEquals(TENANT, args[0]); assertEquals(KEY, args[1]);
                    assertEquals(2, args[2]); assertEquals(2, args[3]); pinnedReads++;
                    return missingUi ? Optional.empty() : Optional.of(new ApprovalUiSchemaStore.PublishedUiSchema(TENANT, ui, UI_HASH, "publisher", NOW));
                }), unexpected(ApprovalFormSubmissionStore.class), unexpected(ApprovalProjectionStore.class), unexpected(ApprovalAttachmentStore.class),
                new FormDataValidator(), new FormSubmissionHasher(), new FormDefaultValueResolver(Clock.fixed(NOW, ZoneOffset.UTC)), UUID::randomUUID,
                port(ApprovalEffectiveReleaseStore.class, (method, args) -> {
                    assertEquals("find", method); assertEquals(TENANT, args[0]); assertEquals(KEY, args[1]);
                    return Optional.of(release(TENANT, KEY, 2, FORM_HASH, UI_HASH));
                }));
        }
    }
    private static <T> T unexpected(Class<T> type) {
        return port(type, (method, args) -> { throw new AssertionError("unexpected " + type.getSimpleName() + "." + method); });
    }
    private static <T> T port(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> handler.apply(method.getName(), args)));
    }
}
