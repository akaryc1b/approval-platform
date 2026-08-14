package db.mysqlmigration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50H7OrchestrationSchemaContractTest {

    private static final List<String> TABLES = List.of(
        "ap_process_migration_canary_selection",
        "ap_process_migration_orchestration_run",
        "ap_process_migration_orchestration_event",
        "ap_process_migration_orchestration_batch",
        "ap_process_migration_kill_switch_observation"
    );

    @Test
    void baselineContainsPortableD7TablesAndRelationalInvariants() {
        List<String> statements = MySqlV50Baseline.splitStatements(
            MySqlV50Baseline.decompressBaseline()
        ).stream()
            .map(MySqlV50Baseline::executableForMySql84)
            .flatMap(Optional::stream)
            .toList();

        for (String table : TABLES) {
            String statement = statements.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).contains(
                    "create table " + table
                ))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "missing MySQL D7 table: " + table
                ));
            String lower = statement.toLowerCase(Locale.ROOT);
            assertTrue(lower.contains("engine=innodb"));
            assertTrue(lower.contains("collate=utf8mb4_0900_as_cs"));
            assertTrue(lower.contains("payload_json json not null"));
            assertFalse(lower.contains(" uuid "));
            assertFalse(lower.contains("timestamptz"));
            assertFalse(lower.contains("jsonb"));
            assertFalse(lower.contains("::"));
        }

        String run = tableStatement(
            statements,
            "ap_process_migration_orchestration_run"
        );
        assertTrue(run.contains("run_id varchar(36) not null"));
        assertTrue(run.contains("run_revision bigint not null"));
        assertTrue(run.contains("started_at datetime(6) not null"));
        assertTrue(run.contains("unique (tenant_id,intent_id,run_revision)"));
        assertTrue(run.contains("unique (tenant_id,request_hash)"));

        String event = tableStatement(
            statements,
            "ap_process_migration_orchestration_event"
        );
        assertTrue(event.contains("unique (tenant_id,run_id,sequence)"));
        assertTrue(event.contains("attempt_id varchar(36)"));

        String batch = tableStatement(
            statements,
            "ap_process_migration_orchestration_batch"
        );
        assertTrue(batch.contains("attempt_ids json not null"));
        assertTrue(batch.contains("dispositions json not null"));
        assertTrue(batch.contains("unique (tenant_id,run_id)"));
        assertTrue(batch.contains("unique (tenant_id,claim_batch_id)"));

        String observation = tableStatement(
            statements,
            "ap_process_migration_kill_switch_observation"
        );
        assertTrue(observation.contains("unique (tenant_id,run_id,attempt_id)"));
        assertTrue(observation.contains("unique (tenant_id,request_hash)"));
        assertTrue(observation.contains("dispatch_allowed boolean not null"));
    }

    @Test
    void baselineRetainsAllFiveD7AppendOnlyGuardTriggers() {
        String lower = MySqlV50Baseline.decompressBaseline()
            .toLowerCase(Locale.ROOT);

        for (String trigger : List.of(
            "trg_process_migration_canary_selection_guard_v47",
            "trg_process_migration_orchestration_run_guard_v47",
            "trg_process_migration_orchestration_event_guard_v47",
            "trg_process_migration_orchestration_batch_guard_v47",
            "trg_process_migration_kill_switch_observation_guard_v47"
        )) {
            assertTrue(lower.contains("create trigger " + trigger));
        }
    }

    private static String tableStatement(List<String> statements, String table) {
        return statements.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(value -> value.contains("create table " + table))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "missing MySQL D7 table: " + table
            ));
    }
}
