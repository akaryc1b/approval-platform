package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Activates and rolls back exact, already deployed immutable Approval Release Packages. */
public final class ApprovalEffectiveReleaseService {

    private static final String ACTIVATE_OPERATION = "approval-release.activate.v1";
    private static final String ROLLBACK_OPERATION = "approval-release.rollback.v1";

    private final IdempotencyGuard idempotency;
    private final ApprovalReleasePackageStore releases;
    private final ApprovalReleaseDeploymentStore deployments;
    private final ApprovalEffectiveReleaseStore effectiveReleases;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalEffectiveReleaseService(
        IdempotencyGuard idempotency,
        ApprovalReleasePackageStore releases,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalEffectiveReleaseStore effectiveReleases,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.releases = Objects.requireNonNull(releases);
        this.deployments = Objects.requireNonNull(deployments);
        this.effectiveReleases = Objects.requireNonNull(effectiveReleases);
        this.auditEvents = Objects.requireNonNull(auditEvents);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public ActivationResult activate(ActivationCommand command) {
        return execute(command, ApprovalEffectiveRelease.Action.ACTIVATE);
    }

    public ActivationResult rollback(ActivationCommand command) {
        return execute(command, ApprovalEffectiveRelease.Action.ROLLBACK);
    }

    public Optional<ApprovalEffectiveRelease> findCurrent(
        String tenantId,
        String definitionKey
    ) {
        return effectiveReleases.find(
            requireText(tenantId, "tenantId"),
            requireText(definitionKey, "definitionKey")
        );
    }

    public ApprovalEffectiveReleaseStore.ActivationPage findHistory(
        String tenantId,
        String definitionKey,
        int limit,
        int offset
    ) {
        return effectiveReleases.findHistory(
            new ApprovalEffectiveReleaseStore.ActivationCriteria(
                tenantId,
                definitionKey,
                limit,
                offset
            )
        );
    }

    private ActivationResult execute(
        ActivationCommand command,
        ApprovalEffectiveRelease.Action action
    ) {
        Objects.requireNonNull(command, "command must not be null");
        String operation = action == ApprovalEffectiveRelease.Action.ROLLBACK
            ? ROLLBACK_OPERATION
            : ACTIVATE_OPERATION;
        return idempotency.execute(
            command.context(),
            operation,
            requestHash(command, action),
            ActivationResult.class,
            () -> activateOnce(command, action)
        );
    }

    private ActivationResult activateOnce(
        ActivationCommand command,
        ApprovalEffectiveRelease.Action action
    ) {
        String tenantId = command.context().tenantId();
        ApprovalReleasePackage releasePackage = releases.find(
            tenantId,
            command.definitionKey(),
            command.releaseVersion()
        ).orElseThrow(() -> new ReleasePackageNotFoundException(
            "Release Package was not found for the tenant"
        ));
        ApprovalReleaseDeployment deployment = deployments.find(
            tenantId,
            command.definitionKey(),
            command.releaseVersion()
        ).orElseThrow(() -> new DeploymentNotReadyException(
            "Release Package has not been deployed"
        ));
        requireDeployable(releasePackage, deployment);

        effectiveReleases.lock(tenantId, command.definitionKey());
        ApprovalEffectiveRelease current = effectiveReleases.find(
            tenantId,
            command.definitionKey()
        ).orElse(null);
        if (sameTarget(current, releasePackage, deployment)) {
            return new ActivationResult(current, null, true);
        }
        requireExpectedRevision(command, current);
        requireRollbackTarget(action, tenantId, command.definitionKey(), releasePackage, current);

        Instant now = clock.instant();
        long revision = current == null ? 1 : current.revision() + 1;
        ApprovalEffectiveRelease next = effectiveRelease(
            command,
            releasePackage,
            deployment,
            current,
            revision,
            now
        );
        ApprovalEffectiveRelease.Activation activation = activation(
            command,
            releasePackage,
            deployment,
            current,
            action,
            revision,
            now
        );
        if (current == null) {
            effectiveReleases.save(next, activation);
        } else if (!effectiveReleases.update(next, current.revision(), activation)) {
            throw new ActivationConflictException(
                "effective release changed concurrently"
            );
        }
        appendAudit(command.context(), next, activation);
        return new ActivationResult(next, activation, false);
    }

    private static void requireDeployable(
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment
    ) {
        if (deployment.status() != ApprovalReleaseDeployment.Status.DEPLOYED) {
            throw new DeploymentNotReadyException(
                "only a successfully deployed Release Package can be activated"
            );
        }
        if (!releasePackage.packageHash().equals(deployment.releasePackageHash())) {
            throw new ActivationConflictException(
                "deployment Package hash does not match the immutable Release Package"
            );
        }
        if (!releasePackage.definitionKey().equals(deployment.definitionKey())
            || releasePackage.releaseVersion() != deployment.releaseVersion()) {
            throw new ActivationConflictException(
                "deployment identity does not match the immutable Release Package"
            );
        }
    }

    private static boolean sameTarget(
        ApprovalEffectiveRelease current,
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment
    ) {
        return current != null
            && current.effectiveReleaseVersion() == releasePackage.releaseVersion()
            && current.releasePackageHash().equals(releasePackage.packageHash())
            && current.engineDefinitionId().equals(deployment.engineDefinitionId());
    }

    private static void requireExpectedRevision(
        ActivationCommand command,
        ApprovalEffectiveRelease current
    ) {
        if (command.expectedRevision() == null) {
            return;
        }
        long actual = current == null ? 0 : current.revision();
        if (command.expectedRevision() != actual) {
            throw new ActivationConflictException(
                "effective release revision changed; expected "
                    + command.expectedRevision()
                    + " but was "
                    + actual
            );
        }
    }

    private void requireRollbackTarget(
        ApprovalEffectiveRelease.Action action,
        String tenantId,
        String definitionKey,
        ApprovalReleasePackage releasePackage,
        ApprovalEffectiveRelease current
    ) {
        if (action != ApprovalEffectiveRelease.Action.ROLLBACK) {
            return;
        }
        if (current == null) {
            throw new EffectiveReleaseNotFoundException(
                "there is no current effective release to roll back"
            );
        }
        if (!effectiveReleases.wasActivated(
            tenantId,
            definitionKey,
            releasePackage.releaseVersion()
        )) {
            throw new ActivationConflictException(
                "rollback target has never been an effective release"
            );
        }
    }

    private static ApprovalEffectiveRelease effectiveRelease(
        ActivationCommand command,
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment,
        ApprovalEffectiveRelease current,
        long revision,
        Instant now
    ) {
        return new ApprovalEffectiveRelease(
            command.context().tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            current == null ? null : current.effectiveReleaseVersion(),
            releasePackage.packageHash(),
            releasePackage.definitionVersion(),
            releasePackage.definitionHash(),
            releasePackage.formPackageVersion(),
            releasePackage.formPackageHash(),
            releasePackage.formVersion(),
            releasePackage.formHash(),
            releasePackage.uiSchemaVersion(),
            releasePackage.uiSchemaHash(),
            releasePackage.compilerVersion(),
            releasePackage.compiledArtifactHash(),
            releasePackage.bpmnHash(),
            releasePackage.deploymentMetadataHash(),
            deployment.engineDeploymentId(),
            deployment.engineDefinitionId(),
            deployment.engineVersion(),
            ApprovalEffectiveRelease.Status.ACTIVE,
            revision,
            command.context().operatorId(),
            now,
            command.reason(),
            command.context().requestId(),
            command.context().traceId()
        );
    }

    private ApprovalEffectiveRelease.Activation activation(
        ActivationCommand command,
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment,
        ApprovalEffectiveRelease current,
        ApprovalEffectiveRelease.Action action,
        long revision,
        Instant now
    ) {
        return new ApprovalEffectiveRelease.Activation(
            identifiers.get(),
            command.context().tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            current == null ? null : current.effectiveReleaseVersion(),
            releasePackage.packageHash(),
            releasePackage.definitionVersion(),
            releasePackage.formPackageVersion(),
            releasePackage.compilerVersion(),
            deployment.engineDeploymentId(),
            deployment.engineDefinitionId(),
            deployment.engineVersion(),
            action,
            revision,
            command.context().operatorId(),
            now,
            command.reason(),
            command.context().requestId(),
            command.context().traceId()
        );
    }

    private void appendAudit(
        RequestContext context,
        ApprovalEffectiveRelease effectiveRelease,
        ApprovalEffectiveRelease.Activation activation
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(
            "releaseVersion",
            Integer.toString(effectiveRelease.effectiveReleaseVersion())
        );
        attributes.put("releasePackageHash", effectiveRelease.releasePackageHash());
        attributes.put(
            "definitionVersion",
            Integer.toString(effectiveRelease.definitionVersion())
        );
        attributes.put(
            "formPackageVersion",
            Integer.toString(effectiveRelease.formPackageVersion())
        );
        attributes.put("compilerVersion", effectiveRelease.compilerVersion());
        attributes.put("engineDeploymentId", effectiveRelease.engineDeploymentId());
        attributes.put("engineDefinitionId", effectiveRelease.engineDefinitionId());
        attributes.put("action", activation.action().name());
        attributes.put("revision", Long.toString(effectiveRelease.revision()));
        if (effectiveRelease.previousReleaseVersion() != null) {
            attributes.put(
                "previousReleaseVersion",
                Integer.toString(effectiveRelease.previousReleaseVersion())
            );
        }
        auditEvents.append(new AuditEvent(
            identifiers.get(),
            context.tenantId(),
            context.operatorId(),
            activation.action() == ApprovalEffectiveRelease.Action.ROLLBACK
                ? "APPROVAL_RELEASE_ROLLED_BACK"
                : "APPROVAL_RELEASE_ACTIVATED",
            "APPROVAL_EFFECTIVE_RELEASE",
            effectiveRelease.definitionKey(),
            context.requestId(),
            context.traceId(),
            effectiveRelease.activatedAt(),
            Map.copyOf(attributes)
        ));
    }

    private static String requestHash(
        ActivationCommand command,
        ApprovalEffectiveRelease.Action action
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, action.name());
            update(digest, command.definitionKey());
            update(digest, Integer.toString(command.releaseVersion()));
            update(
                digest,
                command.expectedRevision() == null
                    ? ""
                    : Long.toString(command.expectedRevision())
            );
            update(digest, command.reason());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    public record ActivationCommand(
        RequestContext context,
        String definitionKey,
        int releaseVersion,
        Long expectedRevision,
        String reason
    ) {
        public ActivationCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            definitionKey = requireText(definitionKey, "definitionKey");
            if (releaseVersion < 1) {
                throw new IllegalArgumentException("releaseVersion must be positive");
            }
            if (expectedRevision != null && expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
            reason = requireText(reason, "reason");
            if (reason.length() > 1000) {
                throw new IllegalArgumentException("reason must not exceed 1000 characters");
            }
        }
    }

    public record ActivationResult(
        ApprovalEffectiveRelease effectiveRelease,
        ApprovalEffectiveRelease.Activation activation,
        boolean replayedExistingActivation
    ) {
        public ActivationResult {
            effectiveRelease = Objects.requireNonNull(
                effectiveRelease,
                "effectiveRelease must not be null"
            );
        }
    }

    public static final class ReleasePackageNotFoundException extends RuntimeException {
        public ReleasePackageNotFoundException(String message) {
            super(message);
        }
    }

    public static final class EffectiveReleaseNotFoundException extends RuntimeException {
        public EffectiveReleaseNotFoundException(String message) {
            super(message);
        }
    }

    public static final class DeploymentNotReadyException extends RuntimeException {
        public DeploymentNotReadyException(String message) {
            super(message);
        }
    }

    public static final class ActivationConflictException extends RuntimeException {
        public ActivationConflictException(String message) {
            super(message);
        }
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
