package db.mysqlmigration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50H8PlanAggregationSchemaContractTest {

    private static final List<String> TABLES = List.of(
        "ap_process_migration_plan_aggregate",
        "ap_process_migration_plan_aggregate_event",
        "ap_process_migration_plan_completion"
    );

    @Test
    void baselineContainsPortableD8TablesAndRelationalInvariants() {
        List<String> statements = MySqlV50Baseline.splitStatements(
            MySqlV50Baseline.decompressBaseline()
        ).stream()
            .map(MySqlV50Baseline::executableForMySql84)
            .flatMap(Optional::stream)
            .toList();

        for (String table : TABLES) {
            String lower = tableStatement(statements, table);
            assertTrue(lower.contains("engine=innodb"));
            assertTrue(lower.contains("collate=utf8mb4_0900_as_cs"));
            assertTrue(lower.contains("payload_json json not null"));
            assertFalse(lower.contains(" uuid "));
            assertFalse(lower.contains("timestamptz"));
            assertFalse(lower.contains("jsonb"));
            assertFalse(lower.contains("::"));
        }

        String aggregate = tableStatement(
            statements,
            "ap_process_migration_plan_aggregate"
        );
        assertTrue(aggregate.contains("aggregate_id varchar(36) not null"));
        assertTrue(aggregate.contains("plan_id varchar(36) not null"));
        assertTrue(aggregate.contains("intent_id varchar(36) not null"));
        assertTrue(aggregate.contains("aggregated_at datetime(6) not null"));
        assertTrue(aggregate.contains("unique (tenant_id,plan_id,aggregate_revision)"));
        assertTrue(aggregate.contains("unique (tenant_id,idempotency_key)"));
        assertTrue(aggregate.contains("unique (tenant_id,request_hash)"));
        assertTrue(aggregate.contains("unique (tenant_id,plan_id,input_evidence_hash)"));
        assertTrue(aggregate.contains("foreign key (tenant_id,plan_id,plan_hash)"));
        assertTrue(aggregate.contains("foreign key (tenant_id,intent_id)"));

        String event = tableStatement(
            statements,
            "ap_process_migration_plan_aggregate_event"
        );
        assertTrue(event.contains("happened_at datetime(6) not null"));
        assertTrue(event.contains("unique (tenant_id,aggregate_id)"));
        assertTrue(event.contains("unique (tenant_id,plan_id,aggregate_revision)"));
        assertTrue(event.contains("foreign key (tenant_id,aggregate_id)"));

        String completion = tableStatement(
            statements,
            "ap_process_migration_plan_completion"
        );
        assertTrue(completion.contains("completed_at datetime(6) not null"));
        assertTrue(completion.contains("unique (tenant_id,plan_id)"));
        assertTrue(completion.contains("unique (tenant_id,intent_id)"));
        assertTrue(completion.contains("unique (tenant_id,aggregate_id)"));
        assertTrue(completion.contains("foreign key (tenant_id,aggregate_id)"));
    }

    @Test
    void baselineInstallsCompleteD8InsertAndAppendOnlyGuards() {
        List<String> guards = MySqlV50Baseline.d8GuardStatements();
        String lower = String.join("\n", guards).toLowerCase(Locale.ROOT);

        assertEquals(9, guards.size());
        for (String table : TABLES) {
            assertTrue(lower.contains("before insert on " + table));
            assertTrue(lower.contains("before update on " + table));
            assertTrue(lower.contains("before delete on " + table));
        }
        assertTrue(lower.contains("d8 aggregate payload mismatch"));
        assertTrue(lower.contains("d8 aggregate predecessor mismatch"));
        assertTrue(lower.contains("d8 aggregate sealed-plan lineage mismatch"));
        assertTrue(lower.contains("d8 aggregate event payload mismatch"));
        assertTrue(lower.contains("d8 aggregate event lineage mismatch"));
        assertTrue(lower.contains("d8 completion payload mismatch"));
        assertTrue(lower.contains("d8 completion aggregate lineage mismatch"));
        assertTrue(lower.contains("json_unquote(json_extract("));
        assertTrue(lower.contains("signal sqlstate '45000'"));
        assertEquals(3, occurrences(lower, "when 'null' then null"));
        assertEquals(6, occurrences(lower, "'$.traceid'"));
        assertFalse(lower.contains("nullif(json_unquote(json_extract("));
        assertFalse(lower.contains("foreign_key_checks"));
        assertFalse(lower.contains("insert ignore"));
        assertFalse(lower.contains("replace into"));
        assertFalse(lower.contains("on duplicate key update"));
    }

    private static String tableStatement(List<String> statements, String table) {
        return statements.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(value -> value.contains("create table " + table))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "missing MySQL D8 table: " + table
            ));
    }

    private static int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
