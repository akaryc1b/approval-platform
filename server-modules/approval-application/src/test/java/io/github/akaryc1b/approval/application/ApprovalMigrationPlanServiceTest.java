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
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationPlanServiceTest extends ApprovalMigrationPlanServiceTestSupport {
    @Test
    void createsCanonicalPlanOnlyFromCompleteReadyAssessment() {
        var result = service.createPlan(new CreatePlanCommand(
            context("migration-requester", "plan-key"),
            assessment(AssessmentStatus.READY, InstanceDecision.ELIGIBLE),
            List.of(SECOND_INSTANCE, FIRST_INSTANCE),
            "Create immutable plan from complete assessment"
        ));

        ApprovalMigrationPlan plan = result.plan();
        assertEquals(PlanStatus.PROPOSED, plan.status());
        assertEquals(List.of(FIRST_INSTANCE, SECOND_INSTANCE), plan.selectedInstances().stream()
            .map(ApprovalMigrationPlan.SelectedInstance::approvalInstanceId)
            .toList());
        assertEquals(NOW.plusSeconds(540), plan.expiresAt());
        assertEquals(1, planStore.events.get(plan.planId()).size());
        assertEquals(1, auditEvents.size());
    }

    @Test
    void rejectsPartialBlockedOrUnselectedAssessmentEvidence() {
        assertThrows(
            ApprovalMigrationPlanService.StaleMigrationAssessmentException.class,
            () -> service.createPlan(new CreatePlanCommand(
                context("migration-requester", "partial-key"),
                assessment(AssessmentStatus.PARTIAL, InstanceDecision.ELIGIBLE),
                List.of(FIRST_INSTANCE),
                "Reject partial migration assessment evidence"
            ))
        );
        assertThrows(
            ApprovalMigrationPlanService.StaleMigrationAssessmentException.class,
            () -> service.createPlan(new CreatePlanCommand(
                context("migration-requester", "blocked-key"),
                assessment(AssessmentStatus.READY, InstanceDecision.BLOCKED),
                List.of(FIRST_INSTANCE),
                "Reject blocked selected migration instance"
            ))
        );
    }

    @Test
    void rejectsNonCanonicalOrDuplicateAssessmentEvidence() {
        AssessmentResult base = assessment(AssessmentStatus.READY, InstanceDecision.ELIGIBLE);
        AssessmentResult nonCanonical = withInstances(base, List.of(
            instance(FIRST_INSTANCE, InstanceDecision.ELIGIBLE, '1', List.of(" review")),
            instance(SECOND_INSTANCE, InstanceDecision.ELIGIBLE, '2')
        ));
        assertThrows(
            ApprovalMigrationPlanService.StaleMigrationAssessmentException.class,
            () -> service.createPlan(new CreatePlanCommand(
                context("migration-requester", "noncanonical-key"),
                nonCanonical,
                List.of(FIRST_INSTANCE),
                "Reject noncanonical task key evidence"
            ))
        );

        AssessmentResult duplicate = withInstances(base, List.of(
            instance(FIRST_INSTANCE, InstanceDecision.ELIGIBLE, '1'),
            instance(FIRST_INSTANCE, InstanceDecision.ELIGIBLE, '2')
        ));
        assertThrows(
            ApprovalMigrationPlanService.StaleMigrationAssessmentException.class,
            () -> service.createPlan(new CreatePlanCommand(
                context("migration-requester", "duplicate-assessment-key"),
                duplicate,
                List.of(FIRST_INSTANCE),
                "Reject duplicate assessment instance evidence"
            ))
        );
    }

    @Test
    void rejectsAuthorizationGateDecisionForAnotherOperator() {
        ApprovalMigrationPlan plan = createPlan();
        authorizationGate.authorizedBy = "different-approver";

        assertThrows(
            ApprovalMigrationPlanService.MigrationPlanNotAuthorizedException.class,
            () -> service.authorizePlan(new AuthorizePlanCommand(
                context("migration-approver", "gate-mismatch-key"),
                plan.planId(),
                1,
                "Reject authorization decision for another operator"
            ))
        );
    }

    @Test
    void targetDeploymentIdentityDriftPreventsAuthorization() {
        ApprovalMigrationPlan plan = createPlan();
        deploymentStore.deployment = new ApprovalReleaseDeployment(
            UUID.fromString("76000000-0000-0000-0000-000000000031"),
            TENANT,
            DEFINITION_KEY,
            2,
            hash('c'),
            ApprovalReleaseDeployment.Status.DEPLOYED,
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
            ApprovalMigrationPlanService.MigrationPlanNotAuthorizedException.class,
            () -> service.authorizePlan(new AuthorizePlanCommand(
                context("migration-approver", "deployment-drift-key"),
                plan.planId(),
                1,
                "Reject authorization after target deployment identity drift"
            ))
        );
    }

    @Test
    void requesterCannotAuthorizeOwnPlan() {
        ApprovalMigrationPlan plan = createPlan();

        assertThrows(
            ApprovalMigrationPlanService.MigrationPlanNotAuthorizedException.class,
            () -> service.authorizePlan(new AuthorizePlanCommand(
                context("migration-requester", "authorization-key"),
                plan.planId(),
                1,
                "Requester cannot approve own migration plan"
            ))
        );
    }

    @Test
    void exactIndependentAuthorizationOpensOnlyTheAuthorizedReadGate() {
        ApprovalMigrationPlan plan = createPlan();

        var result = service.authorizePlan(new AuthorizePlanCommand(
                context("migration-approver", "authorization-key"),
                plan.planId(),
                1,
                "Approve exact immutable migration plan hash"
            ));

        assertEquals(PlanStatus.AUTHORIZED, result.plan().status());
        assertEquals("migration-approver", result.authorization().authorizedBy());
        assertEquals(plan.planId(), authorizationGate.lastRequest.planId());
        assertEquals(plan.planHash(), authorizationGate.lastRequest.planHash());
        assertEquals(plan.selectedInstanceCount(), authorizationGate.lastRequest.selectedInstanceCount());
        assertEquals(
            plan.targetEngineDefinitionId(),
            authorizationGate.lastRequest.targetEngineDefinitionId()
        );
        assertEquals(
            result.plan(),
            service.requireAuthorizedPlan(TENANT, plan.planId(), plan.planHash(), NOW.plusSeconds(1))
        );
        assertThrows(
            ApprovalMigrationPlanService.MigrationPlanNotAuthorizedException.class,
            () -> service.requireAuthorizedPlan(TENANT, plan.planId(), hash('8'), NOW)
        );
        assertTrue(auditEvents.stream().anyMatch(
            event -> event.action().equals("PROCESS_MIGRATION_PLAN_AUTHORIZED")
        ));
    }
}
