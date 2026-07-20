package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.FormDataValidator.NormalizedFormData;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore.FormSubmission;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore.FormSubmissionRevision;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldPermission;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Resolves server-authoritative layout, defaults and field rules for start and task contexts. */
public final class ApprovalFormRuntimeService {

    private final ApprovalFormStore forms;
    private final ApprovalUiSchemaStore uiSchemas;
    private final ApprovalFormSubmissionStore submissions;
    private final ApprovalProjectionStore projections;
    private final ApprovalAttachmentStore attachments;
    private final FormDataValidator validator;
    private final FormSubmissionHasher submissionHasher;
    private final FormDefaultValueResolver defaultValues;
    private final Supplier<UUID> identifiers;

    public ApprovalFormRuntimeService(
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ApprovalFormSubmissionStore submissions,
        ApprovalProjectionStore projections,
        ApprovalAttachmentStore attachments,
        FormDataValidator validator,
        FormSubmissionHasher submissionHasher,
        Supplier<UUID> identifiers
    ) {
        this(
            forms,
            uiSchemas,
            submissions,
            projections,
            attachments,
            validator,
            submissionHasher,
            new FormDefaultValueResolver(Clock.systemUTC()),
            identifiers
        );
    }

    public ApprovalFormRuntimeService(
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ApprovalFormSubmissionStore submissions,
        ApprovalProjectionStore projections,
        ApprovalAttachmentStore attachments,
        FormDataValidator validator,
        FormSubmissionHasher submissionHasher,
        FormDefaultValueResolver defaultValues,
        Supplier<UUID> identifiers
    ) {
        this.forms = Objects.requireNonNull(forms);
        this.uiSchemas = Objects.requireNonNull(uiSchemas);
        this.submissions = Objects.requireNonNull(submissions);
        this.projections = Objects.requireNonNull(projections);
        this.attachments = Objects.requireNonNull(attachments);
        this.validator = Objects.requireNonNull(validator);
        this.submissionHasher = Objects.requireNonNull(submissionHasher);
        this.defaultValues = Objects.requireNonNull(defaultValues);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public RuntimeView startRuntime(String tenantId, String formKey, int formVersion) {
        return startRuntime(tenantId, "system", formKey, formVersion);
    }

    public RuntimeView startRuntime(
        String tenantId,
        String operatorId,
        String formKey,
        int formVersion
    ) {
        PublishedForm form = requireForm(tenantId, formKey, formVersion);
        UiSelection selection = latestUiSchema(tenantId, form.definition());
        RuntimePermissions permissions = permissions(
            form.definition(),
            selection,
            UiSchemaDefinition.START_CONTEXT
        );
        Map<String, Object> values = defaultValues.resolve(form.definition(), operatorId);
        NormalizedFormData normalized = validator.validate(
            form.definition(),
            values,
            permissions.requiredFields()
        );
        return view(
            form.definition(),
            selection,
            UiSchemaDefinition.START_CONTEXT,
            permissions,
            normalized.values(),
            0
        );
    }

    public RuntimeView taskRuntime(String tenantId, String operatorId, UUID taskId) {
        TaskProjection task = requirePendingTask(tenantId, operatorId, taskId);
        FormSubmission submission = submissions.findByInstance(tenantId, task.instanceId())
            .orElseThrow(() -> new IllegalArgumentException("approval instance has no form snapshot"));
        PublishedForm form = requireForm(tenantId, submission.formKey(), submission.formVersion());
        UiSelection selection = pinnedUiSchema(submission, form.definition());
        RuntimePermissions permissions = permissions(
            form.definition(),
            selection,
            task.taskDefinitionKey()
        );
        Optional<FormSubmissionRevision> revision = submissions.findLatestRevision(
            tenantId,
            task.instanceId()
        );
        Map<String, Object> values = revision.map(FormSubmissionRevision::values)
            .orElse(submission.values());
        return view(
            form.definition(),
            selection,
            task.taskDefinitionKey(),
            permissions,
            values,
            revision.map(FormSubmissionRevision::revisionNumber).orElse(0)
        );
    }

    public StartPlan validateStart(
        String tenantId,
        String formKey,
        int formVersion,
        Map<String, Object> input
    ) {
        return validateStart(tenantId, "system", formKey, formVersion, input);
    }

    public StartPlan validateStart(
        String tenantId,
        String operatorId,
        String formKey,
        int formVersion,
        Map<String, Object> input
    ) {
        PublishedForm form = requireForm(tenantId, formKey, formVersion);
        UiSelection selection = latestUiSchema(tenantId, form.definition());
        RuntimePermissions permissions = permissions(
            form.definition(),
            selection,
            UiSchemaDefinition.START_CONTEXT
        );
        Map<String, Object> defaults = defaultValues.resolve(form.definition(), operatorId);
        rejectNonEditable(input, permissions.fieldAccess(), defaults.keySet());
        Map<String, Object> complete = new LinkedHashMap<>(defaults);
        editableValues(input, permissions.fieldAccess()).forEach(complete::put);
        return new StartPlan(
            form,
            selection,
            validator.validate(form.definition(), complete, permissions.requiredFields())
        );
    }

    public RevisionPlan planRevision(
        String tenantId,
        String operatorId,
        UUID taskId,
        Map<String, Object> changes
    ) {
        TaskProjection task = requirePendingTask(tenantId, operatorId, taskId);
        FormSubmission submission = submissions.findByInstance(tenantId, task.instanceId())
            .orElseThrow(() -> new IllegalArgumentException("approval instance has no form snapshot"));
        PublishedForm form = requireForm(tenantId, submission.formKey(), submission.formVersion());
        UiSelection selection = pinnedUiSchema(submission, form.definition());
        RuntimePermissions permissions = permissions(
            form.definition(),
            selection,
            task.taskDefinitionKey()
        );
        rejectNonEditable(changes, permissions.fieldAccess(), Set.of());
        Optional<FormSubmissionRevision> previous = submissions.findLatestRevision(
            tenantId,
            task.instanceId()
        );
        Map<String, Object> merged = new LinkedHashMap<>(
            previous.map(FormSubmissionRevision::values).orElse(submission.values())
        );
        editableValues(changes, permissions.fieldAccess()).forEach(merged::put);
        NormalizedFormData normalized = validator.validate(
            form.definition(),
            merged,
            permissions.requiredFields()
        );
        String contentHash = submissionHasher.hash(
            submission.formKey(),
            submission.formVersion(),
            submission.schemaHash(),
            submission.businessKey(),
            normalized.values(),
            submission.startParameters()
        );
        return new RevisionPlan(
            task,
            submission,
            normalized,
            previous.map(FormSubmissionRevision::revisionNumber).orElse(0) + 1,
            contentHash
        );
    }

    public void saveRevision(
        RevisionPlan plan,
        RequestContext context,
        String requestHash,
        Instant modifiedAt
    ) {
        submissions.lockInstance(context.tenantId(), plan.task().instanceId());
        int currentRevision = submissions.findLatestRevision(
            context.tenantId(),
            plan.task().instanceId()
        ).map(FormSubmissionRevision::revisionNumber).orElse(0);
        if (currentRevision + 1 != plan.revisionNumber()) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "form data changed before the revision could be saved"
            );
        }
        attachments.bindToInstance(
            context.tenantId(),
            context.operatorId(),
            plan.task().instanceId(),
            plan.data().attachmentIds(),
            modifiedAt
        );
        submissions.saveRevision(new FormSubmissionRevision(
            identifiers.get(),
            context.tenantId(),
            plan.task().instanceId(),
            plan.revisionNumber(),
            plan.data().values(),
            context.operatorId(),
            modifiedAt,
            requestHash
        ));
    }

    private PublishedForm requireForm(String tenantId, String formKey, int formVersion) {
        return forms.find(tenantId, formKey, formVersion)
            .orElseThrow(() -> new IllegalArgumentException("published form version was not found"));
    }

    private TaskProjection requirePendingTask(String tenantId, String operatorId, UUID taskId) {
        TaskProjection task = projections.findTask(tenantId, taskId)
            .orElseThrow(() -> new ApprovalProjectionStore.ProjectionConflictException(
                "approval task was not found"
            ));
        if (task.status() != TaskStatus.PENDING || !task.assigneeId().equals(operatorId)) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "task is not pending or is assigned to another operator"
            );
        }
        return task;
    }

    private UiSelection latestUiSchema(String tenantId, FormDefinition form) {
        return uiSchemas.findLatest(tenantId, form.formKey(), form.version())
            .map(schema -> new UiSelection(schema.definition(), schema.contentHash(), false))
            .orElseGet(() -> defaultUiSchema(form));
    }

    private UiSelection pinnedUiSchema(FormSubmission submission, FormDefinition form) {
        if (submission.uiSchemaVersion() == null) {
            return defaultUiSchema(form);
        }
        PublishedUiSchema schema = uiSchemas.find(
            submission.tenantId(),
            submission.formKey(),
            submission.formVersion(),
            submission.uiSchemaVersion()
        ).orElseThrow(() -> new IllegalStateException("pinned UI Schema version is missing"));
        if (!schema.contentHash().equals(submission.uiSchemaHash())) {
            throw new IllegalStateException("pinned UI Schema hash does not match");
        }
        return new UiSelection(schema.definition(), schema.contentHash(), false);
    }

    static RuntimePermissions permissions(
        FormDefinition form,
        UiSelection selection,
        String contextKey
    ) {
        Optional<NodePermissions> configured = selection.definition().nodePermissions().stream()
            .filter(item -> item.contextKey().equals(contextKey))
            .findFirst();
        Map<String, FieldAccess> access = new LinkedHashMap<>();
        Map<String, Boolean> required = new LinkedHashMap<>();
        if (configured.isPresent()) {
            Map<String, FormDefinition.FormField> fields = new LinkedHashMap<>();
            form.fields().forEach(field -> fields.put(field.key(), field));
            configured.get().fields().forEach(item -> {
                FormDefinition.FormField field = fields.get(item.fieldKey());
                access.put(item.fieldKey(), item.access());
                required.put(
                    item.fieldKey(),
                    UiSchemaDefinitionValidator.effectiveRequired(
                        field.required(),
                        item.requiredOverride()
                    )
                );
            });
        } else {
            FieldAccess fallback = selection.defaulted()
                ? UiSchemaDefinition.START_CONTEXT.equals(contextKey)
                    ? FieldAccess.EDITABLE
                    : FieldAccess.READONLY
                : FieldAccess.HIDDEN;
            form.fields().forEach(field -> {
                access.put(field.key(), fallback);
                required.put(field.key(), field.required());
            });
        }
        Map<String, FieldAccess> effectiveAccess = UiSchemaDefinitionValidator.applySectionAccess(
            selection.definition(),
            access
        );
        return new RuntimePermissions(effectiveAccess, Map.copyOf(required));
    }

    private static RuntimeView view(
        FormDefinition form,
        UiSelection selection,
        String contextKey,
        RuntimePermissions permissions,
        Map<String, Object> values,
        int revisionNumber
    ) {
        Map<String, Object> visible = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (permissions.fieldAccess().getOrDefault(key, FieldAccess.HIDDEN) != FieldAccess.HIDDEN) {
                visible.put(key, value);
            }
        });
        return new RuntimeView(
            form,
            selection.definition(),
            selection.contentHash(),
            selection.defaulted(),
            contextKey,
            permissions.fieldAccess(),
            permissions.requiredFields(),
            Map.copyOf(visible),
            revisionNumber
        );
    }

    static void rejectNonEditable(
        Map<String, Object> input,
        Map<String, FieldAccess> permissions,
        Set<String> serverDefaultedFields
    ) {
        if (input == null) {
            return;
        }
        List<String> forbidden = input.keySet().stream()
            .filter(key -> permissions.get(key) != FieldAccess.EDITABLE)
            .filter(key -> !serverDefaultedFields.contains(key))
            .sorted()
            .toList();
        if (!forbidden.isEmpty()) {
            throw new FieldPermissionException(
                "fields are not editable in the current context: " + String.join(",", forbidden)
            );
        }
    }

    private static Map<String, Object> editableValues(
        Map<String, Object> input,
        Map<String, FieldAccess> permissions
    ) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (permissions.get(key) == FieldAccess.EDITABLE) {
                values.put(key, value);
            }
        });
        return values;
    }

    private static UiSelection defaultUiSchema(FormDefinition form) {
        List<FieldLayout> layouts = new ArrayList<>();
        List<FieldPermission> startPermissions = new ArrayList<>();
        for (FormDefinition.FormField field : form.fields()) {
            layouts.add(new FieldLayout(field.key(), null, null, 24));
            startPermissions.add(new FieldPermission(field.key(), FieldAccess.EDITABLE));
        }
        UiSchemaDefinition definition = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            form.formKey(),
            form.version(),
            1,
            form.name() + " default UI",
            List.of(new Section("default", "申请信息", null, false, layouts)),
            List.of(new NodePermissions(UiSchemaDefinition.START_CONTEXT, startPermissions))
        );
        return new UiSelection(definition, null, true);
    }

    public record UiSelection(
        UiSchemaDefinition definition,
        String contentHash,
        boolean defaulted
    ) {
    }

    public record RuntimePermissions(
        Map<String, FieldAccess> fieldAccess,
        Map<String, Boolean> requiredFields
    ) {
        public RuntimePermissions {
            fieldAccess = fieldAccess == null ? Map.of() : Map.copyOf(fieldAccess);
            requiredFields = requiredFields == null ? Map.of() : Map.copyOf(requiredFields);
        }
    }

    public record RuntimeView(
        FormDefinition definition,
        UiSchemaDefinition uiSchema,
        String uiSchemaHash,
        boolean defaultedUiSchema,
        String contextKey,
        Map<String, FieldAccess> fieldPermissions,
        Map<String, Boolean> requiredFields,
        Map<String, Object> values,
        int revisionNumber
    ) {
        public RuntimeView {
            fieldPermissions = fieldPermissions == null ? Map.of() : Map.copyOf(fieldPermissions);
            requiredFields = requiredFields == null ? Map.of() : Map.copyOf(requiredFields);
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    public record StartPlan(
        PublishedForm form,
        UiSelection uiSchema,
        NormalizedFormData data
    ) {
    }

    public record RevisionPlan(
        TaskProjection task,
        FormSubmission submission,
        NormalizedFormData data,
        int revisionNumber,
        String contentHash
    ) {
    }

    public static final class FieldPermissionException extends RuntimeException {
        public FieldPermissionException(String message) {
            super(message);
        }
    }
}
