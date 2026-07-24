package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Idempotent immutable reconciliation append with closed database progression. */
final class JdbcApprovalMigrationReconciliationStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcApprovalMigrationJson json;

    JdbcApprovalMigrationReconciliationStore(
        DataSource dataSource,
        JdbcApprovalMigrationJson json,
        PlatformTransactionManager transactionManager
    ) {
        jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.json = Objects.requireNonNull(json);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    void append(ApprovalMigrationReconciliation value) {
        try {
            transactions.execute(status -> {
                int inserted = jdbc.update("""
                    insert into ap_process_migration_reconciliation (
                      tenant_id,reconciliation_id,intent_id,attempt_id,sequence,status,
                      evidence_hash,resolution_evidence_hash,payload_json,recorded_at,resolved_at
                    ) values (:tenantId,:reconciliationId,:intentId,:attemptId,:sequence,:status,
                      :evidenceHash,:resolutionHash,cast(:payload as jsonb),:recordedAt,:resolvedAt)
                    on conflict (tenant_id,reconciliation_id) do nothing
                    """, parameters(value));
                if (inserted == 0) {
                    ApprovalMigrationReconciliation existing = findById(
                        value.tenantId(), value.reconciliationId()
                    );
                    if (!existing.equals(value)) {
                        throw new MigrationProtocolConflictException(
                            "reconciliation identity was reused with different evidence"
                        );
                    }
                }
                return null;
            });
        } catch (DataIntegrityViolationException exception) {
            throw new MigrationProtocolConflictException("migration reconciliation conflict", exception);
        }
    }

    List<ApprovalMigrationReconciliation> find(String tenantId, UUID attemptId) {
        return List.copyOf(jdbc.query("""
            select payload_json::text from ap_process_migration_reconciliation
            where tenant_id=:tenantId and attempt_id=:attemptId order by sequence
            """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationReconciliation.class)));
    }

    private ApprovalMigrationReconciliation findById(String tenantId, UUID reconciliationId) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_reconciliation
            where tenant_id=:tenantId and reconciliation_id=:reconciliationId
            """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("reconciliationId", reconciliationId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationReconciliation.class))
            .stream().findFirst().orElseThrow(() -> new MigrationProtocolConflictException(
                "reconciliation replay disappeared"
            ));
    }

    private MapSqlParameterSource parameters(ApprovalMigrationReconciliation value) {
        return new MapSqlParameterSource().addValue("tenantId", value.tenantId())
            .addValue("reconciliationId", value.reconciliationId()).addValue("intentId", value.intentId())
            .addValue("attemptId", value.attemptId()).addValue("sequence", value.sequence())
            .addValue("status", value.status().name()).addValue("evidenceHash", value.evidenceHash())
            .addValue("resolutionHash", value.resolutionEvidenceHash())
            .addValue("payload", json.write(value))
            .addValue("recordedAt", JdbcApprovalMigrationJson.offset(value.recordedAt()))
            .addValue("resolvedAt", value.resolvedAt() == null
                ? null : JdbcApprovalMigrationJson.offset(value.resolvedAt()));
    }
}
