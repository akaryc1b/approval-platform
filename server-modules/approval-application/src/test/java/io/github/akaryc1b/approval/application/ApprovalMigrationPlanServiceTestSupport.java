package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationPlanService.AuthorizePlanCommand;
import io.github.akaryc1b.approval.application.ApprovalMigrationPlanService.CreatePlanCommand;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentStatus;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.InstanceAssessment;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.InstanceDecision;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAuthorizationGate;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import org.junit.jupiter.api.BeforeEach;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;


abstract class ApprovalMigrationPlanServiceTestSupport {

    protected static final String TENANT = "tenant-migration-plan";
    protected static final String DEFINITION_KEY = "purchasePayment";
    protected static final Instant NOW = Instant.parse("2026-07-24T15:00:00Z");
    protected static final UUID FIRST_INSTANCE = UUID.fromString(
        "76000000-0000-0000-0000-000000000001"
    );
    protected static final UUID SECOND_INSTANCE = UUID.fromString(
        "76000000-0000-0000-0000-000000000002"
    );

    protected InMemoryPlanStore planStore;
    protected List<AuditEvent> auditEvents;
    protected CapturingAuthorizationGate authorizationGate;
    protected MinimalDeploymentStore deploymentStore;
    protected ApprovalMigrationPlanService service;

    @BeforeEach
    void setUp() {
        planStore = new InMemoryPlanStore();
        auditEvents = new ArrayList<>();
        authorizationGate = new CapturingAuthorizationGate();
        deploymentStore = deploymentStore();
        AtomicLong identifiers = new AtomicLong(10);
        service = new ApprovalMigrationPlanService(
            directIdempotency(),
            planStore,
            authorizationGate,
            releaseStore(),
            packageStore(),
            deploymentStore,
            auditEvents::add,
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> new UUID(76, identifiers.getAndIncrement()),
            Duration.ofMinutes(15),
            Duration.ofMinutes(10),
            Duration.ofMinutes(5)
        );
    }

    protected ApprovalMigrationPlan createPlan() {
        return service.createPlan(new CreatePlanCommand(
            context("migration-requester", "plan-key"),
            assessment(AssessmentStatus.READY, InstanceDecision.ELIGIBLE),
            List.of(FIRST_INSTANCE, SECOND_INSTANCE),
            "Create immutable plan from complete assessment"
        )).plan();
    }

    protected static AssessmentResult assessment(
        AssessmentStatus status,
        InstanceDecision firstDecision
    ) {
        return new AssessmentResult(
            UUID.fromString("76000000-0000-0000-0000-000000000010"),
            TENANT,
            DEFINITION_KEY,
            1,
            hash('b'),
            State.DEPRECATED,
            2,
            hash('c'),
            State.ACTIVE,
            status,
            true,
            status != AssessmentStatus.PARTIAL,
            2,
            100,
            0,
            status == AssessmentStatus.PARTIAL,
            2,
            firstDecision == InstanceDecision.ELIGIBLE ? 2 : 1,
            firstDecision == InstanceDecision.BLOCKED ? 1 : 0,
            0,
            0,
            List.of(),
            List.of(
                instance(FIRST_INSTANCE, firstDecision, '1'),
                instance(SECOND_INSTANCE, InstanceDecision.ELIGIBLE, '2')
            ),
            hash('a'),
            NOW.minusSeconds(60)
        );
    }

    protected static InstanceAssessment instance(UUID id, InstanceDecision decision, char value) {
        return instance(id, decision, value, List.of("approve", "review"));
    }

    protected static InstanceAssessment instance(
        UUID id,
        InstanceDecision decision,
        char value,
        List<String> taskKeys
    ) {
        return new InstanceAssessment(
            id,
            "business-" + value,
            "engine-" + value,
            InstanceStatus.RUNNING,
            decision,
            taskKeys,
            List.of(),
            hash(value)
        );
    }

    protected static AssessmentResult withInstances(
        AssessmentResult source,
        List<InstanceAssessment> instances
    ) {
        return new AssessmentResult(
            source.assessmentId(),
            source.tenantId(),
            source.definitionKey(),
            source.sourceReleaseVersion(),
            source.sourceReleasePackageHash(),
            source.sourceLifecycleState(),
            source.targetReleaseVersion(),
            source.targetReleasePackageHash(),
            source.targetLifecycleState(),
            source.status(),
            source.detectOnly(),
            source.complete(),
            source.totalBindingCount(),
            source.limit(),
            source.offset(),
            source.hasMore(),
            source.runningCount(),
            source.eligibleCount(),
            source.blockedCount(),
            source.terminalCount(),
            source.highImpactChangeCount(),
            source.globalFindings(),
            instances,
            source.reportHash(),
            source.assessedAt()
        );
    }

    protected static RequestContext context(String operator, String idempotencyKey) {
        return new RequestContext(
            TENANT,
            operator,
            "request-" + idempotencyKey,
            idempotencyKey,
            "trace-migration-plan"
        );
    }

    protected static ApprovalProcessReleaseStore releaseStore() {
        Map<Integer, ApprovalProcessRelease> values = Map.of(
            1, release(1, hash('b'), State.DEPRECATED),
            2, release(2, hash('c'), State.ACTIVE)
        );
        return new MinimalReleaseStore(values);
    }

    protected static MinimalDeploymentStore deploymentStore() {
        ApprovalReleaseDeployment deployment = new ApprovalReleaseDeployment(
            UUID.fromString("76000000-0000-0000-0000-000000000030"),
            TENANT,
            DEFINITION_KEY,
            2,
            hash('c'),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            1,
            "engine-deployment-v2",
            "engine-definition-v2",
            2,
            null,
            null,
            "release-operator",
            NOW.minusSeconds(500),
            NOW.minusSeconds(400),
            NOW.minusSeconds(400)
        );
        return new MinimalDeploymentStore(deployment);
    }

    protected static ApprovalReleasePackageStore packageStore() {
        Map<Integer, ApprovalReleasePackage> values = Map.of(
            1, releasePackage(1, hash('b')),
            2, releasePackage(2, hash('c'))
        );
        return new MinimalPackageStore(values);
    }

    protected static ApprovalProcessRelease release(int version, String packageHash, State state) {
        Instant activatedAt = NOW.minusSeconds(300);
        Instant deprecatedAt = state == State.DEPRECATED ? NOW.minusSeconds(120) : null;
        return new ApprovalProcessRelease(
            TENANT, DEFINITION_KEY, version, packageHash, state, 2,
            "publisher", NOW.minusSeconds(600), activatedAt, deprecatedAt, null,
            "release-operator", deprecatedAt == null ? activatedAt : deprecatedAt,
            "Governed release lifecycle evidence", "release-key-" + version,
            "release-request-" + version, "release-trace", "release-audit"
        );
    }

    protected static ApprovalReleasePackage releasePackage(int version, String packageHash) {
        return new ApprovalReleasePackage(
            TENANT, DEFINITION_KEY, version, version, hash('1'), version, hash('2'),
            version, hash('3'), version, hash('4'), "compiler-v1",
            "process.bpmn20.xml", "<definitions/>", hash('5'), hash('6'),
            null, null, hash('7'), packageHash, new UUID(76, version),
            "publisher", NOW.minusSeconds(600)
        );
    }

    protected static final class CapturingAuthorizationGate
        implements ApprovalMigrationPlanAuthorizationGate {

        protected AuthorizationRequest lastRequest;
        protected String authorizedBy;

        @Override
        public AuthorizationDecision requireAuthorization(AuthorizationRequest request) {
            lastRequest = request;
            return new AuthorizationDecision(
                authorizedBy == null ? request.context().operatorId() : authorizedBy,
                "MIGRATION_PLAN_HIGH_RISK",
                "v1",
                hash('9')
            );
        }
    }

    protected static IdempotencyGuard directIdempotency() {
        return new IdempotencyGuard() {
            @Override
            public <T> T execute(
                RequestContext context,
                String operation,
                String requestHash,
                Class<T> resultType,
                Supplier<T> action
            ) {
                return action.get();
            }
        };
    }

    protected static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    protected static final class MinimalReleaseStore implements ApprovalProcessReleaseStore {
        protected final Map<Integer, ApprovalProcessRelease> values;

        protected MinimalReleaseStore(Map<Integer, ApprovalProcessRelease> values) {
            this.values = values;
        }

        @Override
        public Optional<ApprovalProcessRelease> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return TENANT.equals(tenantId) && DEFINITION_KEY.equals(definitionKey)
                ? Optional.ofNullable(values.get(releaseVersion))
                : Optional.empty();
        }

        @Override public void lock(String tenantId, String definitionKey) { throw unsupported(); }
        @Override public Optional<ApprovalProcessRelease> findActive(String t, String d) { throw unsupported(); }
        @Override
        public Optional<ApprovalProcessRelease.Transition> findTransitionByIdempotency(
            String tenantId,
            String idempotencyKey
        ) {
            throw unsupported();
        }
        @Override public ReleasePage findReleases(ReleaseCriteria c) { throw unsupported(); }
        @Override public TransitionPage findHistory(TransitionCriteria c) { throw unsupported(); }
        @Override
        public void savePublished(
            ApprovalProcessRelease release,
            ApprovalProcessRelease.Transition transition
        ) {
            throw unsupported();
        }

        @Override
        public boolean transition(
            ApprovalProcessRelease release,
            long expectedRevision,
            ApprovalProcessRelease.Transition transition
        ) {
            throw unsupported();
        }
    }

    protected static final class MinimalDeploymentStore
        implements ApprovalReleaseDeploymentStore {

        protected ApprovalReleaseDeployment deployment;

        protected MinimalDeploymentStore(ApprovalReleaseDeployment deployment) {
            this.deployment = deployment;
        }

        @Override
        public Optional<ApprovalReleaseDeployment> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return TENANT.equals(tenantId)
                && DEFINITION_KEY.equals(definitionKey)
                && releaseVersion == deployment.releaseVersion()
                ? Optional.of(deployment)
                : Optional.empty();
        }

        @Override public void lock(String t, String d, int v) { throw unsupported(); }
        @Override public void save(ApprovalReleaseDeployment d) { throw unsupported(); }
        @Override
        public boolean update(ApprovalReleaseDeployment d, int expectedAttemptCount) {
            throw unsupported();
        }
    }

    protected static final class MinimalPackageStore implements ApprovalReleasePackageStore {
        protected final Map<Integer, ApprovalReleasePackage> values;

        protected MinimalPackageStore(Map<Integer, ApprovalReleasePackage> values) {
            this.values = values;
        }

        @Override
        public Optional<ApprovalReleasePackage> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return TENANT.equals(tenantId) && DEFINITION_KEY.equals(definitionKey)
                ? Optional.ofNullable(values.get(releaseVersion))
                : Optional.empty();
        }

        @Override public void lockVersion(String t, String d, int v) { throw unsupported(); }
        @Override public Optional<ApprovalReleasePackage> findLatest(String t, String d) { throw unsupported(); }
        @Override public Optional<ApprovalReleasePackage> findByDraft(String t, UUID d) { throw unsupported(); }
        @Override public ReleasePage findReleases(ReleaseCriteria c) { throw unsupported(); }
        @Override public void save(ApprovalReleasePackage p) { throw unsupported(); }
    }

    protected static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed by migration plan test");
    }

    protected static final class InMemoryPlanStore implements ApprovalMigrationPlanStore {
        protected final Map<UUID, ApprovalMigrationPlan> plans = new LinkedHashMap<>();
        protected final Map<UUID, ApprovalMigrationPlanAuthorization> authorizations = new LinkedHashMap<>();
        protected final Map<UUID, List<ApprovalMigrationPlanEvent>> events = new LinkedHashMap<>();

        @Override
        public PlanCreationResult createPlan(
            ApprovalMigrationPlan plan,
            ApprovalMigrationPlanEvent event
        ) {
            plans.put(plan.planId(), plan);
            events.put(plan.planId(), new ArrayList<>(List.of(event)));
            return new PlanCreationResult(plan, false);
        }

        @Override
        public Optional<ApprovalMigrationPlan> findPlan(String tenantId, UUID planId) {
            ApprovalMigrationPlan plan = plans.get(planId);
            return plan != null && plan.tenantId().equals(tenantId)
                ? Optional.of(plan)
                : Optional.empty();
        }

        @Override
        public Optional<ApprovalMigrationPlan> findPlanByHash(String tenantId, String planHash) {
            return plans.values().stream().filter(
                plan -> plan.tenantId().equals(tenantId) && plan.planHash().equals(planHash)
            ).findFirst();
        }

        @Override
        public Optional<ApprovalMigrationPlan> findPlanByIdempotencyKey(
            String tenantId,
            String idempotencyKey
        ) {
            return plans.values().stream().filter(plan -> plan.tenantId().equals(tenantId)
                && plan.idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public AuthorizationResult authorizePlan(
            ApprovalMigrationPlan next,
            long expectedRevision,
            ApprovalMigrationPlanAuthorization authorization,
            ApprovalMigrationPlanEvent event
        ) {
            plans.put(next.planId(), next);
            authorizations.put(next.planId(), authorization);
            events.get(next.planId()).add(event);
            return new AuthorizationResult(next, authorization, false);
        }

        @Override
        public Optional<ApprovalMigrationPlanAuthorization> findAuthorization(
            String tenantId,
            UUID planId
        ) {
            ApprovalMigrationPlanAuthorization value = authorizations.get(planId);
            return value != null && value.tenantId().equals(tenantId)
                ? Optional.of(value)
                : Optional.empty();
        }

        @Override
        public Optional<ApprovalMigrationPlan> findAuthorizedPlan(
            String tenantId,
            UUID planId,
            String planHash,
            Instant validAt
        ) {
            return findPlan(tenantId, planId).filter(
                plan -> plan.planHash().equals(planHash) && plan.authorizedAt(validAt)
            );
        }

        @Override
        public List<ApprovalMigrationPlanEvent> findEvents(String tenantId, UUID planId) {
            return List.copyOf(events.getOrDefault(planId, List.of()));
        }
    }
}
