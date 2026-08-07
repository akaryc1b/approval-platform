package db.mysqlmigration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V50BaselineTest {

    @Test
    void embeddedBaselineIsDeterministicAndContainsCurrentSchema() {
        String sql = V50__Baseline_approval_platform.decompressBaseline();
        var statements = V50__Baseline_approval_platform.splitStatements(sql);

        assertTrue(statements.size() > 150);
        assertTrue(sql.contains("create table if not exists ap_outbox"));
        assertTrue(sql.contains("create table ap_process_migration_plan"));
        assertTrue(sql.contains("create table ap_ai_controlled_automation_lineage"));
        assertTrue(sql.contains("ENGINE=InnoDB"));
        assertTrue(sql.contains("COLLATE=utf8mb4_0900_as_cs"));
        assertFalse(sql.toLowerCase().contains("timestamptz"));
        assertFalse(sql.toLowerCase().contains("jsonb"));
        assertFalse(sql.toLowerCase().contains("bytea"));
        assertFalse(sql.toLowerCase().contains(" on conflict "));
        assertFalse(sql.contains("::"));
    }

    @Test
    void statementSplitterRetainsQuotedSemicolons() {
        var statements = V50__Baseline_approval_platform.splitStatements(
            "create table sample(value varchar(20));"
                + "insert into sample values ('a;b');"
                + "-- comment; ignored\n"
                + "insert into sample values ('c'';d');"
        );

        assertEquals(3, statements.size());
        assertTrue(statements.get(1).contains("'a;b'"));
        assertTrue(statements.get(2).contains("'c'';d'"));
    }
}
