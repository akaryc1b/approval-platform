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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicit, idempotent deployment of one immutable Approval Release Package. */
public final class ApprovalReleaseDeploymentService {

    private static final String DEPLOY_OPERATION = "approval-release.deploy.v1";
    private static final int MAX_ERROR_CODE_LENGTH = 128;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final IdempotencyGuard idempotency;
    private final ApprovalReleasePackageStore releases;
    private final ApprovalReleaseDeploymentStore deployments;
    private final DeploymentPreflight deploymentPreflight;
    private final ApprovalEngine engine;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalReleaseDeploymentService(
        IdempotencyGuard idempotency,
        ApprovalReleasePackageStore releases,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalReleasePreflightService preflight,
        ApprovalEngine engine,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this(
            idempotency,
            releases,
            deployments,
            request -> decision(preflight.preflightDeployment(request)),
            engine,
            auditEvents,
            clock,
            identifiers
        );
    }

    ApprovalReleaseDeploymentService(
        IdempotencyGuard idempotency,
        ApprovalReleasePackageStore releases,
        ApprovalReleaseDeploymentStore deployments,
        DeploymentPreflight deploymentPreflight,
        ApprovalEngine engine,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.releases = Objects.requireNonNull(releases);
        this.deployments = Objects.requireNonNull(deployments);
        this.deploymentPreflight = Objects.requireNonNull(deploymentPreflight);
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

        requireFreshPreflight(command);
        ApprovalReleaseDeployment pending = pending(command, releasePackage, current);
        persistPending(pending, current);

        ApprovalEngine.DeploymentResult engineResult;
        try {
            engineResult = engine.deploy(deployCommand(tenantId, releasePackage));
        } catch (RuntimeException exception) {
            ApprovalReleaseDeployment failed = failed(pending, exception);
            requireUpdated(failed, pending.attemptCount());
            appendAudit(
                command,
                "APPROVAL_RELEASE_DEPLOYMENT_FAILED",
                failed,
                Map.of(
                    "releasePackageHash", releasePackage.packageHash(),
                    "errorCode", failed.lastErrorCode()
                )
            );
            return new DeploymentResult(failed, false);
        }

        ApprovalReleaseDeployment deployed = deployed(pending, engineResult);
        requireUpdated(deployed, pending.attemptCount());
        appendAudit(
            command,
            "APPROVAL_RELEASE_DEPLOYED",
            deployed,
            Map.of(
                "releasePackageHash", releasePackage.packageHash(),
                "engineDeploymentId", engineResult.deploymentId(),
                "engineDefinitionId", engineResult.engineDefinitionId()
            )
        );
        return new DeploymentResult(deployed, false);
    }

    private void requireFreshPreflight(DeployCommand command) {
        DeploymentPreflightDecision decision = deploymentPreflight.check(
            new ApprovalReleasePreflightService.DeploymentRequest(
                command.context().tenantId(),
                command.definitionKey(),
                command.releaseVersion(),
                command.deploymentTarget()
            )
        );
        if (!decision.preflightHash().equals(command.preflightHash())) {
            throw new ReleaseDeploymentPreflightConflict(
                "deployment preflight is stale and must be refreshed"
            );
        }
        if (!decision.deployable() || !decision.errorCodes().isEmpty()) {
            throw new ReleaseDeploymentPreflightConflict(
                "deployment preflight contains blocking errors: "
                    + String.join(", ", decision.errorCodes())
            );
        }
        if (!decision.warningCodes().equals(command.acknowledgedWarningCodes())) {
            throw new ReleaseDeploymentWarningAcknowledgementRequired(
                "all current deployment preflight warnings must be acknowledged exactly"
            );
        }
    }

    private static ApprovalEngine.DeployCommand deployCommand(
        String tenantId,
        ApprovalReleasePackage releasePackage
    ) {
        return new ApprovalEngine.DeployCommand(
            tenantId,
            releasePackage.definitionKey(),
            releasePackage.definitionVersion(),
            releasePackage.bpmnResourceName(),
            releasePackage.bpmnArtifact(),
            releasePackage.bpmnHash()
        );
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
        String rawCode = exception instanceof ApprovalEngine.EngineOperationException engineException
            ? engineException.code()
            : "ENGINE_DEPLOYMENT_FAILED";
        String code = bounded(
            rawCode,
            MAX_ERROR_CODE_LENGTH,
            "ENGINE_DEPLOYMENT_FAILED"
        );
        String message = bounded(
            exception.getMessage(),
            MAX_ERROR_MESSAGE_LENGTH,
            "workflow engine deployment failed"
        );
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

    private static String bounded(String value, int maximumLength, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.length() <= maximumLength
            ? normalized
            : normalized.substring(0, maximumLength);
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
        DeployCommand command,
        String action,
        ApprovalReleaseDeployment deployment,
        Map<String, String> attributes
    ) {
        Map<String, String> complete = new LinkedHashMap<>(attributes);
        complete.put("deploymentTarget", command.deploymentTarget());
        complete.put("preflightHash", command.preflightHash());
        complete.put(
            "acknowledgedWarningCodes",
            String.join(",", command.acknowledgedWarningCodes())
        );
        complete.put("acknowledgedBy", command.context().operatorId());
        complete.put("acknowledgedAt", deployment.updatedAt().toString());
        auditEvents.append(new AuditEvent(
            identifiers.get(),
            command.context().tenantId(),
            command.context().operatorId(),
            action,
            "APPROVAL_RELEASE_DEPLOYMENT",
            deployment.deploymentRecordId().toString(),
            command.context().requestId(),
            command.context().traceId(),
            deployment.updatedAt(),
            Map.copyOf(complete)
        ));
    }

    private static String requestHash(DeployCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "approval-release-deploy-v1");
            update(digest, command.definitionKey());
            update(digest, Integer.toString(command.releaseVersion()));
            update(digest, command.deploymentTarget());
            update(digest, command.preflightHash());
            update(digest, String.join(",", command.acknowledgedWarningCodes()));
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

    private static DeploymentPreflightDecision decision(
        ApprovalReleasePreflightService.PreflightReport report
    ) {
        List<String> errors = report.errors().stream()
            .map(ApprovalReleasePreflightService.Issue::code)
            .distinct()
            .sorted()
            .toList();
        return new DeploymentPreflightDecision(
            report.preflightHash(),
            report.warningCodes(),
            report.deployable(),
            errors
        );
    }

    public record DeployCommand(
        RequestContext context,
        String definitionKey,
        int releaseVersion,
        String deploymentTarget,
        String preflightHash,
        List<String> acknowledgedWarningCodes
    ) {
        public DeployCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            definitionKey = requireText(definitionKey, "definitionKey");
            if (releaseVersion < 1) {
                throw new IllegalArgumentException("releaseVersion must be positive");
            }
            deploymentTarget = deploymentTarget == null || deploymentTarget.isBlank()
                ? "default"
                : deploymentTarget.trim();
            preflightHash = requireHash(preflightHash, "preflightHash");
            TreeSet<String> warningCodes = new TreeSet<>();
            if (acknowledgedWarningCodes != null) {
                acknowledgedWarningCodes.forEach(value -> warningCodes.add(
                    requireText(value, "acknowledgedWarningCode")
                ));
            }
            acknowledgedWarningCodes = List.copyOf(warningCodes);
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

    record DeploymentPreflightDecision(
        String preflightHash,
        List<String> warningCodes,
        boolean deployable,
        List<String> errorCodes
    ) {
        DeploymentPreflightDecision {
            preflightHash = requireHash(preflightHash, "preflightHash");
            warningCodes = warningCodes == null
                ? List.of()
                : new ArrayList<>(new TreeSet<>(warningCodes));
            errorCodes = errorCodes == null
                ? List.of()
                : new ArrayList<>(new TreeSet<>(errorCodes));
            warningCodes = List.copyOf(warningCodes);
            errorCodes = List.copyOf(errorCodes);
        }
    }

    @FunctionalInterface
    interface DeploymentPreflight {
        DeploymentPreflightDecision check(
            ApprovalReleasePreflightService.DeploymentRequest request
        );
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

    public static final class ReleaseDeploymentPreflightConflict extends RuntimeException {
        public ReleaseDeploymentPreflightConflict(String message) {
            super(message);
        }
    }

    public static final class ReleaseDeploymentWarningAcknowledgementRequired
        extends RuntimeException {
        public ReleaseDeploymentWarningAcknowledgementRequired(String message) {
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

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }
}
