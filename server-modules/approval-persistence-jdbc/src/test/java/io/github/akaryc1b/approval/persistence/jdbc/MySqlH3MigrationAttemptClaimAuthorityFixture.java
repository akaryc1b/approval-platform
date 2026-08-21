package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Real-FK consumed-plan authority whose migration Intent remains PENDING for H3. */
final class MySqlH3MigrationAttemptClaimAuthorityFixture {

    private static final String AUTHORIZATION_HASH = "7".repeat(64);
    private static final String INTENT_HASH = "8".repeat(64);

    private MySqlH3MigrationAttemptClaimAuthorityFixture() {
    }

    static void seed(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        String tenantId,
        UUID planId,
        UUID intentId,
        UUID instanceId,
        String definitionKey,
        String workerId,
        Instant now,
        String planHash,
        String sourceBindingHash,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
        Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(sourceRelease, "sourceRelease must not be null");
        Objects.requireNonNull(targetRelease, "targetRelease must not be null");
        Objects.requireNonNull(targetDeployment, "targetDeployment must not be null");

        String tenant = requireText(tenantId, "tenantId");
        String definition = requireText(definitionKey, "definitionKey");
        String worker = requireText(workerId, "workerId");
        String exactPlanHash = requireHash(planHash, "planHash");
        String exactSourceBindingHash = requireHash(
            sourceBindingHash,
            "sourceBindingHash"
        );
        UUID authorizationId = uuid(tenant, "authorization");
        UUID assessmentId = uuid(tenant, "assessment");
        Instant createdAt = now.minusSeconds(100);
        Instant authorizedAt = now.minusSeconds(90);
        Instant consumedAt = now.minusSeconds(80);
        Instant expiresAt = now.plusSeconds(3600);
        Instant authorizationExpiresAt = now.plusSeconds(3500);

        insertProposedPlan(
            jdbc,
            tenant,
            planId,
            assessmentId,
            definition,
            worker,
            exactPlanHash,
            createdAt,
            expiresAt,
            sourceRelease,
            targetRelease,
            targetDeployment
        );
        insertSelection(
            jdbc,
            tenant,
            planId,
            instanceId,
            exactSourceBindingHash
        );
        insertPlanEvent(
            jdbc,
            tenant,
            planId,
            exactPlanHash,
            1,
            null,
            "PROPOSED",
            worker,
            null,
            null,
            createdAt,
            "request-h3-plan-proposed"
        );

        ApprovalMigrationPlanAuthorization authorization =
            new ApprovalMigrationPlanAuthorization(
                authorizationId,
                tenant,
                planId,
                exactPlanHash,
                1,
                sourceRelease.releaseVersion(),
                sourceRelease.packageHash(),
                targetRelease.releaseVersion(),
                targetRelease.packageHash(),
                "MIGRATION_PLAN_HIGH_RISK",
                "v1",
                AUTHORIZATION_HASH,
                worker,
                "Authorize exact H3 claim fixture plan",
                "authorization-h3-" + tenant,
                authorizedAt,
                authorizationExpiresAt,
                "request-h3-authorization",
                "trace-h3",
                "audit-event:h3-authorization"
            );
        insertAuthorization(jdbc, objectMapper, authorization);

        int authorized = jdbc.update("""
            update ap_process_migration_plan set
              status='AUTHORIZED',revision=2,updated_at=?,
              authorization_id=?,authorization_evidence_hash=?,
              authorized_by=?,authorized_at=?,authorization_expires_at=?
            where tenant_id=? and plan_id=? and status='PROPOSED' and revision=1
            """,
            authorizedAt,
            authorizationId.toString(),
            AUTHORIZATION_HASH,
            worker,
            authorizedAt,
            authorizationExpiresAt,
            tenant,
            planId.toString()
        );
        requireOne(authorized, "authorize H3 migration plan");
        insertPlanEvent(
            jdbc,
            tenant,
            planId,
            exactPlanHash,
            2,
            "PROPOSED",
            "AUTHORIZED",
            worker,
            authorizationId,
            AUTHORIZATION_HASH,
            authorizedAt,
            "request-h3-plan-authorized"
        );

        int consumed = jdbc.update("""
            update ap_process_migration_plan set
              status='CONSUMED',revision=3,updated_at=?
            where tenant_id=? and plan_id=? and status='AUTHORIZED' and revision=2
              and authorization_id=? and authorization_evidence_hash=?
            """,
            consumedAt,
            tenant,
            planId.toString(),
            authorizationId.toString(),
            AUTHORIZATION_HASH
        );
        requireOne(consumed, "consume H3 migration plan");
        insertPlanEvent(
            jdbc,
            tenant,
            planId,
            exactPlanHash,
            3,
            "AUTHORIZED",
            "CONSUMED",
            worker,
            authorizationId,
            AUTHORIZATION_HASH,
            consumedAt,
            "request-h3-plan-consumed"
        );

        insertIntent(
            jdbc,
            tenant,
            intentId,
            planId,
            exactPlanHash,
            definition,
            sourceRelease,
            targetRelease,
            createdAt
        );
        insertIntentEvent(
            jdbc,
            tenant,
            intentId,
            1,
            null,
            "PENDING",
            createdAt
        );
        insertConsumption(
            jdbc,
            tenant,
            planId,
            intentId,
            authorizationId,
            exactPlanHash,
            worker,
            consumedAt
        );
    }

    private static void insertProposedPlan(
        JdbcTemplate jdbc,
        String tenant,
        UUID planId,
        UUID assessmentId,
        String definitionKey,
        String worker,
        String planHash,
        Instant createdAt,
        Instant expiresAt,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
        jdbc.update("""
            insert into ap_process_migration_plan (
              tenant_id,plan_id,idempotency_key,plan_hash,assessment_id,
              assessment_report_hash,definition_key,source_release_version,
              source_package_hash,target_release_version,target_package_hash,
              target_deployment_record_id,target_engine_deployment_id,
              target_engine_definition_id,target_engine_version,selected_instance_count,
              status,revision,requested_by,operation_reason,assessed_at,created_at,
              expires_at,updated_at,authorization_id,authorization_evidence_hash,
              authorized_by,authorized_at,authorization_expires_at,request_id,
              trace_id,audit_chain_reference,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            planId.toString(),
            "plan-h3-" + tenant,
            planHash,
            assessmentId.toString(),
            "6".repeat(64),
            definitionKey,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            targetRelease.releaseVersion(),
            targetRelease.packageHash(),
            targetDeployment.deploymentRecordId().toString(),
            targetDeployment.engineDeploymentId(),
            targetDeployment.engineDefinitionId(),
            targetDeployment.engineVersion(),
            1,
            "PROPOSED",
            1,
            worker,
            "H3 bounded migration claim",
            createdAt,
            createdAt,
            expiresAt,
            createdAt,
            null,
            null,
            null,
            null,
            null,
            "request-h3-plan",
            "trace-h3",
            "audit-event:h3-plan",
            "{}"
        );
    }

    private static void insertSelection(
        JdbcTemplate jdbc,
        String tenant,
        UUID planId,
        UUID instanceId,
        String sourceBindingHash
    ) {
        jdbc.update("""
            insert into ap_process_migration_plan_instance (
              tenant_id,plan_id,approval_instance_id,sequence_no,
              expected_instance_status,expected_binding_evidence_hash,
              active_task_definition_keys,instance_evidence_hash,payload_json
            ) values (?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            planId.toString(),
            instanceId.toString(),
            1,
            "RUNNING",
            sourceBindingHash,
            "[\"managerApproval\"]",
            "6".repeat(64),
            "{}"
        );
    }

    private static void insertAuthorization(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        ApprovalMigrationPlanAuthorization authorization
    ) {
        jdbc.update("""
            insert into ap_process_migration_plan_authorization (
              tenant_id,authorization_id,plan_id,plan_hash,idempotency_key,
              selected_instance_count,source_release_version,source_package_hash,
              target_release_version,target_package_hash,authorization_policy,
              authorization_policy_version,authorization_evidence_hash,
              authorized_by,reason,decided_at,expires_at,request_id,trace_id,
              audit_chain_reference,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            authorization.tenantId(),
            authorization.authorizationId().toString(),
            authorization.planId().toString(),
            authorization.planHash(),
            authorization.idempotencyKey(),
            authorization.selectedInstanceCount(),
            authorization.sourceReleaseVersion(),
            authorization.sourcePackageHash(),
            authorization.targetReleaseVersion(),
            authorization.targetPackageHash(),
            authorization.authorizationPolicy(),
            authorization.authorizationPolicyVersion(),
            authorization.authorizationEvidenceHash(),
            authorization.authorizedBy(),
            authorization.reason(),
            authorization.decidedAt(),
            authorization.expiresAt(),
            authorization.requestId(),
            authorization.traceId(),
            authorization.auditChainReference(),
            writeJson(objectMapper, authorization)
        );
    }

    private static void insertPlanEvent(
        JdbcTemplate jdbc,
        String tenant,
        UUID planId,
        String planHash,
        int revision,
        String fromStatus,
        String toStatus,
        String actor,
        UUID authorizationId,
        String authorizationHash,
        Instant happenedAt,
        String requestId
    ) {
        jdbc.update("""
            insert into ap_process_migration_plan_event (
              tenant_id,event_id,plan_id,plan_hash,revision,from_status,to_status,
              actor_id,reason,authorization_id,authorization_evidence_hash,
              happened_at,request_id,trace_id,audit_chain_reference,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            uuid(tenant, "plan-event-" + revision).toString(),
            planId.toString(),
            planHash,
            revision,
            fromStatus,
            toStatus,
            actor,
            "H3 claim fixture transition",
            authorizationId == null ? null : authorizationId.toString(),
            authorizationHash,
            happenedAt,
            requestId,
            "trace-h3",
            "audit-event:h3-plan-event-" + revision,
            "{}"
        );
    }

    private static void insertIntent(
        JdbcTemplate jdbc,
        String tenant,
        UUID intentId,
        UUID planId,
        String planHash,
        String definitionKey,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleasePackage targetRelease,
        Instant createdAt
    ) {
        jdbc.update("""
            insert into ap_process_migration_intent (
              tenant_id,intent_id,idempotency_key,plan_id,plan_hash,definition_key,
              source_release_version,source_package_hash,target_release_version,
              target_package_hash,status,revision,intent_evidence_hash,payload_json,
              created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            intentId.toString(),
            "intent-h3-" + tenant,
            planId.toString(),
            planHash,
            definitionKey,
            sourceRelease.releaseVersion(),
            sourceRelease.packageHash(),
            targetRelease.releaseVersion(),
            targetRelease.packageHash(),
            "PENDING",
            1,
            INTENT_HASH,
            "{}",
            createdAt,
            createdAt
        );
    }

    private static void insertIntentEvent(
        JdbcTemplate jdbc,
        String tenant,
        UUID intentId,
        int revision,
        String fromStatus,
        String toStatus,
        Instant happenedAt
    ) {
        jdbc.update("""
            insert into ap_process_migration_intent_event (
              tenant_id,event_id,intent_id,revision,from_status,to_status,
              payload_json,happened_at
            ) values (?,?,?,?,?,?,?,?)
            """,
            tenant,
            uuid(tenant, "intent-event-" + revision).toString(),
            intentId.toString(),
            revision,
            fromStatus,
            toStatus,
            "{}",
            happenedAt
        );
    }

    private static void insertConsumption(
        JdbcTemplate jdbc,
        String tenant,
        UUID planId,
        UUID intentId,
        UUID authorizationId,
        String planHash,
        String worker,
        Instant consumedAt
    ) {
        jdbc.update("""
            insert into ap_process_migration_plan_consumption (
              tenant_id,consumption_id,plan_id,plan_hash,authorization_id,
              authorization_evidence_hash,intent_id,intent_evidence_hash,
              idempotency_key,request_hash,consumed_by,reason,consumed_at,
              request_id,trace_id,audit_chain_reference,payload_json
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            uuid(tenant, "consumption").toString(),
            planId.toString(),
            planHash,
            authorizationId.toString(),
            AUTHORIZATION_HASH,
            intentId.toString(),
            INTENT_HASH,
            "intent-h3-" + tenant,
            "a".repeat(64),
            worker,
            "H3 bounded migration claim",
            consumedAt,
            "request-h3-consumption",
            "trace-h3",
            "audit-event:h3-consumption",
            "{}"
        );
    }

    private static void requireOne(int affected, String operation) {
        if (affected != 1) {
            throw new IllegalStateException(operation + " affected " + affected + " rows");
        }
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h3:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String writeJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("H3 authority JSON failed", exception);
        }
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null").trim();
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }

    private static String requireHash(String value, String name) {
        String exact = requireText(value, name);
        if (!exact.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return exact;
    }
}
