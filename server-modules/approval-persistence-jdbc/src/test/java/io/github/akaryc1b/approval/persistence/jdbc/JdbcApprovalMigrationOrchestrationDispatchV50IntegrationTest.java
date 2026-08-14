package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch.Snapshot;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.DispatchRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.RunEventType;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.DEFINITION_KEY;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationOrchestrationDispatchV50IntegrationTest
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
    void dispatchEventChainsToExactKillSwitchObservationAfterV50() {
        AdmissionResult admission = persistConsumedPlan(50);
        seedRuntimeEvidence(hash('1'), hash('2'));
        List<AuditEvent> audits = new ArrayList<>();
        ApprovalMigrationOrchestrationStore orchestration = orchestrationStore(audits);
        Snapshot killSwitch = new Snapshot(
            1,
            false,
            "CONFIGURED_OFF",
            hash('8')
        );
        var prepared = orchestration.prepare(new PrepareRequest(
            TENANT,
            admission.intent().intentId(),
            1,
            1,
            killSwitch,
            NOW.plusSeconds(40),
            "request-d7-v50-prepare",
            "trace-d7-v50"
        ));

        assertTrue(prepared.dispatchEligible());
        assertEquals(FIRST_INSTANCE, prepared.canary().approvalInstanceId());

        var provisioned = provisioningStore().ensureInitialAttempts(
            new ProvisioningRequest(
                TENANT,
                admission.intent().intentId(),
                "worker-d7-v50",
                NOW.plusSeconds(45),
                "request-d7-v50-provision",
                "trace-d7-v50",
                hash('7')
            )
        );
        assertEquals(2, provisioned.createdCount());

        ClaimResult claimed = claimStore().claim(new ClaimRequest(
            TENANT,
            admission.intent().intentId(),
            "worker-d7-v50",
            1,
            NOW.plusSeconds(50),
            NOW.plusSeconds(180),
            "request-d7-v50-claim",
            "trace-d7-v50",
            hash('9')
        ));
        assertEquals(1, claimed.batch().claimedCount());
        assertEquals(
            FIRST_INSTANCE,
            claimed.attempts().getFirst().approvalInstanceId()
        );

        var dispatch = orchestration.authorizeDispatch(new DispatchRequest(
            prepared.run(),
            claimed.attempts().getFirst().attemptId(),
            1,
            1,
            killSwitch,
            NOW.plusSeconds(60),
            "request-d7-v50-dispatch",
            "trace-d7-v50"
        ));

        assertTrue(dispatch.allowed());
        assertEquals(RunEventType.DISPATCH_ALLOWED, dispatch.event().eventType());
        assertEquals(
            dispatch.observation().observationEvidenceHash(),
            dispatch.event().predecessorHash()
        );
        assertEquals(
            claimed.attempts().getFirst().attemptId(),
            dispatch.event().attemptId()
        );
        assertEquals(1, count("ap_process_migration_kill_switch_observation"));
        assertEquals(2, count("ap_process_migration_orchestration_event"));
        assertEquals(2, audits.size());
    }

    private ApprovalMigrationOrchestrationStore orchestrationStore(
        List<AuditEvent> audits
    ) {
        return new JdbcApprovalMigrationOrchestrationStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            audits::add,
            UUID::randomUUID
        );
    }

    private JdbcApprovalMigrationAttemptProvisioningStore provisioningStore() {
        return new JdbcApprovalMigrationAttemptProvisioningStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            event -> {
            },
            UUID::randomUUID
        );
    }

    private JdbcApprovalMigrationAttemptClaimStore claimStore() {
        return new JdbcApprovalMigrationAttemptClaimStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            event -> {
            },
            UUID::randomUUID
        );
    }

    private AdmissionResult persistConsumedPlan(long identity) {
        ApprovalMigrationPlan plan = proposed(
            TENANT,
            PLAN_ID,
            "plan-key-v50-" + identity,
            hash('d')
        );
        plans.createPlan(plan, initialEvent(plan, "initial-plan-v50-" + identity));
        ApprovalMigrationPlanAuthorization authorization = authorization(
            plan,
            "migration-approver",
            "authorization-key-v50-" + identity,
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
                "authorization-event-v50-" + identity
            )
        );
        return new JdbcApprovalMigrationExecutionAdmissionStore(
            dataSource,
            mapper(),
            new JdbcTransactionManager(dataSource),
            event -> {
            }
        ).admit(admissionRequest(authorized, identity));
    }

    private AdmissionRequest admissionRequest(
        ApprovalMigrationPlan authorized,
        long identity
    ) {
        Instant consumedAt = NOW.plusSeconds(30);
        UUID intentId = new UUID(995, identity);
        String intentEvidenceHash = hash('f');
        String requestId = "request-admission-d7-v50-" + identity;
        String auditReference = "audit-admission-d7-v50-" + identity;
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
            "admission-d7-v50-" + identity,
            intentEvidenceHash,
            "migration-executor",
            "Admit exact authorized migration plan for D7 V50",
            authorized.expiresAt(),
            consumedAt,
            consumedAt,
            requestId,
            "trace-admission-d7-v50",
            auditReference
        );
        ApprovalMigrationIntentEvent intentEvent = new ApprovalMigrationIntentEvent(
            new UUID(996, identity),
            intent.tenantId(),
            intent.intentId(),
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
            new UUID(997, identity),
            authorized.tenantId(),
            authorized.planId(),
            authorized.planHash(),
            authorized.authorizationId(),
            authorized.authorizationEvidenceHash(),
            intent.intentId(),
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
            new UUID(998, identity),
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
        attributes.put("intentId", intent.intentId().toString());
        AuditEvent audit = new AuditEvent(
            new UUID(999, identity),
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
            consumedAt,
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
        JdbcApprovalRuntimeBindingStore bindings = new JdbcApprovalRuntimeBindingStore(
            dataSource
        );
        createRuntimeEvidence(
            projections,
            bindings,
            source,
            FIRST_INSTANCE,
            "business-first-v50",
            "engine-instance-first-v50",
            firstBindingHash
        );
        createRuntimeEvidence(
            projections,
            bindings,
            source,
            SECOND_INSTANCE,
            "business-second-v50",
            "engine-instance-second-v50",
            secondBindingHash
        );
    }

    private void seedSourceLifecycle(ApprovalReleasePackage source) {
        JdbcApprovalProcessReleaseStore releases = new JdbcApprovalProcessReleaseStore(
            dataSource
        );
        ApprovalProcessRelease.Transition publishedTransition =
            new ApprovalProcessRelease.Transition(
                UUID.fromString("99000000-0000-0000-0000-000000000001"),
                TENANT,
                DEFINITION_KEY,
                source.releaseVersion(),
                source.packageHash(),
                State.DRAFT,
                State.PUBLISHED,
                1,
                "Publish source release for D7 V50 evidence",
                "d7-v50-source-publish",
                source.publishedBy(),
                "request-d7-v50-source-publish",
                "trace-d7-v50-source",
                "audit-d7-v50-source-publish",
                source.publishedAt()
            );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            source,
            publishedTransition
        );
        releases.savePublished(published, publishedTransition);

        ApprovalProcessRelease.Transition activeTransition =
            new ApprovalProcessRelease.Transition(
                UUID.fromString("99000000-0000-0000-0000-000000000002"),
                TENANT,
                DEFINITION_KEY,
                source.releaseVersion(),
                source.packageHash(),
                State.PUBLISHED,
                State.ACTIVE,
                2,
                "Activate source release for D7 V50 evidence",
                "d7-v50-source-activate",
                "migration-release-operator",
                "request-d7-v50-source-activate",
                "trace-d7-v50-source",
                "audit-d7-v50-source-activate",
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
            "po-v50",
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
            "request-binding-d7-v50-" + instanceId,
            "trace-binding-d7-v50",
            "audit-binding-d7-v50-" + instanceId
        ));
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
