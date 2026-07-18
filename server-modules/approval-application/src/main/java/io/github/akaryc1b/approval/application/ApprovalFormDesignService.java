package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore.DraftCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore.DraftPage;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDesignDraft;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Product-level lifecycle for mutable design drafts and atomic immutable Form Packages. */
public final class ApprovalFormDesignService {

    private static final String CREATE_OPERATION = "approval-form-design.create.v1";
    private static final String COPY_OPERATION = "approval-form-design.copy.v1";
    private static final String UPDATE_OPERATION = "approval-form-design.update.v1";
    private static final String VALIDATE_OPERATION = "approval-form-design.validate.v1";
    private static final String ARCHIVE_OPERATION = "approval-form-design.archive.v1";
    private static final String PUBLISH_OPERATION = "approval-form-package.publish.v1";

    private final IdempotencyGuard idempotency;
    private final ApprovalFormDesignDraftStore drafts;
    private final ApprovalFormPackageStore packages;
    private final ApprovalFormStore forms;
    private final ApprovalUiSchemaStore uiSchemas;
    private final AuditEventSink auditEvents;
    private final FormDefinitionValidator formValidator;
    private final UiSchemaDefinitionValidator uiValidator;
    private final FormSchemaHasher formHasher;
    private final UiSchemaHasher uiHasher;
    private final FormPackageHasher packageHasher;
    private final FormDefaultValueResolver defaultValues;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalFormDesignService(
        IdempotencyGuard idempotency,
        ApprovalFormDesignDraftStore drafts,
        ApprovalFormPackageStore packages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        AuditEventSink auditEvents,
        FormDefinitionValidator formValidator,
        UiSchemaDefinitionValidator uiValidator,
        FormSchemaHasher formHasher,
        UiSchemaHasher uiHasher,
        FormPackageHasher packageHasher,
        FormDefaultValueResolver defaultValues,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.drafts = Objects.requireNonNull(drafts);
        this.packages = Objects.requireNonNull(packages);
        this.forms = Objects.requireNonNull(forms);
        this.uiSchemas = Objects.requireNonNull(uiSchemas);
        this.auditEvents = Objects.requireNonNull(auditEvents);
        this.formValidator = Objects.requireNonNull(formValidator);
        this.uiValidator = Objects.requireNonNull(uiValidator);
        this.formHasher = Objects.requireNonNull(formHasher);
        this.uiHasher = Objects.requireNonNull(uiHasher);
        this.packageHasher = Objects.requireNonNull(packageHasher);
        this.defaultValues = Objects.requireNonNull(defaultValues);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public FormDesignDraft createBlank(CreateCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String requestHash = hash(
            "blank",
            command.formKey(),
            command.name(),
            command.formVersion(),
            command.uiSchemaVersion()
        );
        return idempotency.execute(
            command.context(),
            CREATE_OPERATION,
            requestHash,
            FormDesignDraft.class,
            () -> createOnce(command, blank(command), "FORM_DESIGN_DRAFT_CREATED")
        );
    }

    public FormDesignDraft createFromPurchasePaymentTemplate(CreateCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        FormDefinition sourceForm = PurchasePaymentTemplate.formDefinition();
        UiSchemaDefinition sourceUi = PurchasePaymentTemplate.uiSchemaDefinition();
        FormDefinition form = copyForm(
            sourceForm,
            command.formKey(),
            command.formVersion(),
            command.name()
        );
        UiSchemaDefinition uiSchema = copyUi(
            sourceUi,
            command.formKey(),
            command.formVersion(),
            command.uiSchemaVersion(),
            command.name() + " UI"
        );
        String requestHash = hash(
            "purchase-payment-template",
            command.formKey(),
            command.name(),
            command.formVersion(),
            command.uiSchemaVersion(),
            formHasher.hash(form),
            uiHasher.hash(uiSchema)
        );
        return idempotency.execute(
            command.context(),
            CREATE_OPERATION,
            requestHash,
            FormDesignDraft.class,
            () -> createOnce(
                command,
                new DraftContent(form, uiSchema, null, null),
                "FORM_DESIGN_DRAFT_CREATED_FROM_TEMPLATE"
            )
        );
    }

    public FormDesignDraft createFromPublished(CopyPublishedCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        PublishedForm sourceForm = forms.find(
            command.context().tenantId(),
            command.formKey(),
            command.sourceFormVersion()
        ).orElseThrow(() -> new PublishedSchemaNotFoundException(
            "source Form Schema version was not found"
        ));
        PublishedUiSchema sourceUi = uiSchemas.find(
            command.context().tenantId(),
            command.formKey(),
            command.sourceFormVersion(),
            command.sourceUiSchemaVersion()
        ).orElseThrow(() -> new PublishedSchemaNotFoundException(
            "source UI Schema version was not found"
        ));
        FormDefinition form = copyForm(
            sourceForm.definition(),
            command.formKey(),
            command.targetFormVersion(),
            command.name()
        );
        UiSchemaDefinition uiSchema = copyUi(
            sourceUi.definition(),
            command.formKey(),
            command.targetFormVersion(),
            command.targetUiSchemaVersion(),
            command.name() + " UI"
        );
        String requestHash = hash(
            "published",
            command.formKey(),
            command.sourceFormVersion(),
            command.sourceUiSchemaVersion(),
            command.targetFormVersion(),
            command.targetUiSchemaVersion(),
            command.name(),
            sourceForm.contentHash(),
            sourceUi.contentHash()
        );
        return idempotency.execute(
            command.context(),
            COPY_OPERATION,
            requestHash,
            FormDesignDraft.class,
            () -> createOnce(
                new CreateCommand(
                    command.context(),
                    command.formKey(),
                    command.name(),
                    command.targetFormVersion(),
                    command.targetUiSchemaVersion()
                ),
                new DraftContent(
                    form,
                    uiSchema,
                    command.sourceFormVersion(),
                    command.sourceUiSchemaVersion()
                ),
                "FORM_DESIGN_DRAFT_COPIED"
            )
        );
    }

    public Optional<FormDesignDraft> find(String tenantId, UUID draftId) {
        return drafts.find(tenantId, draftId);
    }

    public DraftPage findDrafts(
        String tenantId,
        String keyword,
        FormDesignDraft.Status status,
        int limit,
        int offset
    ) {
        return drafts.findDrafts(new DraftCriteria(tenantId, keyword, status, limit, offset));
    }

    public FormDesignDraft update(UpdateCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ensureDraftBinding(command.draftId(), command.formDefinition(), command.uiSchemaDefinition());
        String requestHash = hash(
            command.draftId(),
            command.expectedRevision(),
            command.name(),
            command.saveMode(),
            formHasher.hash(command.formDefinition()),
            uiHasher.hash(command.uiSchemaDefinition())
        );
        return idempotency.execute(
            command.context(),
            UPDATE_OPERATION,
            requestHash,
            FormDesignDraft.class,
            () -> updateOnce(command)
        );
    }

    public ValidationReport validate(RevisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        FormDesignDraft snapshot = requireDraft(command.context().tenantId(), command.draftId());
        String requestHash = hash(
            command.draftId(),
            command.expectedRevision(),
            formHasher.hash(snapshot.formDefinition()),
            uiHasher.hash(snapshot.uiSchemaDefinition())
        );
        return idempotency.execute(
            command.context(),
            VALIDATE_OPERATION,
            requestHash,
            ValidationReport.class,
            () -> validateOnce(command)
        );
    }

    public PreviewResult preview(PreviewCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        FormDesignDraft draft = requireDraft(command.tenantId(), command.draftId());
        ValidationReport validation = validationReport(draft, draft.revision(), draft.status());
        if (!validation.valid()) {
            throw new IllegalArgumentException(String.join("; ", validation.errors()));
        }
        Map<String, FormDefinition.FormField> fields = new LinkedHashMap<>();
        draft.formDefinition().fields().forEach(field -> fields.put(field.key(), field));
        Optional<NodePermissions> configured = draft.uiSchemaDefinition().nodePermissions().stream()
            .filter(item -> item.contextKey().equals(command.contextKey()))
            .findFirst();
        Map<String, FieldAccess> access = new LinkedHashMap<>();
        Map<String, Boolean> required = new LinkedHashMap<>();
        if (configured.isPresent()) {
            configured.get().fields().forEach(permission -> {
                FormDefinition.FormField field = fields.get(permission.fieldKey());
                access.put(permission.fieldKey(), permission.access());
                required.put(
                    permission.fieldKey(),
                    UiSchemaDefinitionValidator.effectiveRequired(
                        field.required(),
                        permission.requiredOverride()
                    )
                );
            });
        } else {
            draft.formDefinition().fields().forEach(field -> {
                access.put(field.key(), FieldAccess.HIDDEN);
                required.put(field.key(), field.required());
            });
        }
        Map<String, Object> values = defaultValues.resolve(
            draft.formDefinition(),
            command.operatorId()
        );
        Map<String, Object> visibleValues = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (access.getOrDefault(key, FieldAccess.HIDDEN) != FieldAccess.HIDDEN) {
                visibleValues.put(key, value);
            }
        });
        return new PreviewResult(
            draft.draftId(),
            draft.revision(),
            command.contextKey(),
            draft.formDefinition(),
            draft.uiSchemaDefinition(),
            formHasher.hash(draft.formDefinition()),
            uiHasher.hash(draft.uiSchemaDefinition()),
            Map.copyOf(access),
            Map.copyOf(required),
            Map.copyOf(visibleValues)
        );
    }

    public List<String> contextKeys(String tenantId, UUID draftId) {
        FormDesignDraft draft = requireDraft(tenantId, draftId);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        draft.uiSchemaDefinition().nodePermissions().forEach(item -> keys.add(item.contextKey()));
        return List.copyOf(keys);
    }

    public FormDesignDraft archive(RevisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String requestHash = hash(command.draftId(), command.expectedRevision(), "archive");
        return idempotency.execute(
            command.context(),
            ARCHIVE_OPERATION,
            requestHash,
            FormDesignDraft.class,
            () -> archiveOnce(command)
        );
    }

    public PublishResult publish(PublishCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        FormDesignDraft snapshot = requireDraft(command.context().tenantId(), command.draftId());
        String requestHash = hash(
            command.draftId(),
            command.expectedRevision(),
            command.packageVersion(),
            formHasher.hash(snapshot.formDefinition()),
            uiHasher.hash(snapshot.uiSchemaDefinition())
        );
        return idempotency.execute(
            command.context(),
            PUBLISH_OPERATION,
            requestHash,
            PublishResult.class,
            () -> publishOnce(command)
        );
    }

    public Optional<FormPackage> findPackage(
        String tenantId,
        String formKey,
        int packageVersion
    ) {
        return packages.find(tenantId, formKey, packageVersion);
    }

    private DraftContent blank(CreateCommand command) {
        FormDefinition form = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            command.formKey(),
            command.formVersion(),
            command.name(),
            List.of()
        );
        UiSchemaDefinition uiSchema = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            command.formKey(),
            command.formVersion(),
            command.uiSchemaVersion(),
            command.name() + " UI",
            List.of(new UiSchemaDefinition.Section(
                "default",
                "表单内容",
                null,
                false,
                List.of()
            )),
            List.of(new NodePermissions(UiSchemaDefinition.START_CONTEXT, List.of()))
        );
        return new DraftContent(form, uiSchema, null, null);
    }

    private FormDesignDraft createOnce(
        CreateCommand command,
        DraftContent content,
        String eventType
    ) {
        Instant now = clock.instant();
        FormDesignDraft draft = new FormDesignDraft(
            identifiers.get(),
            command.context().tenantId(),
            command.formKey(),
            command.name(),
            content.formDefinition(),
            content.uiSchemaDefinition(),
            content.sourceFormVersion(),
            content.sourceUiSchemaVersion(),
            1,
            FormDesignDraft.Status.DRAFT,
            null,
            command.context().operatorId(),
            command.context().operatorId(),
            now,
            now
        );
        drafts.save(draft);
        appendAudit(
            command.context(),
            eventType,
            draft,
            now,
            Map.of("revision", "1")
        );
        return draft;
    }

    private FormDesignDraft updateOnce(UpdateCommand command) {
        FormDesignDraft current = requireDraft(command.context().tenantId(), command.draftId());
        requireEditable(current);
        requireRevision(current, command.expectedRevision());
        if (!current.formKey().equals(command.formDefinition().formKey())) {
            throw new DraftStateConflictException("draft formKey cannot be changed");
        }
        FormDesignDraft updated = new FormDesignDraft(
            current.draftId(),
            current.tenantId(),
            current.formKey(),
            command.name(),
            command.formDefinition(),
            command.uiSchemaDefinition(),
            current.sourceFormVersion(),
            current.sourceUiSchemaVersion(),
            current.revision() + 1,
            FormDesignDraft.Status.DRAFT,
            null,
            current.createdBy(),
            command.context().operatorId(),
            current.createdAt(),
            clock.instant()
        );
        if (!drafts.update(updated, command.expectedRevision())) {
            throw new DraftRevisionConflictException("form design draft revision is stale");
        }
        if (command.saveMode() == SaveMode.EXPLICIT) {
            appendAudit(
                command.context(),
                "FORM_DESIGN_DRAFT_SAVED",
                updated,
                updated.updatedAt(),
                Map.of("revision", Long.toString(updated.revision()))
            );
        }
        return updated;
    }

    private ValidationReport validateOnce(RevisionCommand command) {
        drafts.lock(command.context().tenantId(), command.draftId());
        FormDesignDraft current = requireDraft(command.context().tenantId(), command.draftId());
        requireEditable(current);
        requireRevision(current, command.expectedRevision());
        ValidationReport report = validationReport(current, current.revision(), current.status());
        if (!report.valid()) {
            return report;
        }
        Instant now = clock.instant();
        FormDesignDraft validated = copyState(
            current,
            current.revision() + 1,
            FormDesignDraft.Status.VALIDATED,
            null,
            command.context().operatorId(),
            now
        );
        if (!drafts.update(validated, command.expectedRevision())) {
            throw new DraftRevisionConflictException("form design draft revision is stale");
        }
        appendAudit(
            command.context(),
            "FORM_DESIGN_DRAFT_VALIDATED",
            validated,
            now,
            Map.of(
                "revision", Long.toString(validated.revision()),
                "formHash", formHasher.hash(validated.formDefinition()),
                "uiSchemaHash", uiHasher.hash(validated.uiSchemaDefinition())
            )
        );
        return validationReport(validated, validated.revision(), validated.status());
    }

    private FormDesignDraft archiveOnce(RevisionCommand command) {
        drafts.lock(command.context().tenantId(), command.draftId());
        FormDesignDraft current = requireDraft(command.context().tenantId(), command.draftId());
        requireEditable(current);
        requireRevision(current, command.expectedRevision());
        Instant now = clock.instant();
        FormDesignDraft archived = copyState(
            current,
            current.revision() + 1,
            FormDesignDraft.Status.ARCHIVED,
            null,
            command.context().operatorId(),
            now
        );
        if (!drafts.update(archived, command.expectedRevision())) {
            throw new DraftRevisionConflictException("form design draft revision is stale");
        }
        appendAudit(
            command.context(),
            "FORM_DESIGN_DRAFT_ARCHIVED",
            archived,
            now,
            Map.of("revision", Long.toString(archived.revision()))
        );
        return archived;
    }

    private PublishResult publishOnce(PublishCommand command) {
        drafts.lock(command.context().tenantId(), command.draftId());
        FormDesignDraft current = requireDraft(command.context().tenantId(), command.draftId());
        if (current.status() == FormDesignDraft.Status.PUBLISHED) {
            return replayPublished(command, current);
        }
        requireEditable(current);
        requireRevision(current, command.expectedRevision());
        ValidationReport validation = validationReport(current, current.revision(), current.status());
        if (!validation.valid()) {
            throw new IllegalArgumentException(String.join("; ", validation.errors()));
        }

        String formHash = validation.formHash();
        String uiHash = validation.uiSchemaHash();
        String packageHash = packageHasher.hash(
            current.formKey(),
            command.packageVersion(),
            current.formDefinition().version(),
            formHash,
            current.uiSchemaDefinition().version(),
            uiHash
        );
        packages.lockVersion(
            command.context().tenantId(),
            current.formKey(),
            command.packageVersion()
        );
        Optional<FormPackage> existingPackage = packages.find(
            command.context().tenantId(),
            current.formKey(),
            command.packageVersion()
        );
        if (existingPackage.isPresent()) {
            throw packageConflict(existingPackage.get(), current.draftId(), packageHash);
        }

        publishForm(command.context(), current.formDefinition(), formHash);
        publishUi(command.context(), current.uiSchemaDefinition(), uiHash);

        Instant now = clock.instant();
        FormPackage formPackage = new FormPackage(
            command.context().tenantId(),
            current.formKey(),
            command.packageVersion(),
            current.formDefinition().version(),
            formHash,
            current.uiSchemaDefinition().version(),
            uiHash,
            packageHash,
            current.draftId(),
            command.context().operatorId(),
            now
        );
        packages.save(formPackage);
        FormDesignDraft published = copyState(
            current,
            current.revision() + 1,
            FormDesignDraft.Status.PUBLISHED,
            command.packageVersion(),
            command.context().operatorId(),
            now
        );
        if (!drafts.update(published, command.expectedRevision())) {
            throw new DraftRevisionConflictException("form design draft revision is stale");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("draftId", current.draftId().toString());
        attributes.put("packageVersion", Integer.toString(command.packageVersion()));
        attributes.put("packageHash", packageHash);
        attributes.put("formKey", current.formKey());
        attributes.put("formVersion", Integer.toString(current.formDefinition().version()));
        attributes.put("formHash", formHash);
        attributes.put(
            "uiSchemaVersion",
            Integer.toString(current.uiSchemaDefinition().version())
        );
        attributes.put("uiSchemaHash", uiHash);
        appendAudit(
            command.context(),
            "FORM_PACKAGE_PUBLISHED",
            published,
            now,
            Map.copyOf(attributes)
        );
        return result(formPackage, published.revision(), false);
    }

    private PublishResult replayPublished(PublishCommand command, FormDesignDraft current) {
        FormPackage existing = packages.findByDraft(
            command.context().tenantId(),
            current.draftId()
        ).orElseThrow(() -> new IllegalStateException(
            "published draft has no immutable Form Package"
        ));
        if (existing.packageVersion() != command.packageVersion()) {
            throw new PackageVersionConflictException(
                "published draft is bound to a different package version"
            );
        }
        return result(existing, current.revision(), true);
    }

    private void publishForm(
        RequestContext context,
        FormDefinition definition,
        String contentHash
    ) {
        forms.lockVersion(context.tenantId(), definition.formKey(), definition.version());
        Optional<PublishedForm> existing = forms.find(
            context.tenantId(),
            definition.formKey(),
            definition.version()
        );
        if (existing.isPresent()) {
            if (!existing.get().contentHash().equals(contentHash)) {
                throw new PackageVersionConflictException(
                    "Form Schema version already has different content"
                );
            }
            return;
        }
        forms.save(new PublishedForm(
            context.tenantId(),
            definition,
            contentHash,
            context.operatorId(),
            clock.instant()
        ));
    }

    private void publishUi(
        RequestContext context,
        UiSchemaDefinition definition,
        String contentHash
    ) {
        uiSchemas.lockVersion(
            context.tenantId(),
            definition.formKey(),
            definition.formVersion(),
            definition.version()
        );
        Optional<PublishedUiSchema> existing = uiSchemas.find(
            context.tenantId(),
            definition.formKey(),
            definition.formVersion(),
            definition.version()
        );
        if (existing.isPresent()) {
            if (!existing.get().contentHash().equals(contentHash)) {
                throw new PackageVersionConflictException(
                    "UI Schema version already has different content"
                );
            }
            return;
        }
        uiSchemas.save(new PublishedUiSchema(
            context.tenantId(),
            definition,
            contentHash,
            context.operatorId(),
            clock.instant()
        ));
    }

    private RuntimeException packageConflict(
        FormPackage existing,
        UUID draftId,
        String packageHash
    ) {
        if (!existing.packageHash().equals(packageHash)) {
            return new PackageVersionConflictException(
                "Form Package version already has different content"
            );
        }
        if (!existing.sourceDraftId().equals(draftId)) {
            return new PackageVersionConflictException(
                "Form Package version already belongs to another draft"
            );
        }
        return new PackageVersionConflictException("Form Package version is already published");
    }

    private ValidationReport validationReport(
        FormDesignDraft draft,
        long revision,
        FormDesignDraft.Status status
    ) {
        List<String> errors = new ArrayList<>();
        try {
            formValidator.validate(draft.formDefinition());
        } catch (IllegalArgumentException exception) {
            errors.add(safeMessage(exception));
        }
        if (errors.isEmpty()) {
            try {
                uiValidator.validate(draft.formDefinition(), draft.uiSchemaDefinition());
            } catch (IllegalArgumentException exception) {
                errors.add(safeMessage(exception));
            }
        }
        String formHash = formHasher.hash(draft.formDefinition());
        String uiHash = uiHasher.hash(draft.uiSchemaDefinition());
        return new ValidationReport(
            errors.isEmpty(),
            draft.draftId(),
            revision,
            status,
            draft.formDefinition().version(),
            draft.uiSchemaDefinition().version(),
            draft.formDefinition().fields().size(),
            draft.uiSchemaDefinition().sections().size(),
            draft.uiSchemaDefinition().nodePermissions().size(),
            formHash,
            uiHash,
            List.copyOf(errors),
            List.of()
        );
    }

    private void appendAudit(
        RequestContext context,
        String eventType,
        FormDesignDraft draft,
        Instant occurredAt,
        Map<String, String> extraAttributes
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("draftId", draft.draftId().toString());
        attributes.put("formKey", draft.formKey());
        attributes.put("status", draft.status().name());
        attributes.putAll(extraAttributes);
        auditEvents.append(new AuditEvent(
            identifiers.get(),
            context.tenantId(),
            context.operatorId(),
            eventType,
            "FORM_DESIGN_DRAFT",
            draft.draftId().toString(),
            context.requestId(),
            context.traceId(),
            occurredAt,
            Map.copyOf(attributes)
        ));
    }

    private FormDesignDraft requireDraft(String tenantId, UUID draftId) {
        return drafts.find(tenantId, draftId)
            .orElseThrow(() -> new DraftNotFoundException("form design draft was not found"));
    }

    private static void requireEditable(FormDesignDraft draft) {
        if (!draft.editable()) {
            throw new DraftStateConflictException(
                "form design draft is not editable in status " + draft.status()
            );
        }
    }

    private static void requireRevision(FormDesignDraft draft, long expectedRevision) {
        if (draft.revision() != expectedRevision) {
            throw new DraftRevisionConflictException(
                "expected revision " + expectedRevision + " but current revision is "
                    + draft.revision()
            );
        }
    }

    private static void ensureDraftBinding(
        UUID draftId,
        FormDefinition form,
        UiSchemaDefinition uiSchema
    ) {
        Objects.requireNonNull(draftId, "draftId must not be null");
        Objects.requireNonNull(form, "formDefinition must not be null");
        Objects.requireNonNull(uiSchema, "uiSchemaDefinition must not be null");
        if (!form.formKey().equals(uiSchema.formKey())
            || form.version() != uiSchema.formVersion()) {
            throw new IllegalArgumentException("draft UI Schema must bind to its Form Schema");
        }
    }

    private static FormDefinition copyForm(
        FormDefinition source,
        String formKey,
        int version,
        String name
    ) {
        return new FormDefinition(
            source.schemaVersion(),
            formKey,
            version,
            name,
            source.fields()
        );
    }

    private static UiSchemaDefinition copyUi(
        UiSchemaDefinition source,
        String formKey,
        int formVersion,
        int uiSchemaVersion,
        String name
    ) {
        return new UiSchemaDefinition(
            source.schemaVersion(),
            formKey,
            formVersion,
            uiSchemaVersion,
            name,
            source.sections(),
            source.nodePermissions()
        );
    }

    private static FormDesignDraft copyState(
        FormDesignDraft source,
        long revision,
        FormDesignDraft.Status status,
        Integer packageVersion,
        String updatedBy,
        Instant updatedAt
    ) {
        return new FormDesignDraft(
            source.draftId(),
            source.tenantId(),
            source.formKey(),
            source.name(),
            source.formDefinition(),
            source.uiSchemaDefinition(),
            source.sourceFormVersion(),
            source.sourceUiSchemaVersion(),
            revision,
            status,
            packageVersion,
            source.createdBy(),
            updatedBy,
            source.createdAt(),
            updatedAt
        );
    }

    private static PublishResult result(
        FormPackage formPackage,
        long draftRevision,
        boolean replayed
    ) {
        return new PublishResult(
            formPackage.sourceDraftId(),
            draftRevision,
            formPackage.formKey(),
            formPackage.packageVersion(),
            formPackage.formVersion(),
            formPackage.formHash(),
            formPackage.uiSchemaVersion(),
            formPackage.uiSchemaHash(),
            formPackage.packageHash(),
            formPackage.publishedBy(),
            formPackage.publishedAt(),
            replayed
        );
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? "form package validation failed"
            : exception.getMessage();
    }

    private static String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record DraftContent(
        FormDefinition formDefinition,
        UiSchemaDefinition uiSchemaDefinition,
        Integer sourceFormVersion,
        Integer sourceUiSchemaVersion
    ) {
    }

    public enum SaveMode {
        AUTO_SAVE,
        EXPLICIT
    }

    public record CreateCommand(
        RequestContext context,
        String formKey,
        String name,
        int formVersion,
        int uiSchemaVersion
    ) {
        public CreateCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            formKey = requireText(formKey, "formKey");
            name = requireText(name, "name");
            requirePositive(formVersion, "formVersion");
            requirePositive(uiSchemaVersion, "uiSchemaVersion");
        }
    }

    public record CopyPublishedCommand(
        RequestContext context,
        String formKey,
        int sourceFormVersion,
        int sourceUiSchemaVersion,
        int targetFormVersion,
        int targetUiSchemaVersion,
        String name
    ) {
        public CopyPublishedCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            formKey = requireText(formKey, "formKey");
            name = requireText(name, "name");
            requirePositive(sourceFormVersion, "sourceFormVersion");
            requirePositive(sourceUiSchemaVersion, "sourceUiSchemaVersion");
            requirePositive(targetFormVersion, "targetFormVersion");
            requirePositive(targetUiSchemaVersion, "targetUiSchemaVersion");
        }
    }

    public record UpdateCommand(
        RequestContext context,
        UUID draftId,
        long expectedRevision,
        String name,
        FormDefinition formDefinition,
        UiSchemaDefinition uiSchemaDefinition,
        SaveMode saveMode
    ) {
        public UpdateCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            draftId = Objects.requireNonNull(draftId, "draftId must not be null");
            requirePositive(expectedRevision, "expectedRevision");
            name = requireText(name, "name");
            formDefinition = Objects.requireNonNull(formDefinition);
            uiSchemaDefinition = Objects.requireNonNull(uiSchemaDefinition);
            saveMode = saveMode == null ? SaveMode.AUTO_SAVE : saveMode;
        }
    }

    public record RevisionCommand(
        RequestContext context,
        UUID draftId,
        long expectedRevision
    ) {
        public RevisionCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            draftId = Objects.requireNonNull(draftId, "draftId must not be null");
            requirePositive(expectedRevision, "expectedRevision");
        }
    }

    public record PreviewCommand(
        String tenantId,
        String operatorId,
        UUID draftId,
        String contextKey
    ) {
        public PreviewCommand {
            tenantId = requireText(tenantId, "tenantId");
            operatorId = requireText(operatorId, "operatorId");
            draftId = Objects.requireNonNull(draftId, "draftId must not be null");
            contextKey = requireText(contextKey, "contextKey");
        }
    }

    public record PublishCommand(
        RequestContext context,
        UUID draftId,
        long expectedRevision,
        int packageVersion
    ) {
        public PublishCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            draftId = Objects.requireNonNull(draftId, "draftId must not be null");
            requirePositive(expectedRevision, "expectedRevision");
            requirePositive(packageVersion, "packageVersion");
        }
    }

    public record ValidationReport(
        boolean valid,
        UUID draftId,
        long revision,
        FormDesignDraft.Status status,
        int formVersion,
        int uiSchemaVersion,
        int fieldCount,
        int sectionCount,
        int nodePermissionCount,
        String formHash,
        String uiSchemaHash,
        List<String> errors,
        List<String> warnings
    ) {
        public ValidationReport {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record PreviewResult(
        UUID draftId,
        long revision,
        String contextKey,
        FormDefinition definition,
        UiSchemaDefinition uiSchema,
        String formHash,
        String uiSchemaHash,
        Map<String, FieldAccess> fieldPermissions,
        Map<String, Boolean> requiredFields,
        Map<String, Object> values
    ) {
        public PreviewResult {
            fieldPermissions = Map.copyOf(fieldPermissions);
            requiredFields = Map.copyOf(requiredFields);
            values = Map.copyOf(values);
        }
    }

    public record PublishResult(
        UUID draftId,
        long draftRevision,
        String formKey,
        int packageVersion,
        int formVersion,
        String formHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        String packageHash,
        String publishedBy,
        Instant publishedAt,
        boolean replayedExistingPackage
    ) {
    }

    public static final class DraftNotFoundException extends RuntimeException {
        public DraftNotFoundException(String message) {
            super(message);
        }
    }

    public static final class PublishedSchemaNotFoundException extends RuntimeException {
        public PublishedSchemaNotFoundException(String message) {
            super(message);
        }
    }

    public static final class DraftRevisionConflictException extends RuntimeException {
        public DraftRevisionConflictException(String message) {
            super(message);
        }
    }

    public static final class DraftStateConflictException extends RuntimeException {
        public DraftStateConflictException(String message) {
            super(message);
        }
    }

    public static final class PackageVersionConflictException extends RuntimeException {
        public PackageVersionConflictException(String message) {
            super(message);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
