package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionResult;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.DEFINITION_KEY;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationAttemptProvisioningStoreIntegrationTest
    extends JdbcApprovalMigrationPlanStoreIntegrationTestSupport {

    @BeforeEach
    void cleanRuntimeEvidence() {
        jdbc.execute("""
            truncate table ap_process_runtime_binding,ap_approval_instance,
              ap_process_release_lifecycle_history,ap_process_release_lifecycle,
              ap_definition_version cascade
            """);
    }

    @Test
    void createsExactInitialAttemptsAndReplaysWithoutDuplicateAudit() {
        AdmissionResult admission = admitConsumedPlan(1);
        seedRuntimeEvidence(hash('1'), hash('2'));
        List<AuditEvent> audits = new ArrayList<>();
        JdbcApprovalMigrationAttemptProvisioningStore provisioning = provisioningStore(audits);
        ProvisioningRequest request = request(
            admission.intent(),
            "request-provision-one",
            hash('7')
        );

        ProvisioningResult first = provisioning.ensureInitialAttempts(request);
        ProvisioningResult replay = provisioning.ensureInitialAttempts(request);

        assertFalse(first.replayedExistingProvisioning());
        assertTrue(replay.replayedExistingProvisioning());
        assertEquals(2, first.createdCount());
        assertEquals(0, replay.createdCount());
        assertEquals(first.initialAttempts(), replay.initialAttempts());
        assertEquals(2, first.initialAttempts().size());
        assertEquals(
            List.of(FIRST_INSTANCE, SECOND_INSTANCE),
            first.initialAttempts().stream()
                .map(ApprovalMigrationAttempt::approvalInstanceId)
                .toList()
        );
        assertEquals(
            List.of(hash('1'), hash('2')),
            first.initialAttempts().stream()
                .map(ApprovalMigrationAttempt::expectedBindingEvidenceHash)
                .toList()
        );
        assertTrue(first.initialAttempts().stream().allMatch(
            attempt -> attempt.status() == AttemptStatus.PENDING
                && attempt.attemptNumber() == 1
                && attempt.parentAttemptId() == null
                && "source-definition-v1".equals(attempt.sourceEngineDefinitionId())
                && "engine-definition-v2".equals(attempt.targetEngineDefinitionId())
        ));
        assertEquals(2, count("ap_process_migration_attempt"));
        assertEquals(2, count("ap_process_migration_attempt_event"));
        assertEquals(1, audits.size());
    }

    @Test
    void concurrentProvisioningHasOneCreatorAndOneAuthoritativeReplay() throws Exception {
        AdmissionResult admission = admitConsumedPlan(2);
        seedRuntimeEvidence(hash('1'), hash('2'));
        List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        JdbcApprovalMigrationAttemptProvisioningStore firstStore = provisioningStore(audits);
        JdbcApprovalMigrationAttemptProvisioningStore secondStore = provisioningStore(audits);
        ProvisioningRequest firstRequest = request(
            admission.intent(),
            "request-provision-concurrent-one",
            hash('8')
        );
        ProvisioningRequest secondRequest = request(
            admission.intent(),
            "request-provision-concurrent-two",
            hash('9')
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProvisioningResult> first = executor.submit(
                () -> gatedProvision(firstStore, firstRequest, ready, start)
            );
            Future<ProvisioningResult> second = executor.submit(
                () -> gatedProvision(secondStore, secondRequest, ready, start)
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<ProvisioningResult> results = List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
            );

            assertEquals(
                2,
                results.stream()
                    .mapToInt(ProvisioningResult::createdCount)
                    .sum()
            );
            assertEquals(
                1,
                results.stream()
                    .filter(ProvisioningResult::replayedExistingProvisioning)
                    .count()
            );
            assertEquals(results.get(0).initialAttempts(), results.get(1).initialAttempts());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertEquals(2, count("ap_process_migration_attempt"));
        assertEquals(2, count("ap_process_migration_attempt_event"));
        assertEquals(1, audits.size());
    }

    @Test
    void rejectsRuntimeBindingDriftWithoutCreatingAttempts() {
        AdmissionResult admission = admitConsumedPlan(3);
        seedRuntimeEvidence(hash('a'), hash('2'));
        List<AuditEvent> audits = new ArrayList<>();
        JdbcApprovalMigrationAttemptProvisioningStore provisioning = provisioningStore(audits);

        assertThrows(
            ApprovalMigrationAttemptProvisioningStore.MigrationAttemptProvisioningConflictException.class,
            () -> provisioning.ensureInitialAttempts(request(
                admission.intent(),
                "request-provision-drift",
                hash('b')
            ))
        );

        assertEquals(0, count("ap_process_migration_attempt"));
        assertEquals(0, count("ap_process_migration_attempt_event"));
        assertEquals(0, audits.size());
    }

    @Test
    void auditFailureRollsBackAllInitialAttemptsAndEvents() {
        AdmissionResult admission = admitConsumedPlan(4);
        seedRuntimeEvidence(hash('1'), hash('2'));
        JdbcApprovalMigrationAttemptProvisioningStore provisioning =
            new JdbcApprovalMigrationAttemptProvisioningStore(
                dataSource,
                mapper(),
                new JdbcTransactionManager(dataSource),
                event -> {
                    throw new IllegalStateException("provisioning audit persistence failed");
                },
                UUID::randomUUID
            );

        assertThrows(
            IllegalStateException.class,
            () -> provisioning.ensureInitialAttempts(request(
                admission.intent(),
                "request-provision-audit",
                hash('c')
            ))
        );

        assertEquals(0, count("ap_process_migration_attempt"));
        assertEquals(0, count("ap_process_migration_attempt_event"));
    }

    private AdmissionResult admitConsumedPlan(long identity) {
        ApprovalMigrationPlan plan = proposed(
            TENANT,
            PLAN_ID,
            "plan-key-" + identity,
            hash('d')
        );
        plans.createPlan(plan, initialEvent(plan, "initial-plan-" + identity));
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-key-" + identity,
            hash('e')
        );
        ApprovalMigrationPlan authorized = plan.authorized(authorization);
        plans.authorizePlan(
            authorized,
            1,
            authorization,
            authorizationEvent(
                plan,
                authorized,
                authorization,
                "authorization-event-" + identity
            )
        );
        AdmissionRequest request = admissionRequest(
            authorized,
            identity
        );
        return new JdbcApprovalMigrationExecutionAdmissionStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            event -> {
            }
        ).admit(request);
    }

    private AdmissionRequest admissionRequest(
        ApprovalMigrationPlan authorized,
        long identity
    ) {
        Instant consumedAt = NOW.plusSeconds(30);
        UUID intentId = new UUID(880, identity);
        String intentEvidenceHash = hash('f');
        String requestId = "request-admission-" + identity;
        String auditReference = "audit-admission-" + identity;
        ApprovalMigrationIntent intent = new ApprovalMigrationIntent(
            intentId,
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            authorized.definitionKey(),
            authorized.sourceReleaseVersion(),
            authorized.sourcePackageHash(),
            authorized.targetReleaseVersion(),
            authorized.targetPackageHash(),
            authorized.selectedInstanceCount(),
            IntentStatus.PENDING,
            1,
            "admission-key-" + identity,
            intentEvidenceHash,
            "migration-executor",
            "Admit exact authorized migration plan",
            authorized.expiresAt(),
            consumedAt,
            consumedAt,
            requestId,
            "trace-admission",
            auditReference
        );
        ApprovalMigrationIntentEvent intentEvent = new ApprovalMigrationIntentEvent(
            new UUID(881, identity),
            authorized.tenantId(),
            intentId,
            1,
            null,
            IntentStatus.PENDING,
            intent.operationReason(),
            intent.requestedBy(),
            consumedAt,
            requestId,
            intent.traceId(),
            auditReference
        );
        ApprovalMigrationPlanConsumption consumption = new ApprovalMigrationPlanConsumption(
            new UUID(882, identity),
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            authorized.authorizationId(),
            authorized.authorizationEvidenceHash(),
            intentId,
            intentEvidenceHash,
            intent.idempotencyKey(),
            hash('6'),
            intent.requestedBy(),
            intent.operationReason(),
            consumedAt,
            requestId,
            intent.traceId(),
            auditReference
        );
        ApprovalMigrationPlan consumed = consumed(authorized, consumedAt);
        ApprovalMigrationPlanEvent planEvent = new ApprovalMigrationPlanEvent(
            new UUID(883, identity),
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            consumed.revision(),
            PlanStatus.AUTHORIZED,
            PlanStatus.CONSUMED,
            intent.requestedBy(),
            intent.operationReason(),
            authorized.authorizationId(),
            authorized.authorizationEvidenceHash(),
            consumedAt,
            requestId,
            intent.traceId(),
            auditReference
        );
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("planHash", authorized.planHash());
        attributes.put("intentId", intentId.toString());
        AuditEvent audit = new AuditEvent(
            new UUID(884, identity),
            authorized.tenantId(),
            intent.requestedBy(),
            "PROCESS_MIGRATION_PLAN_CONSUMED",
            "APPROVAL_MIGRATION_PLAN",
            authorized.planId().toString(),
            requestId,
            intent.traceId(),
            consumedAt,
            Map.copyOf(attributes)
        );
        return new AdmissionRequest(
            consumed,
            authorized.revision(),
            intent,
            intentEvent,
            consumption,
            planEvent,
            audit
        );
    }

    private static ApprovalMigrationPlan consumed(
        ApprovalMigrationPlan plan,
        Instant consumedAt
    ) {
        return new ApprovalMigrationPlan(
            plan.planId(), plan.tenantId(), plan.assessmentId(), plan.assessmentReportHash(),
            plan.definitionKey(), plan.sourceReleaseVersion(), plan.sourcePackageHash(),
            plan.targetReleaseVersion(), plan.targetPackageHash(), plan.targetDeploymentRecordId(),
            plan.targetEngineDeploymentId(), plan.targetEngineDefinitionId(),
            plan.targetEngineVersion(), plan.selectedInstances(), PlanStatus.CONSUMED,
            plan.revision() + 1, plan.idempotencyKey(), plan.planHash(), plan.requestedBy(),
            plan.operationReason(), plan.assessedAt(), plan.createdAt(), plan.expiresAt(),
            consumedAt, plan.authorizationId(), plan.authorizationEvidenceHash(),
            plan.authorizedBy(), plan.authorizedAt(), plan.authorizationExpiresAt(),
            plan.requestId(), plan.traceId(), plan.auditChainReference()
        );
    }

    private void seedRuntimeEvidence(
        String firstBindingHash,
        String secondBindingHash
    ) {
        ApprovalReleasePackage source = new JdbcApprovalReleasePackageStore(dataSource)
            .find(TENANT, DEFINITION_KEY, 1)
            .orElseThrow();
        seedSourceLifecycle(source);

        JdbcApprovalProjectionStore projections = new JdbcApprovalProjectionStore(
            dataSource,
            mapper()
        );
        projections.saveDefinition(new PublishedDefinition(
            TENANT,
            DEFINITION_KEY,
            source.definitionVersion(),
            "purchasePaymentForm",
            source.formVersion(),
            source.compilerVersion(),
            source.definitionHash(),
            "source-deployment-v1",
            "source-definition-v1",
            1,
            source.publishedBy(),
            source.publishedAt()
        ));
        JdbcApprovalRuntimeBindingStore bindings = new JdbcApprovalRuntimeBindingStore(dataSource);
        createRuntimeEvidence(
            projections,
            bindings,
            source,
            FIRST_INSTANCE,
            "business-first",
            "engine-instance-first",
            firstBindingHash
        );
        createRuntimeEvidence(
            projections,
            bindings,
            source,
            SECOND_INSTANCE,
            "business-second",
            "engine-instance-second",
            secondBindingHash
        );
    }

    private void seedSourceLifecycle(ApprovalReleasePackage source) {
        JdbcApprovalProcessReleaseStore releases = new JdbcApprovalProcessReleaseStore(dataSource);
        ApprovalProcessRelease.Transition publishedTransition = new ApprovalProcessRelease.Transition(
            UUID.fromString("89000000-0000-0000-0000-000000000001"),
            TENANT,
            DEFINITION_KEY,
            source.releaseVersion(),
            source.packageHash(),
            State.DRAFT,
            State.PUBLISHED,
            1,
            "Publish source release for migration provisioning evidence",
            "provisioning-source-publish",
            source.publishedBy(),
            "request-provisioning-source-publish",
            "trace-provisioning-source",
            "audit-provisioning-source-publish",
            source.publishedAt()
        );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            source,
            publishedTransition
        );
        releases.savePublished(published, publishedTransition);

        ApprovalProcessRelease.Transition activeTransition = new ApprovalProcessRelease.Transition(
            UUID.fromString("89000000-0000-0000-0000-000000000002"),
            TENANT,
            DEFINITION_KEY,
            source.releaseVersion(),
            source.packageHash(),
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "Activate source release for migration provisioning evidence",
            "provisioning-source-activate",
            "migration-release-operator",
            "request-provisioning-source-activate",
            "trace-provisioning-source",
            "audit-provisioning-source-activate",
            source.publishedAt().plusSeconds(1)
        );
        ApprovalProcessRelease active = published.transitioned(activeTransition);
        if (!releases.transition(active, published.revision(), activeTransition)) {
            throw new AssertionError("source release lifecycle activation failed");
        }
    }

    private void createRuntimeEvidence(
        JdbcApprovalProjectionStore projections,
        JdbcApprovalRuntimeBindingStore bindings,
        ApprovalReleasePackage source,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        String bindingEvidenceHash
    ) {
        Instant createdAt = NOW.plusSeconds(1);
        projections.createInstance(new InstanceProjection(
            instanceId,
            TENANT,
            businessKey,
            engineInstanceId,
            DEFINITION_KEY,
            source.definitionVersion(),
            "purchasePaymentForm",
            source.formVersion(),
            source.compilerVersion(),
            source.definitionHash(),
            source.releaseVersion(),
            source.packageHash(),
            source.formPackageVersion(),
            source.formPackageHash(),
            source.uiSchemaVersion(),
            source.uiSchemaHash(),
            "source-definition-v1",
            "migration-initiator",
            new BigDecimal("100.00"),
            "supplier",
            "po-1",
            List.of(),
            new AssigneeSnapshot(
                "manager-one",
                "finance-reviewer",
                List.of("finance-approver"),
                Map.of()
            ),
            hash('9'),
            InstanceStatus.RUNNING,
            1,
            createdAt,
            createdAt
        ), List.of());
        bindings.save(new ApprovalRuntimeBinding(
            TENANT,
            instanceId,
            businessKey,
            engineInstanceId,
            DEFINITION_KEY,
            source.releaseVersion(),
            source.packageHash(),
            source.definitionVersion(),
            source.definitionHash(),
            source.formPackageVersion(),
            source.formPackageHash(),
            source.formVersion(),
            source.formHash(),
            source.uiSchemaVersion(),
            source.uiSchemaHash(),
            source.compilerVersion(),
            source.compiledArtifactHash(),
            source.bpmnHash(),
            source.deploymentMetadataHash(),
            "source-deployment-v1",
            "source-definition-v1",
            1,
            bindingEvidenceHash,
            "migration-binder",
            NOW.plusSeconds(2),
            "request-binding-" + instanceId,
            "trace-binding",
            "audit-binding-" + instanceId
        ));
    }

    private JdbcApprovalMigrationAttemptProvisioningStore provisioningStore(
        List<AuditEvent> audits
    ) {
        return new JdbcApprovalMigrationAttemptProvisioningStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            audits::add,
            UUID::randomUUID
        );
    }

    private static ProvisioningRequest request(
        ApprovalMigrationIntent intent,
        String requestId,
        String requestHash
    ) {
        return new ProvisioningRequest(
            intent.tenantId(),
            intent.intentId(),
            "server-worker-provision",
            NOW.plusSeconds(40),
            requestId,
            "trace-provision",
            requestHash
        );
    }

    private static ProvisioningResult gatedProvision(
        JdbcApprovalMigrationAttemptProvisioningStore provisioning,
        ProvisioningRequest request,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return provisioning.ensureInitialAttempts(request);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
