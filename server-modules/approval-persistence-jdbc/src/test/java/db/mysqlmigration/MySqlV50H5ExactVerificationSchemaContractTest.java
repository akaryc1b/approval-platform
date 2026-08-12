package db.mysqlmigration;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50H5ExactVerificationSchemaContractTest {

    @Test
    void baselineContainsPortableExactVerificationEvidenceSchema() {
        String statement = MySqlV50Baseline.splitStatements(
            MySqlV50Baseline.decompressBaseline()
        ).stream()
            .filter(value -> value.contains(
                "create table ap_process_migration_exact_verification"
            ))
            .map(MySqlV50Baseline::executableForMySql84)
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "missing MySQL exact-verification evidence table"
            ));
        String lower = statement.toLowerCase(Locale.ROOT);

        assertTrue(lower.contains(
            "create table ap_process_migration_exact_verification"
        ));
        assertTrue(lower.contains("verification_id varchar(36) not null"));
        assertTrue(lower.contains("attempt_id varchar(36) not null"));
        assertTrue(lower.contains("engine_request_id varchar(36) not null"));
        assertTrue(lower.contains("engine_outcome_id varchar(36) not null"));
        assertTrue(lower.contains("expected_attempt_revision bigint not null"));
        assertTrue(lower.contains("expected_fence_revision bigint not null"));
        assertTrue(lower.contains("recorded_at datetime(6) not null"));
        assertTrue(lower.contains("payload_json json not null"));
        assertTrue(lower.contains("unique (tenant_id,attempt_id)"));
        assertTrue(lower.contains("unique (tenant_id,request_hash)"));
        assertTrue(lower.contains("'exact_target_runtime'"));
        assertTrue(lower.contains("'exact_source_runtime'"));
        assertTrue(lower.contains("'target_history_terminal'"));
        assertTrue(lower.contains("'mixed_source_target_evidence'"));
        assertTrue(lower.contains("'read_failure_reconciliation_required'"));
        assertTrue(lower.contains("'incomplete_reconciliation_required'"));
        assertTrue(lower.contains("engine=innodb"));
        assertTrue(lower.contains("collate=utf8mb4_0900_as_cs"));

        assertFalse(lower.contains(" uuid "));
        assertFalse(lower.contains("timestamptz"));
        assertFalse(lower.contains("jsonb"));
        assertFalse(lower.contains("::"));
    }
}
