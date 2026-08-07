package db.mysqlmigration;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V50BaselineTest {

    @Test
    void embeddedBaselineIsDeterministicAndContainsCurrentSchema() {
        var migration = new MySqlV50Baseline();
        String sql = MySqlV50Baseline.decompressBaseline();
        var statements = MySqlV50Baseline.splitStatements(sql);

        assertEquals("50", migration.getVersion().getVersion());
        assertEquals("Baseline approval platform", migration.getDescription());
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
    void normalizesUnsupportedIdempotentDdlForACleanMySql84Baseline() {
        String normalized = MySqlV50Baseline.normalizeForMySql84("""
            alter table ap_comment
                add column if not exists parent_comment_id varchar(36);
            create unique index if not exists uk_sample on ap_sample(id);
            create index if not exists ix_sample on ap_sample(created_at);
            alter table ap_sample
                add constraint if not exists fk_sample
                foreign key (parent_id) references ap_parent(id);
            create table if not exists ap_sample(id bigint);
            """);

        assertTrue(normalized.contains("add column parent_comment_id"));
        assertTrue(normalized.contains("create unique index uk_sample"));
        assertTrue(normalized.contains("create index ix_sample"));
        assertTrue(normalized.contains("add constraint fk_sample"));
        assertTrue(normalized.contains("create table if not exists ap_sample"));
        assertFalse(normalized.contains("add column if not exists"));
        assertFalse(normalized.contains("index if not exists"));
        assertFalse(normalized.contains("constraint if not exists"));
    }

    @Test
    void ignoresOnlyTheKnownMissingHistoricalForeignKeyDrop() {
        SQLException missingObject = new SQLException(
            "Can't DROP 'ap_approval_comment_parent_fk'",
            "42000",
            1091
        );
        SQLException syntaxError = new SQLException("syntax error", "42000", 1064);

        assertTrue(MySqlV50Baseline.isIgnorableCleanBaselineForeignKeyDrop(
            "alter table ap_approval_comment "
                + "drop foreign key ap_approval_comment_parent_fk",
            missingObject
        ));
        assertFalse(MySqlV50Baseline.isIgnorableCleanBaselineForeignKeyDrop(
            "alter table ap_approval_comment drop column parent_comment_id",
            missingObject
        ));
        assertFalse(MySqlV50Baseline.isIgnorableCleanBaselineForeignKeyDrop(
            "alter table ap_other_comment "
                + "drop foreign key ap_approval_comment_parent_fk",
            missingObject
        ));
        assertFalse(MySqlV50Baseline.isIgnorableCleanBaselineForeignKeyDrop(
            "alter table ap_approval_comment "
                + "drop foreign key ap_approval_comment_parent_fk",
            syntaxError
        ));
    }

    @Test
    void statementSplitterRetainsQuotedSemicolons() {
        var statements = MySqlV50Baseline.splitStatements(
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
