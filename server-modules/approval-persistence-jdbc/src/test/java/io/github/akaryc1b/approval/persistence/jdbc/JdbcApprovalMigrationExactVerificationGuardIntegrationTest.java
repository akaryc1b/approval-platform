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

class JdbcApprovalMigrationExactVerificationGuardIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    private static final UUID PLAN_ID = UUID.fromString("43000000-0000-0000-0000-000000000001");
    private static final UUID INTENT_ID = UUID.fromString("43000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT_ID = UUID.fromString("43000000-0000-0000-0000-000000000003");
    private static final UUID INSTANCE_ID = UUID.fromString("43000000-0000-0000-0000-000000000004");
    private static final UUID FENCE_ID = UUID.fromString("43000000-0000-0000-0000-000000000005");
    private static final UUID ENGINE_REQUEST_ID = UUID.fromString("43000000-0000-0000-0000-000000000006");
    private static final UUID ENGINE_OUTCOME_ID = UUID.fromString("43000000-0000-0000-0000-000000000007");
    private static final UUID VERIFICATION_ID = UUID.fromString("43000000-0000-0000-0000-000000000008");
    private static final String WORKER = "worker-d4-postgres";
    private static final String SOURCE = "engine-definition-v1";
    private static final String TARGET = "engine-definition-v2";

    @Test
    void enforcesReturnedOutcomeFenceAndAppendOnlyExactEvidence() {
        seedVerifyingLineage();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);

        assertThrows(
            DataAccessException.class,
            () -> named.update(verificationSql(), verificationParameters("spoofed-worker"))
        );
        assertEquals(0, count("ap_process_migration_exact_verification"));

        assertEquals(1, named.update(verificationSql(), verificationParameters(WORKER)));
        assertEquals(1, count("ap_process_migration_exact_verification"));

        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_exact_verification set trace_id='tamper' "
                + "where tenant_id=? and verification_id=?",
            TENANT,
            VERIFICATION_ID
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_exact_verification "
                + "where tenant_id=? and verification_id=?",
            TENANT,
            VERIFICATION_ID
        ));
    }

    private void seedVerifyingLineage() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.executeUpdate(intentInsert());
                statement.executeUpdate(attemptInsert());
                statement.executeUpdate(fenceInsert());
                statement.executeUpdate(engineRequestInsert());
                statement.executeUpdate(engineOutcomeInsert());
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private static String intentInsert() {
        return """
            insert into ap_process_migration_intent (
              tenant_id,intent_id,idempotency_key,plan_id,plan_hash,definition_key,
              source_release_version,source_package_hash,target_release_version,target_package_hash,
              status,revision,intent_evidence_hash,payload_json,created_at,updated_at
            ) values ('%s','%s','intent-d4','%s','%s','%s',1,'%s',2,'%s','RUNNING',2,'%s',
              '{}'::jsonb,timestamptz '%s',timestamptz '%s')
            """.formatted(
            TENANT, INTENT_ID, PLAN_ID, hash('4'), DEFINITION_KEY, hash('b'), hash('c'), hash('8'),
            offset(NOW.minusSeconds(30)), offset(NOW.minusSeconds(20))
        );
    }

    private static String attemptInsert() {
        String payload = """
            {"sourceEngineDefinitionId":"%s","targetEngineDefinitionId":"%s"}
            """.formatted(SOURCE, TARGET).replace("'", "''");
        return """
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,parent_attempt_id,
              status,revision,engine_outcome,lease_actor,lease_owner,lease_until,
              engine_request_reference,failure_class,error_summary,expected_binding_evidence_hash,
              payload_json,created_at,updated_at
            ) values ('%s','%s','%s','%s',1,null,'VERIFYING',4,'ACCEPTED','%s',null,null,
              '%s','NONE',null,'%s','%s'::jsonb,timestamptz '%s',timestamptz '%s')
            """.formatted(
            TENANT, ATTEMPT_ID, INTENT_ID, INSTANCE_ID, WORKER, ENGINE_REQUEST_ID,
            hash('7'), payload, offset(NOW.minusSeconds(20)), offset(NOW.minusSeconds(10))
        );
    }

    private static String fenceInsert() {
        return """
            insert into ap_approval_instance_command_fence (
              tenant_id,fence_id,approval_instance_id,attempt_id,operation,status,revision,
              lease_owner,lease_until,idempotency_key,request_hash,acquired_at,updated_at,
              released_at,request_id,trace_id,payload_json
            ) values ('%s','%s','%s','%s','MIGRATION','ACTIVE',1,'%s',timestamptz '%s',
              'fence-d4','%s',timestamptz '%s',timestamptz '%s',null,
              'request-fence-d4','trace-d4','{}'::jsonb)
            """.formatted(
            TENANT, FENCE_ID, INSTANCE_ID, ATTEMPT_ID, WORKER, offset(NOW.plusSeconds(300)),
            hash('9'), offset(NOW.minusSeconds(20)), offset(NOW.minusSeconds(10))
        );
    }

    private static String engineRequestInsert() {
        return """
            insert into ap_process_migration_engine_request (
              tenant_id,engine_request_id,intent_id,attempt_id,approval_instance_id,worker_id,
              attempt_revision,fence_id,fence_revision,engine_instance_id,
              source_binding_evidence_hash,source_engine_definition_id,target_release_version,
              target_package_hash,target_engine_deployment_id,target_engine_definition_id,
              activity_mapping_json,request_hash,evidence_hash,requested_at,request_id,trace_id,payload_json
            ) values ('%s','%s','%s','%s','%s','%s',2,'%s',1,'engine-instance-d4',
              '%s','%s',2,'%s','engine-deployment-v2','%s','[]'::jsonb,'%s','%s',
              timestamptz '%s','request-engine-d4','trace-d4','{}'::jsonb)
            """.formatted(
            TENANT, ENGINE_REQUEST_ID, INTENT_ID, ATTEMPT_ID, INSTANCE_ID, WORKER, FENCE_ID,
            hash('7'), SOURCE, hash('c'), TARGET, hash('a'), hash('d'),
            offset(NOW.minusSeconds(15))
        );
    }

    private static String engineOutcomeInsert() {
        return """
            insert into ap_process_migration_engine_outcome (
              tenant_id,engine_outcome_id,engine_request_id,intent_id,attempt_id,worker_id,
              expected_attempt_revision,expected_fence_revision,disposition,engine_call_attempted,
              engine_call_returned,engine_call_may_have_occurred,stable_code,bounded_summary,
              pre_dispatch_snapshot_hash,outcome_hash,recorded_at,request_id,trace_id,payload_json
            ) values ('%s','%s','%s','%s','%s','%s',3,1,
              'CALL_RETURNED_AWAITING_VERIFICATION',true,true,false,'ENGINE_CALL_RETURNED',
              'returned; exact verification required','%s','%s',timestamptz '%s',
              'request-engine-d4','trace-d4','{}'::jsonb)
            """.formatted(
            TENANT, ENGINE_OUTCOME_ID, ENGINE_REQUEST_ID, INTENT_ID, ATTEMPT_ID, WORKER,
            hash('e'), hash('f'), offset(NOW.minusSeconds(12))
        );
    }

    private static String verificationSql() {
        return """
            insert into ap_process_migration_exact_verification (
              tenant_id,verification_id,intent_id,attempt_id,engine_request_id,engine_outcome_id,
              worker_id,expected_attempt_revision,expected_fence_revision,
              source_engine_definition_id,target_engine_definition_id,classification,
              read_succeeded,runtime_present,history_present,truncated,
              observed_runtime_definition_id,observed_history_definition_id,snapshot_hash,
              request_hash,verification_evidence_hash,recorded_at,request_id,trace_id,payload_json
            ) values (:tenantId,:verificationId,:intentId,:attemptId,:engineRequestId,:engineOutcomeId,
              :workerId,4,1,:sourceDefinition,:targetDefinition,'EXACT_TARGET_RUNTIME',
              true,true,true,false,:targetDefinition,:targetDefinition,:snapshotHash,
              :requestHash,:evidenceHash,:recordedAt,'request-verify-d4','trace-d4','{}'::jsonb)
            """;
    }

    private static MapSqlParameterSource verificationParameters(String worker) {
        return new MapSqlParameterSource()
            .addValue("tenantId", TENANT)
            .addValue("verificationId", VERIFICATION_ID)
            .addValue("intentId", INTENT_ID)
            .addValue("attemptId", ATTEMPT_ID)
            .addValue("engineRequestId", ENGINE_REQUEST_ID)
            .addValue("engineOutcomeId", ENGINE_OUTCOME_ID)
            .addValue("workerId", worker)
            .addValue("sourceDefinition", SOURCE)
            .addValue("targetDefinition", TARGET)
            .addValue("snapshotHash", hash('1'))
            .addValue("requestHash", hash('2'))
            .addValue("evidenceHash", hash('3'))
            .addValue("recordedAt", offset(NOW));
    }

    private static OffsetDateTime offset(java.time.Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
