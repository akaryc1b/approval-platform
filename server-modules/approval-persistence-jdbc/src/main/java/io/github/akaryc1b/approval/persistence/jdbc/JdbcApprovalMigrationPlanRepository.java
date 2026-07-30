package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQL mapping for immutable migration plans; transaction ownership stays with writers. */
final class JdbcApprovalMigrationPlanRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;

    JdbcApprovalMigrationPlanRepository(DataSource dataSource, JdbcApprovalMigrationJson json) {
        jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.json = Objects.requireNonNull(json);
    }

    Optional<ApprovalMigrationPlan> findPlan(String tenantId, UUID planId) {
        return queryPlan(
            "tenant_id=:tenantId and plan_id=:planId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("planId", planId)
        );
    }

    Optional<ApprovalMigrationPlan> findPlanByHash(String tenantId, String planHash) {
        return queryPlan(
            "tenant_id=:tenantId and plan_hash=:planHash",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("planHash", planHash)
        );
    }

    Optional<ApprovalMigrationPlan> findPlanByIdempotencyKey(
        String tenantId,
        String idempotencyKey
    ) {
        return queryPlan(
            "tenant_id=:tenantId and idempotency_key=:idempotencyKey",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey)
        );
    }

    Optional<ApprovalMigrationPlanAuthorization> findAuthorization(
        String tenantId,
        UUID planId
    ) {
        return jdbc.query("""
            select payload_json::text
            from ap_process_migration_plan_authorization
            where tenant_id=:tenantId and plan_id=:planId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("planId", planId),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationPlanAuthorization.class
            )).stream().findFirst();
    }

    Optional<ApprovalMigrationPlan> findAuthorizedPlan(
        String tenantId,
        UUID planId,
        String planHash,
        Instant validAt
    ) {
        return queryPlan(
            """
            tenant_id=:tenantId and plan_id=:planId and plan_hash=:planHash
              and status='AUTHORIZED' and expires_at>:validAt
              and authorization_expires_at>:validAt
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("planId", planId)
                .addValue("planHash", planHash)
                .addValue("validAt", JdbcApprovalMigrationJson.offset(validAt))
        );
    }

    List<ApprovalMigrationPlanEvent> findEvents(String tenantId, UUID planId) {
        return List.copyOf(jdbc.query("""
            select payload_json::text
            from ap_process_migration_plan_event
            where tenant_id=:tenantId and plan_id=:planId
            order by revision
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("planId", planId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationPlanEvent.class)));
    }

    int insertPlan(ApprovalMigrationPlan plan) {
        return jdbc.update("""
            insert into ap_process_migration_plan (
              tenant_id,plan_id,idempotency_key,plan_hash,assessment_id,assessment_report_hash,
              definition_key,source_release_version,source_package_hash,target_release_version,
              target_package_hash,target_deployment_record_id,target_engine_deployment_id,
              target_engine_definition_id,target_engine_version,selected_instance_count,status,
              revision,requested_by,
              operation_reason,assessed_at,created_at,expires_at,updated_at,authorization_id,
              authorization_evidence_hash,authorized_by,authorized_at,authorization_expires_at,
              request_id,trace_id,audit_chain_reference,payload_json
            ) values (
              :tenantId,:planId,:idempotencyKey,:planHash,:assessmentId,:assessmentReportHash,
              :definitionKey,:sourceVersion,:sourceHash,:targetVersion,:targetHash,
              :targetDeploymentRecordId,:targetEngineDeploymentId,:targetEngineDefinitionId,
              :targetEngineVersion,:selectedCount,:status,:revision,:requestedBy,:operationReason,
              :assessedAt,:createdAt,:expiresAt,
              :updatedAt,:authorizationId,:authorizationEvidenceHash,:authorizedBy,:authorizedAt,
              :authorizationExpiresAt,:requestId,:traceId,:auditChainReference,cast(:payload as jsonb)
            ) on conflict (tenant_id,idempotency_key) do nothing
            """, planParameters(plan));
    }


    void insertSelections(ApprovalMigrationPlan plan) {
        for (int index = 0; index < plan.selectedInstances().size(); index++) {
            ApprovalMigrationPlan.SelectedInstance item = plan.selectedInstances().get(index);
            jdbc.update("""
                insert into ap_process_migration_plan_instance (
                  tenant_id,plan_id,approval_instance_id,sequence_no,expected_instance_status,
                  expected_binding_evidence_hash,active_task_definition_keys,instance_evidence_hash,
                  payload_json
                ) values (
                  :tenantId,:planId,:instanceId,:sequenceNo,:expectedStatus,:bindingHash,
                  cast(:taskKeys as jsonb),:instanceEvidenceHash,cast(:payload as jsonb)
                )
                """, new MapSqlParameterSource()
                    .addValue("tenantId", plan.tenantId())
                    .addValue("planId", plan.planId())
                    .addValue("instanceId", item.approvalInstanceId())
                    .addValue("sequenceNo", index + 1)
                    .addValue("expectedStatus", item.expectedInstanceStatus().name())
                    .addValue("bindingHash", item.expectedBindingEvidenceHash())
                    .addValue("taskKeys", json.write(item.expectedActiveTaskDefinitionKeys()))
                    .addValue("instanceEvidenceHash", item.instanceEvidenceHash())
                    .addValue("payload", json.write(item)));
        }
    }


    int insertAuthorization(ApprovalMigrationPlanAuthorization authorization) {
        return jdbc.update("""
            insert into ap_process_migration_plan_authorization (
              tenant_id,authorization_id,plan_id,plan_hash,idempotency_key,
              selected_instance_count,source_release_version,source_package_hash,
              target_release_version,target_package_hash,authorization_policy,
              authorization_policy_version,authorization_evidence_hash,authorized_by,reason,
              decided_at,expires_at,request_id,trace_id,audit_chain_reference,payload_json
            ) values (
              :tenantId,:authorizationId,:planId,:planHash,:idempotencyKey,:selectedCount,
              :sourceVersion,:sourceHash,:targetVersion,:targetHash,:policy,:policyVersion,
              :evidenceHash,:authorizedBy,:reason,:decidedAt,:expiresAt,:requestId,:traceId,
              :auditChainReference,cast(:payload as jsonb)
            ) on conflict (tenant_id,plan_id) do nothing
            """, new MapSqlParameterSource()
                .addValue("tenantId", authorization.tenantId())
                .addValue("authorizationId", authorization.authorizationId())
                .addValue("planId", authorization.planId())
                .addValue("planHash", authorization.planHash())
                .addValue("idempotencyKey", authorization.idempotencyKey())
                .addValue("selectedCount", authorization.selectedInstanceCount())
                .addValue("sourceVersion", authorization.sourceReleaseVersion())
                .addValue("sourceHash", authorization.sourcePackageHash())
                .addValue("targetVersion", authorization.targetReleaseVersion())
                .addValue("targetHash", authorization.targetPackageHash())
                .addValue("policy", authorization.authorizationPolicy())
                .addValue("policyVersion", authorization.authorizationPolicyVersion())
                .addValue("evidenceHash", authorization.authorizationEvidenceHash())
                .addValue("authorizedBy", authorization.authorizedBy())
                .addValue("reason", authorization.reason())
                .addValue("decidedAt", JdbcApprovalMigrationJson.offset(authorization.decidedAt()))
                .addValue("expiresAt", JdbcApprovalMigrationJson.offset(authorization.expiresAt()))
                .addValue("requestId", authorization.requestId())
                .addValue("traceId", authorization.traceId())
                .addValue("auditChainReference", authorization.auditChainReference())
                .addValue("payload", json.write(authorization)));
    }


    int updatePlan(
        ApprovalMigrationPlan next,
        long expectedRevision,
        ApprovalMigrationPlanEvent event
    ) {
        return jdbc.update("""
            update ap_process_migration_plan set
              status=:status,revision=:revision,updated_at=:updatedAt,
              authorization_id=:authorizationId,
              authorization_evidence_hash=:authorizationEvidenceHash,
              authorized_by=:authorizedBy,authorized_at=:authorizedAt,
              authorization_expires_at=:authorizationExpiresAt,
              payload_json=cast(:payload as jsonb)
            where tenant_id=:tenantId and plan_id=:planId
              and revision=:expectedRevision and status=:fromStatus
            """, planParameters(next)
                .addValue("expectedRevision", expectedRevision)
                .addValue("fromStatus", event.fromStatus().name()));
    }

    void appendEvent(ApprovalMigrationPlanEvent event) {
        jdbc.update("""
            insert into ap_process_migration_plan_event (
              tenant_id,event_id,plan_id,plan_hash,revision,from_status,to_status,actor_id,
              reason,authorization_id,authorization_evidence_hash,happened_at,request_id,
              trace_id,audit_chain_reference,payload_json
            ) values (
              :tenantId,:eventId,:planId,:planHash,:revision,:fromStatus,:toStatus,:actorId,
              :reason,:authorizationId,:authorizationEvidenceHash,:happenedAt,:requestId,
              :traceId,:auditChainReference,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", event.eventId())
                .addValue("planId", event.planId())
                .addValue("planHash", event.planHash())
                .addValue("revision", event.revision())
                .addValue("fromStatus", event.fromStatus() == null ? null : event.fromStatus().name())
                .addValue("toStatus", event.toStatus().name())
                .addValue("actorId", event.actorId())
                .addValue("reason", event.reason())
                .addValue("authorizationId", event.authorizationId())
                .addValue("authorizationEvidenceHash", event.authorizationEvidenceHash())
                .addValue("happenedAt", JdbcApprovalMigrationJson.offset(event.happenedAt()))
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("auditChainReference", event.auditChainReference())
                .addValue("payload", json.write(event)));
    }

    private Optional<ApprovalMigrationPlan> queryPlan(
        String predicate,
        MapSqlParameterSource parameters
    ) {
        return jdbc.query(
            "select payload_json::text from ap_process_migration_plan where " + predicate,
            parameters,
            (row, number) -> json.read(row.getString(1), ApprovalMigrationPlan.class)
        ).stream().findFirst();
    }

    private MapSqlParameterSource planParameters(ApprovalMigrationPlan plan) {
        return new MapSqlParameterSource()
            .addValue("tenantId", plan.tenantId())
            .addValue("planId", plan.planId())
            .addValue("idempotencyKey", plan.idempotencyKey())
            .addValue("planHash", plan.planHash())
            .addValue("assessmentId", plan.assessmentId())
            .addValue("assessmentReportHash", plan.assessmentReportHash())
            .addValue("definitionKey", plan.definitionKey())
            .addValue("sourceVersion", plan.sourceReleaseVersion())
            .addValue("sourceHash", plan.sourcePackageHash())
            .addValue("targetVersion", plan.targetReleaseVersion())
            .addValue("targetHash", plan.targetPackageHash())
            .addValue("targetDeploymentRecordId", plan.targetDeploymentRecordId())
            .addValue("targetEngineDeploymentId", plan.targetEngineDeploymentId())
            .addValue("targetEngineDefinitionId", plan.targetEngineDefinitionId())
            .addValue("targetEngineVersion", plan.targetEngineVersion())
            .addValue("selectedCount", plan.selectedInstanceCount())
            .addValue("status", plan.status().name())
            .addValue("revision", plan.revision())
            .addValue("requestedBy", plan.requestedBy())
            .addValue("operationReason", plan.operationReason())
            .addValue("assessedAt", JdbcApprovalMigrationJson.offset(plan.assessedAt()))
            .addValue("createdAt", JdbcApprovalMigrationJson.offset(plan.createdAt()))
            .addValue("expiresAt", JdbcApprovalMigrationJson.offset(plan.expiresAt()))
            .addValue("updatedAt", JdbcApprovalMigrationJson.offset(plan.updatedAt()))
            .addValue("authorizationId", plan.authorizationId())
            .addValue("authorizationEvidenceHash", plan.authorizationEvidenceHash())
            .addValue("authorizedBy", plan.authorizedBy())
            .addValue(
                "authorizedAt",
                plan.authorizedAt() == null
                    ? null
                    : JdbcApprovalMigrationJson.offset(plan.authorizedAt())
            )
            .addValue(
                "authorizationExpiresAt",
                plan.authorizationExpiresAt() == null
                    ? null
                    : JdbcApprovalMigrationJson.offset(plan.authorizationExpiresAt())
            )
            .addValue("requestId", plan.requestId())
            .addValue("traceId", plan.traceId())
            .addValue("auditChainReference", plan.auditChainReference())
            .addValue("payload", json.write(plan));
    }
}
