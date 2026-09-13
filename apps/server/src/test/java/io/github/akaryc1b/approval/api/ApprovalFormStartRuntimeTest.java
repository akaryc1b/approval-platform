package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.FormSubmissionHasher;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real controller/runtime/validation; storage ports are test doubles, not database acceptance. */
class ApprovalFormStartRuntimeTest {

    private static final String TENANT = "runtime-opening-test";
    private static final String ACTOR = "test-applicant";
    private final ApprovalFormStore forms = mock(ApprovalFormStore.class);
    private final ApprovalUiSchemaStore schemas = mock(ApprovalUiSchemaStore.class);
    private final ApprovalFormSubmissionStore submissions = mock(ApprovalFormSubmissionStore.class);
    private final ApprovalProjectionStore projections = mock(ApprovalProjectionStore.class);
    private final ApprovalAttachmentStore attachments = mock(ApprovalAttachmentStore.class);
    private final ApprovalFormRuntimeService service = new ApprovalFormRuntimeService(
        forms, schemas, submissions, projections, attachments, new FormDataValidator(),
        new FormSubmissionHasher(), UUID::randomUUID
    );

    private void published(boolean withUiSchema) {
        Instant time = Instant.parse("2026-09-11T00:00:00Z");
        when(forms.find(TENANT, "purchase-payment", 1)).thenReturn(Optional.of(new PublishedForm(
            TENANT, PurchasePaymentTemplate.formDefinition(), "form-hash", ACTOR, time
        )));
        when(schemas.findLatest(TENANT, "purchase-payment", 1)).thenReturn(withUiSchema
            ? Optional.of(new PublishedUiSchema(TENANT, PurchasePaymentTemplate.uiSchemaDefinition(),
                "ui-hash", ACTOR, time))
            : Optional.empty());
    }

    @Test
    void actualStartRuntimeEndpointOpensUnfilledCanonicalPurchaseForm() throws Exception {
        published(true);
        var mvc = MockMvcBuilders.standaloneSetup(new ApprovalFormRuntimeController(service)).build();
        mvc.perform(get("/api/approval/forms/purchase-payment/versions/1/runtime")
                .header("X-Tenant-Id", TENANT).header("X-Operator-Id", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.definition.formKey").value("purchase-payment"))
            .andExpect(jsonPath("$.definition.fields.length()").value(4))
            .andExpect(jsonPath("$.values").isEmpty())
            .andExpect(jsonPath("$.requiredFields.amount").value(true))
            .andExpect(jsonPath("$.requiredFields.attachments").value(true))
            .andExpect(jsonPath("$.fieldPermissions.amount").value("EDITABLE"))
            .andExpect(jsonPath("$.fieldPermissions.attachments").value("EDITABLE"))
            .andExpect(jsonPath("$.defaultedUiSchema").value(false));
        verifyNoInteractions(submissions, projections, attachments);
    }

    @Test
    void unfilledDefaultLayoutKeepsOriginalSchemaAndRequiredMetadata() {
        published(false);
        var runtime = service.startRuntime(TENANT, ACTOR, "purchase-payment", 1);
        assertEquals(PurchasePaymentTemplate.formDefinition(), runtime.definition());
        assertTrue(runtime.defaultedUiSchema());
        assertTrue(runtime.values().isEmpty());
        assertTrue(runtime.requiredFields().values().stream().allMatch(Boolean::booleanValue));
        assertEquals(4, runtime.requiredFields().size());
        verifyNoInteractions(submissions, projections, attachments);
    }

    @Test
    void startValidationStillRejectsEveryMissingRequiredValueAfterOpening() {
        published(true);
        service.startRuntime(TENANT, ACTOR, "purchase-payment", 1);
        Map<String, Object> complete = Map.of(
            "amount", "12500.00", "supplier", "Test supplies", "purchaseOrderReference", "PO-TEST-1",
            "attachments", List.of("12345678-1234-4234-8234-123456789abc")
        );
        for (String key : complete.keySet()) {
            Map<String, Object> incomplete = new LinkedHashMap<>(complete);
            incomplete.remove(key);
            assertThrows(FormDataValidator.FormDataValidationException.class,
                () -> service.validateStart(TENANT, ACTOR, "purchase-payment", 1, incomplete));
        }
        var plan = service.validateStart(TENANT, ACTOR, "purchase-payment", 1, complete);
        assertEquals(4, plan.data().values().size());
        assertEquals(1, plan.data().attachmentIds().size());
        verifyNoInteractions(submissions, projections, attachments);
    }
}
