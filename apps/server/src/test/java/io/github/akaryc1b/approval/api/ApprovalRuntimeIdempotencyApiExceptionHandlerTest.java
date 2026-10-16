package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalAttachmentService;
import io.github.akaryc1b.approval.application.ApprovalFormService;
import io.github.akaryc1b.approval.application.ApprovalMessageService;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard.IdempotencyConflictException;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalRuntimeIdempotencyApiExceptionHandlerTest {

    private static final IdempotencyConflictException CONFLICT = new IdempotencyConflictException(
        "must not disclose stored actor, request hash or cached private result"
    );

    @Test
    void attachmentUploadConflictReturnsNonRetryable409() throws Exception {
        var service = mock(ApprovalAttachmentService.class);
        doThrow(CONFLICT).when(service).upload(any());
        MockMvc mvc = mvc(new ApprovalAttachmentController(service));
        assertConflict(mvc, multipart("/api/approval/attachments").file(new MockMultipartFile(
            "file", "synthetic.txt", "text/plain", "synthetic".getBytes(StandardCharsets.UTF_8)
        )));
    }

    @Test
    void copyAndUrgeConflictsReturnNonRetryable409() throws Exception {
        var service = mock(ApprovalMessageService.class);
        doThrow(CONFLICT).when(service).copy(any());
        doThrow(CONFLICT).when(service).urge(any());
        MockMvc mvc = mvc(new ApprovalMessageController(service));
        for (String operation : List.of("copy", "urge")) {
            assertConflict(mvc, post("/api/approval/instances/{id}/" + operation, UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(operation.equals("copy") ? "{\"recipientIds\":[\"recipient\"]}" : "{}"));
        }
    }

    @Test
    void legacyFormPublicationConflictReturnsNonRetryable409() throws Exception {
        var service = mock(ApprovalFormService.class);
        doThrow(CONFLICT).when(service).publish(any());
        assertConflict(mvc(new ApprovalFormController(service)), post("/api/approval/forms/publish")
            .contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsBytes(PurchasePaymentTemplate.formDefinition())));
    }

    @Test
    void legacyUiSchemaPublicationConflictReturnsNonRetryable409() throws Exception {
        var service = mock(ApprovalUiSchemaService.class);
        doThrow(CONFLICT).when(service).publish(any());
        assertConflict(mvc(new ApprovalUiSchemaController(service)), post("/api/approval/ui-schemas/publish")
            .contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsBytes(PurchasePaymentTemplate.uiSchemaDefinition())));
    }

    @Test
    void adviceScopePreservesExistingSpecializedConflictContracts() {
        var annotation = ApprovalRuntimeIdempotencyApiExceptionHandler.class.getAnnotation(RestControllerAdvice.class);
        assertEquals(Set.of(ApprovalAttachmentController.class, ApprovalMessageController.class,
            ApprovalFormController.class, ApprovalUiSchemaController.class), Set.of(annotation.assignableTypes()));
        var existing = new ApprovalDelegationApiExceptionHandler().conflict(CONFLICT, new MockHttpServletRequest());
        assertEquals(409, existing.getStatusCode().value());
        assertEquals("APPROVAL_DELEGATION_CONFLICT", existing.getBody().code());
    }

    private MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApprovalRuntimeIdempotencyApiExceptionHandler()).build();
    }

    private void assertConflict(MockMvc mvc, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        mvc.perform(request.header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "operator")
                .header("X-Request-Id", "idempotency-test").header("Idempotency-Key", "same-key"))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Request-Id", "idempotency-test"))
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
            .andExpect(jsonPath("$.message").value("idempotency key conflicts with the current command"))
            .andExpect(jsonPath("$.retryable").value(false))
            .andExpect(jsonPath("$.requestId").value("idempotency-test"));
    }
}
