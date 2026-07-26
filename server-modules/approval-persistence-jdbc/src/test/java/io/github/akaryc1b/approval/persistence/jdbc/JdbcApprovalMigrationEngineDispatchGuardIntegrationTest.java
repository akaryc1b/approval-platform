package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.DEFINITION_KEY;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcApprovalMigrationEngineDispatchGuardIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    private static final UUID PLAN_ID = UUID.fromString("42000000-0000-0000-0000-000000000001");
    private static final UUID INTENT_ID = UUID.fromString("42000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT_ID = UUID.fromString("42000000-0000-0000-0000-000000000003");
    private static final UUID INSTANCE_ID = UUID.fromString("42000000-0000-0000-0000-000000000004");
    private static final UUID FENCE_ID = UUID.fromString("42000000-0000-0000-0000-000000000005");
    private static final UUID REQUEST_ID = UUID.fromString("42000000-0000-0000-0000-000000000006");
    private static final UUID OUTCOME_ID = UUID.fromString("42000000-0000-0000-0000-000000000007");
    private static final String WORKER = "worker-d3-postgres";
    private static final String SOURCE_DEFINITION = "engine-definition-v1";
    private static final String TARGET_DEPLOYMENT = "engine-deployment-v2";
    private static final String TARGET_DEFINITION = "engine-definition-v2";
    private static final String ENGINE_INSTANCE = "engine-instance-d3";
    private static final String BINDING_HASH = "7".repeat(64);

    @Test
    void enforcesExactRequestAndOutcomeFencingAndAppendOnlyEvidence() {
        seedClaimedAttemptAndFence();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);

        MapSqlParameterSource request = requestParameters(WORKER);
        MapSqlParameterSource spoofed = requestParameters("spoofed-worker");
        assertThrows(DataAccessException.class, () -> named.update(requestSql(), spoofed));
        assertEquals(0, count("ap_process_migration_engine_request"));

        assertEquals(1, named.update(requestSql(), request));
        assertEquals(1, count("ap_process_migration_engine_request"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_engine_request set bounded_summary='tamper' "
                + "where tenant_id=? and engine_request_id=?",
            TENANT,
            REQUEST_ID
        ));

        advanceAttemptToRequested();
        MapSqlParameterSource staleOutcome = outcomeParameters("stale-worker");
        assertThrows(DataAccessException.class, () -> named.update(outcomeSql(), staleOutcome));
        assertEquals(0, count("ap_process_migration_engine_outcome"));

        assertEquals(1, named.update(outcomeSql(), outcomeParameters(WORKER)));
        assertEquals(1, count("ap_process_migration_engine_outcome"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_engine_outcome "
                + "where tenant_id=? and engine_outcome_id=?",
            TENANT,
            OUTCOME_ID
        ));
    }

    private void seedClaimedAttemptAndFence() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.executeUpdate(planInsert());
                statement.executeUpdate(intentInsert());
                statement.executeUpdate(attemptInsert());
                statement.executeUpdate(fenceInsert());
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private void advanceAttemptToRequested() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.executeUpdate("""
                    update ap_process_migration_attempt
                    set status='ENGINE_REQUESTED',revision=3,engine_outcome='NOT_REQUESTED',
                        lease_actor='%s',lease_owner=null,lease_until=null,
                        engine_request_reference='%s',updated_at=timestamptz '%s',
                        payload_json=jsonb_set(jsonb_set(jsonb_set(payload_json,
                          '{status}','\"ENGINE_REQUESTED\"'::jsonb),
                          '{revision}','3'::jsonb),
                          '{engineRequestReference}','\"%s\"'::jsonb)
                    where tenant_id='%s' and attempt_id='%s'
                    """.formatted(
                    WORKER,
                    REQUEST_ID,
                    offset(NOW.plusSeconds(20)),
                    REQUEST_ID,
                    TENANT,
                    ATTEMPT_ID
                ));
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private static String planInsert() {
        return """
            insert into ap_process_migration_plan (
              tenant_id,plan_id,idempotency_key,plan_hash,assessment_id,assessment_report_hash,
              definition_key,source_release_version,source_package_hash,target_release_version,
              target_package_hash,target_deployment_record_id,target_engine_deployment_id,
              target_engine_definition_id,target_engine_version,selected_instance_count,status,
              revision,requested_by,operation_reason,assessed_at,created_at,expires_at,updated_at,
              authorization_id,authorization_evidence_hash,authorized_by,authorized_at,
              authorization_expires_at,request_id,trace_id,audit_chain_reference,payload_json
            ) values (
              '%s','%s','plan-d3','%s','%s','%s','%s',1,'%s',2,'%s','%s','%s','%s',2,1,
              'CONSUMED',3,'operator','D3 guarded dispatch',timestamptz '%s',timestamptz '%s',
              timestamptz '%s',timestamptz '%s','%s','%s','approver',timestamptz '%s',
              timestamptz '%s','request-plan-d3','trace-d3','audit-d3','{}'::jsonb
            )
            """.formatted(
            TENANT, PLAN_ID, hash('4'), UUID.fromString("42000000-0000-0000-0000-000000000008"),
            hash('5'), DEFINITION_KEY, hash('b'), hash('c'),
            UUID.fromString("42000000-0000-0000-0000-000000000009"), TARGET_DEPLOYMENT,
            TARGET_DEFINITION, offset(NOW.minusSeconds(30)), offset(NOW.minusSeconds(20)),
            offset(NOW.plusSeconds(600)), offset(NOW),
            UUID.fromString("42000000-0000-0000-0000-000000000010"), hash('6'),
            offset(NOW.minusSeconds(10)), offset(NOW.plusSeconds(500))
        );
    }

    private static String intentInsert() {
        return """
            insert into ap_process_migration_intent (
              tenant_id,intent_id,idempotency_key,plan_id,plan_hash,definition_key,
              source_release_version,source_package_hash,target_release_version,target_package_hash,
              status,revision,intent_evidence_hash,payload_json,created_at,updated_at
            ) values ('%s','%s','intent-d3','%s','%s','%s',1,'%s',2,'%s','RUNNING',2,'%s',
              '{}'::jsonb,timestamptz '%s',timestamptz '%s')
            """.formatted(
            TENANT, INTENT_ID, PLAN_ID, hash('4'), DEFINITION_KEY, hash('b'), hash('c'), hash('8'),
            offset(NOW.minusSeconds(10)), offset(NOW)
        );
    }

    private static String attemptInsert() {
        String payload = """
            {"attemptId":"%s","tenantId":"%s","intentId":"%s",
             "approvalInstanceId":"%s","attemptNumber":1,"parentAttemptId":null,
             "engineInstanceId":"%s","sourceReleaseVersion":1,"sourcePackageHash":"%s",
             "expectedBindingEvidenceHash":"%s","sourceEngineDefinitionId":"%s",
             "targetEngineDefinitionId":"%s","status":"CLAIMED","engineOutcome":"NOT_REQUESTED",
             "revision":2,"leaseOwner":"%s","leaseUntil":"%s","engineRequestReference":null,
             "failureClass":"NONE","errorSummary":null,"createdAt":"%s","updatedAt":"%s",
             "requestId":"request-attempt-d3","traceId":"trace-d3"}
            """.formatted(
            ATTEMPT_ID, TENANT, INTENT_ID, INSTANCE_ID, ENGINE_INSTANCE, hash('b'), BINDING_HASH,
            SOURCE_DEFINITION, TARGET_DEFINITION, WORKER, NOW.plusSeconds(300),
            NOW.minusSeconds(5), NOW
        ).replace("'", "''");
        return """
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,parent_attempt_id,
              status,revision,engine_outcome,lease_actor,lease_owner,lease_until,
              engine_request_reference,failure_class,error_summary,expected_binding_evidence_hash,
              payload_json,created_at,updated_at
            ) values ('%s','%s','%s','%s',1,null,'CLAIMED',2,'NOT_REQUESTED','%s','%s',
              timestamptz '%s',null,'NONE',null,'%s','%s'::jsonb,timestamptz '%s',timestamptz '%s')
            """.formatted(
            TENANT, ATTEMPT_ID, INTENT_ID, INSTANCE_ID, WORKER, WORKER,
            offset(NOW.plusSeconds(300)), BINDING_HASH, payload,
            offset(NOW.minusSeconds(5)), offset(NOW)
        );
    }

    private static String fenceInsert() {
        return """
            insert into ap_approval_instance_command_fence (
              tenant_id,fence_id,approval_instance_id,attempt_id,operation,status,revision,
              lease_owner,lease_until,idempotency_key,request_hash,acquired_at,updated_at,
              released_at,request_id,trace_id,payload_json
            ) values ('%s','%s','%s','%s','MIGRATION','ACTIVE',1,'%s',timestamptz '%s',
              'fence-d3','%s',timestamptz '%s',timestamptz '%s',null,
              'request-fence-d3','trace-d3','{}'::jsonb)
            """.formatted(
            TENANT, FENCE_ID, INSTANCE_ID, ATTEMPT_ID, WORKER, offset(NOW.plusSeconds(300)),
            hash('9'), offset(NOW), offset(NOW)
        );
    }

    private static String requestSql() {
        return """
            insert into ap_process_migration_engine_request (
              tenant_id,engine_request_id,intent_id,attempt_id,approval_instance_id,worker_id,
              attempt_revision,fence_id,fence_revision,engine_instance_id,
              source_binding_evidence_hash,source_engine_definition_id,target_release_version,
              target_package_hash,target_engine_deployment_id,target_engine_definition_id,
              activity_mapping_json,request_hash,evidence_hash,requested_at,request_id,trace_id,payload_json
            ) values (:tenantId,:engineRequestId,:intentId,:attemptId,:instanceId,:workerId,2,
              :fenceId,1,:engineInstanceId,:bindingHash,:sourceDefinition,2,:targetPackageHash,
              :targetDeployment,:targetDefinition,'[]'::jsonb,:requestHash,:evidenceHash,
              :requestedAt,'request-engine-d3','trace-d3','{}'::jsonb)
            """;
    }

    private static MapSqlParameterSource requestParameters(String worker) {
        return new MapSqlParameterSource()
            .addValue("tenantId", TENANT)
            .addValue("engineRequestId", REQUEST_ID)
            .addValue("intentId", INTENT_ID)
            .addValue("attemptId", ATTEMPT_ID)
            .addValue("instanceId", INSTANCE_ID)
            .addValue("workerId", worker)
            .addValue("fenceId", FENCE_ID)
            .addValue("engineInstanceId", ENGINE_INSTANCE)
            .addValue("bindingHash", BINDING_HASH)
            .addValue("sourceDefinition", SOURCE_DEFINITION)
            .addValue("targetPackageHash", hash('c'))
            .addValue("targetDeployment", TARGET_DEPLOYMENT)
            .addValue("targetDefinition", TARGET_DEFINITION)
            .addValue("requestHash", hash('a'))
            .addValue("evidenceHash", hash('d'))
            .addValue("requestedAt", offset(NOW.plusSeconds(10)));
    }

    private static String outcomeSql() {
        return """
            insert into ap_process_migration_engine_outcome (
              tenant_id,engine_outcome_id,engine_request_id,intent_id,attempt_id,worker_id,
              expected_attempt_revision,expected_fence_revision,disposition,engine_call_attempted,
              engine_call_returned,engine_call_may_have_occurred,stable_code,bounded_summary,
              pre_dispatch_snapshot_hash,outcome_hash,recorded_at,request_id,trace_id,payload_json
            ) values (:tenantId,:outcomeId,:engineRequestId,:intentId,:attemptId,:workerId,3,1,
              'CALL_RETURNED_AWAITING_VERIFICATION',true,true,false,'ENGINE_CALL_RETURNED',
              'returned; verification required',:snapshotHash,:outcomeHash,:recordedAt,
              'request-engine-d3','trace-d3','{}'::jsonb)
            """;
    }

    private static MapSqlParameterSource outcomeParameters(String worker) {
        return new MapSqlParameterSource()
            .addValue("tenantId", TENANT)
            .addValue("outcomeId", OUTCOME_ID)
            .addValue("engineRequestId", REQUEST_ID)
            .addValue("intentId", INTENT_ID)
            .addValue("attemptId", ATTEMPT_ID)
            .addValue("workerId", worker)
            .addValue("snapshotHash", hash('e'))
            .addValue("outcomeHash", hash('f'))
            .addValue("recordedAt", offset(NOW.plusSeconds(30)));
    }

    private static OffsetDateTime offset(java.time.Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
