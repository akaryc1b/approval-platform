package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAuthorizationGate;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAuthorizationGate.AuthorizationDecision;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAuthorizationGate.AuthorizationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan.SelectedInstance;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Plan-only M5-C coordinator. It never creates an execution intent or invokes Flowable. */
public final class ApprovalMigrationPlanService {

    private static final String CREATE_OPERATION = "approval-migration-plan.create.v1";
    private static final String AUTHORIZE_OPERATION = "approval-migration-plan.authorize.v1";
    private static final String CREATE_AUDIT_ACTION = "PROCESS_MIGRATION_PLAN_CREATED";
    private static final String AUTHORIZE_AUDIT_ACTION = "PROCESS_MIGRATION_PLAN_AUTHORIZED";

    private final IdempotencyGuard idempotency;
    private final ApprovalMigrationPlanStore plans;
    private final ApprovalMigrationPlanAuthorizationGate authorizationGate;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final Duration authorizationTtl;
    private final ApprovalMigrationPlanEvidenceValidator evidence;
    private final ApprovalMigrationPlanSupport support;

    public ApprovalMigrationPlanService(
        IdempotencyGuard idempotency,
        ApprovalMigrationPlanStore plans,
        ApprovalMigrationPlanAuthorizationGate authorizationGate,
        ApprovalProcessReleaseStore releases,
        ApprovalReleasePackageStore packages,
        ApprovalReleaseDeploymentStore deployments,
        AuditEventSink auditEvents,
        ApprovalReleasePackageHasher hasher,
        Clock clock,
        Supplier<UUID> identifiers,
        Duration assessmentMaximumAge,
        Duration planTtl,
        Duration authorizationTtl
    ) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.authorizationGate = Objects.requireNonNull(
            authorizationGate,
            "authorizationGate must not be null"
        );
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.authorizationTtl = ApprovalMigrationPlanSupport.positive(
            authorizationTtl,
            "authorizationTtl"
        );
        Duration maximumAge = ApprovalMigrationPlanSupport.positive(
            assessmentMaximumAge,
            "assessmentMaximumAge"
        );
        support = new ApprovalMigrationPlanSupport(
            Objects.requireNonNull(hasher, "hasher must not be null"),
            ApprovalMigrationPlanSupport.positive(planTtl, "planTtl"),
            maximumAge
        );
        evidence = new ApprovalMigrationPlanEvidenceValidator(
            Objects.requireNonNull(releases, "releases must not be null"),
            Objects.requireNonNull(packages, "packages must not be null"),
            Objects.requireNonNull(deployments, "deployments must not be null"),
            hasher,
            maximumAge
        );
    }

    public CreatePlanResult createPlan(CreatePlanCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return idempotency.execute(
            command.context(),
            CREATE_OPERATION,
            support.createRequestHash(command),
            CreatePlanResult.class,
            () -> createPlanOnce(command)
        );
    }

    public AuthorizePlanResult authorizePlan(AuthorizePlanCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ApprovalMigrationPlan current = plans.findPlan(
            command.context().tenantId(),
            command.planId()
        ).orElseThrow(() -> new MigrationPlanNotFoundException(
            "Migration plan was not found for the tenant"
        ));
        return idempotency.execute(
            command.context(),
            AUTHORIZE_OPERATION,
            support.authorizeRequestHash(command, current.planHash()),
            AuthorizePlanResult.class,
            () -> authorizePlanOnce(command, current)
        );
    }

    public ApprovalMigrationPlan requireAuthorizedPlan(
        String tenantId,
        UUID planId,
        String planHash,
        Instant validAt
    ) {
        return plans.findAuthorizedPlan(tenantId, planId, planHash, validAt)
            .orElseThrow(() -> new MigrationPlanNotAuthorizedException(
                "Migration plan is not authorized and current for the tenant"
            ));
    }

    private CreatePlanResult createPlanOnce(CreatePlanCommand command) {
        RequestContext context = command.context();
        AssessmentResult assessment = command.assessment();
        Instant now = clock.instant();
        evidence.requireAssessment(context, assessment, now);
        ApprovalReleaseDeployment targetDeployment = evidence.currentReleaseEvidence(assessment);
        List<SelectedInstance> selected = evidence.selectInstances(
            assessment,
            command.selectedInstanceIds()
        );
        Instant expiresAt = support.planExpiry(assessment, now);
        String planHash = support.planHash(
            assessment,
            targetDeployment,
            selected,
            context.operatorId(),
            command.reason(),
            expiresAt
        );
        UUID planId = nextIdentifier("planId");
        UUID auditEventId = nextIdentifier("auditEventId");
        auditEvents.append(new AuditEvent(
            auditEventId,
            context.tenantId(),
            context.operatorId(),
            CREATE_AUDIT_ACTION,
            "APPROVAL_MIGRATION_PLAN",
            planId.toString(),
            context.requestId(),
            context.traceId(),
            now,
            support.createAuditAttributes(
                assessment,
                targetDeployment,
                selected,
                planHash,
                command.reason(),
                expiresAt
            )
        ));
        ApprovalMigrationPlan plan = new ApprovalMigrationPlan(
            planId,
            context.tenantId(),
            assessment.assessmentId(),
            assessment.reportHash(),
            assessment.definitionKey(),
            assessment.sourceReleaseVersion(),
            assessment.sourceReleasePackageHash(),
            assessment.targetReleaseVersion(),
            assessment.targetReleasePackageHash(),
            targetDeployment.deploymentRecordId(),
            targetDeployment.engineDeploymentId(),
            targetDeployment.engineDefinitionId(),
            targetDeployment.engineVersion(),
            selected,
            PlanStatus.PROPOSED,
            1,
            context.idempotencyKey(),
            planHash,
            context.operatorId(),
            command.reason(),
            assessment.assessedAt(),
            now,
            expiresAt,
            now,
            null,
            null,
            null,
            null,
            null,
            context.requestId(),
            context.traceId(),
            "audit-event:" + auditEventId
        );
        ApprovalMigrationPlanEvent event = new ApprovalMigrationPlanEvent(
            nextIdentifier("planEventId"),
            context.tenantId(),
            planId,
            planHash,
            1,
            null,
            PlanStatus.PROPOSED,
            context.operatorId(),
            command.reason(),
            null,
            null,
            now,
            context.requestId(),
            context.traceId(),
            "audit-event:" + auditEventId
        );
        ApprovalMigrationPlanStore.PlanCreationResult stored = plans.createPlan(plan, event);
        return new CreatePlanResult(stored.plan(), stored.replayedExistingPlan());
    }

    private AuthorizePlanResult authorizePlanOnce(
        AuthorizePlanCommand command,
        ApprovalMigrationPlan current
    ) {
        RequestContext context = command.context();
        if (current.revision() != command.expectedRevision()) {
            throw new MigrationPlanConflictException("Migration plan revision is stale");
        }
        Instant now = clock.instant();
        if (current.status() != PlanStatus.PROPOSED || !now.isBefore(current.expiresAt())) {
            throw new MigrationPlanNotAuthorizedException(
                "Only a current proposed migration plan may be authorized"
            );
        }
        if (current.requestedBy().equals(context.operatorId())) {
            throw new MigrationPlanNotAuthorizedException(
                "Migration plan requester cannot authorize the same plan"
            );
        }
        evidence.requireCurrentPlanReleaseEvidence(current);
        Instant authorizationExpiresAt = ApprovalMigrationPlanSupport.earlier(
            now.plus(authorizationTtl),
            current.expiresAt()
        );
        if (!authorizationExpiresAt.isAfter(now)) {
            throw new MigrationPlanNotAuthorizedException(
                "Migration plan cannot receive an expired authorization"
            );
        }
        AuthorizationDecision decision = authorizationGate.requireAuthorization(
            new AuthorizationRequest(
                context,
                current.planId(),
                current.planHash(),
                current.selectedInstanceCount(),
                current.sourceReleaseVersion(),
                current.sourcePackageHash(),
                current.targetReleaseVersion(),
                current.targetPackageHash(),
                current.targetDeploymentRecordId(),
                current.targetEngineDeploymentId(),
                current.targetEngineDefinitionId(),
                current.targetEngineVersion(),
                command.reason()
            )
        );
        if (!context.operatorId().equals(decision.authorizedBy())) {
            throw new MigrationPlanNotAuthorizedException(
                "Authorization gate decision does not match the authenticated operator"
            );
        }
        UUID authorizationId = nextIdentifier("authorizationId");
        UUID auditEventId = nextIdentifier("auditEventId");
        ApprovalMigrationPlanAuthorization authorization = new ApprovalMigrationPlanAuthorization(
            authorizationId,
            context.tenantId(),
            current.planId(),
            current.planHash(),
            current.selectedInstanceCount(),
            current.sourceReleaseVersion(),
            current.sourcePackageHash(),
            current.targetReleaseVersion(),
            current.targetPackageHash(),
            decision.authorizationPolicy(),
            decision.authorizationPolicyVersion(),
            decision.authorizationEvidenceHash(),
            context.operatorId(),
            command.reason(),
            context.idempotencyKey(),
            now,
            authorizationExpiresAt,
            context.requestId(),
            context.traceId(),
            "audit-event:" + auditEventId
        );
        ApprovalMigrationPlan next = current.authorized(authorization);
        auditEvents.append(new AuditEvent(
            auditEventId,
            context.tenantId(),
            context.operatorId(),
            AUTHORIZE_AUDIT_ACTION,
            "APPROVAL_MIGRATION_PLAN",
            current.planId().toString(),
            context.requestId(),
            context.traceId(),
            now,
            support.authorizationAuditAttributes(current, authorization)
        ));
        ApprovalMigrationPlanEvent event = new ApprovalMigrationPlanEvent(
            nextIdentifier("planEventId"),
            context.tenantId(),
            current.planId(),
            current.planHash(),
            next.revision(),
            current.status(),
            next.status(),
            context.operatorId(),
            command.reason(),
            authorization.authorizationId(),
            authorization.authorizationEvidenceHash(),
            now,
            context.requestId(),
            context.traceId(),
            "audit-event:" + auditEventId
        );
        ApprovalMigrationPlanStore.AuthorizationResult stored = plans.authorizePlan(
            next,
            command.expectedRevision(),
            authorization,
            event
        );
        return new AuthorizePlanResult(
            stored.plan(),
            stored.authorization(),
            stored.replayedExistingAuthorization()
        );
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
    }

    public record CreatePlanCommand(
        RequestContext context,
        AssessmentResult assessment,
        List<UUID> selectedInstanceIds,
        String reason
    ) {
        public CreatePlanCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            assessment = Objects.requireNonNull(assessment, "assessment must not be null");
            selectedInstanceIds = selectedInstanceIds == null
                ? List.of()
                : List.copyOf(selectedInstanceIds);
            reason = ApprovalMigrationPlanSupport.normalizeReason(reason);
        }
    }

    public record CreatePlanResult(
        ApprovalMigrationPlan plan,
        boolean replayedExistingPlan
    ) {
        public CreatePlanResult {
            plan = Objects.requireNonNull(plan, "plan must not be null");
        }
    }

    public record AuthorizePlanCommand(
        RequestContext context,
        UUID planId,
        long expectedRevision,
        String reason
    ) {
        public AuthorizePlanCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            planId = Objects.requireNonNull(planId, "planId must not be null");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
            reason = ApprovalMigrationPlanSupport.normalizeReason(reason);
        }
    }

    public record AuthorizePlanResult(
        ApprovalMigrationPlan plan,
        ApprovalMigrationPlanAuthorization authorization,
        boolean replayedExistingAuthorization
    ) {
        public AuthorizePlanResult {
            plan = Objects.requireNonNull(plan, "plan must not be null");
            authorization = Objects.requireNonNull(
                authorization,
                "authorization must not be null"
            );
        }
    }

    public static final class MigrationPlanEvidenceNotFoundException extends RuntimeException {
        public MigrationPlanEvidenceNotFoundException(String message) { super(message); }
    }

    public static final class MigrationPlanNotFoundException extends RuntimeException {
        public MigrationPlanNotFoundException(String message) { super(message); }
    }

    public static final class StaleMigrationAssessmentException extends RuntimeException {
        public StaleMigrationAssessmentException(String message) { super(message); }
    }

    public static final class MigrationPlanNotAuthorizedException extends RuntimeException {
        public MigrationPlanNotAuthorizedException(String message) { super(message); }
    }

    public static final class MigrationPlanConflictException extends RuntimeException {
        public MigrationPlanConflictException(String message) { super(message); }
    }
}
