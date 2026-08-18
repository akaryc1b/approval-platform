package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.AttemptStatusFilter;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.FailureClass;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceSort;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.ReconciliationState;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.TimelineEvent;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationResult;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationDiagnosticsQueryMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00.123456500Z");
    private static final Instant OBSERVED_AT = NOW.plusSeconds(600);
    private static final String WORKER = "worker-me2";
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final String TARGET_PACKAGE_HASH = "3".repeat(64);
    private static final String PLAN_HASH = "4".repeat(64);
    private static final String SNAPSHOT_HASH = "e".repeat(64);

    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private ApprovalReleasePackageStore releasePackages;
    private ApprovalReleaseDeploymentStore deployments;
    private ApprovalRuntimeBindingStore runtimeBindings;

    @BeforeEach
    @Override
    void reset() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        store = JdbcApprovalProjectionStoreFactory.create(dataSource, objectMapper);
        releasePackages = JdbcApprovalReleasePackageStoreFactory.create(dataSource);
        deployments = JdbcApprovalReleaseDeploymentStoreFactory.create(dataSource);
        runtimeBindings = JdbcApprovalRuntimeBindingStoreFactory.create(dataSource);
    }

    @Test
    void readsTenantScopedD8PlanPageAndTimelineWithoutMutation() {
        Authority authority = seedPendingAuthority("Tenant-ME2-Pending");
        AggregationResult aggregate = aggregate(
            authority,
            1,
            "request-me2-pending-aggregate"
        );
        Authority other = seedPendingAuthority("Tenant-ME2-Other");
        aggregate(other, 1, "request-me2-other-aggregate");

        int planRows = count("ap_process_migration_plan", authority.tenantId());
        int aggregateRows = count(
            "ap_process_migration_plan_aggregate",
            authority.tenantId()
        );
        int eventRows = count(
            "ap_process_migration_plan_aggregate_event",
            authority.tenantId()
        );

        ApprovalMigrationDiagnosticsQuery query = query();
        assertInstanceOf(JdbcMySqlApprovalMigrationDiagnosticsQuery.class, query);

        var plan = query.findPlanDiagnostics(
            authority.tenantId(),
            authority.planId()
        ).orElseThrow();
        assertEquals(authority.planId(), plan.planId());
        assertEquals("CONSUMED", plan.planStatus());
        assertEquals(authority.intentId(), plan.intentId());
        assertEquals("UNKNOWN", plan.intentStatus());
        assertEquals(1, plan.selectedCount());
        assertEquals(0, plan.provisionedAttemptCount());
        assertEquals(1, plan.unresolvedCount());
        assertEquals(1L, plan.aggregateRevision());
        assertEquals("NOT_STARTED", plan.aggregateStatus());
        assertEquals(aggregate.aggregate().aggregateHash(), plan.aggregateHash());
        assertEquals(OBSERVED_AT, plan.observedAt());

        var page = query.findInstances(criteria(
            authority,
            AttemptStatusFilter.UNPROVISIONED,
            FailureClass.NONE,
            ReconciliationState.NONE
        ));
        assertEquals(authority.planId(), page.planId());
        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        assertFalse(page.hasMore());
        var item = page.items().getFirst();
        assertEquals(authority.instanceId(), item.approvalInstanceId());
        assertFalse(item.canary());
        assertNull(item.attemptId());
        assertEquals("UNPROVISIONED", item.attemptStatus());
        assertEquals(FailureClass.NONE, item.failureClass());
        assertEquals(ReconciliationState.NONE, item.reconciliationState());
        assertEquals("NOT_RECORDED", item.bindingResult());
        assertEquals("6".repeat(64), item.selectedInstanceEvidenceHash());
        assertEquals(item.selectedInstanceEvidenceHash(), item.latestEvidenceHash());
        assertNotNull(item.latestEvidenceAt());

        var instance = query.findInstance(
            authority.tenantId(),
            authority.planId(),
            authority.instanceId()
        ).orElseThrow();
        assertEquals(authority.instanceId(), instance.instance().approvalInstanceId());
        assertEquals(List.of("PLAN_SELECTION"), instance.timeline().stream()
            .map(TimelineEvent::stage)
            .toList());
        assertEquals(OBSERVED_AT, instance.observedAt());

        assertTrue(query.findPlanDiagnostics(
            authority.tenantId(),
            other.planId()
        ).isEmpty());
        assertTrue(query.findPlanDiagnostics(
            authority.tenantId().toLowerCase(),
            authority.planId()
        ).isEmpty());
        assertTrue(query.findInstance(
            authority.tenantId(),
            authority.planId(),
            UUID.randomUUID()
        ).isEmpty());
        assertThrows(
            JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException.class,
            () -> query.findInstances(new InstanceCriteria(
                authority.tenantId(),
                other.planId(),
                null,
                null,
                null,
                null,
                null,
                null,
                InstanceSort.SEQUENCE_ASC,
                1,
                50
            ))
        );

        assertEquals(planRows, count("ap_process_migration_plan", authority.tenantId()));
        assertEquals(
            aggregateRows,
            count("ap_process_migration_plan_aggregate", authority.tenantId())
        );
        assertEquals(
            eventRows,
            count("ap_process_migration_plan_aggregate_event", authority.tenantId())
        );
    }

    @Test
    void readsRealLatestAttemptTerminalOutcomeFiltersAndTimeline() {
        Authority authority = seedPendingAuthority("Tenant-ME2-Terminal");
        ClaimResult claimed = provisionAndClaim(authority);
        UUID attemptId = claimed.attempts().getFirst().attemptId();
        ApprovalMigrationEngineExecutionStore execution =
            JdbcApprovalMigrationEngineExecutionStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        var prepared = execution.prepare(new PrepareRequest(
            authority.tenantId(),
            attemptId,
            WORKER,
            2,
            1,
            NOW.plusSeconds(30),
            "request-me2-engine-prepare",
            "trace-me2"
        ));
        execution.finalizeOutcome(new FinalizeRequest(
            prepared,
            FinalDisposition.ENGINE_REJECTED,
            true,
            true,
            false,
            "ENGINE_REJECTED_TARGET",
            "engine rejected the exact target",
            SNAPSHOT_HASH,
            NOW.plusSeconds(40)
        ));
        aggregate(authority, 1, "request-me2-terminal-aggregate");

        int attemptsBefore = count(
            "ap_process_migration_attempt",
            authority.tenantId()
        );
        int outcomesBefore = count(
            "ap_process_migration_engine_outcome",
            authority.tenantId()
        );

        ApprovalMigrationDiagnosticsQuery query = query();
        var plan = query.findPlanDiagnostics(
            authority.tenantId(),
            authority.planId()
        ).orElseThrow();
        assertEquals(1, plan.provisionedAttemptCount());
        assertEquals(1, plan.terminalFailedCount());
        assertEquals(0, plan.unresolvedCount());
        assertEquals("COMPLETED_WITH_TERMINAL_FAILURE", plan.aggregateStatus());
        assertEquals("COMPLETED_WITH_TERMINAL_FAILURE", plan.completionStatus());
        assertNotNull(plan.completedAt());

        InstanceCriteria filtered = new InstanceCriteria(
            authority.tenantId(),
            authority.planId(),
            authority.instanceId(),
            AttemptStatusFilter.FAILED_TERMINAL,
            FailureClass.TERMINAL_FAILURE,
            ReconciliationState.NONE,
            OffsetDateTime.ofInstant(NOW.minusSeconds(300), ZoneOffset.UTC),
            OffsetDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC),
            InstanceSort.LATEST_EVIDENCE_DESC,
            1,
            10
        );
        var page = query.findInstances(filtered);
        assertEquals(1, page.total());
        var item = page.items().getFirst();
        assertEquals(attemptId, item.attemptId());
        assertEquals("FAILED_TERMINAL", item.attemptStatus());
        assertEquals("ENGINE_REJECTED", item.engineDisposition());
        assertEquals("ENGINE_REJECTED_TARGET", item.engineStableCode());
        assertEquals(FailureClass.TERMINAL_FAILURE, item.failureClass());
        assertEquals(ReconciliationState.NONE, item.reconciliationState());
        assertNotNull(item.latestEvidenceHash());
        assertNotNull(item.latestEvidenceAt());

        var instance = query.findInstance(
            authority.tenantId(),
            authority.planId(),
            authority.instanceId()
        ).orElseThrow();
        List<String> stages = instance.timeline().stream()
            .map(TimelineEvent::stage)
            .toList();
        assertTrue(stages.contains("PLAN_SELECTION"));
        assertTrue(stages.contains("ATTEMPT"));
        assertTrue(stages.contains("ENGINE_REQUEST"));
        assertTrue(stages.contains("ENGINE_OUTCOME"));

        assertEquals(
            attemptsBefore,
            count("ap_process_migration_attempt", authority.tenantId())
        );
        assertEquals(
            outcomesBefore,
            count("ap_process_migration_engine_outcome", authority.tenantId())
        );
    }

    private ApprovalMigrationDiagnosticsQuery query() {
        return JdbcApprovalMigrationDiagnosticsQueryFactory.create(
            dataSource,
            transactionManager,
            Clock.fixed(OBSERVED_AT, ZoneOffset.UTC)
        );
    }

    private static InstanceCriteria criteria(
        Authority authority,
        AttemptStatusFilter attemptStatus,
        FailureClass failureClass,
        ReconciliationState reconciliationState
    ) {
        return new InstanceCriteria(
            authority.tenantId(),
            authority.planId(),
            null,
            attemptStatus,
            failureClass,
            reconciliationState,
            null,
            null,
            InstanceSort.SEQUENCE_ASC,
            1,
            50
        );
    }

    private AggregationResult aggregate(
        Authority authority,
        long revision,
        String requestId
    ) {
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationPlanAggregationStore aggregation =
            JdbcApprovalMigrationPlanAggregationStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                audits::add,
                UUID::randomUUID
            );
        AggregationResult result = aggregation.aggregate(new AggregationRequest(
            new RequestContext(
                authority.tenantId(),
                "migration-operator",
                requestId,
                "aggregation-key-" + requestId,
                "trace-me2"
            ),
            authority.planId(),
            revision,
            "Aggregate exact consumed plan for ME2 diagnostics",
            NOW.plusSeconds(60 + revision)
        ));
        assertEquals(1, audits.size());
        return result;
    }

    private ClaimResult provisionAndClaim(Authority authority) {
        ApprovalMigrationAttemptProvisioningStore provisioning =
            JdbcApprovalMigrationAttemptProvisioningStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        var provisioned = provisioning.ensureInitialAttempts(new ProvisioningRequest(
            authority.tenantId(),
            authority.intentId(),
            WORKER,
            NOW.plusSeconds(10),
            "request-me2-provision",
            "trace-me2",
            hash('f')
        ));
        assertEquals(1, provisioned.createdCount());

        ApprovalMigrationAttemptClaimStore claims =
            JdbcApprovalMigrationAttemptClaimStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager,
                event -> {
                },
                UUID::randomUUID
            );
        return claims.claim(new ClaimRequest(
            authority.tenantId(),
            authority.intentId(),
            WORKER,
            1,
            NOW.plusSeconds(20),
            NOW.plusSeconds(180),
            "request-me2-claim",
            "trace-me2",
            hash('c')
        ));
    }

    private Authority seedPendingAuthority(String tenant) {
        UUID planId = uuid(tenant, "plan");
        UUID intentId = uuid(tenant, "intent");
        UUID instanceId = uuid(tenant, "instance");
        String businessKey = "business-" + tenant;
        String engineInstanceId = "engine-instance-" + tenant;

        MySqlApprovalProjectionProvenanceFixture.seed(
            jdbc,
            tenant,
            DEFINITION_KEY,
            DEFINITION_AT
        );
        ApprovalReleasePackage sourceRelease = releasePackages.find(
            tenant,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).orElseThrow();
        ApprovalReleaseDeployment sourceDeployment =
            MySqlApprovalReleaseLifecycleFixture.seedDeployed(
                deployments,
                sourceRelease,
                NOW.minusSeconds(300)
            );
        ApprovalReleasePackage targetRelease = MySqlApprovalReleaseLifecycleFixture.seedRelease(
            jdbc,
            releasePackages,
            tenant,
            DEFINITION_KEY,
            3,
            TARGET_PACKAGE_HASH,
            NOW.minusSeconds(240)
        );
        ApprovalReleaseDeployment targetDeployment =
            MySqlApprovalReleaseLifecycleFixture.seedDeployed(
                deployments,
                targetRelease,
                NOW.minusSeconds(180)
            );
        MySqlH2MigrationAttemptProvisioningFixture.seedActiveSourceRelease(
            dataSource,
            sourceRelease,
            WORKER
        );
        seedProjectionInstance(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease,
            sourceDeployment
        );
        runtimeBindings.save(binding(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease,
            sourceDeployment
        ));
        MySqlH3MigrationAttemptClaimAuthorityFixture.seed(
            jdbc,
            objectMapper,
            tenant,
            planId,
            intentId,
            instanceId,
            DEFINITION_KEY,
            WORKER,
            NOW,
            PLAN_HASH,
            SOURCE_BINDING_HASH,
            sourceRelease,
            targetRelease,
            targetDeployment
        );
        return new Authority(tenant, planId, intentId, instanceId);
    }

    private void seedProjectionInstance(
        String tenant,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment
    ) {
        ApprovalProjectionStore projection = JdbcApprovalProjectionStoreFactory.create(
            dataSource,
            objectMapper
        );
        transactions.executeWithoutResult(status -> {
            projection.lockDefinition(tenant, DEFINITION_KEY, 1);
            if (projection.findDefinition(tenant, DEFINITION_KEY, 1).isEmpty()) {
                projection.saveDefinition(definition(tenant));
            }
            projection.lockBusinessKey(tenant, businessKey);
            projection.createInstance(
                instance(tenant, instanceId, engineInstanceId, businessKey),
                List.of()
            );
        });
        jdbc.update("""
            update ap_approval_instance set
              release_version=?,release_package_hash=?,form_package_version=?,
              form_package_hash=?,ui_schema_version=?,ui_schema_hash=?,
              engine_definition_id=?
            where tenant_id=? and instance_id=?
            """,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            sourceRelease.formPackageVersion(),
            sourceRelease.formPackageHash(),
            sourceRelease.uiSchemaVersion(),
            sourceRelease.uiSchemaHash(),
            sourceDeployment.engineDefinitionId(),
            tenant,
            instanceId.toString()
        );
    }

    private ApprovalRuntimeBinding binding(
        String tenant,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleaseDeployment sourceDeployment
    ) {
        return new ApprovalRuntimeBinding(
            tenant,
            instanceId,
            businessKey,
            engineInstanceId,
            sourceRelease.definitionKey(),
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            sourceRelease.definitionVersion(),
            sourceRelease.definitionHash(),
            sourceRelease.formPackageVersion(),
            sourceRelease.formPackageHash(),
            sourceRelease.formVersion(),
            sourceRelease.formHash(),
            sourceRelease.uiSchemaVersion(),
            sourceRelease.uiSchemaHash(),
            sourceRelease.compilerVersion(),
            sourceRelease.compiledArtifactHash(),
            sourceRelease.bpmnHash(),
            sourceRelease.deploymentMetadataHash(),
            sourceDeployment.engineDeploymentId(),
            sourceDeployment.engineDefinitionId(),
            sourceDeployment.engineVersion(),
            SOURCE_BINDING_HASH,
            WORKER,
            NOW.minusSeconds(120),
            "request-me2-binding",
            "trace-me2",
            "audit-event:me2-binding"
        );
    }

    private int count(String table, String tenant) {
        Integer value = jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id=?",
            Integer.class,
            tenant
        );
        return value == null ? 0 : value;
    }

    private static String hash(char value) {
        return Character.toString(value).repeat(64);
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-me2:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Authority(
        String tenantId,
        UUID planId,
        UUID intentId,
        UUID instanceId
    ) {
    }
}
