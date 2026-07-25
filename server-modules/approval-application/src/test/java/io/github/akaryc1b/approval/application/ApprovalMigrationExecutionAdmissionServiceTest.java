package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationExecutionAdmissionService.AdmitPlanCommand;
import io.github.akaryc1b.approval.application.ApprovalMigrationPlanService.AuthorizePlanCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationExecutionAdmissionServiceTest
    extends ApprovalMigrationPlanServiceTestSupport {

    private InMemoryAdmissionStore admissionStore;
    private ApprovalMigrationExecutionAdmissionService admissionService;
    private ApprovalMigrationPlan authorizedPlan;

    @BeforeEach
    void setUpAdmission() {
        authorizedPlan = service.authorizePlan(new AuthorizePlanCommand(
            context("migration-approver", "authorization-key"),
            createPlan().planId(),
            1,
            "Authorize immutable plan before server admission"
        )).plan();
        admissionStore = new InMemoryAdmissionStore();
        AtomicLong identifiers = new AtomicLong(100);
        admissionService = new ApprovalMigrationExecutionAdmissionService(
            directIdempotency(),
            service,
            admissionStore,
            releaseStore(),
            packageStore(),
            deploymentStore,
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
            () -> new UUID(77, identifiers.getAndIncrement())
        );
    }

    @Test
    void consumesExactAuthorizedPlanAndCreatesOnePendingIntentAtomically() {
        var result = admissionService.admit(command("admission-key", "Admit exact authorized plan"));

        assertEquals(PlanStatus.CONSUMED, result.plan().status());
        assertEquals(3, result.plan().revision());
        assertEquals(IntentStatus.PENDING, result.intent().status());
        assertEquals(1, result.intent().revision());
        assertEquals(authorizedPlan.planId(), result.intent().planId());
        assertEquals(authorizedPlan.planHash(), result.intent().planHash());
        assertEquals(authorizedPlan.selectedInstanceCount(), result.intent().selectedInstanceCount());
        assertEquals(result.intent().intentId(), result.consumption().intentId());
        assertEquals(result.intent().intentEvidenceHash(), result.consumption().intentEvidenceHash());
        assertEquals(3, planStore.events.get(authorizedPlan.planId()).size());
        assertTrue(auditEvents.stream().anyMatch(
            event -> event.action().equals("PROCESS_MIGRATION_PLAN_CONSUMED")
        ));
    }

    @Test
    void exactReplayReturnsAuthoritativeExistingIntentWithoutDuplicateEvidence() {
        var first = admissionService.admit(command("replay-key", "Admit exact authorized plan"));
        var replay = admissionService.admit(command("replay-key", "Admit exact authorized plan"));

        assertTrue(replay.replayedExistingAdmission());
        assertEquals(first.plan(), replay.plan());
        assertEquals(first.intent(), replay.intent());
        assertEquals(first.consumption(), replay.consumption());
        assertEquals(3, planStore.events.get(authorizedPlan.planId()).size());
        assertEquals(3, auditEvents.size());
    }

    @Test
    void changedPayloadWithSameIdempotencyKeyConflicts() {
        admissionService.admit(command("conflict-key", "Admit exact authorized plan"));

        assertThrows(
            ApprovalMigrationExecutionAdmissionStore
                .MigrationExecutionAdmissionConflictException.class,
            () -> admissionService.admit(command(
                "conflict-key",
                "Admit exact authorized plan with changed reason"
            ))
        );
    }

    @Test
    void expiredAuthorizationAndDeploymentDriftFailClosed() {
        ApprovalMigrationExecutionAdmissionService expired = new ApprovalMigrationExecutionAdmissionService(
            directIdempotency(),
            service,
            new InMemoryAdmissionStore(),
            releaseStore(),
            packageStore(),
            deploymentStore,
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC),
            () -> UUID.randomUUID()
        );
        assertThrows(
            ApprovalMigrationPlanService.MigrationPlanNotAuthorizedException.class,
            () -> expired.admit(command("expired-key", "Reject expired plan admission"))
        );

        deploymentStore.deployment = new io.github.akaryc1b.approval.domain.definition
            .ApprovalReleaseDeployment(
                UUID.fromString("76000000-0000-0000-0000-000000000031"),
                TENANT,
                DEFINITION_KEY,
                2,
                hash('c'),
                io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment
                    .Status.DEPLOYED,
                2,
                "engine-deployment-v2-replaced",
                "engine-definition-v2-replaced",
                3,
                null,
                null,
                "release-operator",
                NOW.minusSeconds(500),
                NOW.minusSeconds(100),
                NOW.minusSeconds(100)
            );
        assertThrows(
            ApprovalMigrationExecutionAdmissionService
                .MigrationExecutionAdmissionNotAuthorizedException.class,
            () -> admissionService.admit(command("drift-key", "Reject deployment drift"))
        );
    }

    @Test
    void admissionStoreFailureDoesNotConsumePlanOrCreateIntent() {
        ApprovalMigrationExecutionAdmissionService failing = new ApprovalMigrationExecutionAdmissionService(
            directIdempotency(),
            service,
            new FailingAdmissionStore(),
            releaseStore(),
            packageStore(),
            deploymentStore,
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
            () -> UUID.randomUUID()
        );

        assertThrows(
            IllegalStateException.class,
            () -> failing.admit(command("audit-failure-key", "Fail closed on audit failure"))
        );
        assertEquals(PlanStatus.AUTHORIZED, planStore.plans.get(authorizedPlan.planId()).status());
        assertTrue(admissionStore.intents.isEmpty());
        assertEquals(2, auditEvents.size());
    }

    private AdmitPlanCommand command(String key, String reason) {
        return new AdmitPlanCommand(
            context("migration-executor", key),
            authorizedPlan.planId(),
            authorizedPlan.planHash(),
            reason
        );
    }

    private final class InMemoryAdmissionStore
        implements ApprovalMigrationExecutionAdmissionStore {

        private final Map<UUID, ApprovalMigrationIntent> intents = new LinkedHashMap<>();
        private final Map<UUID, ApprovalMigrationPlanConsumption> consumptions =
            new LinkedHashMap<>();
        private final Map<String, UUID> idempotency = new LinkedHashMap<>();

        @Override
        public AdmissionResult admit(AdmissionRequest request) {
            UUID existingId = idempotency.get(request.consumption().idempotencyKey());
            if (existingId != null) {
                ApprovalMigrationPlanConsumption existing = consumptions.get(existingId);
                if (!existing.requestHash().equals(request.consumption().requestHash())) {
                    throw new MigrationExecutionAdmissionConflictException(
                        "idempotency key was reused with different evidence"
                    );
                }
                return new AdmissionResult(
                    planStore.plans.get(existing.planId()),
                    intents.get(existing.intentId()),
                    existing,
                    true
                );
            }
            ApprovalMigrationPlan current = planStore.plans.get(request.consumedPlan().planId());
            if (current.status() != PlanStatus.AUTHORIZED
                || current.revision() != request.expectedRevision()) {
                throw new MigrationExecutionAdmissionConflictException("plan CAS conflict");
            }
            auditEvents.add(request.auditEvent());
            intents.put(request.intent().intentId(), request.intent());
            consumptions.put(request.consumption().consumptionId(), request.consumption());
            idempotency.put(
                request.consumption().idempotencyKey(),
                request.consumption().consumptionId()
            );
            planStore.plans.put(request.consumedPlan().planId(), request.consumedPlan());
            planStore.events.get(request.consumedPlan().planId()).add(request.planEvent());
            return new AdmissionResult(
                request.consumedPlan(),
                request.intent(),
                request.consumption(),
                false
            );
        }

        @Override
        public Optional<ApprovalMigrationPlanConsumption> findConsumption(
            String tenantId,
            UUID planId
        ) {
            return consumptions.values().stream().filter(
                value -> value.tenantId().equals(tenantId) && value.planId().equals(planId)
            ).findFirst();
        }
    }

    private static final class FailingAdmissionStore
        implements ApprovalMigrationExecutionAdmissionStore {

        @Override
        public AdmissionResult admit(AdmissionRequest request) {
            throw new IllegalStateException("audit persistence failed");
        }

        @Override
        public Optional<ApprovalMigrationPlanConsumption> findConsumption(
            String tenantId,
            UUID planId
        ) {
            return Optional.empty();
        }
    }
}
