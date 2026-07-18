package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.engine.ApprovalEngine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicit, idempotent deployment of one immutable Approval Release Package. */
public final class ApprovalReleaseDeploymentService {

    private static final String DEPLOY_OPERATION = "approval-release.deploy.v1";

    private final IdempotencyGuard idempotency;
    private final ApprovalReleasePackageStore releases;
    private final ApprovalReleaseDeploymentStore deployments;
    private final ApprovalEngine engine;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalReleaseDeploymentService(
        IdempotencyGuard idempotency,
        ApprovalReleasePackageStore releases,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalEngine engine,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.releases = Objects.requireNonNull(releases);
        this.deployments = Objects.requireNonNull(deployments);
        this.engine = Objects.requireNonNull(engine);
        this.auditEvents = Objects.requireNonNull(auditEvents);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public DeploymentResult deploy(DeployCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return idempotency.execute(
            command.context(),
            DEPLOY_OPERATION,
            requestHash(command),
            DeploymentResult.class,
            () -> deployOnce(command)
        );
    }

    public Optional<ApprovalReleaseDeployment> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return deployments.find(tenantId, definitionKey, releaseVersion);
    }

    private DeploymentResult deployOnce(DeployCommand command) {
        String tenantId = command.context().tenantId();
        releases.lockVersion(tenantId, command.definitionKey(), command.releaseVersion());
        ApprovalReleasePackage releasePackage = releases.find(
            tenantId,
            command.definitionKey(),
            command.releaseVersion()
        ).orElseThrow(() -> new ReleasePackageNotFoundException(
            "Release Package was not found for the tenant"
        ));

        deployments.lock(tenantId, command.definitionKey(), command.releaseVersion());
        ApprovalReleaseDeployment current = deployments.find(
            tenantId,
            command.definitionKey(),
            command.releaseVersion()
        ).orElse(null);
        if (current != null) {
            requirePackageIdentity(current, releasePackage);
            if (current.status() == ApprovalReleaseDeployment.Status.DEPLOYED) {
                return new DeploymentResult(current, true);
            }
        }

        ApprovalReleaseDeployment pending = pending(command, releasePackage, current);
        persistPending(pending, current);
        try {
            ApprovalEngine.DeploymentResult engineResult = engine.deploy(
                new ApprovalEngine.DeployCommand(
                    tenantId,
                    releasePackage.definitionKey(),
                    releasePackage.definitionVersion(),
                    releasePackage.bpmnResourceName(),
                    releasePackage.bpmnArtifact(),
                    releasePackage.bpmnHash()
                )
            );
            ApprovalReleaseDeployment deployed = deployed(pending, engineResult);
            requireUpdated(deployed, pending.attemptCount());
            appendAudit(
                command.context(),
                "APPROVAL_RELEASE_DEPLOYED",
                deployed,
                Map.of(
                    "releasePackageHash", releasePackage.packageHash(),
                    "engineDeploymentId", engineResult.deploymentId(),
                    "engineDefinitionId", engineResult.engineDefinitionId()
                )
            );
            return new DeploymentResult(deployed, false);
        } catch (RuntimeException exception) {
            ApprovalReleaseDeployment failed = failed(pending, exception);
            requireUpdated(failed, pending.attemptCount());
            appendAudit(
                command.context(),
                "APPROVAL_RELEASE_DEPLOYMENT_FAILED",
                failed,
                Map.of(
                    "releasePackageHash", releasePackage.packageHash(),
                    "errorCode", failed.lastErrorCode()
                )
            );
            return new DeploymentResult(failed, false);
        }
    }

    private ApprovalReleaseDeployment pending(
        DeployCommand command,
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment current
    ) {
        Instant now = clock.instant();
        return new ApprovalReleaseDeployment(
            current == null ? identifiers.get() : current.deploymentRecordId(),
            command.context().tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            ApprovalReleaseDeployment.Status.PENDING,
            current == null ? 1 : current.attemptCount() + 1,
            null,
            null,
            null,
            null,
            null,
            command.context().operatorId(),
            current == null ? now : current.createdAt(),
            now,
            null
        );
    }

    private void persistPending(
        ApprovalReleaseDeployment pending,
        ApprovalReleaseDeployment current
    ) {
        if (current == null) {
            deployments.save(pending);
        } else if (!deployments.update(pending, current.attemptCount())) {
            throw new ReleaseDeploymentConflictException(
                "Release Package deployment attempt changed concurrently"
            );
        }
    }

    private void requireUpdated(
        ApprovalReleaseDeployment deployment,
        int expectedAttemptCount
    ) {
        if (!deployments.update(deployment, expectedAttemptCount)) {
            throw new ReleaseDeploymentConflictException(
                "Release Package deployment state changed concurrently"
            );
        }
    }

    private ApprovalReleaseDeployment deployed(
        ApprovalReleaseDeployment pending,
        ApprovalEngine.DeploymentResult engineResult
    ) {
        Instant now = clock.instant();
        return new ApprovalReleaseDeployment(
            pending.deploymentRecordId(),
            pending.tenantId(),
            pending.definitionKey(),
            pending.releaseVersion(),
            pending.releasePackageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            pending.attemptCount(),
            engineResult.deploymentId(),
            engineResult.engineDefinitionId(),
            engineResult.engineVersion(),
            null,
            null,
            pending.requestedBy(),
            pending.createdAt(),
            now,
            now
        );
    }

    private ApprovalReleaseDeployment failed(
        ApprovalReleaseDeployment pending,
        RuntimeException exception
    ) {
        String code = exception instanceof ApprovalEngine.EngineOperationException engineException
            ? engineException.code()
            : "ENGINE_DEPLOYMENT_FAILED";
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = "workflow engine deployment failed";
        }
        return new ApprovalReleaseDeployment(
            pending.deploymentRecordId(),
            pending.tenantId(),
            pending.definitionKey(),
            pending.releaseVersion(),
            pending.releasePackageHash(),
            ApprovalReleaseDeployment.Status.FAILED,
            pending.attemptCount(),
            null,
            null,
            null,
            code,
            message,
            pending.requestedBy(),
            pending.createdAt(),
            clock.instant(),
            null
        );
    }

    private static void requirePackageIdentity(
        ApprovalReleaseDeployment deployment,
        ApprovalReleasePackage releasePackage
    ) {
        if (!deployment.releasePackageHash().equals(releasePackage.packageHash())) {
            throw new ReleaseDeploymentConflictException(
                "deployment projection references different Release Package content"
            );
        }
    }

    private void appendAudit(
        RequestContext context,
        String action,
        ApprovalReleaseDeployment deployment,
        Map<String, String> attributes
    ) {
        auditEvents.append(new AuditEvent(
            identifiers.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            "APPROVAL_RELEASE_DEPLOYMENT",
            deployment.deploymentRecordId().toString(),
            context.requestId(),
            context.traceId(),
            deployment.updatedAt(),
            attributes
        ));
    }

    private static String requestHash(DeployCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "approval-release-deploy-v1");
            update(digest, command.definitionKey());
            update(digest, Integer.toString(command.releaseVersion()));
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

    public record DeployCommand(
        RequestContext context,
        String definitionKey,
        int releaseVersion
    ) {
        public DeployCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            definitionKey = requireText(definitionKey, "definitionKey");
            if (releaseVersion < 1) {
                throw new IllegalArgumentException("releaseVersion must be positive");
            }
        }
    }

    public record DeploymentResult(
        ApprovalReleaseDeployment deployment,
        boolean replayedExistingDeployment
    ) {
        public DeploymentResult {
            deployment = Objects.requireNonNull(deployment, "deployment must not be null");
        }
    }

    public static final class ReleasePackageNotFoundException extends RuntimeException {
        public ReleasePackageNotFoundException(String message) {
            super(message);
        }
    }

    public static final class ReleaseDeploymentConflictException extends RuntimeException {
        public ReleaseDeploymentConflictException(String message) {
            super(message);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
