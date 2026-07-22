package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseDeactivationPort;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Governed explicit deprecation and terminal retirement for immutable process releases. */
public final class ApprovalProcessReleaseDispositionService {

    private static final String DEPRECATE_OPERATION =
        "approval-process-release.deprecate.v1";
    private static final String RETIRE_OPERATION = "approval-process-release.retire.v1";
    private static final int MIN_REASON_CODE_POINTS = 8;
    private static final int MAX_REASON_CODE_POINTS = 512;

    private final IdempotencyGuard idempotency;
    private final ApprovalProcessReleaseStore releases;
    private final ApprovalEffectiveReleaseStore effectiveReleases;
    private final ApprovalEffectiveReleaseDeactivationPort deactivation;
    private final ApprovalRuntimeBindingStore runtimeBindings;
    private final AuditEventSink auditEvents;
    private final ApprovalReleasePackageHasher hasher;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalProcessReleaseDispositionService(
        IdempotencyGuard idempotency,
        ApprovalProcessReleaseStore releases,
        ApprovalEffectiveReleaseStore effectiveReleases,
        ApprovalEffectiveReleaseDeactivationPort deactivation,
        ApprovalRuntimeBindingStore runtimeBindings,
        AuditEventSink auditEvents,
        ApprovalReleasePackageHasher hasher,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        this.releases = Objects.requireNonNull(releases, "releases must not be null");
        this.effectiveReleases = Objects.requireNonNull(
            effectiveReleases,
            "effectiveReleases must not be null"
        );
        this.deactivation = Objects.requireNonNull(
            deactivation,
            "deactivation must not be null"
        );
        this.runtimeBindings = Objects.requireNonNull(
            runtimeBindings,
            "runtimeBindings must not be null"
        );
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    public DispositionResult deprecate(DispositionCommand command) {
        return execute(command, Operation.DEPRECATE);
    }

    public DispositionResult retire(DispositionCommand command) {
        return execute(command, Operation.RETIRE);
    }

    private DispositionResult execute(DispositionCommand command, Operation operation) {
        Objects.requireNonNull(command, "command must not be null");
        return idempotency.execute(
            command.context(),
            operation == Operation.DEPRECATE ? DEPRECATE_OPERATION : RETIRE_OPERATION,
            requestHash(command, operation),
            DispositionResult.class,
            () -> executeOnce(command, operation)
        );
    }

    private DispositionResult executeOnce(
        DispositionCommand command,
        Operation operation
    ) {
        RequestContext context = command.context();
        releases.lock(context.tenantId(), command.definitionKey());
        effectiveReleases.lock(context.tenantId(), command.definitionKey());

        ApprovalProcessRelease current = releases.find(
            context.tenantId(),
            command.definitionKey(),
            command.releaseVersion()
        ).orElseThrow(() -> new ProcessReleaseNotFoundException(
            "Release lifecycle was not found for the tenant"
        ));
        requireExpectedRevision(command, current);
        Optional<ApprovalEffectiveRelease> effective = effectiveReleases.find(
            context.tenantId(),
            command.definitionKey()
        );
        long runtimeUsageCount = runtimeBindings.countReleaseUsage(
            context.tenantId(),
            command.definitionKey(),
            command.releaseVersion()
        );
        State targetState = operation.targetState();
        if (current.lifecycleState() == targetState) {
            requireNotEffective(current, effective);
            return new DispositionResult(current, runtimeUsageCount, true);
        }
        requirePermitted(operation, current, effective);

        Instant now = clock.instant();
        UUID auditEventId = nextIdentifier("auditEventId");
        String auditReference = "audit-event:" + auditEventId;
        auditEvents.append(new AuditEvent(
            auditEventId,
            context.tenantId(),
            context.operatorId(),
            operation.auditAction(),
            "APPROVAL_PROCESS_RELEASE",
            current.definitionKey() + ':' + current.releaseVersion(),
            context.requestId(),
            context.traceId(),
            now,
            auditAttributes(command, operation, current, runtimeUsageCount)
        ));

        ApprovalProcessRelease.Transition transition = new ApprovalProcessRelease.Transition(
            nextIdentifier("transitionId"),
            current.tenantId(),
            current.definitionKey(),
            current.releaseVersion(),
            current.releasePackageHash(),
            current.lifecycleState(),
            targetState,
            current.revision() + 1,
            command.reason(),
            transitionIdempotencyKey(command, operation),
            context.operatorId(),
            context.requestId(),
            context.traceId(),
            auditReference,
            now
        );
        ApprovalProcessRelease next = current.transitioned(transition);
        if (!releases.transition(next, current.revision(), transition)) {
            throw new DispositionEvidenceConflictException(
                "Release lifecycle changed concurrently"
            );
        }
        if (operation == Operation.DEPRECATE) {
            ApprovalEffectiveRelease exact = effective.orElseThrow();
            if (!deactivation.clear(
                context.tenantId(),
                command.definitionKey(),
                exact.revision()
            )) {
                throw new DispositionEvidenceConflictException(
                    "Current effective release changed concurrently"
                );
            }
        }
        return new DispositionResult(next, runtimeUsageCount, false);
    }

    private String requestHash(DispositionCommand command, Operation operation) {
        return hasher.hashValues(
            operation.name(),
            command.definitionKey(),
            command.releaseVersion(),
            command.expectedRevision(),
            command.reason()
        );
    }

    private String transitionIdempotencyKey(
        DispositionCommand command,
        Operation operation
    ) {
        return operation.name().toLowerCase(Locale.ROOT)
            + ':'
            + hasher.hashValues(
                command.context().tenantId(),
                command.definitionKey(),
                command.releaseVersion(),
                operation.name(),
                command.context().idempotencyKey()
            );
    }

    private static void requireExpectedRevision(
        DispositionCommand command,
        ApprovalProcessRelease current
    ) {
        if (current.revision() != command.expectedRevision()) {
            throw new DispositionEvidenceConflictException(
                "Release lifecycle revision changed; expected "
                    + command.expectedRevision()
                    + " but was "
                    + current.revision()
            );
        }
    }

    private static void requirePermitted(
        Operation operation,
        ApprovalProcessRelease current,
        Optional<ApprovalEffectiveRelease> effective
    ) {
        if (operation == Operation.DEPRECATE) {
            if (current.lifecycleState() != State.ACTIVE) {
                throw new DispositionEvidenceConflictException(
                    "Only an ACTIVE release can be explicitly deprecated"
                );
            }
            ApprovalEffectiveRelease exact = effective.orElseThrow(() ->
                new DispositionEvidenceConflictException(
                    "ACTIVE lifecycle is missing its effective release projection"
                )
            );
            requireExactEffective(current, exact);
            return;
        }
        if (current.lifecycleState() != State.PUBLISHED
            && current.lifecycleState() != State.DEPRECATED) {
            throw new DispositionEvidenceConflictException(
                "Only a PUBLISHED or DEPRECATED release can be retired"
            );
        }
        requireNotEffective(current, effective);
    }

    private static void requireNotEffective(
        ApprovalProcessRelease current,
        Optional<ApprovalEffectiveRelease> effective
    ) {
        if (effective.isPresent()
            && effective.get().effectiveReleaseVersion() == current.releaseVersion()) {
            throw new DispositionEvidenceConflictException(
                "Non-ACTIVE lifecycle is still referenced by the effective release projection"
            );
        }
    }

    private static void requireExactEffective(
        ApprovalProcessRelease lifecycle,
        ApprovalEffectiveRelease effective
    ) {
        if (!effective.tenantId().equals(lifecycle.tenantId())
            || !effective.definitionKey().equals(lifecycle.definitionKey())
            || effective.effectiveReleaseVersion() != lifecycle.releaseVersion()
            || !effective.releasePackageHash().equals(lifecycle.releasePackageHash())) {
            throw new DispositionEvidenceConflictException(
                "Effective release does not match the ACTIVE lifecycle evidence"
            );
        }
    }

    private static Map<String, String> auditAttributes(
        DispositionCommand command,
        Operation operation,
        ApprovalProcessRelease current,
        long runtimeUsageCount
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("operation", operation.name());
        attributes.put("definitionKey", current.definitionKey());
        attributes.put("releaseVersion", Integer.toString(current.releaseVersion()));
        attributes.put("releasePackageHash", current.releasePackageHash());
        attributes.put("fromLifecycleState", current.lifecycleState().name());
        attributes.put("toLifecycleState", operation.targetState().name());
        attributes.put("runtimeUsageCount", Long.toString(runtimeUsageCount));
        attributes.put("reason", command.reason());
        return Map.copyOf(attributes);
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
    }

    public enum Operation {
        DEPRECATE("PROCESS_RELEASE_DEPRECATION_AUTHORIZED", State.DEPRECATED),
        RETIRE("PROCESS_RELEASE_RETIREMENT_AUTHORIZED", State.RETIRED);

        private final String auditAction;
        private final State targetState;

        Operation(String auditAction, State targetState) {
            this.auditAction = auditAction;
            this.targetState = targetState;
        }

        String auditAction() {
            return auditAction;
        }

        State targetState() {
            return targetState;
        }
    }

    public record DispositionCommand(
        RequestContext context,
        String definitionKey,
        int releaseVersion,
        long expectedRevision,
        String reason
    ) {
        public DispositionCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            definitionKey = requireText(definitionKey, "definitionKey");
            if (releaseVersion < 1) {
                throw new IllegalArgumentException("releaseVersion must be positive");
            }
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
            reason = normalizeReason(reason);
        }
    }

    public record DispositionResult(
        ApprovalProcessRelease lifecycle,
        long runtimeUsageCount,
        boolean replayedExistingDisposition
    ) {
        public DispositionResult {
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
            if (runtimeUsageCount < 0) {
                throw new IllegalArgumentException("runtimeUsageCount must not be negative");
            }
        }
    }

    public static final class ProcessReleaseNotFoundException extends RuntimeException {
        public ProcessReleaseNotFoundException(String message) {
            super(message);
        }
    }

    public static final class DispositionEvidenceConflictException extends RuntimeException {
        public DispositionEvidenceConflictException(String message) {
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

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
