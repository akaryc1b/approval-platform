package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.FormCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.FormPage;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Validates, publishes and queries immutable form schema versions.
 */
public final class ApprovalFormService {

    private static final String PUBLISH_OPERATION = "approval-form.publish.v1";

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalFormStore forms;
    private final AuditEventSink auditEvents;
    private final FormDefinitionValidator validator;
    private final FormSchemaHasher hasher;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalFormService(
        IdempotencyGuard idempotencyGuard,
        ApprovalFormStore forms,
        AuditEventSink auditEvents,
        FormDefinitionValidator validator,
        FormSchemaHasher hasher,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(idempotencyGuard);
        this.forms = Objects.requireNonNull(forms);
        this.auditEvents = Objects.requireNonNull(auditEvents);
        this.validator = Objects.requireNonNull(validator);
        this.hasher = Objects.requireNonNull(hasher);
        this.clock = Objects.requireNonNull(clock);
        this.identifierGenerator = Objects.requireNonNull(identifierGenerator);
    }

    public ValidationResult validate(FormDefinition definition) {
        validator.validate(definition);
        return new ValidationResult(
            true,
            definition.formKey(),
            definition.version(),
            definition.fields().size(),
            hasher.hash(definition),
            List.of()
        );
    }

    public PublishResult publish(PublishCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ValidationResult validation = validate(command.definition());
        return idempotencyGuard.execute(
            command.context(),
            PUBLISH_OPERATION,
            validation.contentHash(),
            PublishResult.class,
            () -> publishOnce(command, validation)
        );
    }

    public Optional<PublishedForm> find(String tenantId, String formKey, int version) {
        return forms.find(tenantId, formKey, version);
    }

    public FormPage findForms(String tenantId, String keyword, int limit, int offset) {
        return forms.findForms(new FormCriteria(tenantId, keyword, limit, offset));
    }

    public FormDefinition purchasePaymentTemplate() {
        return PurchasePaymentTemplate.formDefinition();
    }

    private PublishResult publishOnce(PublishCommand command, ValidationResult validation) {
        RequestContext context = command.context();
        FormDefinition definition = command.definition();
        forms.lockVersion(context.tenantId(), definition.formKey(), definition.version());
        Optional<PublishedForm> existing = forms.find(
            context.tenantId(), definition.formKey(), definition.version()
        );
        if (existing.isPresent()) {
            if (!existing.get().contentHash().equals(validation.contentHash())) {
                throw new FormVersionConflictException(
                    "published form version has a different content hash"
                );
            }
            return result(existing.get(), true);
        }

        Instant now = clock.instant();
        PublishedForm published = new PublishedForm(
            context.tenantId(),
            definition,
            validation.contentHash(),
            context.operatorId(),
            now
        );
        forms.save(published);
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            "FORM_PUBLISHED",
            "APPROVAL_FORM",
            definition.formKey() + ':' + definition.version(),
            context.requestId(),
            context.traceId(),
            now,
            Map.of(
                "formKey", definition.formKey(),
                "formVersion", Integer.toString(definition.version()),
                "schemaVersion", definition.schemaVersion(),
                "fieldCount", Integer.toString(definition.fields().size()),
                "contentHash", validation.contentHash()
            )
        ));
        return result(published, false);
    }

    private static PublishResult result(PublishedForm published, boolean replayed) {
        FormDefinition definition = published.definition();
        return new PublishResult(
            definition.formKey(),
            definition.version(),
            definition.name(),
            definition.schemaVersion(),
            definition.fields().size(),
            published.contentHash(),
            published.publishedBy(),
            published.publishedAt(),
            replayed
        );
    }

    public record ValidationResult(
        boolean valid,
        String formKey,
        int version,
        int fieldCount,
        String contentHash,
        List<String> warnings
    ) {
        public ValidationResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record PublishCommand(RequestContext context, FormDefinition definition) {
        public PublishCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            definition = Objects.requireNonNull(definition, "definition must not be null");
        }
    }

    public record PublishResult(
        String formKey,
        int version,
        String name,
        String schemaVersion,
        int fieldCount,
        String contentHash,
        String publishedBy,
        Instant publishedAt,
        boolean replayedExistingVersion
    ) {
    }

    public static final class FormVersionConflictException extends RuntimeException {
        public FormVersionConflictException(String message) {
            super(message);
        }
    }
}
