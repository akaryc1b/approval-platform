package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.text.Normalizer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Governed publication facade that atomically binds an immutable Release Package to its
 * tenant-scoped lifecycle evidence. Production publishers and stores must participate in the
 * transaction opened by the shared idempotency guard.
 */
public final class ApprovalProcessReleaseLifecycleService {

    private static final String PUBLISH_OPERATION = "approval-process-release.publish.v1";
    private static final String PUBLISH_AUDIT_ACTION = "PROCESS_RELEASE_PUBLISH_AUTHORIZED";
    private static final int MIN_REASON_CODE_POINTS = 8;
    private static final int MAX_REASON_CODE_POINTS = 512;

    private final IdempotencyGuard idempotency;
    private final ApprovalDesignDraftStore drafts;
    private final ReleasePublisher publisher;
    private final ApprovalProcessReleaseStore releases;
    private final AuditEventSink auditEvents;
    private final ApprovalReleasePackageHasher hasher;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalProcessReleaseLifecycleService(
        IdempotencyGuard idempotency,
        ApprovalDesignDraftStore drafts,
        ReleasePublisher publisher,
        ApprovalProcessReleaseStore releases,
        AuditEventSink auditEvents,
        ApprovalReleasePackageHasher hasher,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        this.drafts = Objects.requireNonNull(drafts, "drafts must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.releases = Objects.requireNonNull(releases, "releases must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    public PublishResult publish(PublishCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ApprovalDesignCommands.Publish publication = command.publication();
        RequestContext context = publication.context();
        ApprovalDesignDraft draft = drafts.find(context.tenantId(), publication.draftId())
            .orElseThrow(() -> new ReleaseDraftNotFoundException(
                "Release draft was not found for the tenant"
            ));
        return idempotency.execute(
            context,
            PUBLISH_OPERATION,
            requestHash(command),
            PublishResult.class,
            () -> publishOnce(command, draft.definitionKey())
        );
    }

    private PublishResult publishOnce(PublishCommand command, String definitionKey) {
        ApprovalDesignCommands.Publish publication = command.publication();
        RequestContext context = publication.context();
        releases.lock(context.tenantId(), definitionKey);

        Optional<ApprovalProcessRelease> existing = releases.find(
            context.tenantId(),
            definitionKey,
            publication.releaseVersion()
        );
        if (existing.isPresent()) {
            ApprovalDesignResults.Publish result = publisher.publish(publication);
            requireIdentity(existing.get(), result.releasePackage());
            return new PublishResult(result, existing.get(), true);
        }

        UUID auditEventId = nextIdentifier("auditEventId");
        auditEvents.append(new AuditEvent(
            auditEventId,
            context.tenantId(),
            context.operatorId(),
            PUBLISH_AUDIT_ACTION,
            "APPROVAL_PROCESS_RELEASE",
            definitionKey + ':' + publication.releaseVersion(),
            context.requestId(),
            context.traceId(),
            clock.instant(),
            auditAttributes(command, definitionKey)
        ));

        ApprovalDesignResults.Publish result = publisher.publish(publication);
        if (result.replayedExistingRelease()) {
            throw new LifecycleEvidenceMissingException(
                "Published Release Package is missing lifecycle evidence"
            );
        }
        ApprovalReleasePackage releasePackage = result.releasePackage();
        requirePublicationIdentity(publication, definitionKey, releasePackage);
        ApprovalProcessRelease.Transition transition = new ApprovalProcessRelease.Transition(
            nextIdentifier("transitionId"),
            context.tenantId(),
            definitionKey,
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            State.DRAFT,
            State.PUBLISHED,
            1,
            command.reason(),
            context.idempotencyKey(),
            context.operatorId(),
            context.requestId(),
            context.traceId(),
            "audit-event:" + auditEventId,
            releasePackage.publishedAt()
        );
        ApprovalProcessRelease lifecycle = ApprovalProcessRelease.published(
            releasePackage,
            transition
        );
        releases.savePublished(lifecycle, transition);
        return new PublishResult(result, lifecycle, false);
    }

    private String requestHash(PublishCommand command) {
        ApprovalDesignCommands.Publish publication = command.publication();
        return hasher.hashValues(
            publication.draftId(),
            publication.expectedRevision(),
            publication.definitionVersion(),
            publication.releaseVersion(),
            publication.deploymentTarget(),
            publication.preflightHash(),
            String.join(",", publication.acknowledgedWarningCodes()),
            command.reason()
        );
    }

    private static Map<String, String> auditAttributes(
        PublishCommand command,
        String definitionKey
    ) {
        ApprovalDesignCommands.Publish publication = command.publication();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("draftId", publication.draftId().toString());
        attributes.put("definitionKey", definitionKey);
        attributes.put("targetDefinitionVersion", Integer.toString(
            publication.definitionVersion()
        ));
        attributes.put("targetReleaseVersion", Integer.toString(publication.releaseVersion()));
        attributes.put("preflightHash", publication.preflightHash());
        attributes.put("reason", command.reason());
        attributes.put(
            "acknowledgedWarningCodes",
            String.join(",", publication.acknowledgedWarningCodes())
        );
        return Map.copyOf(attributes);
    }

    private static void requirePublicationIdentity(
        ApprovalDesignCommands.Publish publication,
        String definitionKey,
        ApprovalReleasePackage releasePackage
    ) {
        RequestContext context = publication.context();
        if (!context.tenantId().equals(releasePackage.tenantId())
            || !context.operatorId().equals(releasePackage.publishedBy())
            || !definitionKey.equals(releasePackage.definitionKey())
            || publication.definitionVersion() != releasePackage.definitionVersion()
            || publication.releaseVersion() != releasePackage.releaseVersion()) {
            throw new PublicationEvidenceConflictException(
                "Published Release Package does not match the governed publication command"
            );
        }
    }

    private static void requireIdentity(
        ApprovalProcessRelease lifecycle,
        ApprovalReleasePackage releasePackage
    ) {
        if (!lifecycle.tenantId().equals(releasePackage.tenantId())
            || !lifecycle.definitionKey().equals(releasePackage.definitionKey())
            || lifecycle.releaseVersion() != releasePackage.releaseVersion()
            || !lifecycle.releasePackageHash().equals(releasePackage.packageHash())) {
            throw new PublicationEvidenceConflictException(
                "Existing lifecycle evidence does not match the immutable Release Package"
            );
        }
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(identifiers.get(), "generated " + name + " must not be null");
    }

    @FunctionalInterface
    public interface ReleasePublisher {
        ApprovalDesignResults.Publish publish(ApprovalDesignCommands.Publish command);
    }

    public record PublishCommand(
        ApprovalDesignCommands.Publish publication,
        String reason
    ) {
        public PublishCommand {
            publication = Objects.requireNonNull(publication, "publication must not be null");
            reason = normalizeReason(reason);
        }
    }

    public record PublishResult(
        ApprovalDesignResults.Publish publication,
        ApprovalProcessRelease lifecycle,
        boolean replayedExistingLifecycle
    ) {
        public PublishResult {
            publication = Objects.requireNonNull(publication, "publication must not be null");
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
            requireIdentity(lifecycle, publication.releasePackage());
        }
    }

    public static final class ReleaseDraftNotFoundException extends RuntimeException {
        public ReleaseDraftNotFoundException(String message) {
            super(message);
        }
    }

    public static final class LifecycleEvidenceMissingException extends RuntimeException {
        public LifecycleEvidenceMissingException(String message) {
            super(message);
        }
    }

    public static final class PublicationEvidenceConflictException extends RuntimeException {
        public PublicationEvidenceConflictException(String message) {
            super(message);
        }
    }

    private static String normalizeReason(String supplied) {
        Objects.requireNonNull(supplied, "reason must not be null");
        String normalized = Normalizer.normalize(supplied.trim(), Normalizer.Form.NFKC);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_REASON_CODE_POINTS || length > MAX_REASON_CODE_POINTS) {
            throw new IllegalArgumentException("reason must contain between 8 and 512 characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            int type = Character.getType(value);
            if (Character.isISOControl(value)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE) {
                throw new IllegalArgumentException("reason contains unsupported characters");
            }
        }
        return normalized;
    }
}
