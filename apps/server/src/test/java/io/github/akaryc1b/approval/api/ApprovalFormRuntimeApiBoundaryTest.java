package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.FormSubmissionHasher;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalFormRuntimeApiBoundaryTest {

    private static final UUID INSTANCE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private final ApprovalProjectionStore projections = mock(ApprovalProjectionStore.class);
    private final ApprovalFormStore forms = mock(ApprovalFormStore.class);
    private final ApprovalFormSubmissionStore submissions = mock(ApprovalFormSubmissionStore.class);
    private final ApprovalMessageStore messages = mock(ApprovalMessageStore.class);
    private final ApprovalAttachmentStore attachments = mock(ApprovalAttachmentStore.class);

    @Test
    void taskAndSnapshotReadsHideUnrelatedCrossTenantMissingAndCompletedResources() throws Exception {
        InstanceProjection instance = mock(InstanceProjection.class);
        when(instance.initiatorId()).thenReturn("initiator");
        when(projections.findInstance("tenant-a", INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(projections.findTasks("tenant-a", INSTANCE_ID)).thenReturn(List.of(task("assignee", TaskStatus.PENDING)));
        when(projections.findTask("tenant-a", TASK_ID)).thenReturn(Optional.of(task("assignee", TaskStatus.PENDING)));
        MockMvc mvc = mvc(submissionService());
        for (String tenant : List.of("tenant-a", "tenant-b")) {
            for (String path : List.of(
                "/api/approval/tasks/" + TASK_ID + "/form-runtime",
                "/api/approval/instances/" + INSTANCE_ID + "/form-snapshot"
            )) {
                mvc.perform(get(path).header("X-Tenant-Id", tenant).header("X-Operator-Id", "outsider"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("APPROVAL_RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("approval resource was not found"))
                    .andExpect(jsonPath("$.retryable").value(false));
            }
        }
        when(projections.findTask("tenant-a", TASK_ID)).thenReturn(Optional.of(task("assignee", TaskStatus.COMPLETED)));
        mvc.perform(get("/api/approval/tasks/{id}/form-runtime", TASK_ID)
                .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "assignee"))
            .andExpect(status().isNotFound());
        verifyNoInteractions(submissions, forms);
    }

    @Test
    void snapshotInitiatorHistoricalAssigneeAndCopyRecipientKeepExistingAccess() {
        InstanceProjection instance = mock(InstanceProjection.class);
        when(instance.initiatorId()).thenReturn("initiator");
        when(projections.findInstance("tenant-a", INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(projections.findTasks("tenant-a", INSTANCE_ID)).thenReturn(List.of(task("former", TaskStatus.COMPLETED)));
        when(messages.isRecipient("tenant-a", "copy", INSTANCE_ID)).thenReturn(true);
        ApprovalFormSubmissionService service = submissionService();
        for (String operator : List.of("initiator", "former", "copy")) {
            assertTrue(service.findByInstance("tenant-a", operator, INSTANCE_ID).isEmpty());
        }
        verify(submissions, org.mockito.Mockito.times(3)).findByInstance("tenant-a", INSTANCE_ID);
    }

    @Test
    void currentDelegateOrHandoverAssigneeKeepsRuntimeAccess() throws Exception {
        var definition = PurchasePaymentTemplate.formDefinition();
        when(forms.find("tenant-a", definition.formKey(), definition.version())).thenReturn(Optional.of(
            new PublishedForm("tenant-a", definition, "schema-hash", "publisher", NOW)
        ));
        when(submissions.findByInstance("tenant-a", INSTANCE_ID)).thenReturn(Optional.of(
            new ApprovalFormSubmissionStore.FormSubmission(
                UUID.randomUUID(), "tenant-a", definition.formKey(), definition.version(), "schema-hash",
                "business-key", Map.of(), Map.of(), INSTANCE_ID, "initiator", NOW, "request-hash"
            )
        ));
        MockMvc mvc = mvc(submissionService());
        for (String assignee : List.of("delegate", "handover")) {
            when(projections.findTask("tenant-a", TASK_ID)).thenReturn(Optional.of(task(assignee, TaskStatus.PENDING)));
            mvc.perform(get("/api/approval/tasks/{id}/form-runtime", TASK_ID)
                    .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", assignee))
                .andExpect(status().isOk());
        }
    }

    @Test
    void realValidatorMissingAmountIsAFieldSpecificNonRetryableBadRequest() throws Exception {
        var definition = PurchasePaymentTemplate.formDefinition();
        when(forms.find("tenant-a", definition.formKey(), definition.version())).thenReturn(Optional.of(
            new PublishedForm("tenant-a", definition, "schema-hash", "publisher", NOW)
        ));
        mvc(submissionService()).perform(submissionRequest())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FORM_DATA_INVALID"))
            .andExpect(jsonPath("$.message").value("amount is required"))
            .andExpect(jsonPath("$.retryable").value(false))
            .andExpect(jsonPath("$.requestId").value("form-test"));
    }

    @Test
    void commandConflictsAndInitiatorMismatchKeepTheirDistinctClientErrors() throws Exception {
        ApprovalFormSubmissionService service = mock(ApprovalFormSubmissionService.class);
        MockMvc mvc = mvc(service);
        for (var error : List.of(
            new IdempotencyGuard.IdempotencyConflictException("idempotency key was reused"),
            new ApprovalProjectionStore.ProjectionConflictException("attachment is already bound")
        )) {
            doThrow(error).when(service).submit(any());
            mvc.perform(submissionRequest()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(error instanceof IdempotencyGuard.IdempotencyConflictException
                    ? "IDEMPOTENCY_CONFLICT" : "PROJECTION_CONFLICT"))
                .andExpect(jsonPath("$.retryable").value(false));
        }
        doThrow(new PurchasePaymentAssigneeResolver.AssigneeResolutionException(
            "INITIATOR_OPERATOR_MISMATCH", "initiator external ID does not match the authenticated operator"
        )).when(service).submit(any());
        mvc.perform(submissionRequest()).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INITIATOR_OPERATOR_MISMATCH"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submissionRequest() {
        return post("/api/approval/forms/{key}/versions/{version}/submissions",
                PurchasePaymentTemplate.DEFINITION_KEY, PurchasePaymentTemplate.FORM_VERSION)
            .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "initiator")
            .header("X-Request-Id", "form-test").header("Idempotency-Key", "form-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessKey\":\"business-key\",\"values\":{},\"startParameters\":{}}");
    }

    private TaskProjection task(String assignee, TaskStatus status) {
        return new TaskProjection(TASK_ID, INSTANCE_ID, "tenant-a", "engine-task", "managerApproval",
            "Manager approval", assignee, status, 1, NOW, NOW, null);
    }

    private ApprovalFormSubmissionService submissionService() {
        return new ApprovalFormSubmissionService(
            mock(IdempotencyGuard.class), forms, submissions, attachments, projections, messages,
            mock(FormSubmissionWorkflowStarter.class), new FormDataValidator(), mock(FormSubmissionHasher.class),
            mock(AuditEventSink.class), Clock.systemUTC(), UUID::randomUUID
        );
    }

    private MockMvc mvc(ApprovalFormSubmissionService service) {
        ApprovalFormRuntimeService runtime = new ApprovalFormRuntimeService(
            forms, mock(ApprovalUiSchemaStore.class), submissions, projections, attachments,
            new FormDataValidator(), mock(FormSubmissionHasher.class), UUID::randomUUID
        );
        return MockMvcBuilders.standaloneSetup(
                new ApprovalFormRuntimeController(runtime), new ApprovalFormSubmissionController(service)
            )
            .setControllerAdvice(new ApprovalParticipantReadApiExceptionHandler(), new ApprovalFormRuntimeApiExceptionHandler())
            .build();
    }
}
