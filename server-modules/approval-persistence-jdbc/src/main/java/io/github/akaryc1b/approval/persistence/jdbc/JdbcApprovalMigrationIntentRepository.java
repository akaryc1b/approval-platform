package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared intent SQL mapping; transaction ownership stays with writers. */
final class JdbcApprovalMigrationIntentRepository {

    final NamedParameterJdbcTemplate jdbc;
    final JdbcApprovalMigrationJson json;

    JdbcApprovalMigrationIntentRepository(DataSource dataSource, JdbcApprovalMigrationJson json) {
        jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.json = Objects.requireNonNull(json);
    }

    Optional<ApprovalMigrationIntent> find(String tenantId, UUID intentId) {
        return query("tenant_id=:tenantId and intent_id=:intentId", new MapSqlParameterSource()
            .addValue("tenantId", tenantId).addValue("intentId", intentId));
    }

    Optional<ApprovalMigrationIntent> findByIdempotencyKey(String tenantId, String key) {
        return query("tenant_id=:tenantId and idempotency_key=:key", new MapSqlParameterSource()
            .addValue("tenantId", tenantId).addValue("key", key));
    }

    List<ApprovalMigrationIntentEvent> events(String tenantId, UUID intentId) {
        return List.copyOf(jdbc.query("""
            select payload_json::text from ap_process_migration_intent_event
            where tenant_id=:tenantId and intent_id=:intentId order by revision
            """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("intentId", intentId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationIntentEvent.class)));
    }

    int insert(ApprovalMigrationIntent value) {
        return jdbc.update("""
            insert into ap_process_migration_intent (
              tenant_id,intent_id,idempotency_key,plan_id,plan_hash,definition_key,
              source_release_version,source_package_hash,target_release_version,target_package_hash,
              status,revision,intent_evidence_hash,payload_json,created_at,updated_at
            ) values (:tenantId,:intentId,:idempotencyKey,:planId,:planHash,:definitionKey,
              :sourceVersion,:sourceHash,:targetVersion,:targetHash,:status,:revision,:evidenceHash,
              cast(:payload as jsonb),:createdAt,:updatedAt)
            on conflict (tenant_id,idempotency_key) do nothing
            """, parameters(value));
    }

    int update(ApprovalMigrationIntent next, long expectedRevision, ApprovalMigrationIntentEvent event) {
        return jdbc.update("""
            update ap_process_migration_intent set status=:status,revision=:revision,
              payload_json=cast(:payload as jsonb),updated_at=:updatedAt
            where tenant_id=:tenantId and intent_id=:intentId
              and revision=:expectedRevision and status=:fromStatus
            """, parameters(next).addValue("expectedRevision", expectedRevision)
                .addValue("fromStatus", event.fromStatus().name()));
    }

    void appendEvent(ApprovalMigrationIntentEvent event) {
        jdbc.update("""
            insert into ap_process_migration_intent_event (
              tenant_id,event_id,intent_id,revision,from_status,to_status,payload_json,happened_at
            ) values (:tenantId,:eventId,:intentId,:revision,:fromStatus,:toStatus,
              cast(:payload as jsonb),:happenedAt)
            """, new MapSqlParameterSource().addValue("tenantId", event.tenantId())
                .addValue("eventId", event.eventId()).addValue("intentId", event.intentId())
                .addValue("revision", event.revision())
                .addValue("fromStatus", event.fromStatus() == null ? null : event.fromStatus().name())
                .addValue("toStatus", event.toStatus().name()).addValue("payload", json.write(event))
                .addValue("happenedAt", JdbcApprovalMigrationJson.offset(event.happenedAt())));
    }

    private Optional<ApprovalMigrationIntent> query(String predicate, MapSqlParameterSource parameters) {
        return jdbc.query("select payload_json::text from ap_process_migration_intent where " + predicate,
            parameters, (row, number) -> json.read(row.getString(1), ApprovalMigrationIntent.class))
            .stream().findFirst();
    }

    private MapSqlParameterSource parameters(ApprovalMigrationIntent value) {
        return new MapSqlParameterSource().addValue("tenantId", value.tenantId())
            .addValue("intentId", value.intentId()).addValue("idempotencyKey", value.idempotencyKey())
            .addValue("planId", value.planId()).addValue("planHash", value.planHash())
            .addValue("definitionKey", value.definitionKey())
            .addValue("sourceVersion", value.sourceReleaseVersion()).addValue("sourceHash", value.sourcePackageHash())
            .addValue("targetVersion", value.targetReleaseVersion()).addValue("targetHash", value.targetPackageHash())
            .addValue("status", value.status().name()).addValue("revision", value.revision())
            .addValue("evidenceHash", value.intentEvidenceHash()).addValue("payload", json.write(value))
            .addValue("createdAt", JdbcApprovalMigrationJson.offset(value.createdAt()))
            .addValue("updatedAt", JdbcApprovalMigrationJson.offset(value.updatedAt()));
    }
}
