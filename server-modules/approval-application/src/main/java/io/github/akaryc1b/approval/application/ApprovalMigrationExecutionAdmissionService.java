package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionResult;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Server-only M5-D1 bridge from one authorized immutable plan to one M5-B intent. */
public final class ApprovalMigrationExecutionAdmissionService {

    private static final String OPERATION = "approval-migration-execution.admit.v1";
    private static final String AUDIT_ACTION = "PROCESS_MIGRATION_PLAN_CONSUMED";

    private final IdempotencyGuard idempotency;
    private final ApprovalMigrationPlanService plans;
    private final ApprovalMigrationExecutionAdmissionStore admissions;
    private final ApprovalProcessReleaseStore releases;
    private final ApprovalReleasePackageStore packages;
    private final ApprovalReleaseDeploymentStore deployments;
    private final ApprovalReleasePackageHasher hasher;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalMigrationExecutionAdmissionService(
        IdempotencyGuard idempotency,
        ApprovalMigrationPlanService plans,
        ApprovalMigrationExecutionAdmissionStore admissions,
        ApprovalProcessReleaseStore releases,
        ApprovalReleasePackageStore packages,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalReleasePackageHasher hasher,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.admissions = Objects.requireNonNull(admissions, "admissions must not be null");
        this.releases = Objects.requireNonNull(releases, "releases must not be null");
        this.packages = Objects.requireNonNull(packages, "packages must not be null");
        this.deployments = Objects.requireNonNull(deployments, "deployments must not be null");
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    public AdmissionResult admit(AdmitPlanCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String requestHash = requestHash(command);
        return idempotency.execute(
            command.context(),
            OPERATION,
            requestHash,
            AdmissionResult.class,
            () -> admissions.findAdmission(
                command.context().tenantId(),
                command.planId(),
                command.context().idempotencyKey(),
                requestHash
            ).orElseGet(() -> admitOnce(command, requestHash))
        );
    }

    private AdmissionResult admitOnce(AdmitPlanCommand command, String requestHash) {
        RequestContext context = command.context();
        Instant now = clock.instant();
        ApprovalMigrationPlan plan = plans.requireAuthorizedPlan(
            context.tenantId(),
            command.planId(),
            command.planHash(),
            now
        );
        requireCurrentReleaseEvidence(plan);

        UUID auditEventId = nextIdentifier("auditEventId");
        UUID intentId = nextIdentifier("intentId");
        UUID consumptionId = nextIdentifier("consumptionId");
        String auditReference = "audit-event:" + auditEventId;
        String intentEvidenceHash = hasher.hashValues(
            "M5-D1-EXECUTION-INTENT-V1",
            plan.tenantId(),
            plan.planId(),
            plan.planHash(),
            plan.authorizationId(),
            plan.authorizationEvidenceHash(),
            plan.definitionKey(),
            plan.sourceReleaseVersion(),
            plan.sourcePackageHash(),
            plan.targetReleaseVersion(),
            plan.targetPackageHash(),
            plan.targetDeploymentRecordId(),
            plan.targetEngineDeploymentId(),
            plan.targetEngineDefinitionId(),
            plan.targetEngineVersion(),
            plan.selectedInstanceCount(),
            context.operatorId(),
            command.reason(),
            context.idempotencyKey(),
            requestHash,
            now,
            plan.expiresAt()
        );
        ApprovalMigrationIntent intent = new ApprovalMigrationIntent(
            intentId,
            plan.tenantId(),
            plan.planId(),
            plan.planHash(),
            plan.definitionKey(),
            plan.sourceReleaseVersion(),
            plan.sourcePackageHash(),
            plan.targetReleaseVersion(),
            plan.targetPackageHash(),
            plan.selectedInstanceCount(),
            IntentStatus.PENDING,
            1,
            context.idempotencyKey(),
            intentEvidenceHash,
            context.operatorId(),
            command.reason(),
            plan.expiresAt(),
            now,
            now,
            context.requestId(),
            context.traceId(),
            auditReference
        );
        ApprovalMigrationIntentEvent intentEvent = new ApprovalMigrationIntentEvent(
            nextIdentifier("intentEventId"),
            plan.tenantId(),
            intentId,
            1,
            null,
            IntentStatus.PENDING,
            command.reason(),
            context.operatorId(),
            now,
            context.requestId(),
            context.traceId(),
            auditReference
        );
        ApprovalMigrationPlanConsumption consumption = new ApprovalMigrationPlanConsumption(
            consumptionId,
            plan.tenantId(),
            plan.planId(),
            plan.planHash(),
            plan.authorizationId(),
            plan.authorizationEvidenceHash(),
            intentId,
            intentEvidenceHash,
            context.idempotencyKey(),
            requestHash,
            context.operatorId(),
            command.reason(),
            now,
            context.requestId(),
            context.traceId(),
            auditReference
        );
        ApprovalMigrationPlan consumedPlan = consumed(plan, now);
        ApprovalMigrationPlanEvent planEvent = new ApprovalMigrationPlanEvent(
            nextIdentifier("planEventId"),
            plan.tenantId(),
            plan.planId(),
            plan.planHash(),
            consumedPlan.revision(),
            PlanStatus.AUTHORIZED,
            PlanStatus.CONSUMED,
            context.operatorId(),
            command.reason(),
            plan.authorizationId(),
            plan.authorizationEvidenceHash(),
            now,
            context.requestId(),
            context.traceId(),
            auditReference
        );
        AuditEvent auditEvent = new AuditEvent(
            auditEventId,
            plan.tenantId(),
            context.operatorId(),
            AUDIT_ACTION,
            "APPROVAL_MIGRATION_PLAN",
            plan.planId().toString(),
            context.requestId(),
            context.traceId(),
            now,
            auditAttributes(plan, intent, consumption)
        );
        return admissions.admit(new AdmissionRequest(
            consumedPlan,
            plan.revision(),
            intent,
            intentEvent,
            consumption,
            planEvent,
            auditEvent
        ));
    }

    private void requireCurrentReleaseEvidence(ApprovalMigrationPlan plan) {
        ApprovalProcessRelease source = releases.find(
            plan.tenantId(), plan.definitionKey(), plan.sourceReleaseVersion()
        ).orElseThrow(() -> notAuthorized("source release lifecycle is missing"));
        ApprovalProcessRelease target = releases.find(
            plan.tenantId(), plan.definitionKey(), plan.targetReleaseVersion()
        ).orElseThrow(() -> notAuthorized("target release lifecycle is missing"));
        ApprovalReleasePackage sourcePackage = packages.find(
            plan.tenantId(), plan.definitionKey(), plan.sourceReleaseVersion()
        ).orElseThrow(() -> notAuthorized("source release package is missing"));
        ApprovalReleasePackage targetPackage = packages.find(
            plan.tenantId(), plan.definitionKey(), plan.targetReleaseVersion()
        ).orElseThrow(() -> notAuthorized("target release package is missing"));
        ApprovalReleaseDeployment targetDeployment = deployments.find(
            plan.tenantId(), plan.definitionKey(), plan.targetReleaseVersion()
        ).orElseThrow(() -> notAuthorized("target deployment is missing"));
        if (!source.releasePackageHash().equals(plan.sourcePackageHash())
            || !sourcePackage.packageHash().equals(plan.sourcePackageHash())
            || source.lifecycleState() == State.ACTIVE
            || source.lifecycleState() == State.PUBLISHED
            || !target.releasePackageHash().equals(plan.targetPackageHash())
            || !targetPackage.packageHash().equals(plan.targetPackageHash())
            || target.lifecycleState() != State.ACTIVE
            || targetDeployment.status() != ApprovalReleaseDeployment.Status.DEPLOYED
            || !targetDeployment.deploymentRecordId().equals(plan.targetDeploymentRecordId())
            || !targetDeployment.releasePackageHash().equals(plan.targetPackageHash())
            || !targetDeployment.engineDeploymentId().equals(plan.targetEngineDeploymentId())
            || !targetDeployment.engineDefinitionId().equals(plan.targetEngineDefinitionId())
            || targetDeployment.engineVersion() != plan.targetEngineVersion()) {
            throw notAuthorized("release or target deployment identity changed after authorization");
        }
    }

    private String requestHash(AdmitPlanCommand command) {
        return hasher.hashValues(
            command.context().tenantId(),
            command.context().operatorId(),
            command.context().idempotencyKey(),
            command.planId(),
            command.planHash(),
            command.reason()
        );
    }

    private static ApprovalMigrationPlan consumed(ApprovalMigrationPlan plan, Instant now) {
        if (plan.status() != PlanStatus.AUTHORIZED || !plan.authorizedAt(now)) {
            throw notAuthorized("only a current authorized plan may be consumed");
        }
        return new ApprovalMigrationPlan(
            plan.planId(),
            plan.tenantId(),
            plan.assessmentId(),
            plan.assessmentReportHash(),
            plan.definitionKey(),
            plan.sourceReleaseVersion(),
            plan.sourcePackageHash(),
            plan.targetReleaseVersion(),
            plan.targetPackageHash(),
            plan.targetDeploymentRecordId(),
            plan.targetEngineDeploymentId(),
            plan.targetEngineDefinitionId(),
            plan.targetEngineVersion(),
            plan.selectedInstances(),
            PlanStatus.CONSUMED,
            plan.revision() + 1,
            plan.idempotencyKey(),
            plan.planHash(),
            plan.requestedBy(),
            plan.operationReason(),
            plan.assessedAt(),
            plan.createdAt(),
            plan.expiresAt(),
            now,
            plan.authorizationId(),
            plan.authorizationEvidenceHash(),
            plan.authorizedBy(),
            plan.authorizedAt(),
            plan.authorizationExpiresAt(),
            plan.requestId(),
            plan.traceId(),
            plan.auditChainReference()
        );
    }

    private static Map<String, String> auditAttributes(
        ApprovalMigrationPlan plan,
        ApprovalMigrationIntent intent,
        ApprovalMigrationPlanConsumption consumption
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("planHash", plan.planHash());
        values.put("authorizationId", plan.authorizationId().toString());
        values.put("authorizationEvidenceHash", plan.authorizationEvidenceHash());
        values.put("intentId", intent.intentId().toString());
        values.put("intentEvidenceHash", intent.intentEvidenceHash());
        values.put("consumptionId", consumption.consumptionId().toString());
        values.put("requestHash", consumption.requestHash());
        values.put("selectedInstanceCount", Integer.toString(plan.selectedInstanceCount()));
        values.put("sourceReleaseVersion", Integer.toString(plan.sourceReleaseVersion()));
        values.put("targetReleaseVersion", Integer.toString(plan.targetReleaseVersion()));
        return Map.copyOf(values);
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
    }

    private static MigrationExecutionAdmissionNotAuthorizedException notAuthorized(String message) {
        return new MigrationExecutionAdmissionNotAuthorizedException(message);
    }

    public record AdmitPlanCommand(
        RequestContext context,
        UUID planId,
        String planHash,
        String reason
    ) {
        public AdmitPlanCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            planId = Objects.requireNonNull(planId, "planId must not be null");
            planHash = requireHash(planHash);
            reason = ApprovalMigrationPlanSupport.normalizeReason(reason);
        }

        private static String requireHash(String value) {
            Objects.requireNonNull(value, "planHash must not be null");
            String normalized = value.trim();
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("planHash must be a lowercase SHA-256 value");
            }
            return normalized;
        }
    }

    public static final class MigrationExecutionAdmissionNotAuthorizedException
        extends RuntimeException {
        public MigrationExecutionAdmissionNotAuthorizedException(String message) {
            super(message);
        }
    }
}
