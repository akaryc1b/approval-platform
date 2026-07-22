package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService.ActivationCommand;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService.ActivationResult;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Atomically switches the tenant default release lifecycle and the exact effective release
 * projection. The effective release delegate and both stores must join the transaction opened by
 * the shared idempotency guard.
 */
public final class ApprovalProcessReleaseActivationService {

    private static final String ACTIVATE_OPERATION = "approval-process-release.activate.v1";
    private static final String ROLLBACK_OPERATION = "approval-process-release.rollback.v1";

    private final IdempotencyGuard idempotency;
    private final ApprovalProcessReleaseStore releases;
    private final EffectiveReleaseActivator effectiveReleases;
    private final AuditEventSink auditEvents;
    private final ApprovalReleasePackageHasher hasher;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalProcessReleaseActivationService(
        IdempotencyGuard idempotency,
        ApprovalProcessReleaseStore releases,
        EffectiveReleaseActivator effectiveReleases,
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
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    public ActivationOutcome activate(ActivationCommand command) {
        return execute(command, Operation.ACTIVATE);
    }

    public ActivationOutcome rollback(ActivationCommand command) {
        return execute(command, Operation.ROLLBACK);
    }

    private ActivationOutcome execute(ActivationCommand command, Operation operation) {
        Objects.requireNonNull(command, "command must not be null");
        String idempotencyOperation = operation == Operation.ROLLBACK
            ? ROLLBACK_OPERATION
            : ACTIVATE_OPERATION;
        return idempotency.execute(
            command.context(),
            idempotencyOperation,
            requestHash(command, operation),
            ActivationOutcome.class,
            () -> executeOnce(command, operation)
        );
    }

    private ActivationOutcome executeOnce(ActivationCommand command, Operation operation) {
        RequestContext context = command.context();
        releases.lock(context.tenantId(), command.definitionKey());
        ApprovalProcessRelease target = releases.find(
            context.tenantId(),
            command.definitionKey(),
            command.releaseVersion()
        ).orElseThrow(() -> new ProcessReleaseNotFoundException(
            "Release lifecycle was not found for the tenant"
        ));
        Optional<ApprovalProcessRelease> current = releases.findActive(
            context.tenantId(),
            command.definitionKey()
        );

        if (target.lifecycleState() == State.ACTIVE) {
            ActivationResult effective = effectiveReleases.execute(command, operation);
            requireExactTarget(effective.effectiveRelease(), target);
            return new ActivationOutcome(effective, target, null, true);
        }
        requireTargetState(operation, target);

        Instant now = clock.instant();
        UUID auditEventId = nextIdentifier("auditEventId");
        String auditReference = "audit-event:" + auditEventId;
        auditEvents.append(new AuditEvent(
            auditEventId,
            context.tenantId(),
            context.operatorId(),
            operation.auditAction(),
            "APPROVAL_PROCESS_RELEASE",
            command.definitionKey() + ':' + command.releaseVersion(),
            context.requestId(),
            context.traceId(),
            now,
            auditAttributes(command, operation, target, current.orElse(null))
        ));

        ApprovalProcessRelease deprecated = null;
        if (current.isPresent()) {
            ApprovalProcessRelease active = current.get();
            if (active.releaseVersion() == target.releaseVersion()) {
                throw new ActivationEvidenceConflictException(
                    "Active release lookup conflicts with the target lifecycle state"
                );
            }
            ApprovalProcessRelease.Transition transition = transition(
                active,
                State.DEPRECATED,
                command,
                operation,
                "deprecate-current",
                supersedeReason(command, target),
                auditReference,
                now
            );
            deprecated = active.transitioned(transition);
            if (!releases.transition(deprecated, active.revision(), transition)) {
                throw new ActivationEvidenceConflictException(
                    "Current active release changed concurrently"
                );
            }
        }

        ApprovalProcessRelease.Transition targetTransition = transition(
            target,
            State.ACTIVE,
            command,
            operation,
            "activate-target",
            command.reason(),
            auditReference,
            now
        );
        ApprovalProcessRelease activated = target.transitioned(targetTransition);
        if (!releases.transition(activated, target.revision(), targetTransition)) {
            throw new ActivationEvidenceConflictException(
                "Target release lifecycle changed concurrently"
            );
        }

        ActivationResult effective = effectiveReleases.execute(command, operation);
        requireExactTarget(effective.effectiveRelease(), activated);
        return new ActivationOutcome(effective, activated, deprecated, false);
    }

    private static void requireTargetState(Operation operation, ApprovalProcessRelease target) {
        if (target.lifecycleState() == State.RETIRED) {
            throw new ActivationEvidenceConflictException("Retired releases cannot be activated");
        }
        if (operation == Operation.ROLLBACK && target.lifecycleState() != State.DEPRECATED) {
            throw new ActivationEvidenceConflictException(
                "Rollback target must be a deprecated previously active release"
            );
        }
        if (target.lifecycleState() != State.PUBLISHED
            && target.lifecycleState() != State.DEPRECATED) {
            throw new ActivationEvidenceConflictException(
                "Release lifecycle state is not eligible for activation"
            );
        }
    }

    private ApprovalProcessRelease.Transition transition(
        ApprovalProcessRelease release,
        State targetState,
        ActivationCommand command,
        Operation operation,
        String phase,
        String reason,
        String auditReference,
        Instant happenedAt
    ) {
        RequestContext context = command.context();
        return new ApprovalProcessRelease.Transition(
            nextIdentifier("transitionId"),
            release.tenantId(),
            release.definitionKey(),
            release.releaseVersion(),
            release.releasePackageHash(),
            release.lifecycleState(),
            targetState,
            release.revision() + 1,
            reason,
            transitionIdempotencyKey(command, operation, phase),
            context.operatorId(),
            context.requestId(),
            context.traceId(),
            auditReference,
            happenedAt
        );
    }

    private String transitionIdempotencyKey(
        ActivationCommand command,
        Operation operation,
        String phase
    ) {
        return operation.name().toLowerCase(Locale.ROOT)
            + ':'
            + hasher.hashValues(
                command.context().tenantId(),
                command.definitionKey(),
                command.releaseVersion(),
                operation.name(),
                phase,
                command.context().idempotencyKey()
            );
    }

    private String requestHash(ActivationCommand command, Operation operation) {
        return hasher.hashValues(
            operation.name(),
            command.definitionKey(),
            command.releaseVersion(),
            command.expectedRevision(),
            command.reason()
        );
    }

    private static Map<String, String> auditAttributes(
        ActivationCommand command,
        Operation operation,
        ApprovalProcessRelease target,
        ApprovalProcessRelease current
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("operation", operation.name());
        attributes.put("definitionKey", command.definitionKey());
        attributes.put("targetReleaseVersion", Integer.toString(command.releaseVersion()));
        attributes.put("targetReleasePackageHash", target.releasePackageHash());
        attributes.put("targetLifecycleState", target.lifecycleState().name());
        attributes.put("reason", command.reason());
        if (current != null) {
            attributes.put("currentActiveReleaseVersion", Integer.toString(
                current.releaseVersion()
            ));
        }
        return Map.copyOf(attributes);
    }

    private static String supersedeReason(
        ActivationCommand command,
        ApprovalProcessRelease target
    ) {
        String value = "Superseded by release " + target.releaseVersion() + ": "
            + command.reason();
        int length = value.codePointCount(0, value.length());
        if (length <= 1000) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, 1000));
    }

    private static void requireExactTarget(
        ApprovalEffectiveRelease effective,
        ApprovalProcessRelease target
    ) {
        if (!effective.tenantId().equals(target.tenantId())
            || !effective.definitionKey().equals(target.definitionKey())
            || effective.effectiveReleaseVersion() != target.releaseVersion()
            || !effective.releasePackageHash().equals(target.releasePackageHash())) {
            throw new ActivationEvidenceConflictException(
                "Effective release does not match the activated lifecycle evidence"
            );
        }
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(identifiers.get(), "generated " + name + " must not be null");
    }

    public enum Operation {
        ACTIVATE("PROCESS_RELEASE_ACTIVATION_AUTHORIZED"),
        ROLLBACK("PROCESS_RELEASE_ROLLBACK_AUTHORIZED");

        private final String auditAction;

        Operation(String auditAction) {
            this.auditAction = auditAction;
        }

        String auditAction() {
            return auditAction;
        }
    }

    @FunctionalInterface
    public interface EffectiveReleaseActivator {
        ActivationResult execute(ActivationCommand command, Operation operation);
    }

    public record ActivationOutcome(
        ActivationResult effective,
        ApprovalProcessRelease activeRelease,
        ApprovalProcessRelease deprecatedRelease,
        boolean replayedExistingLifecycle
    ) {
        public ActivationOutcome {
            effective = Objects.requireNonNull(effective, "effective must not be null");
            activeRelease = Objects.requireNonNull(
                activeRelease,
                "activeRelease must not be null"
            );
            requireExactTarget(effective.effectiveRelease(), activeRelease);
            if (activeRelease.lifecycleState() != State.ACTIVE) {
                throw new IllegalArgumentException("activeRelease must be ACTIVE");
            }
            if (deprecatedRelease != null
                && deprecatedRelease.lifecycleState() != State.DEPRECATED) {
                throw new IllegalArgumentException(
                    "deprecatedRelease must be DEPRECATED when present"
                );
            }
        }
    }

    public static final class ProcessReleaseNotFoundException extends RuntimeException {
        public ProcessReleaseNotFoundException(String message) {
            super(message);
        }
    }

    public static final class ActivationEvidenceConflictException extends RuntimeException {
        public ActivationEvidenceConflictException(String message) {
            super(message);
        }
    }
}
