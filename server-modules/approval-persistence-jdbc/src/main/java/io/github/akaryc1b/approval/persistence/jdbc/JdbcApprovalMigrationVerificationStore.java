package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Idempotent immutable verification append with database-enforced gap-free sequence. */
final class JdbcApprovalMigrationVerificationStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcApprovalMigrationJson json;

    JdbcApprovalMigrationVerificationStore(
        DataSource dataSource,
        JdbcApprovalMigrationJson json,
        PlatformTransactionManager transactionManager
    ) {
        jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.json = Objects.requireNonNull(json);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    void append(ApprovalMigrationVerification value) {
        try {
            transactions.execute(status -> {
                int inserted = jdbc.update("""
                    insert into ap_process_migration_verification (
                      tenant_id,verification_id,intent_id,attempt_id,sequence,outcome,
                      evidence_hash,payload_json,recorded_at
                    ) values (:tenantId,:verificationId,:intentId,:attemptId,:sequence,:outcome,
                      :evidenceHash,cast(:payload as jsonb),:recordedAt)
                    on conflict (tenant_id,verification_id) do nothing
                    """, parameters(value));
                if (inserted == 0) {
                    ApprovalMigrationVerification existing = findById(
                        value.tenantId(), value.verificationId()
                    );
                    if (!existing.equals(value)) {
                        throw new MigrationProtocolConflictException(
                            "verification identity was reused with different evidence"
                        );
                    }
                }
                return null;
            });
        } catch (DataIntegrityViolationException exception) {
            throw new MigrationProtocolConflictException("migration verification conflict", exception);
        }
    }

    List<ApprovalMigrationVerification> find(String tenantId, UUID attemptId) {
        return List.copyOf(jdbc.query("""
            select payload_json::text from ap_process_migration_verification
            where tenant_id=:tenantId and attempt_id=:attemptId order by sequence
            """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationVerification.class)));
    }

    private ApprovalMigrationVerification findById(String tenantId, UUID verificationId) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_verification
            where tenant_id=:tenantId and verification_id=:verificationId
            """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("verificationId", verificationId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationVerification.class))
            .stream().findFirst().orElseThrow(() -> new MigrationProtocolConflictException(
                "verification replay disappeared"
            ));
    }

    private MapSqlParameterSource parameters(ApprovalMigrationVerification value) {
        return new MapSqlParameterSource().addValue("tenantId", value.tenantId())
            .addValue("verificationId", value.verificationId()).addValue("intentId", value.intentId())
            .addValue("attemptId", value.attemptId()).addValue("sequence", value.sequence())
            .addValue("outcome", value.outcome().name()).addValue("evidenceHash", value.evidenceHash())
            .addValue("payload", json.write(value))
            .addValue("recordedAt", JdbcApprovalMigrationJson.offset(value.recordedAt()));
    }
}
