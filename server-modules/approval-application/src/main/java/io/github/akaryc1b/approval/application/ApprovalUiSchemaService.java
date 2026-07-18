package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Validates, publishes and queries immutable UI Schema versions. */
public final class ApprovalUiSchemaService {

    private static final String PUBLISH_OPERATION = "approval-ui-schema.publish.v1";

    private final IdempotencyGuard idempotency;
    private final ApprovalFormStore forms;
    private final ApprovalUiSchemaStore uiSchemas;
    private final AuditEventSink auditEvents;
    private final UiSchemaDefinitionValidator validator;
    private final UiSchemaHasher hasher;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalUiSchemaService(
        IdempotencyGuard idempotency,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        AuditEventSink auditEvents,
        UiSchemaDefinitionValidator validator,
        UiSchemaHasher hasher,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.forms = Objects.requireNonNull(forms);
        this.uiSchemas = Objects.requireNonNull(uiSchemas);
        this.auditEvents = Objects.requireNonNull(auditEvents);
        this.validator = Objects.requireNonNull(validator);
        this.hasher = Objects.requireNonNull(hasher);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public ValidationResult validate(String tenantId, UiSchemaDefinition definition) {
        FormDefinition form = forms.find(tenantId, definition.formKey(), definition.formVersion())
            .orElseThrow(() -> new IllegalArgumentException("published Form Schema version was not found"))
            .definition();
        validator.validate(form, definition);
        return new ValidationResult(
            true,
            definition.formKey(),
            definition.formVersion(),
            definition.version(),
            definition.sections().size(),
            hasher.hash(definition)
        );
    }

    public PublishResult publish(PublishCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ValidationResult validation = validate(command.context().tenantId(), command.definition());
        return idempotency.execute(
            command.context(),
            PUBLISH_OPERATION,
            validation.contentHash(),
            PublishResult.class,
            () -> publishOnce(command, validation)
        );
    }

    public Optional<PublishedUiSchema> find(
        String tenantId,
        String formKey,
        int formVersion,
        int uiSchemaVersion
    ) {
        return uiSchemas.find(tenantId, formKey, formVersion, uiSchemaVersion);
    }

    public Optional<PublishedUiSchema> findLatest(
        String tenantId,
        String formKey,
        int formVersion
    ) {
        return uiSchemas.findLatest(tenantId, formKey, formVersion);
    }

    public UiSchemaDefinition purchasePaymentTemplate() {
        return PurchasePaymentTemplate.uiSchemaDefinition();
    }

    private PublishResult publishOnce(PublishCommand command, ValidationResult validation) {
        RequestContext context = command.context();
        UiSchemaDefinition definition = command.definition();
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
            if (!existing.get().contentHash().equals(validation.contentHash())) {
                throw new UiSchemaVersionConflictException(
                    "published UI Schema version has a different content hash"
                );
            }
            return result(existing.get(), true);
        }

        Instant now = clock.instant();
        PublishedUiSchema published = new PublishedUiSchema(
            context.tenantId(),
            definition,
            validation.contentHash(),
            context.operatorId(),
            now
        );
        uiSchemas.save(published);
        auditEvents.append(new AuditEvent(
            identifiers.get(),
            context.tenantId(),
            context.operatorId(),
            "UI_SCHEMA_PUBLISHED",
            "APPROVAL_UI_SCHEMA",
            definition.formKey() + ':' + definition.formVersion() + ':' + definition.version(),
            context.requestId(),
            context.traceId(),
            now,
            Map.of(
                "formKey", definition.formKey(),
                "formVersion", Integer.toString(definition.formVersion()),
                "uiSchemaVersion", Integer.toString(definition.version()),
                "contentHash", validation.contentHash()
            )
        ));
        return result(published, false);
    }

    private static PublishResult result(PublishedUiSchema published, boolean replayed) {
        UiSchemaDefinition definition = published.definition();
        return new PublishResult(
            definition.formKey(),
            definition.formVersion(),
            definition.version(),
            definition.name(),
            published.contentHash(),
            published.publishedBy(),
            published.publishedAt(),
            replayed
        );
    }

    public record ValidationResult(
        boolean valid,
        String formKey,
        int formVersion,
        int uiSchemaVersion,
        int sectionCount,
        String contentHash
    ) {
    }

    public record PublishCommand(RequestContext context, UiSchemaDefinition definition) {
        public PublishCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            definition = Objects.requireNonNull(definition, "definition must not be null");
        }
    }

    public record PublishResult(
        String formKey,
        int formVersion,
        int uiSchemaVersion,
        String name,
        String contentHash,
        String publishedBy,
        Instant publishedAt,
        boolean replayedExistingVersion
    ) {
    }

    public static final class UiSchemaVersionConflictException extends RuntimeException {
        public UiSchemaVersionConflictException(String message) {
            super(message);
        }
    }
}
