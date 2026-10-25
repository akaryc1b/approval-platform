package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalFormStartRuntimeTest {

    @Test
    void emptyPurchaseFormOpensWithoutRelaxingSubmissionRequirements() {
        FormDefinition form = PurchasePaymentTemplate.formDefinition();
        ApprovalFormRuntimeService service = service(form);
        var runtime = service.startRuntime("demo", "employee", form.formKey(), form.version());
        assertEquals(form, runtime.definition());
        assertEquals(Map.of(), runtime.values());
        assertEquals(4, runtime.requiredFields().size());
        assertTrue(runtime.requiredFields().values().stream().allMatch(Boolean.TRUE::equals));
        assertThrows(FormDataValidator.FormDataValidationException.class,
            () -> service.validateStart("demo", "employee", form.formKey(), form.version(), Map.of()));
        assertThrows(FormDataValidator.FormDataValidationException.class,
            () -> service.validateStart("demo", "employee", form.formKey(), form.version(), Map.of(
                "amount", "10.00", "supplier", "Supplier", "purchaseOrderReference", "ORDER",
                "attachments", java.util.List.of()
            )));
    }

    @Test
    void presentDefaultsAreNormalizedButMissingRequiredFieldsRemainEmpty() {
        FormDefinition form = withDefault("amount", "12.30");
        var runtime = service(form).startRuntime("demo", "employee", form.formKey(), form.version());
        assertEquals(Map.of("amount", new BigDecimal("12.3")), runtime.values());
        assertEquals(form, runtime.definition());
        assertEquals(Boolean.TRUE, runtime.requiredFields().get("attachments"));
    }

    @Test
    void invalidPresentDefaultsStillFailIncludingAttachmentMinimum() {
        for (FormDefinition form : java.util.List.of(
            withDefault("amount", "not-a-number"),
            withDefault("amount", "0.00"),
            withDefault("attachments", java.util.List.of())
        )) {
            assertThrows(FormDataValidator.FormDataValidationException.class,
                () -> service(form).startRuntime("demo", "employee", form.formKey(), form.version()));
        }
    }

    private static FormDefinition withDefault(String key, Object value) {
        FormDefinition form = PurchasePaymentTemplate.formDefinition();
        return new FormDefinition(form.schemaVersion(), form.formKey(), form.version(), form.name(),
            form.fields().stream().map(field -> field.key().equals(key)
                ? new FormField(field.key(), field.type(), field.label(), field.required(),
                    field.constraints(), DefaultValue.literal(value), field.options())
                : field).toList());
    }

    private static ApprovalFormRuntimeService service(FormDefinition form) {
        var published = new ApprovalFormStore.PublishedForm("demo", form, "hash", "publisher", Instant.EPOCH);
        return new ApprovalFormRuntimeService(
            port(ApprovalFormStore.class, method -> method.equals("find") ? Optional.of(published) : null),
            port(ApprovalUiSchemaStore.class, method -> method.equals("findLatest") ? Optional.empty() : null),
            port(ApprovalFormSubmissionStore.class, method -> null),
            port(ApprovalProjectionStore.class, method -> null),
            port(ApprovalAttachmentStore.class, method -> null),
            new FormDataValidator(), new FormSubmissionHasher(), UUID::randomUUID
        );
    }

    private static <T> T port(Class<T> type, Function<String, Object> read) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, arguments) -> {
                Object result = read.apply(method.getName());
                if (result == null) {
                    throw new AssertionError("unexpected store call: " + method.getName());
                }
                return result;
            }));
    }
}
