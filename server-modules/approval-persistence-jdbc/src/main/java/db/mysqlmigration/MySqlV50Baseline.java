package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** MySQL 8.4 current-schema baseline. PostgreSQL V1-V50 remain immutable and separate. */
public final class MySqlV50Baseline implements JavaMigration {

    private static final MigrationVersion VERSION = MigrationVersion.fromVersion("50");
    private static final String DESCRIPTION = "Baseline approval platform";
    private static final int BASELINE_CHECKSUM = -392744554;
    private static final int MISSING_OBJECT_ERROR_CODE = 1091;
    private static final Pattern ADD_COLUMN_IF_NOT_EXISTS = Pattern.compile(
        "\\badd\\s+column\\s+if\\s+not\\s+exists\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CREATE_UNIQUE_INDEX_IF_NOT_EXISTS = Pattern.compile(
        "\\bcreate\\s+unique\\s+index\\s+if\\s+not\\s+exists\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CREATE_INDEX_IF_NOT_EXISTS = Pattern.compile(
        "\\bcreate\\s+index\\s+if\\s+not\\s+exists\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ADD_CONSTRAINT_IF_NOT_EXISTS = Pattern.compile(
        "\\badd\\s+constraint\\s+if\\s+not\\s+exists\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern V32_TEMPORARY_RELEASE_EVENT = Pattern.compile(
        "^\\s*create\\s+temporary\\s+table\\s+ap_v32_release_event\\b",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern V32_RELEASE_EVENT_BACKFILL = Pattern.compile(
        "^\\s*with\\s+ranked_release_events\\b.*\\bfrom\\s+ap_v32_release_event\\b",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern NOTIFICATION_INTENT_TABLE = Pattern.compile(
        "^\\s*create\\s+table\\s+ap_notification_intent\\b",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern NOTIFICATION_BUSINESS_EVENT_COLUMN = Pattern.compile(
        "(\\bbusiness_event_key\\s+varchar\\(512\\)\\s+not\\s+null\\s*,)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NOTIFICATION_DEDUPLICATION_UNIQUE = Pattern.compile(
        "(constraint\\s+uk_notification_business_recipient_channel\\s+)"
            + "unique\\s*\\(\\s*tenant_id\\s*,\\s*business_event_key\\s*,"
            + "\\s*recipient_id\\s*,\\s*channel\\s*\\)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern CONSISTENCY_FINDING_AGGREGATE_INDEX = Pattern.compile(
        "^\\s*create\\s+index\\s+idx_consistency_finding_aggregate\\b",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern CONSISTENCY_FINDING_AGGREGATE_COLUMN = Pattern.compile(
        "(\\baggregate_type\\s*,\\s*)aggregate_id(\\s*,\\s*detected_at\\s+desc)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern PROCESS_RUNTIME_BINDING_TRIGGER_DROP = Pattern.compile(
        "^\\s*drop\\s+trigger\\s+trg_process_runtime_binding_immutable"
            + "\\s+on\\s+ap_process_runtime_binding\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ASSISTANCE_RETENTION_INTERVAL = Pattern.compile(
        "recorded_at\\s*\\+\\s*interval\\s+'3650 days'",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HISTORICAL_COMMENT_PARENT_FOREIGN_KEY_DROP = Pattern.compile(
        "\\balter\\s+table\\s+ap_approval_comment\\s+drop\\s+foreign\\s+key\\s+"
            + "ap_approval_comment_parent_fk\\b",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final String NOTIFICATION_DEDUPLICATION_HASH = """
        $1
            business_recipient_channel_hash binary(32)
                generated always as (
                    unhex(sha2(concat(
                        hex(tenant_id), ':',
                        hex(business_event_key), ':',
                        hex(recipient_id), ':',
                        hex(channel)
                    ), 256))
                ) stored,
        """;
    private static final List<String> BASELINE_RESOURCES = List.of(
        "db/mysqlmigration/baseline-001.b64",
        "db/mysqlmigration/baseline-002.b64",
        "db/mysqlmigration/baseline-003.b64",
        "db/mysqlmigration/baseline-004.b64",
        "db/mysqlmigration/baseline-005.b64",
        "db/mysqlmigration/baseline-006.b64",
        "db/mysqlmigration/baseline-007.b64",
        "db/mysqlmigration/baseline-008.b64",
        "db/mysqlmigration/baseline-009.b64"
    );

    @Override
    public MigrationVersion getVersion() {
        return VERSION;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Integer getChecksum() {
        return BASELINE_CHECKSUM;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        List<String> statements = splitStatements(decompressBaseline());
        int index = 0;
        try (Statement statement = context.getConnection().createStatement()) {
            for (String command : statements) {
                index++;
                Optional<String> executable = executableForMySql84(command);
                if (executable.isEmpty()) {
                    continue;
                }
                try {
                    statement.execute(executable.orElseThrow());
                } catch (SQLException exception) {
                    if (!isIgnorableCleanBaselineForeignKeyDrop(
                        executable.orElseThrow(),
                        exception
                    )) {
                        throw exception;
                    }
                }
            }
        } catch (SQLException exception) {
            throw new FlywayException(
                "MySQL 8.4 baseline statement " + index + " failed",
                exception
            );
        }
    }

    static Optional<String> executableForMySql84(String command) {
        if (isCleanBaselineOnlyHistoricalBackfill(command)) {
            return Optional.empty();
        }
        return Optional.of(normalizeForMySql84(command));
    }

    static boolean isCleanBaselineOnlyHistoricalBackfill(String command) {
        return V32_TEMPORARY_RELEASE_EVENT.matcher(command).find()
            || V32_RELEASE_EVENT_BACKFILL.matcher(command).find();
    }

    static String normalizeForMySql84(String command) {
        String normalized = ADD_COLUMN_IF_NOT_EXISTS.matcher(command)
            .replaceAll("add column");
        normalized = CREATE_UNIQUE_INDEX_IF_NOT_EXISTS.matcher(normalized)
            .replaceAll("create unique index");
        normalized = CREATE_INDEX_IF_NOT_EXISTS.matcher(normalized)
            .replaceAll("create index");
        normalized = ADD_CONSTRAINT_IF_NOT_EXISTS.matcher(normalized)
            .replaceAll("add constraint");
        normalized = normalizeNotificationDeduplication(normalized);
        normalized = normalizeConsistencyFindingAggregateIndex(normalized);
        normalized = PROCESS_RUNTIME_BINDING_TRIGGER_DROP.matcher(normalized)
            .replaceAll("drop trigger if exists trg_process_runtime_binding_immutable");
        return ASSISTANCE_RETENTION_INTERVAL.matcher(normalized)
            .replaceAll("date_add(recorded_at, interval 3650 day)");
    }

    private static String normalizeNotificationDeduplication(String command) {
        if (!NOTIFICATION_INTENT_TABLE.matcher(command).find()) {
            return command;
        }
        String normalized = NOTIFICATION_BUSINESS_EVENT_COLUMN.matcher(command)
            .replaceFirst(NOTIFICATION_DEDUPLICATION_HASH);
        return NOTIFICATION_DEDUPLICATION_UNIQUE.matcher(normalized)
            .replaceFirst("$1unique (business_recipient_channel_hash)");
    }

    private static String normalizeConsistencyFindingAggregateIndex(String command) {
        if (!CONSISTENCY_FINDING_AGGREGATE_INDEX.matcher(command).find()) {
            return command;
        }
        return CONSISTENCY_FINDING_AGGREGATE_COLUMN.matcher(command)
            .replaceFirst("$1aggregate_id(500)$2");
    }

    static boolean isIgnorableCleanBaselineForeignKeyDrop(
        String command,
        SQLException exception
    ) {
        return exception.getErrorCode() == MISSING_OBJECT_ERROR_CODE
            && HISTORICAL_COMMENT_PARENT_FOREIGN_KEY_DROP.matcher(command).find();
    }

    static String decompressBaseline() {
        StringBuilder encoded = new StringBuilder();
        ClassLoader loader = MySqlV50Baseline.class.getClassLoader();
        for (String resource : BASELINE_RESOURCES) {
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new FlywayException("missing MySQL baseline resource: " + resource);
                }
                encoded.append(new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip());
            } catch (IOException exception) {
                throw new FlywayException("MySQL baseline resource read failed", exception);
            }
        }
        byte[] compressed = Base64.getDecoder().decode(encoded.toString());
        try (GZIPInputStream input = new GZIPInputStream(
            new ByteArrayInputStream(compressed)
        ); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new FlywayException("MySQL baseline decompression failed", exception);
        }
    }

    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < script.length(); index++) {
            char value = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            if (lineComment) {
                if (value == '\n') {
                    lineComment = false;
                    current.append(value);
                }
                continue;
            }
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (!quoted && value == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (!quoted && value == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (value == '\'') {
                current.append(value);
                if (quoted && next == '\'') {
                    current.append(next);
                    index++;
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (!quoted && value == ';') {
                String command = current.toString().strip();
                if (!command.isEmpty()) {
                    statements.add(command);
                }
                current.setLength(0);
                continue;
            }
            current.append(value);
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return List.copyOf(statements);
    }
}
