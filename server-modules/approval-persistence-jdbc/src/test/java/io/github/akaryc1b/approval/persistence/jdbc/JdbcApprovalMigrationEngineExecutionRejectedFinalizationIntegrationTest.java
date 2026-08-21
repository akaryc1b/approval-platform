package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.PreparedDispatch;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort.MigrationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.DEFINITION_KEY;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationEngineExecutionRejectedFinalizationIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    private static final UUID INTENT_ID = UUID.fromString("42100000-0000-0000-0000-000000000001");
    private static final UUID ATTEMPT_ID = UUID.fromString("42100000-0000-0000-0000-000000000002");
    private static final UUID INSTANCE_ID = UUID.fromString("42100000-0000-0000-0000-000000000003");
    private static final UUID FENCE_ID = UUID.fromString("42100000-0000-0000-0000-000000000004");
    private static final UUID REQUEST_ID = UUID.fromString("42100000-0000-0000-0000-000000000005");
    private static final String WORKER = "worker-d3-rejected-postgres";
    private static final String ENGINE_INSTANCE = "engine-instance-d3-rejected";
    private static final String SOURCE_DEFINITION = "engine-definition-v1";
    private static final String TARGET_DEFINITION = "engine-definition-v2";
    private static final String TARGET_DEPLOYMENT = "engine-deployment-v2";

    @Test
    void rejectedFinalizationClearsMutableRequestReferenceAndRetainsImmutableEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ApprovalMigrationAttempt requested = requestedAttempt();
        ApprovalMigrationCommandFence fence = activeFence();
        seedRequestedLineage(mapper, requested, fence);

        List<AuditEvent> audits = new ArrayList<>();
        Iterator<UUID> identifiers = List.of(
            UUID.fromString("42100000-0000-0000-0000-000000000006"),
            UUID.fromString("42100000-0000-0000-0000-000000000007"),
            UUID.fromString("42100000-0000-0000-0000-000000000008")
        ).iterator();
        ApprovalMigrationEngineExecutionStore execution = new JdbcApprovalMigrationEngineExecutionStore(
            dataSource,
            mapper,
            new JdbcTransactionManager(dataSource),
            audits::add,
            identifiers::next
        );
        PreparedDispatch prepared = new PreparedDispatch(
            REQUEST_ID,
            hash('d'),
            requested,
            1,
            new MigrationCommand(
                TENANT,
                INSTANCE_ID,
                ATTEMPT_ID,
                ENGINE_INSTANCE,
                SOURCE_DEFINITION,
                TARGET_DEPLOYMENT,
                TARGET_DEFINITION,
                List.of()
            ),
            NOW.plusSeconds(20),
            "request-d3-rejected",
            "trace-d3-rejected"
        );

        ApprovalMigrationAttempt rejected = execution.finalizeOutcome(new FinalizeRequest(
            prepared,
            FinalDisposition.ENGINE_REJECTED,
            true,
            true,
            false,
            "ENGINE_REJECTED_TARGET",
            "engine rejected the requested target",
            hash('e'),
            NOW.plusSeconds(30)
        ));

        assertEquals(AttemptStatus.FAILED_TERMINAL, rejected.status());
        assertEquals(EngineOutcome.REJECTED, rejected.engineOutcome());
        assertEquals(FailureClass.ENGINE_REJECTED, rejected.failureClass());
        assertNull(rejected.engineRequestReference());
        assertTrue(rejected.errorSummary().startsWith("ENGINE_REJECTED_TARGET:"));

        assertEquals(1, count("ap_process_migration_engine_request"));
        assertEquals(1, count("ap_process_migration_engine_outcome"));
        assertEquals(4, count("ap_process_migration_attempt_event"));
        assertEquals(1, audits.size());
        assertEquals("FAILED_TERMINAL", scalar(
            "select status from ap_process_migration_attempt where tenant_id=? and attempt_id=?"
        ));
        assertEquals("REJECTED", scalar(
            "select engine_outcome from ap_process_migration_attempt where tenant_id=? and attempt_id=?"
        ));
        assertNull(jdbc.queryForObject(
            "select engine_request_reference from ap_process_migration_attempt "
                + "where tenant_id=? and attempt_id=?",
            String.class,
            TENANT,
            ATTEMPT_ID
        ));
    }

    private ApprovalMigrationAttempt pendingAttempt() {
        return new ApprovalMigrationAttempt(
            ATTEMPT_ID,
            TENANT,
            INTENT_ID,
            INSTANCE_ID,
            ENGINE_INSTANCE,
            1,
            null,
            hash('7'),
            SOURCE_DEFINITION,
            TARGET_DEFINITION,
            AttemptStatus.PENDING,
            EngineOutcome.NOT_REQUESTED,
            1,
            null,
            null,
            null,
            FailureClass.NONE,
            null,
            NOW.minusSeconds(30),
            NOW.minusSeconds(30),
            "request-attempt-d3-rejected",
            "trace-d3-rejected"
        );
    }

    private ApprovalMigrationAttempt claimedAttempt() {
        return new ApprovalMigrationAttempt(
            ATTEMPT_ID,
            TENANT,
            INTENT_ID,
            INSTANCE_ID,
            ENGINE_INSTANCE,
            1,
            null,
            hash('7'),
            SOURCE_DEFINITION,
            TARGET_DEFINITION,
            AttemptStatus.CLAIMED,
            EngineOutcome.NOT_REQUESTED,
            2,
            WORKER,
            NOW.plusSeconds(300),
            null,
            FailureClass.NONE,
            null,
            NOW.minusSeconds(30),
            NOW.minusSeconds(20),
            "request-attempt-d3-rejected",
            "trace-d3-rejected"
        );
    }

    private ApprovalMigrationAttempt requestedAttempt() {
        return new ApprovalMigrationAttempt(
            ATTEMPT_ID,
            TENANT,
            INTENT_ID,
            INSTANCE_ID,
            ENGINE_INSTANCE,
            1,
            null,
            hash('7'),
            SOURCE_DEFINITION,
            TARGET_DEFINITION,
            AttemptStatus.ENGINE_REQUESTED,
            EngineOutcome.NOT_REQUESTED,
            3,
            null,
            null,
            REQUEST_ID.toString(),
            FailureClass.NONE,
            null,
            NOW.minusSeconds(30),
            NOW,
            "request-attempt-d3-rejected",
            "trace-d3-rejected"
        );
    }

    private ApprovalMigrationCommandFence activeFence() {
        return new ApprovalMigrationCommandFence(
            FENCE_ID,
            TENANT,
            INSTANCE_ID,
            ATTEMPT_ID,
            ApprovalCommandOperation.MIGRATION,
            ApprovalMigrationCommandFence.FenceStatus.ACTIVE,
            1,
            WORKER,
            NOW.plusSeconds(300),
            "fence-d3-rejected",
            hash('9'),
            NOW.minusSeconds(20),
            NOW,
            null,
            "request-fence-d3-rejected",
            "trace-d3-rejected"
        );
    }

    private List<ApprovalMigrationAttemptEvent> requestedAttemptEvents() {
        ApprovalMigrationAttempt pending = pendingAttempt();
        ApprovalMigrationAttempt claimed = claimedAttempt();
        ApprovalMigrationAttempt requested = requestedAttempt();
        return List.of(
            new ApprovalMigrationAttemptEvent(
                UUID.fromString("42100000-0000-0000-0000-000000000010"),
                TENANT,
                ATTEMPT_ID,
                1,
                null,
                AttemptStatus.PENDING,
                EngineOutcome.NOT_REQUESTED,
                FailureClass.NONE,
                null,
                pending.updatedAt(),
                "request-event-d3-pending",
                "trace-d3-rejected"
            ).withDurableEvidence(pending, null),
            new ApprovalMigrationAttemptEvent(
                UUID.fromString("42100000-0000-0000-0000-000000000011"),
                TENANT,
                ATTEMPT_ID,
                2,
                AttemptStatus.PENDING,
                AttemptStatus.CLAIMED,
                EngineOutcome.NOT_REQUESTED,
                FailureClass.NONE,
                null,
                claimed.updatedAt(),
                "request-event-d3-claimed",
                "trace-d3-rejected"
            ).withDurableEvidence(claimed, WORKER),
            new ApprovalMigrationAttemptEvent(
                UUID.fromString("42100000-0000-0000-0000-000000000012"),
                TENANT,
                ATTEMPT_ID,
                3,
                AttemptStatus.CLAIMED,
                AttemptStatus.ENGINE_REQUESTED,
                EngineOutcome.NOT_REQUESTED,
                FailureClass.NONE,
                null,
                requested.updatedAt(),
                "request-event-d3-requested",
                "trace-d3-rejected"
            ).withDurableEvidence(requested, WORKER)
        );
    }

    private void seedRequestedLineage(
        ObjectMapper mapper,
        ApprovalMigrationAttempt requested,
        ApprovalMigrationCommandFence fence
    ) throws Exception {
        String attemptPayload = mapper.writeValueAsString(requested).replace("'", "''");
        String fencePayload = mapper.writeValueAsString(fence).replace("'", "''");
        List<ApprovalMigrationAttemptEvent> events = requestedAttemptEvents();
        List<String> eventPayloads = events.stream()
            .map(event -> {
                try {
                    return mapper.writeValueAsString(event).replace("'", "''");
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new IllegalStateException("attempt event fixture is not serializable", exception);
                }
            })
            .toList();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.executeUpdate("""
                    insert into ap_process_migration_intent (
                      tenant_id,intent_id,idempotency_key,plan_id,plan_hash,definition_key,
                      source_release_version,source_package_hash,target_release_version,target_package_hash,
                      status,revision,intent_evidence_hash,payload_json,created_at,updated_at
                    ) values ('%s','%s','intent-d3-rejected','%s','%s','%s',1,'%s',2,'%s',
                      'RUNNING',2,'%s','{}'::jsonb,timestamptz '%s',timestamptz '%s')
                    """.formatted(
                    TENANT,
                    INTENT_ID,
                    UUID.fromString("42100000-0000-0000-0000-000000000009"),
                    hash('4'),
                    DEFINITION_KEY,
                    hash('b'),
                    hash('c'),
                    hash('8'),
                    offset(NOW.minusSeconds(30)),
                    offset(NOW.minusSeconds(20))
                ));
                statement.executeUpdate("""
                    insert into ap_process_migration_attempt (
                      tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,parent_attempt_id,
                      status,revision,engine_outcome,lease_actor,lease_owner,lease_until,
                      engine_request_reference,failure_class,error_summary,expected_binding_evidence_hash,
                      payload_json,created_at,updated_at
                    ) values ('%s','%s','%s','%s',1,null,'ENGINE_REQUESTED',3,'NOT_REQUESTED',
                      '%s',null,null,'%s','NONE',null,'%s','%s'::jsonb,timestamptz '%s',timestamptz '%s')
                    """.formatted(
                    TENANT,
                    ATTEMPT_ID,
                    INTENT_ID,
                    INSTANCE_ID,
                    WORKER,
                    REQUEST_ID,
                    hash('7'),
                    attemptPayload,
                    offset(requested.createdAt()),
                    offset(requested.updatedAt())
                ));
                for (int index = 0; index < events.size(); index++) {
                    ApprovalMigrationAttemptEvent event = events.get(index);
                    statement.executeUpdate("""
                        insert into ap_process_migration_attempt_event (
                          tenant_id,event_id,attempt_id,revision,from_status,to_status,engine_outcome,
                          lease_actor,lease_owner,lease_until,engine_request_reference,
                          failure_class,error_summary,payload_json,happened_at
                        ) values ('%s','%s','%s',%d,%s,'%s','%s',%s,%s,%s,%s,'%s',%s,
                          '%s'::jsonb,timestamptz '%s')
                        """.formatted(
                        event.tenantId(),
                        event.eventId(),
                        event.attemptId(),
                        event.revision(),
                        sqlText(event.fromStatus() == null ? null : event.fromStatus().name()),
                        event.toStatus().name(),
                        event.engineOutcome().name(),
                        sqlText(event.leaseActor()),
                        sqlText(event.leaseOwner()),
                        event.leaseUntil() == null
                            ? "null"
                            : "timestamptz '" + offset(event.leaseUntil()) + "'",
                        sqlText(event.engineRequestReference()),
                        event.failureClass().name(),
                        sqlText(event.errorSummary()),
                        eventPayloads.get(index),
                        offset(event.happenedAt())
                    ));
                }
                statement.executeUpdate("""
                    insert into ap_approval_instance_command_fence (
                      tenant_id,fence_id,approval_instance_id,attempt_id,operation,status,revision,
                      lease_owner,lease_until,idempotency_key,request_hash,acquired_at,updated_at,
                      released_at,request_id,trace_id,payload_json
                    ) values ('%s','%s','%s','%s','MIGRATION','ACTIVE',1,'%s',timestamptz '%s',
                      'fence-d3-rejected','%s',timestamptz '%s',timestamptz '%s',null,
                      'request-fence-d3-rejected','trace-d3-rejected','%s'::jsonb)
                    """.formatted(
                    TENANT,
                    FENCE_ID,
                    INSTANCE_ID,
                    ATTEMPT_ID,
                    WORKER,
                    offset(fence.leaseUntil()),
                    hash('9'),
                    offset(fence.acquiredAt()),
                    offset(fence.updatedAt()),
                    fencePayload
                ));
                statement.executeUpdate("""
                    insert into ap_process_migration_engine_request (
                      tenant_id,engine_request_id,intent_id,attempt_id,approval_instance_id,worker_id,
                      attempt_revision,fence_id,fence_revision,engine_instance_id,
                      source_binding_evidence_hash,source_engine_definition_id,target_release_version,
                      target_package_hash,target_engine_deployment_id,target_engine_definition_id,
                      activity_mapping_json,request_hash,evidence_hash,requested_at,request_id,trace_id,payload_json
                    ) values ('%s','%s','%s','%s','%s','%s',2,'%s',1,'%s','%s','%s',2,'%s',
                      '%s','%s','[]'::jsonb,'%s','%s',timestamptz '%s',
                      'request-d3-rejected','trace-d3-rejected','{}'::jsonb)
                    """.formatted(
                    TENANT,
                    REQUEST_ID,
                    INTENT_ID,
                    ATTEMPT_ID,
                    INSTANCE_ID,
                    WORKER,
                    FENCE_ID,
                    ENGINE_INSTANCE,
                    hash('7'),
                    SOURCE_DEFINITION,
                    hash('c'),
                    TARGET_DEPLOYMENT,
                    TARGET_DEFINITION,
                    hash('a'),
                    hash('d'),
                    offset(NOW.plusSeconds(20))
                ));
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private String scalar(String sql) {
        return jdbc.queryForObject(sql, String.class, TENANT, ATTEMPT_ID);
    }

    private static String sqlText(String value) {
        return value == null ? "null" : "'" + value.replace("'", "''") + "'";
    }

    private static OffsetDateTime offset(java.time.Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
