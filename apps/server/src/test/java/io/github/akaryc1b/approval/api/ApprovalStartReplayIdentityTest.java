package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalParticipantResourceNotFoundException;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.FormSubmissionHasher;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalStartReplayIdentityTest {

    private static final UUID INSTANCE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private final ApprovalProjectionStore projections = mock(ApprovalProjectionStore.class);
    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalFormSubmissionStore submissions = mock(ApprovalFormSubmissionStore.class);
    private final ApprovalFormStore forms = mock(ApprovalFormStore.class);

    @Test
    void mismatchedInitiatorIsRejectedBeforeIdempotencyOrBusinessKeyLookup() {
        var command = new PurchasePaymentApplicationService.StartCommand(
            context("outsider"), "business-key", BigDecimal.TEN, "supplier", "order", List.of("attachment"),
            new PurchasePaymentAssigneeResolver.AssigneeRules(
                "connector", new ExternalId("connector", "USER", "owner"), "reviewer", "finance", 10
            )
        );
        var failure = assertThrows(PurchasePaymentAssigneeResolver.AssigneeResolutionException.class,
            () -> startService().start(command));
        assertEquals("INITIATOR_OPERATOR_MISMATCH", failure.code());
        verifyNoInteractions(guard, engine, projections);
    }

    @Test
    void existingInstanceOwnershipIsCheckedForInternalSnapshotStartsBeforeReplay() {
        when(projections.findByBusinessKey("tenant-a", "business-key"))
            .thenReturn(Optional.of(instance("owner")));
        assertThrows(ApprovalParticipantResourceNotFoundException.class,
            () -> startService().start(startCommand("outsider")));
        verifyNoInteractions(guard, engine);
    }

    @Test
    void cachedStartResultIsRecheckedAfterConcurrentLookupAndOwnerCanReplay() {
        var result = new PurchasePaymentApplicationService.StartResult(INSTANCE_ID, InstanceStatus.RUNNING, List.of(), NOW);
        when(guard.execute(any(), anyString(), anyString(), eq(PurchasePaymentApplicationService.StartResult.class), any()))
            .thenReturn(result);
        when(projections.findInstance("tenant-a", INSTANCE_ID)).thenReturn(Optional.of(instance("owner")));
        assertThrows(ApprovalParticipantResourceNotFoundException.class,
            () -> startService().start(startCommand("outsider")));
        assertSame(result, startService().start(startCommand("owner")));
        verifyNoInteractions(engine);
    }

    @Test
    void existingSubmissionOwnershipIsCheckedBeforeValidationOrReplay() {
        when(submissions.findByBusinessKey("tenant-a", "business-key"))
            .thenReturn(Optional.of(submission("owner")));
        assertThrows(ApprovalParticipantResourceNotFoundException.class,
            () -> formService().submit(submissionCommand("outsider")));
        verifyNoInteractions(guard, forms);
    }

    @Test
    void cachedSubmissionResultIsRecheckedAfterConcurrentLookupAndOwnerCanReplay() {
        var definition = PurchasePaymentTemplate.formDefinition();
        when(forms.find("tenant-a", definition.formKey(), definition.version())).thenReturn(Optional.of(
            new ApprovalFormStore.PublishedForm("tenant-a", definition, "schema-hash", "publisher", NOW)
        ));
        var result = new ApprovalFormSubmissionService.SubmissionResult(
            UUID.randomUUID(), INSTANCE_ID, definition.formKey(), definition.version(), "schema-hash",
            "business-key", Map.of("supplier", "private supplier"), "owner", NOW, true
        );
        when(guard.execute(any(), anyString(), anyString(), eq(ApprovalFormSubmissionService.SubmissionResult.class), any()))
            .thenReturn(result);
        assertThrows(ApprovalParticipantResourceNotFoundException.class,
            () -> formService().submit(submissionCommand("outsider")));
        assertSame(result, formService().submit(submissionCommand("owner")));
    }

    @Test
    void freshKeyStartDedupRechecksOwnerWhenExistingInstanceAppearsAfterPrecheck() {
        InstanceProjection existing = instance("owner");
        when(projections.findByBusinessKey("tenant-a", "business-key"))
            .thenReturn(Optional.empty(), Optional.of(existing));
        when(guard.execute(any(), anyString(), anyString(), eq(PurchasePaymentApplicationService.StartResult.class), any()))
            .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(4).get());

        assertThrows(ApprovalParticipantResourceNotFoundException.class,
            () -> startService().start(startCommand("outsider")));
        verifyNoInteractions(engine);
    }

    @Test
    void freshKeySubmissionDedupRechecksOwnerWhenExistingSubmissionAppearsAfterPrecheck() {
        var definition = PurchasePaymentTemplate.formDefinition();
        when(forms.find("tenant-a", definition.formKey(), definition.version())).thenReturn(Optional.of(
            new ApprovalFormStore.PublishedForm("tenant-a", definition, "schema-hash", "publisher", NOW)
        ));
        when(submissions.findByBusinessKey("tenant-a", "business-key"))
            .thenReturn(Optional.empty(), Optional.of(submission("owner")));
        when(guard.execute(any(), anyString(), anyString(), eq(ApprovalFormSubmissionService.SubmissionResult.class), any()))
            .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(4).get());

        assertThrows(ApprovalParticipantResourceNotFoundException.class,
            () -> formService().submit(submissionCommand("outsider")));
    }

    private PurchasePaymentApplicationService startService() {
        return new PurchasePaymentApplicationService(engine, mock(ApprovalDslCompiler.class), guard, projections,
            mock(AuditEventSink.class), Clock.systemUTC(), UUID::randomUUID);
    }

    private ApprovalFormSubmissionService formService() {
        return new ApprovalFormSubmissionService(guard, forms, submissions, mock(ApprovalAttachmentStore.class),
            projections, mock(ApprovalMessageStore.class), mock(FormSubmissionWorkflowStarter.class),
            new FormDataValidator(), new FormSubmissionHasher(), mock(AuditEventSink.class), Clock.systemUTC(), UUID::randomUUID);
    }

    private PurchasePaymentApplicationService.StartCommand startCommand(String operator) {
        return new PurchasePaymentApplicationService.StartCommand(context(operator), "business-key", BigDecimal.TEN,
            "supplier", "order", List.of("attachment"), new AssigneeSnapshot("manager", "reviewer", List.of("finance"), Map.of()));
    }

    private ApprovalFormSubmissionService.SubmissionCommand submissionCommand(String operator) {
        return new ApprovalFormSubmissionService.SubmissionCommand(context(operator), PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION, "business-key", Map.of(
                "amount", BigDecimal.TEN, "supplier", "supplier", "purchaseOrderReference", "order",
                "attachments", List.of("30000000-0000-0000-0000-000000000001")
            ), Map.of());
    }

    private InstanceProjection instance(String owner) {
        return new InstanceProjection(INSTANCE_ID, "tenant-a", "business-key", "engine-instance",
            "purchase-payment", 1, "purchase-payment", 1, "compiler", "content-hash", owner,
            BigDecimal.TEN, "supplier", "order", List.of(),
            new AssigneeSnapshot("manager", "reviewer", List.of("finance"), Map.of()),
            "request-hash", InstanceStatus.RUNNING, 1, NOW, NOW);
    }

    private ApprovalFormSubmissionStore.FormSubmission submission(String owner) {
        return new ApprovalFormSubmissionStore.FormSubmission(UUID.randomUUID(), "tenant-a", PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION, "schema-hash", "business-key", Map.of(), Map.of(), INSTANCE_ID, owner, NOW, "hash");
    }

    private RequestContext context(String operator) {
        return new RequestContext("tenant-a", operator, "request", "idempotency-key", "trace");
    }
}
