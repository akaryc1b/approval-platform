package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore.MigrationPlanConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan.SelectedInstance;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.ExpectedInstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.DEFINITION_KEY;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.OTHER_TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class JdbcApprovalMigrationPlanStoreIntegrationTestSupport
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    protected static final UUID PLAN_ID = UUID.fromString(
        "77000000-0000-0000-0000-000000000001"
    );
    protected static final UUID ASSESSMENT_ID = UUID.fromString(
        "77000000-0000-0000-0000-000000000002"
    );
    protected static final UUID FIRST_INSTANCE = UUID.fromString(
        "77000000-0000-0000-0000-000000000011"
    );
    protected static final UUID SECOND_INSTANCE = UUID.fromString(
        "77000000-0000-0000-0000-000000000012"
    );

    protected JdbcApprovalMigrationPlanStore plans;

    @BeforeEach
    void setUpPlanStore() {
        seedTargetDeployments();
        plans = newStore();
    }

    protected boolean authorizeConcurrently(
        JdbcApprovalMigrationPlanStore store,
        ApprovalMigrationPlan plan,
        ApprovalMigrationPlanAuthorization authorization,
        CountDownLatch ready,
        CountDownLatch start,
        String suffix
    ) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        ApprovalMigrationPlan next = plan.authorized(authorization);
        try {
            store.authorizePlan(
                next,
                1,
                authorization,
                authorizationEvent(plan, next, authorization, suffix)
            );
            return true;
        } catch (MigrationPlanConflictException exception) {
            return false;
        }
    }

    private void seedTargetDeployments() {
        seedTargetDeployment(TENANT, UUID.fromString(
            "77000000-0000-0000-0000-000000000030"
        ));
        seedTargetDeployment(OTHER_TENANT, UUID.fromString(
            "77000000-0000-0000-0000-000000000030"
        ));
    }

    private void seedTargetDeployment(String tenantId, UUID deploymentRecordId) {
        jdbc.update("""
            insert into ap_approval_release_deployment (
              deployment_record_id,tenant_id,definition_key,release_version,
              release_package_hash,status,attempt_count,engine_deployment_id,
              engine_definition_id,engine_version,last_error_code,last_error_message,
              requested_by,created_at,updated_at,deployed_at
            ) values (?,?,?,?,?,'DEPLOYED',1,?,?,2,null,null,?,?,?,?)
            """,
            deploymentRecordId,
            tenantId,
            DEFINITION_KEY,
            2,
            hash('c'),
            "engine-deployment-v2",
            "engine-definition-v2",
            "release-operator",
            offset(NOW.minusSeconds(500)),
            offset(NOW.minusSeconds(400)),
            offset(NOW.minusSeconds(400))
        );
    }

    protected static OffsetDateTime offset(java.time.Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    protected JdbcApprovalMigrationPlanStore newStore() {
        return new JdbcApprovalMigrationPlanStore(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            new JdbcTransactionManager(dataSource)
        );
    }

    protected static ApprovalMigrationPlan proposed(
        String tenantId,
        UUID planId,
        String idempotencyKey,
        String planHash
    ) {
        return new ApprovalMigrationPlan(
            planId,
            tenantId,
            ASSESSMENT_ID,
            hash('a'),
            DEFINITION_KEY,
            1,
            hash('b'),
            2,
            hash('c'),
            UUID.fromString("77000000-0000-0000-0000-000000000030"),
            "engine-deployment-v2",
            "engine-definition-v2",
            2,
            List.of(
                selected(FIRST_INSTANCE, '1'),
                selected(SECOND_INSTANCE, '2')
            ),
            PlanStatus.PROPOSED,
            1,
            idempotencyKey,
            planHash,
            "migration-requester",
            "Create immutable migration plan from assessment",
            NOW.minusSeconds(60),
            NOW,
            NOW.plusSeconds(300),
            NOW,
            null,
            null,
            null,
            null,
            null,
            "request-" + idempotencyKey,
            "trace-migration-plan",
            "audit-" + idempotencyKey
        );
    }

    protected static SelectedInstance selected(UUID instanceId, char value) {
        return new SelectedInstance(
            instanceId,
            ExpectedInstanceStatus.RUNNING,
            List.of("approve", "review"),
            hash(value),
            hash((char) (value + 2))
        );
    }

    protected static ApprovalMigrationPlanEvent initialEvent(
        ApprovalMigrationPlan plan,
        String suffix
    ) {
        return new ApprovalMigrationPlanEvent(
            UUID.nameUUIDFromBytes(suffix.getBytes()),
            plan.tenantId(),
            plan.planId(),
            plan.planHash(),
            1,
            null,
            PlanStatus.PROPOSED,
            plan.requestedBy(),
            "Create immutable migration plan",
            null,
            null,
            plan.createdAt(),
            plan.requestId(),
            plan.traceId(),
            plan.auditChainReference()
        );
    }

    protected static ApprovalMigrationPlanAuthorization authorization(
        ApprovalMigrationPlan plan,
        String authorizedBy,
        String idempotencyKey,
        String evidenceHash
    ) {
        return new ApprovalMigrationPlanAuthorization(
            UUID.nameUUIDFromBytes(idempotencyKey.getBytes()),
            plan.tenantId(),
            plan.planId(),
            plan.planHash(),
            plan.selectedInstanceCount(),
            plan.sourceReleaseVersion(),
            plan.sourcePackageHash(),
            plan.targetReleaseVersion(),
            plan.targetPackageHash(),
            "MIGRATION_PLAN_HIGH_RISK",
            "v1",
            evidenceHash,
            authorizedBy,
            "Approve exact immutable migration plan hash",
            idempotencyKey,
            NOW.plusSeconds(20),
            NOW.plusSeconds(100),
            "request-" + idempotencyKey,
            "trace-migration-plan",
            "audit-" + idempotencyKey
        );
    }

    protected static ApprovalMigrationPlanEvent authorizationEvent(
        ApprovalMigrationPlan current,
        ApprovalMigrationPlan next,
        ApprovalMigrationPlanAuthorization authorization,
        String suffix
    ) {
        return new ApprovalMigrationPlanEvent(
            UUID.nameUUIDFromBytes(suffix.getBytes()),
            current.tenantId(),
            current.planId(),
            current.planHash(),
            next.revision(),
            current.status(),
            next.status(),
            authorization.authorizedBy(),
            authorization.reason(),
            authorization.authorizationId(),
            authorization.authorizationEvidenceHash(),
            authorization.decidedAt(),
            authorization.requestId(),
            authorization.traceId(),
            authorization.auditChainReference()
        );
    }
}
