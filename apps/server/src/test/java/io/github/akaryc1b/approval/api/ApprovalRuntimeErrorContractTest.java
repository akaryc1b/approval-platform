package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.ApprovalParticipationQueryService;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.PurchasePaymentCollaborationService;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalRuntimeErrorContractTest {

    @Test
    void nonEditableFieldsAndInvalidFormValuesReturn400WithoutWeakeningPermissionChecks() throws Exception {
        PurchasePaymentTaskActionService actions = mock(PurchasePaymentTaskActionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PurchasePaymentController(null, actions, null, null))
            .setControllerAdvice(new ApprovalApiExceptionHandler()).build();
        for (RuntimeException failure : List.of(
            new ApprovalFormRuntimeService.FieldPermissionException("fields are not editable in the current context: amount"),
            new FormDataValidator.FormDataValidationException("amount must be at least 0.01")
        )) {
            doThrow(failure).when(actions).resubmit(any());
            mvc.perform(post("/api/approval/tasks/{id}/resubmit", UUID.randomUUID())
                    .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "initiator")
                    .header("X-Request-Id", "error-test").header("Idempotency-Key", "error-key")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"amount\":-1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_DATA_INVALID"))
                .andExpect(jsonPath("$.message").value(failure.getMessage()))
                .andExpect(jsonPath("$.retryable").value(false));
        }
    }

    @Test
    void withdrawalConflictsReturn409AndKeepBusinessRejection() throws Exception {
        PurchasePaymentCollaborationService service = mock(PurchasePaymentCollaborationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PurchasePaymentCollaborationController(service))
            .setControllerAdvice(new ApprovalApiExceptionHandler()).build();
        for (String message : List.of("only the process initiator can withdraw the request",
            "only a running request can be withdrawn")) {
            doThrow(new ApprovalProjectionStore.ProjectionConflictException(message)).when(service).withdraw(any());
            mvc.perform(post("/api/approval/instances/{id}/withdraw", UUID.randomUUID())
                    .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "operator")
                    .header("X-Request-Id", "error-test").header("Idempotency-Key", "error-key")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECTION_CONFLICT"))
                .andExpect(jsonPath("$.retryable").value(false));
        }
    }

    @Test
    void invalidParticipationPagingReturns400BeforeQueryingData() throws Exception {
        ApprovalParticipationQuery query = mock(ApprovalParticipationQuery.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ApprovalParticipationController(
                new ApprovalParticipationQueryService(query)))
            .setControllerAdvice(new ApprovalApiExceptionHandler()).build();
        for (String path : List.of("/api/approval/instances/started", "/api/approval/tasks/processed")) {
            for (var request : List.of(get(path).param("limit", "0"), get(path).param("offset", "-1"))) {
                mvc.perform(request.header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "operator"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.retryable").value(false));
            }
        }
        verifyNoInteractions(query);
    }
}
