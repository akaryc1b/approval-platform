package db.mysqlmigration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V50BaselineTest {

    private static final int MANIFEST_CHUNK_SIZE = 6_000;

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
    void emitsExactNormalizedStatementManifest() throws Exception {
        var statements = baselineStatements();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        for (int offset = 0; offset < statements.size(); offset++) {
            int index = offset + 1;
            Optional<String> executable = MySqlV50Baseline.executableForMySql84(
                statements.get(offset)
            );
            if (executable.isEmpty()) {
                System.out.printf("mysql-baseline-skipped index=%d%n", index);
                continue;
            }
            byte[] bytes = executable.orElseThrow().getBytes(StandardCharsets.UTF_8);
            String sha256 = HexFormat.of().formatHex(digest.digest(bytes));
            String encoded = Base64.getEncoder().encodeToString(bytes);
            int chunks = Math.max(
                1,
                (encoded.length() + MANIFEST_CHUNK_SIZE - 1) / MANIFEST_CHUNK_SIZE
            );
            System.out.printf(
                "mysql-baseline-manifest index=%d bytes=%d sha256=%s chunks=%d%n",
                index,
                bytes.length,
                sha256,
                chunks
            );
            for (int chunk = 0; chunk < chunks; chunk++) {
                int start = chunk * MANIFEST_CHUNK_SIZE;
                int end = Math.min(encoded.length(), start + MANIFEST_CHUNK_SIZE);
                System.out.printf(
                    "mysql-baseline-content index=%d chunk=%d/%d data=%s%n",
                    index,
                    chunk + 1,
                    chunks,
                    encoded.substring(start, end)
                );
            }
        }

        assertTrue(statements.size() > 150);
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
    void preservesFullNotificationTupleThroughFailClosedGeneratedHashUniqueness() {
        String normalized = executableStatementContaining("create table ap_notification_intent");

        assertTrue(normalized.contains("business_event_key varchar(512) not null"));
        assertTrue(normalized.contains("business_recipient_channel_hash binary(32)"));
        assertTrue(normalized.contains("generated always as"));
        assertTrue(normalized.contains("hex(tenant_id)"));
        assertTrue(normalized.contains("hex(business_event_key)"));
        assertTrue(normalized.contains("hex(recipient_id)"));
        assertTrue(normalized.contains("hex(channel)"));
        assertTrue(normalized.contains("unique (business_recipient_channel_hash)"));
        assertFalse(normalized.contains("business_event_key("));
        assertFalse(normalized.contains("recipient_id("));
    }

    @Test
    void boundsOnlyTheNonUniqueConsistencyLookupPrefix() {
        String normalized = executableStatementContaining(
            "create index idx_consistency_finding_aggregate"
        );

        assertTrue(normalized.contains("aggregate_id(500)"));
        assertTrue(normalized.contains("detected_at desc"));
    }

    @Test
    void convertsInlineReferencesIntoEnforcedNamedForeignKeys() {
        String executable = executableBaseline();

        for (String name : List.of(
            "fk_approval_task_instance",
            "fk_approval_message_instance",
            "fk_approval_message_task",
            "fk_approval_comment_instance",
            "fk_approval_attachment_instance",
            "fk_form_submission_instance",
            "fk_form_submission_revision_instance"
        )) {
            assertTrue(
                executable.contains("constraint " + name),
                () -> "missing governed MySQL foreign key: " + name
            );
        }
        assertFalse(executable.contains(
            "instance_id varchar(36) not null references"
        ));
        assertFalse(executable.contains(
            "instance_id varchar(36) references"
        ));
        assertFalse(executable.contains(
            "task_id varchar(36) references"
        ));
    }

    @Test
    void restoresCommentLifecycleAndAuditIntegrityConstraints() {
        String comment = executableStatementContaining(
            "add column status varchar(32) not null default 'ACTIVE'"
        );
        String audit = executableStatementContaining(
            "add column schema_name varchar(128)"
        );

        assertTrue(comment.contains("updated_at datetime(6) not null"));
        assertTrue(comment.contains("uk_approval_comment_tenant_comment"));
        assertTrue(comment.contains("chk_approval_comment_deleted_metadata"));
        assertTrue(audit.contains("schema_name varchar(128) not null"));
        assertTrue(audit.contains("schema_version int not null"));
        assertTrue(audit.contains("tenant_sequence bigint not null"));
        assertTrue(audit.contains("previous_hash varchar(64) not null"));
        assertTrue(audit.contains("payload_hash varchar(64) not null"));
        assertTrue(audit.contains("current_hash varchar(64) not null"));
        assertTrue(audit.contains("uk_audit_event_tenant_sequence"));
        assertTrue(audit.contains("uk_audit_event_tenant_hash"));
    }

    @Test
    void preservesV37RequiredMigrationEvidenceNullability() {
        String attempt = executableStatementContaining(
            "alter table ap_process_migration_attempt\n add column lease_actor"
        );
        String event = executableStatementContaining(
            "alter table ap_process_migration_attempt_event\n"
                + " add column engine_outcome"
        );

        assertTrue(attempt.contains("failure_class varchar(32) not null"));
        assertTrue(event.contains("engine_outcome varchar(32) not null"));
        assertTrue(event.contains("failure_class varchar(32) not null"));
    }

    @Test
    void skipsOnlyTheEmptyHistoricalV32ReleaseBackfill() {
        List<String> skipped = baselineStatements().stream()
            .filter(statement -> MySqlV50Baseline.executableForMySql84(statement).isEmpty())
            .toList();

        assertEquals(3, skipped.size());
        assertTrue(skipped.stream().anyMatch(statement -> statement.contains(
            "create temporary table ap_v32_release_event"
        )));
        assertEquals(
            2,
            skipped.stream().filter(statement -> statement.contains(
                "from ap_v32_release_event"
            )).count()
        );
    }

    @Test
    void executableBaselineExcludesKnownPostgreSqlOnlySyntax() {
        String executable = executableBaseline().toLowerCase();

        assertFalse(executable.contains(" on commit drop"));
        assertFalse(executable.contains("distinct on"));
        assertFalse(executable.contains(" || "));
        assertFalse(executable.contains("interval '3650 days'"));
        assertFalse(executable.contains(
            "drop trigger trg_process_runtime_binding_immutable on"
        ));
        assertTrue(executable.contains(
            "drop trigger if exists trg_process_runtime_binding_immutable"
        ));
        assertTrue(executable.contains(
            "date_add(recorded_at, interval 3650 day)"
        ));
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

    private static List<String> baselineStatements() {
        return MySqlV50Baseline.splitStatements(MySqlV50Baseline.decompressBaseline());
    }

    private static String executableBaseline() {
        return baselineStatements().stream()
            .map(MySqlV50Baseline::executableForMySql84)
            .flatMap(Optional::stream)
            .reduce("", (left, right) -> left + '\n' + right);
    }

    private static String executableStatementContaining(String marker) {
        return baselineStatements().stream()
            .filter(statement -> statement.contains(marker))
            .map(MySqlV50Baseline::executableForMySql84)
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing baseline statement: " + marker));
    }
}
