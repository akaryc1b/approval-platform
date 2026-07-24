package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared attempt SQL mapping; transaction ownership stays with writers. */
final class JdbcApprovalMigrationAttemptRepository {

    final NamedParameterJdbcTemplate jdbc;
    final JdbcApprovalMigrationJson json;

    JdbcApprovalMigrationAttemptRepository(DataSource dataSource, JdbcApprovalMigrationJson json) {
        jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.json = Objects.requireNonNull(json);
    }

    Optional<ApprovalMigrationAttempt> find(String tenantId, UUID attemptId) {
        return query("tenant_id=:tenantId and attempt_id=:attemptId", new MapSqlParameterSource()
            .addValue("tenantId", tenantId).addValue("attemptId", attemptId));
    }

    Optional<ApprovalMigrationAttempt> findByNumber(
        String tenantId,
        UUID intentId,
        UUID instanceId,
        int number
    ) {
        return query("tenant_id=:tenantId and intent_id=:intentId and approval_instance_id=:instanceId and attempt_number=:number",
            new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("intentId", intentId)
                .addValue("instanceId", instanceId).addValue("number", number));
    }

    List<ApprovalMigrationAttempt> findByIntent(String tenantId, UUID intentId) {
        return List.copyOf(jdbc.query("""
            select payload_json::text from ap_process_migration_attempt
            where tenant_id=:tenantId and intent_id=:intentId order by approval_instance_id,attempt_number
            """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("intentId", intentId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationAttempt.class)));
    }

    List<ApprovalMigrationAttemptEvent> events(String tenantId, UUID attemptId) {
        return List.copyOf(jdbc.query("""
            select payload_json::text from ap_process_migration_attempt_event
            where tenant_id=:tenantId and attempt_id=:attemptId order by revision
            """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationAttemptEvent.class)));
    }

    int insert(ApprovalMigrationAttempt value) {
        return jdbc.update("""
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,parent_attempt_id,
              status,revision,engine_outcome,lease_owner,lease_until,expected_binding_evidence_hash,
              payload_json,created_at,updated_at
            ) values (:tenantId,:attemptId,:intentId,:instanceId,:attemptNumber,:parentAttemptId,
              :status,:revision,:engineOutcome,:leaseOwner,:leaseUntil,:bindingHash,
              cast(:payload as jsonb),:createdAt,:updatedAt)
            on conflict (tenant_id,intent_id,approval_instance_id,attempt_number) do nothing
            """, parameters(value));
    }

    int update(ApprovalMigrationAttempt next, long expectedRevision, ApprovalMigrationAttemptEvent event) {
        return jdbc.update("""
            update ap_process_migration_attempt set status=:status,revision=:revision,
              engine_outcome=:engineOutcome,lease_owner=:leaseOwner,lease_until=:leaseUntil,
              payload_json=cast(:payload as jsonb),updated_at=:updatedAt
            where tenant_id=:tenantId and attempt_id=:attemptId
              and revision=:expectedRevision and status=:fromStatus
            """, parameters(next).addValue("expectedRevision", expectedRevision)
                .addValue("fromStatus", event.fromStatus().name()));
    }

    void appendEvent(ApprovalMigrationAttemptEvent event) {
        jdbc.update("""
            insert into ap_process_migration_attempt_event (
              tenant_id,event_id,attempt_id,revision,from_status,to_status,payload_json,happened_at
            ) values (:tenantId,:eventId,:attemptId,:revision,:fromStatus,:toStatus,
              cast(:payload as jsonb),:happenedAt)
            """, new MapSqlParameterSource().addValue("tenantId", event.tenantId())
                .addValue("eventId", event.eventId()).addValue("attemptId", event.attemptId())
                .addValue("revision", event.revision())
                .addValue("fromStatus", event.fromStatus() == null ? null : event.fromStatus().name())
                .addValue("toStatus", event.toStatus().name()).addValue("payload", json.write(event))
                .addValue("happenedAt", JdbcApprovalMigrationJson.offset(event.happenedAt())));
    }

    private Optional<ApprovalMigrationAttempt> query(String predicate, MapSqlParameterSource parameters) {
        return jdbc.query("select payload_json::text from ap_process_migration_attempt where " + predicate,
            parameters, (row, number) -> json.read(row.getString(1), ApprovalMigrationAttempt.class))
            .stream().findFirst();
    }

    private MapSqlParameterSource parameters(ApprovalMigrationAttempt value) {
        return new MapSqlParameterSource().addValue("tenantId", value.tenantId())
            .addValue("attemptId", value.attemptId()).addValue("intentId", value.intentId())
            .addValue("instanceId", value.approvalInstanceId()).addValue("attemptNumber", value.attemptNumber())
            .addValue("parentAttemptId", value.parentAttemptId()).addValue("status", value.status().name())
            .addValue("revision", value.revision()).addValue("engineOutcome", value.engineOutcome().name())
            .addValue("leaseOwner", value.leaseOwner())
            .addValue("leaseUntil", value.leaseUntil() == null ? null : JdbcApprovalMigrationJson.offset(value.leaseUntil()))
            .addValue("bindingHash", value.expectedBindingEvidenceHash()).addValue("payload", json.write(value))
            .addValue("createdAt", JdbcApprovalMigrationJson.offset(value.createdAt()))
            .addValue("updatedAt", JdbcApprovalMigrationJson.offset(value.updatedAt()));
    }
}
