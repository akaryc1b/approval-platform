package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.InstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanDetail;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanPage;
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
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.TerminalOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationOperationsQueryMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00.123456500Z");
    private static final Instant OBSERVED_AT = NOW.plusSeconds(600);
    private static final String WORKER = "worker-me1";
    private static final String SOURCE_BINDING_HASH = "9".repeat(64);
    private static final String TARGET_PACKAGE_HASH = "3".repeat(64);
    private static final String PLAN_HASH = "4".repeat(64);
    private static final String SNAPSHOT_HASH = "e".repeat(64);
    private static final String SELECTED_EVIDENCE_HASH = "6".repeat(64);

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
    void readsTenantScopedPendingD8SummaryPlanDetailAndInstancesWithoutMutation() {
        Authority authority = seedPendingAuthority("Tenant-ME1-Pending");
        AggregationResult aggregate = aggregate(
            authority,
            1,
            "request-me1-pending-aggregate"
        );
        Authority other = seedPendingAuthority("Tenant-ME1-Other");
        aggregate(other, 1, "request-me1-other-aggregate");

        int planRows = count("ap_process_migration_plan", authority.tenantId());
        int aggregateRows = count(
            "ap_process_migration_plan_aggregate",
            authority.tenantId()
        );
        int eventRows = count(
            "ap_process_migration_plan_aggregate_event",
            authority.tenantId()
        );

        ApprovalMigrationOperationsQuery query = query();
        assertInstanceOf(JdbcMySqlApprovalMigrationOperationsQuery.class, query);

        var summary = query.summarize(authority.tenantId());
        assertEquals(authority.tenantId(), summary.tenantId());
        assertEquals(1, summary.totalPlans());
        assertEquals(1, summary.consumedPlans());
        assertEquals(1, summary.activePlans());
        assertEquals(0, summary.pausedPlans());
        assertEquals(1, summary.unresolvedPlans());
        assertEquals(0, summary.completedPlans());
        assertEquals(0, summary.killSwitchObservedPlans());
        assertEquals(aggregate.aggregate().aggregatedAt(), summary.latestAggregatedAt());
        assertEquals(OBSERVED_AT, summary.observedAt());

        PlanPage page = query.findPlans(new PlanCriteria(
            authority.tenantId(),
            DEFINITION_KEY,
            PlanStatus.CONSUMED,
            AggregateStatus.NOT_STARTED,
            false,
            50,
            0
        ));
        assertEquals(1, page.total());
        assertFalse(page.hasMore());
        assertEquals(1, page.items().size());
        var item = page.items().getFirst();
        assertEquals(authority.planId(), item.planId());
        assertEquals(PLAN_HASH, item.planHash());
        assertEquals(PlanStatus.CONSUMED, item.planStatus());
        assertEquals(1L, item.aggregateRevision());
        assertEquals(AggregateStatus.NOT_STARTED, item.aggregateStatus());
        assertEquals(TerminalOutcome.UNRESOLVED, item.terminalOutcome());
        assertEquals(1, item.selectedInstanceCount());
        assertEquals(1, item.unresolvedCount());
        assertFalse(item.paused());
        assertEquals(PauseReason.NONE, item.pauseReason());

        PlanDetail detail = query.findPlan(
            authority.tenantId(),
            authority.planId()
        ).orElseThrow();
        assertEquals(authority.sourcePackageHash(), detail.sourcePackageHash());
        assertEquals(authority.targetPackageHash(), detail.targetPackageHash());
        assertEquals(
            aggregate.aggregate().inputEvidenceHash(),
            detail.inputEvidenceHash()
        );
        assertEquals(aggregate.aggregate().aggregateHash(), detail.aggregateHash());
        assertNull(detail.completionEvidenceHash());

        InstancePage instances = query.findInstances(
            authority.tenantId(),
            authority.planId(),
            50,
            0
        );
        assertEquals(authority.planId(), instances.planId());
        assertEquals(1, instances.total());
        assertFalse(instances.hasMore());
        var instance = instances.items().getFirst();
        assertEquals(1, instance.sequenceNo());
        assertEquals(authority.instanceId(), instance.approvalInstanceId());
        assertFalse(instance.canary());
        assertNull(instance.attemptId());
        assertFalse(instance.exactCompletion());
        assertFalse(instance.bindingConflict());
        assertEquals(SELECTED_EVIDENCE_HASH, instance.selectedInstanceEvidenceHash());
        assertEquals(SELECTED_EVIDENCE_HASH, instance.latestEvidenceHash());
        assertNull(instance.latestEvidenceAt());

        assertTrue(query.findPlan(authority.tenantId(), other.planId()).isEmpty());
        assertEquals(0, query.summarize(authority.tenantId().toLowerCase()).totalPlans());
        assertThrows(
            JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException.class,
            () -> query.findInstances(
                authority.tenantId(),
                other.planId(),
                50,
                0
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> query.findInstances(authority.tenantId(), authority.planId(), 201, 0)
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
    void selectsLatestAggregateRevisionAndRealAttemptEvidence() {
        Authority authority = seedPendingAuthority("Tenant-ME1-Latest");
        AggregationResult first = aggregate(
            authority,
            1,
            "request-me1-latest-first"
        );
        ClaimResult claimed = provisionAndClaim(authority);
        AggregationResult second = aggregate(
            authority,
            2,
            "request-me1-latest-second"
        );

        assertEquals(2, count(
            "ap_process_migration_plan_aggregate",
            authority.tenantId()
        ));
        assertNotEquals(
            first.aggregate().aggregateHash(),
            second.aggregate().aggregateHash()
        );

        ApprovalMigrationOperationsQuery query = query();
        PlanDetail detail = query.findPlan(
            authority.tenantId(),
            authority.planId()
        ).orElseThrow();
        assertEquals(2L, detail.plan().aggregateRevision());
        assertEquals(second.aggregate().status(), detail.plan().aggregateStatus());
        assertEquals(second.aggregate().aggregateHash(), detail.aggregateHash());
        assertNotEquals(first.aggregate().aggregateHash(), detail.aggregateHash());

        PlanPage filtered = query.findPlans(new PlanCriteria(
            authority.tenantId(),
            null,
            null,
            second.aggregate().status(),
            second.aggregate().paused(),
            1,
            0
        ));
        assertEquals(1, filtered.total());
        assertFalse(filtered.hasMore());

        var claimedAttempt = claimed.attempts().getFirst();
        var instance = query.findInstances(
            authority.tenantId(),
            authority.planId(),
            1,
            0
        ).items().getFirst();
        assertEquals(claimedAttempt.attemptId(), instance.attemptId());
        assertEquals(claimedAttempt.attemptNumber(), instance.attemptNumber());
        assertEquals(claimedAttempt.status().name(), instance.attemptStatus());
        assertEquals(claimedAttempt.revision(), instance.attemptRevision());
        assertEquals(claimedAttempt.engineOutcome().name(), instance.engineOutcome());
        assertEquals(SELECTED_EVIDENCE_HASH, instance.latestEvidenceHash());
        assertEquals(claimedAttempt.updatedAt(), instance.latestEvidenceAt());
    }

    @Test
    void readsRealTerminalAggregateAndCompletionEvidence() {
        Authority authority = seedPendingAuthority("Tenant-ME1-Terminal");
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
            "request-me1-engine-prepare",
            "trace-me1"
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
        AggregationResult result = aggregate(
            authority,
            1,
            "request-me1-terminal-aggregate"
        );

        ApprovalMigrationOperationsQuery query = query();
        var summary = query.summarize(authority.tenantId());
        assertEquals(1, summary.totalPlans());
        assertEquals(0, summary.activePlans());
        assertEquals(0, summary.unresolvedPlans());
        assertEquals(1, summary.completedPlans());

        PlanDetail detail = query.findPlan(
            authority.tenantId(),
            authority.planId()
        ).orElseThrow();
        assertEquals(
            AggregateStatus.COMPLETED_WITH_TERMINAL_FAILURE,
            detail.plan().aggregateStatus()
        );
        assertEquals(
            TerminalOutcome.COMPLETED_WITH_TERMINAL_FAILURE,
            detail.plan().terminalOutcome()
        );
        assertEquals(1, detail.plan().terminalFailedCount());
        assertEquals(0, detail.plan().unresolvedCount());
        assertEquals(result.aggregate().status(), detail.plan().completionStatus());
        assertNotNull(detail.plan().completedAt());
        assertNotNull(detail.completionEvidenceHash());

        var instance = query.findInstances(
            authority.tenantId(),
            authority.planId(),
            50,
            0
        ).items().getFirst();
        assertEquals(attemptId, instance.attemptId());
        assertEquals("FAILED_TERMINAL", instance.attemptStatus());
        assertEquals("REJECTED", instance.engineOutcome());
        assertFalse(instance.exactCompletion());
        assertFalse(instance.bindingConflict());
        assertNotNull(instance.latestEvidenceAt());
    }

    private ApprovalMigrationOperationsQuery query() {
        return JdbcApprovalMigrationOperationsQueryFactory.create(
            dataSource,
            transactionManager,
            Clock.fixed(OBSERVED_AT, ZoneOffset.UTC)
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
                "trace-me1"
            ),
            authority.planId(),
            revision,
            "Aggregate exact consumed plan for ME1 visibility",
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
            "request-me1-provision",
            "trace-me1",
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
            "request-me1-claim",
            "trace-me1",
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
        return new Authority(
            tenant,
            planId,
            intentId,
            instanceId,
            sourceRelease.packageHash(),
            targetRelease.packageHash()
        );
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
            "request-me1-binding",
            "trace-me1",
            "audit-event:me1-binding"
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
            ("mysql-me1:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Authority(
        String tenantId,
        UUID planId,
        UUID intentId,
        UUID instanceId,
        String sourcePackageHash,
        String targetPackageHash
    ) {
    }
}
