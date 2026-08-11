package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.RuntimeBindingEnforcingProjectionStore;
import io.github.akaryc1b.approval.application.RuntimeBindingRecordingAuditEventSink;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalRuntimeBindingStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant DIRECT_AT = Instant.parse(
        "2026-08-11T04:05:06.999999500Z"
    );
    private static final Instant START_AT = Instant.parse(
        "2026-08-11T05:30:00.123456Z"
    );
    private static final Clock START_CLOCK = Clock.fixed(START_AT, ZoneOffset.UTC);

    private ApprovalRuntimeBindingStore bindings;
    private ApprovalReleasePackageStore releasePackages;
    private ApprovalReleaseDeploymentStore deployments;
    private ApprovalEffectiveReleaseStore effectiveReleases;
    private ApprovalProcessReleaseStore processReleases;
    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;

    @BeforeEach
    @Override
    void reset() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        store = JdbcApprovalProjectionStoreFactory.create(dataSource, objectMapper);
        bindings = JdbcApprovalRuntimeBindingStoreFactory.create(dataSource);
        releasePackages = JdbcApprovalReleasePackageStoreFactory.create(dataSource);
        deployments = JdbcApprovalReleaseDeploymentStoreFactory.create(dataSource);
        effectiveReleases = JdbcApprovalEffectiveReleaseStoreFactory.create(dataSource);
        processReleases = JdbcApprovalProcessReleaseStoreFactory.create(dataSource);
    }

    @Test
    void storeRoundTripsUuidTimeTenantReleaseCountAndDeterministicPagination() {
        String tenant = "Tenant-G3-Direct";
        ReleaseEvidence evidence = seedReleaseEvidence(tenant);
        UUID firstId = uuid("direct-first");
        UUID secondId = uuid("direct-second");
        seedInstance(tenant, firstId, "engine-instance-g3-direct-1", "business-g3-direct-1");
        seedInstance(tenant, secondId, "engine-instance-g3-direct-2", "business-g3-direct-2");

        ApprovalRuntimeBinding first = binding(
            evidence,
            firstId,
            "business-g3-direct-1",
            "engine-instance-g3-direct-1",
            DIRECT_AT,
            "1".repeat(64)
        );
        ApprovalRuntimeBinding second = binding(
            evidence,
            secondId,
            "business-g3-direct-2",
            "engine-instance-g3-direct-2",
            DIRECT_AT.plusSeconds(1),
            "2".repeat(64)
        );
        bindings.save(first);
        bindings.save(second);

        assertInstanceOf(JdbcMySqlApprovalRuntimeBindingStore.class, bindings);
        ApprovalRuntimeBinding restored = bindings.find(tenant, firstId).orElseThrow();
        assertEquals(first.approvalInstanceId(), restored.approvalInstanceId());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(first.boundAt()),
            restored.boundAt()
        );
        assertEquals(first.bindingEvidenceHash(), restored.bindingEvidenceHash());
        assertEquals(
            firstId,
            bindings.findByEngineInstance(tenant, first.engineInstanceId())
                .orElseThrow()
                .approvalInstanceId()
        );
        assertFalse(bindings.find(tenant.toLowerCase(Locale.ROOT), firstId).isPresent());
        assertFalse(bindings.findByEngineInstance(
            tenant.toLowerCase(Locale.ROOT),
            first.engineInstanceId()
        ).isPresent());
        assertEquals(
            2,
            bindings.countReleaseUsage(
                tenant,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
            )
        );

        ApprovalRuntimeBindingStore.BindingPage firstPage = bindings.findByRelease(
            new ApprovalRuntimeBindingStore.BindingCriteria(
                tenant,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION,
                1,
                0
            )
        );
        assertEquals(2, firstPage.total());
        assertEquals(secondId, firstPage.items().getFirst().approvalInstanceId());
        assertTrue(firstPage.hasMore());

        ApprovalRuntimeBindingStore.BindingPage secondPage = bindings.findByRelease(
            new ApprovalRuntimeBindingStore.BindingCriteria(
                tenant,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION,
                1,
                1
            )
        );
        assertEquals(firstId, secondPage.items().getFirst().approvalInstanceId());
        assertFalse(secondPage.hasMore());
    }

    @Test
    void immutableDuplicateUpdateDeleteAndForeignKeyBoundariesFailClosed() {
        String tenant = "Tenant-G3-Immutable";
        ReleaseEvidence evidence = seedReleaseEvidence(tenant);
        UUID instanceId = uuid("immutable-instance");
        seedInstance(
            tenant,
            instanceId,
            "engine-instance-g3-immutable",
            "business-g3-immutable"
        );
        ApprovalRuntimeBinding exact = binding(
            evidence,
            instanceId,
            "business-g3-immutable",
            "engine-instance-g3-immutable",
            DIRECT_AT,
            "3".repeat(64)
        );
        bindings.save(exact);

        assertThrows(DataAccessException.class, () -> bindings.save(exact));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            """
            update ap_process_runtime_binding
            set request_id = 'mutated'
            where tenant_id = ? and approval_instance_id = ?
            """,
            tenant,
            instanceId.toString()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            """
            delete from ap_process_runtime_binding
            where tenant_id = ? and approval_instance_id = ?
            """,
            tenant,
            instanceId.toString()
        ));

        ApprovalRuntimeBinding missingInstance = binding(
            evidence,
            uuid("missing-instance"),
            "business-g3-missing",
            "engine-instance-g3-missing",
            DIRECT_AT.plusSeconds(2),
            "4".repeat(64)
        );
        assertThrows(DataAccessException.class, () -> bindings.save(missingInstance));
        assertEquals(
            canonicalBinding(exact),
            bindings.find(tenant, instanceId).orElseThrow()
        );
    }

    @Test
    void exactStartCommitsProjectionBindingAuditAndIdempotencyOnce() {
        String tenant = "Tenant-G3-Start";
        ReleaseEvidence evidence = seedStartEvidence(tenant);
        ExactStartEngine engine = new ExactStartEngine(evidence);
        PurchasePaymentApplicationService service = startService(
            engine,
            JdbcAuditEventStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager
            ),
            uuid("start-instance")
        );
        var command = startCommand(tenant, "start-runtime-g3");

        var first = service.start(command);
        var replay = service.start(command);

        assertEquals(first, replay);
        assertEquals(1, engine.starts.get());
        assertEquals(1, countTenantRows("ap_approval_instance", tenant));
        assertEquals(1, countTenantRows("ap_approval_task", tenant));
        assertEquals(1, countTenantRows("ap_process_runtime_binding", tenant));
        assertEquals(1, countTenantRows("ap_audit_event", tenant));
        assertEquals(1, countTenantRows("ap_command_idempotency", tenant));

        ApprovalRuntimeBinding binding = bindings.find(tenant, first.instanceId()).orElseThrow();
        assertTrue(binding.binds(evidence.releasePackage(), evidence.deployment()));
        assertEquals("engine-instance-g3-1", binding.engineInstanceId());
        assertEquals(START_AT, binding.boundAt());
        String auditEventId = jdbc.queryForObject(
            "select event_id from ap_audit_event where tenant_id = ? and action = 'INSTANCE_STARTED'",
            String.class,
            tenant
        );
        assertEquals("audit-event:" + auditEventId, binding.auditChainReference());
        assertEquals(expectedBindingHash(binding, evidence), binding.bindingEvidenceHash());
    }

    @Test
    void delegateAuditFailureAfterBindingInsertRollsBackAllPlatformEvidence() {
        String tenant = "Tenant-G3-Rollback";
        ReleaseEvidence evidence = seedStartEvidence(tenant);
        ExactStartEngine engine = new ExactStartEngine(evidence);
        AuditEventSink failingAudit = event -> {
            throw new IllegalStateException("audit unavailable after runtime binding insert");
        };
        PurchasePaymentApplicationService service = startService(
            engine,
            failingAudit,
            uuid("rollback-instance")
        );

        assertThrows(
            IllegalStateException.class,
            () -> service.start(startCommand(tenant, "start-runtime-g3-failure"))
        );

        assertEquals(1, engine.starts.get());
        assertEquals(0, countTenantRows("ap_approval_instance", tenant));
        assertEquals(0, countTenantRows("ap_approval_task", tenant));
        assertEquals(0, countTenantRows("ap_process_runtime_binding", tenant));
        assertEquals(0, countTenantRows("ap_audit_event", tenant));
        assertEquals(0, countTenantRows("ap_command_idempotency", tenant));
    }

    @Test
    void releaseBoundProjectionReadFailsClosedWithoutImmutableBinding() {
        String tenant = "Tenant-G3-Fail-Closed";
        seedReleaseEvidence(tenant);
        UUID instanceId = uuid("missing-binding-projection");
        seedInstance(
            tenant,
            instanceId,
            "engine-instance-g3-fail-closed",
            "business-g3-fail-closed"
        );
        ApprovalProjectionStore raw = JdbcApprovalProjectionStoreFactory.create(
            dataSource,
            objectMapper
        );
        ApprovalProjectionStore enforcing = new RuntimeBindingEnforcingProjectionStore(
            raw,
            bindings
        );

        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> enforcing.findInstance(tenant, instanceId)
        );
    }

    private ReleaseEvidence seedReleaseEvidence(String tenant) {
        MySqlApprovalProjectionProvenanceFixture.seed(
            jdbc,
            tenant,
            DEFINITION_KEY,
            DEFINITION_AT
        );
        ApprovalReleasePackage releasePackage = releasePackages.find(
            tenant,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.RELEASE_VERSION
        ).orElseThrow();
        ApprovalReleaseDeployment deployment = MySqlApprovalReleaseLifecycleFixture.seedDeployed(
            deployments,
            releasePackage,
            START_AT.minusSeconds(60)
        );
        seedActiveLifecycle(releasePackage);
        return new ReleaseEvidence(releasePackage, deployment);
    }

    private void seedActiveLifecycle(ApprovalReleasePackage releasePackage) {
        ApprovalProcessRelease.Transition publish = new ApprovalProcessRelease.Transition(
            uuid("publish-" + releasePackage.tenantId()),
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            State.DRAFT,
            State.PUBLISHED,
            1,
            "Publish release for G3 runtime binding provenance",
            "g3-publish-" + releasePackage.tenantId(),
            releasePackage.publishedBy(),
            "request-g3-publish-" + releasePackage.tenantId(),
            "trace-g3",
            "audit-event:g3-publish-" + releasePackage.tenantId(),
            releasePackage.publishedAt()
        );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            releasePackage,
            publish
        );
        processReleases.savePublished(published, publish);

        Instant activatedAt = releasePackage.publishedAt().plusSeconds(1);
        ApprovalProcessRelease.Transition activate = new ApprovalProcessRelease.Transition(
            uuid("activate-" + releasePackage.tenantId()),
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "Activate release for G3 runtime binding provenance",
            "g3-activate-" + releasePackage.tenantId(),
            "Operator-G3",
            "request-g3-activate-" + releasePackage.tenantId(),
            "trace-g3",
            "audit-event:g3-activate-" + releasePackage.tenantId(),
            activatedAt
        );
        ApprovalProcessRelease active = published.transitioned(activate);
        assertTrue(processReleases.transition(active, published.revision(), activate));
    }

    private ReleaseEvidence seedStartEvidence(String tenant) {
        ReleaseEvidence evidence = seedReleaseEvidence(tenant);
        seedDefinition(tenant);
        ApprovalEffectiveRelease effective = effective(
            evidence.releasePackage(),
            evidence.deployment()
        );
        effectiveReleases.save(effective, activation(effective));
        return evidence;
    }

    private void seedDefinition(String tenant) {
        ApprovalProjectionStore raw = JdbcApprovalProjectionStoreFactory.create(
            dataSource,
            objectMapper
        );
        transactions.executeWithoutResult(status -> {
            raw.lockDefinition(
                tenant,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.DEFINITION_VERSION
            );
            if (raw.findDefinition(
                tenant,
                DEFINITION_KEY,
                MySqlApprovalProjectionProvenanceFixture.DEFINITION_VERSION
            ).isEmpty()) {
                raw.saveDefinition(definition(tenant));
            }
        });
    }

    private void seedInstance(
        String tenant,
        UUID instanceId,
        String engineInstanceId,
        String businessKey
    ) {
        seedDefinition(tenant);
        ApprovalProjectionStore raw = JdbcApprovalProjectionStoreFactory.create(
            dataSource,
            objectMapper
        );
        transactions.executeWithoutResult(status -> {
            raw.lockBusinessKey(tenant, businessKey);
            raw.createInstance(
                instance(tenant, instanceId, engineInstanceId, businessKey),
                List.of()
            );
        });
    }

    private ApprovalRuntimeBinding binding(
        ReleaseEvidence evidence,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        Instant boundAt,
        String evidenceHash
    ) {
        ApprovalReleasePackage releasePackage = evidence.releasePackage();
        ApprovalReleaseDeployment deployment = evidence.deployment();
        return new ApprovalRuntimeBinding(
            releasePackage.tenantId(),
            instanceId,
            businessKey,
            engineInstanceId,
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
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
            evidenceHash,
            "Operator-G3",
            boundAt,
            "request-g3-" + instanceId,
            "trace-g3",
            "audit-event:g3-" + instanceId
        );
    }

    private ApprovalRuntimeBinding canonicalBinding(ApprovalRuntimeBinding value) {
        return new ApprovalRuntimeBinding(
            value.tenantId(),
            value.approvalInstanceId(),
            value.businessKey(),
            value.engineInstanceId(),
            value.definitionKey(),
            value.releaseVersion(),
            value.releasePackageHash(),
            value.definitionVersion(),
            value.definitionHash(),
            value.formPackageVersion(),
            value.formPackageHash(),
            value.formVersion(),
            value.formHash(),
            value.uiSchemaVersion(),
            value.uiSchemaHash(),
            value.compilerVersion(),
            value.compiledArtifactHash(),
            value.bpmnHash(),
            value.deploymentMetadataHash(),
            value.engineDeploymentId(),
            value.engineDefinitionId(),
            value.engineVersion(),
            value.bindingEvidenceHash(),
            value.boundBy(),
            AuditHashCanonicalizer.canonicalInstant(value.boundAt()),
            value.requestId(),
            value.traceId(),
            value.auditChainReference()
        );
    }

    private ApprovalEffectiveRelease effective(
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment
    ) {
        return new ApprovalEffectiveRelease(
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            null,
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
            1,
            "Operator-G3",
            START_AT.minusSeconds(30),
            "Activate release for G3 runtime start",
            "request-effective-g3",
            "trace-g3"
        );
    }

    private ApprovalEffectiveRelease.Activation activation(ApprovalEffectiveRelease effective) {
        return new ApprovalEffectiveRelease.Activation(
            uuid("activation-" + effective.tenantId()),
            effective.tenantId(),
            effective.definitionKey(),
            effective.effectiveReleaseVersion(),
            effective.previousReleaseVersion(),
            effective.releasePackageHash(),
            effective.definitionVersion(),
            effective.formPackageVersion(),
            effective.compilerVersion(),
            effective.engineDeploymentId(),
            effective.engineDefinitionId(),
            effective.engineVersion(),
            ApprovalEffectiveRelease.Action.ACTIVATE,
            effective.revision(),
            effective.activatedBy(),
            effective.activatedAt(),
            effective.changeReason(),
            effective.requestId(),
            effective.traceId()
        );
    }

    private PurchasePaymentApplicationService startService(
        ExactStartEngine engine,
        AuditEventSink delegateAudit,
        UUID instanceId
    ) {
        ApprovalProjectionStore raw = JdbcApprovalProjectionStoreFactory.create(
            dataSource,
            objectMapper
        );
        ApprovalProjectionStore bindingProjection = new RuntimeBindingEnforcingProjectionStore(
            raw,
            bindings
        );
        AuditEventSink bindingAudit = new RuntimeBindingRecordingAuditEventSink(
            delegateAudit,
            raw,
            releasePackages,
            deployments,
            bindings,
            new ApprovalReleasePackageHasher()
        );
        return new PurchasePaymentApplicationService(
            engine,
            new ApprovalDslCompiler(),
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactionManager,
                START_CLOCK
            ),
            bindingProjection,
            bindingAudit,
            (context, rules) -> {
                throw new AssertionError("explicit assignees must bypass resolver");
            },
            ApprovalBusinessEventOutbox.noOp(),
            effectiveReleases,
            START_CLOCK,
            () -> instanceId
        );
    }

    private PurchasePaymentApplicationService.StartCommand startCommand(
        String tenant,
        String idempotencyKey
    ) {
        return new PurchasePaymentApplicationService.StartCommand(
            new RequestContext(
                tenant,
                "Initiator-G3",
                "request-" + idempotencyKey,
                idempotencyKey,
                "trace-g3-start"
            ),
            "business-" + idempotencyKey,
            new BigDecimal("5000.00"),
            "Supplier G3",
            "PO-G3-1",
            List.of("attachment-g3-1"),
            new AssigneeSnapshot(
                "Manager-G3",
                "Finance-Reviewer-G3",
                List.of("Finance-G3-A", "Finance-G3-B"),
                Map.of("resolvedFrom", "runtime-binding-g3-test")
            )
        );
    }

    private String expectedBindingHash(
        ApprovalRuntimeBinding binding,
        ReleaseEvidence evidence
    ) {
        ApprovalReleasePackage releasePackage = evidence.releasePackage();
        ApprovalReleaseDeployment deployment = evidence.deployment();
        return new ApprovalReleasePackageHasher().hashValues(
            binding.tenantId(),
            binding.approvalInstanceId(),
            binding.businessKey(),
            binding.engineInstanceId(),
            binding.definitionKey(),
            releasePackage.releaseVersion(),
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
            binding.boundBy(),
            binding.boundAt(),
            binding.requestId(),
            binding.traceId(),
            binding.auditChainReference()
        );
    }

    private int countTenantRows(String table, String tenant) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id = ?",
            Integer.class,
            tenant
        );
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-g3:" + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private record ReleaseEvidence(
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment
    ) {
    }

    private static final class ExactStartEngine implements ApprovalEngine {

        private final ReleaseEvidence evidence;
        private final AtomicInteger starts = new AtomicInteger();

        private ExactStartEngine(ReleaseEvidence evidence) {
            this.evidence = evidence;
        }

        @Override
        public DeploymentResult deploy(DeployCommand command) {
            throw new AssertionError("release-bound start must not deploy");
        }

        @Override
        public StartResult start(StartCommand command) {
            throw new AssertionError("release-bound start must use startExact");
        }

        @Override
        public StartResult startExact(ExactStartCommand command) {
            int sequence = starts.incrementAndGet();
            assertEquals(
                evidence.deployment().engineDefinitionId(),
                command.engineDefinitionId()
            );
            assertEquals(
                evidence.releasePackage().packageHash(),
                command.releasePackageHash()
            );
            return new StartResult("engine-instance-g3-" + sequence);
        }

        @Override
        public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
            return List.of(new TaskSnapshot(
                "engine-task-g3-1",
                query.processInstanceId(),
                "managerApproval",
                "Manager approval",
                "Manager-G3",
                START_AT
            ));
        }

        @Override
        public TaskResult complete(CompleteTaskCommand command) {
            throw new AssertionError("runtime binding G3 start test must not complete tasks");
        }
    }
}
