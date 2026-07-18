package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.FormDataValidator.NormalizedFormData;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore.FormSubmission;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter.WorkflowStartResult;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Validates a published form, starts its workflow and saves an immutable data snapshot. */
public final class ApprovalFormSubmissionService {

    private static final String SUBMIT_OPERATION = "approval-form.submit.v1";

    private final IdempotencyGuard idempotency;
    private final ApprovalFormStore forms;
    private final ApprovalFormSubmissionStore submissions;
    private final ApprovalAttachmentStore attachments;
    private final ApprovalProjectionStore projections;
    private final ApprovalMessageStore messages;
    private final FormSubmissionWorkflowStarter workflowStarter;
    private final FormDataValidator validator;
    private final FormSubmissionHasher hasher;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalFormSubmissionService(
        IdempotencyGuard idempotency,
        ApprovalFormStore forms,
        ApprovalFormSubmissionStore submissions,
        ApprovalAttachmentStore attachments,
        ApprovalProjectionStore projections,
        ApprovalMessageStore messages,
        FormSubmissionWorkflowStarter workflowStarter,
        FormDataValidator validator,
        FormSubmissionHasher hasher,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.forms = Objects.requireNonNull(forms);
        this.submissions = Objects.requireNonNull(submissions);
        this.attachments = Objects.requireNonNull(attachments);
        this.projections = Objects.requireNonNull(projections);
        this.messages = Objects.requireNonNull(messages);
        this.workflowStarter = Objects.requireNonNull(workflowStarter);
        this.validator = Objects.requireNonNull(validator);
        this.hasher = Objects.requireNonNull(hasher);
        this.auditEvents = Objects.requireNonNull(auditEvents);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public SubmissionResult submit(SubmissionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        PublishedForm form = forms.find(
            command.context().tenantId(), command.formKey(), command.formVersion()
        ).orElseThrow(() -> new IllegalArgumentException("published form version was not found"));
        NormalizedFormData data = validator.validate(form.definition(), command.values());
        String requestHash = hasher.hash(
            command.formKey(), command.formVersion(), form.contentHash(),
            command.businessKey(), data.values(), command.startParameters()
        );
        return idempotency.execute(
            command.context(), SUBMIT_OPERATION, requestHash, SubmissionResult.class,
            () -> submitOnce(command, form, data, requestHash)
        );
    }

    public Optional<SubmissionSnapshot> findByInstance(
        String tenantId,
        String operatorId,
        UUID instanceId
    ) {
        authorizeParticipant(tenantId, operatorId, instanceId);
        return submissions.findByInstance(tenantId, instanceId).map(this::snapshot);
    }

    private SubmissionResult submitOnce(
        SubmissionCommand command,
        PublishedForm form,
        NormalizedFormData data,
        String requestHash
    ) {
        Optional<FormSubmission> existing = submissions.findByBusinessKey(
            command.context().tenantId(), command.businessKey()
        );
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw new ApprovalProjectionStore.ProjectionConflictException(
                    "business key is already used by a different form submission"
                );
            }
            return result(existing.get(), true);
        }

        WorkflowStartResult workflow = workflowStarter.start(
            command.context(), command.formKey(), command.formVersion(), command.businessKey(),
            data.values(), command.startParameters()
        );
        Instant now = clock.instant();
        attachments.bindToInstance(
            command.context().tenantId(), command.context().operatorId(),
            workflow.instanceId(), data.attachmentIds(), now
        );
        FormSubmission submission = new FormSubmission(
            identifiers.get(), command.context().tenantId(), command.formKey(),
            command.formVersion(), form.contentHash(), command.businessKey(),
            data.values(), command.startParameters(), workflow.instanceId(),
            command.context().operatorId(), now, requestHash
        );
        submissions.save(submission);
        auditEvents.append(new AuditEvent(
            identifiers.get(), command.context().tenantId(), command.context().operatorId(),
            "FORM_SUBMITTED", "FORM_SUBMISSION", submission.submissionId().toString(),
            command.context().requestId(), command.context().traceId(), now,
            Map.of(
                "formKey", submission.formKey(),
                "formVersion", Integer.toString(submission.formVersion()),
                "businessKey", submission.businessKey(),
                "instanceId", submission.instanceId().toString(),
                "schemaHash", submission.schemaHash()
            )
        ));
        return result(submission, false);
    }

    private SubmissionSnapshot snapshot(FormSubmission submission) {
        FormDefinition definition = forms.find(
            submission.tenantId(), submission.formKey(), submission.formVersion()
        ).orElseThrow(() -> new IllegalStateException("submission form version is missing"))
            .definition();
        return new SubmissionSnapshot(submission, definition);
    }

    private void authorizeParticipant(String tenantId, String operatorId, UUID instanceId) {
        ApprovalProjectionStore.InstanceProjection instance = projections.findInstance(tenantId, instanceId)
            .orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
                "approval instance was not found"
            ));
        boolean taskParticipant = projections.findTasks(tenantId, instanceId).stream()
            .anyMatch(task -> task.assigneeId().equals(operatorId));
        boolean messageRecipient = messages.isRecipient(tenantId, operatorId, instanceId);
        if (!instance.initiatorId().equals(operatorId) && !taskParticipant && !messageRecipient) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "operator is not a participant in the approval instance"
            );
        }
    }

    private static SubmissionResult result(FormSubmission submission, boolean replayed) {
        return new SubmissionResult(
            submission.submissionId(), submission.instanceId(), submission.formKey(),
            submission.formVersion(), submission.schemaHash(), submission.businessKey(),
            submission.values(), submission.submittedBy(), submission.submittedAt(), replayed
        );
    }

    public record SubmissionCommand(
        RequestContext context,
        String formKey,
        int formVersion,
        String businessKey,
        Map<String, Object> values,
        Map<String, Object> startParameters
    ) {
        public SubmissionCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            formKey = requireText(formKey, "formKey");
            if (formVersion < 1) throw new IllegalArgumentException("formVersion must be positive");
            businessKey = requireText(businessKey, "businessKey");
            values = values == null ? Map.of() : Map.copyOf(values);
            startParameters = startParameters == null ? Map.of() : Map.copyOf(startParameters);
        }
    }

    public record SubmissionResult(
        UUID submissionId, UUID instanceId, String formKey, int formVersion,
        String schemaHash, String businessKey, Map<String, Object> values,
        String submittedBy, Instant submittedAt, boolean replayedExistingSubmission
    ) {
        public SubmissionResult { values = values == null ? Map.of() : Map.copyOf(values); }
    }

    public record SubmissionSnapshot(FormSubmission submission, FormDefinition definition) {
        public SubmissionSnapshot {
            submission = Objects.requireNonNull(submission);
            definition = Objects.requireNonNull(definition);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
