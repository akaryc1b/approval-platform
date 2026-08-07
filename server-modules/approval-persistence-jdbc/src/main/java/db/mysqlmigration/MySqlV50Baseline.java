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
    private static final int BASELINE_CHECKSUM = -392744555;
    private static final int MISSING_OBJECT_ERROR_CODE = 1091;
    private static final String TABLE_OPTIONS_MARKER = "\n) ENGINE=InnoDB";
    private static final String REQUIRED_INSTANCE_REFERENCE =
        "instance_id varchar(36) not null references ap_approval_instance(instance_id)";
    private static final String OPTIONAL_INSTANCE_REFERENCE =
        "instance_id varchar(36) references ap_approval_instance(instance_id)";
    private static final String OPTIONAL_TASK_REFERENCE =
        "task_id varchar(36) references ap_approval_task(task_id)";
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
        normalized = normalizeEnforcedForeignKeys(normalized);
        normalized = normalizeCommentLifecycle(normalized);
        normalized = normalizeAuditIntegrity(normalized);
        normalized = normalizeMigrationExecutionNullability(normalized);
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

    private static String normalizeEnforcedForeignKeys(String command) {
        if (createsTable(command, "ap_approval_task")) {
            String normalized = requireReplace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "approval task instance reference"
            );
            return appendTableConstraints(normalized, """
                    constraint fk_approval_task_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        if (createsTable(command, "ap_approval_message")) {
            String normalized = requireReplace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "approval message instance reference"
            );
            normalized = requireReplace(
                normalized,
                OPTIONAL_TASK_REFERENCE,
                "task_id varchar(36)",
                "approval message task reference"
            );
            return appendTableConstraints(normalized, """
                    constraint fk_approval_message_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id),
                    constraint fk_approval_message_task
                        foreign key (task_id)
                        references ap_approval_task (task_id)
                """);
        }
        if (createsTable(command, "ap_approval_comment")) {
            String normalized = requireReplace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "approval comment instance reference"
            );
            return appendTableConstraints(normalized, """
                    constraint fk_approval_comment_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        if (createsTable(command, "ap_approval_attachment")) {
            String normalized = requireReplace(
                command,
                OPTIONAL_INSTANCE_REFERENCE,
                "instance_id varchar(36)",
                "approval attachment instance reference"
            );
            return appendTableConstraints(normalized, """
                    constraint fk_approval_attachment_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        if (createsTable(command, "ap_form_submission")) {
            String normalized = requireReplace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "form submission instance reference"
            );
            return appendTableConstraints(normalized, """
                    constraint fk_form_submission_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        if (createsTable(command, "ap_form_submission_revision")) {
            String normalized = requireReplace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "form revision instance reference"
            );
            return appendTableConstraints(normalized, """
                    constraint fk_form_submission_revision_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        return command;
    }

    private static String normalizeCommentLifecycle(String command) {
        if (!command.stripLeading().startsWith(
            "alter table ap_approval_comment\n    add column status"
        )) {
            return command;
        }
        String normalized = requireReplace(
            command,
            "add column updated_at datetime(6),",
            "add column updated_at datetime(6) not null,",
            "comment updated-at nullability"
        );
        return requireReplace(
            normalized,
            "add column version bigint not null default 1",
            """
            add column version bigint not null default 1,
                add constraint uk_approval_comment_tenant_comment
                    unique (tenant_id, comment_id),
                add constraint chk_approval_comment_status
                    check (status in ('ACTIVE', 'DELETED')),
                add constraint chk_approval_comment_visibility
                    check (visibility in ('PARTICIPANTS', 'MENTIONED_ONLY')),
                add constraint chk_approval_comment_revision
                    check (current_revision > 0),
                add constraint chk_approval_comment_version
                    check (version > 0),
                add constraint chk_approval_comment_deleted_metadata
                    check (
                        (
                            status = 'ACTIVE'
                            and deleted_at is null
                            and deleted_by is null
                            and delete_reason is null
                        )
                        or (
                            status = 'DELETED'
                            and deleted_at is not null
                            and deleted_by is not null
                            and delete_reason is not null
                        )
                    )
            """.strip(),
            "comment lifecycle constraints"
        );
    }

    private static String normalizeAuditIntegrity(String command) {
        if (!command.stripLeading().startsWith(
            "alter table ap_audit_event\n    add column schema_name"
        )) {
            return command;
        }
        String normalized = requireReplace(
            command,
            "add column schema_name varchar(128),",
            "add column schema_name varchar(128) not null,",
            "audit schema name nullability"
        );
        normalized = requireReplace(
            normalized,
            "add column schema_version int,",
            "add column schema_version int not null,",
            "audit schema version nullability"
        );
        normalized = requireReplace(
            normalized,
            "add column tenant_sequence bigint,",
            "add column tenant_sequence bigint not null,",
            "audit sequence nullability"
        );
        normalized = requireReplace(
            normalized,
            "add column previous_hash varchar(64),",
            "add column previous_hash varchar(64) not null,",
            "audit previous hash nullability"
        );
        normalized = requireReplace(
            normalized,
            "add column payload_hash varchar(64),",
            "add column payload_hash varchar(64) not null,",
            "audit payload hash nullability"
        );
        return requireReplace(
            normalized,
            "add column current_hash varchar(64)",
            """
            add column current_hash varchar(64) not null,
                add constraint chk_audit_event_schema_version
                    check (schema_version >= 0),
                add constraint chk_audit_event_tenant_sequence
                    check (tenant_sequence > 0),
                add constraint chk_audit_event_previous_hash
                    check (regexp_like(previous_hash, '^[0-9a-f]{64}$', 'c')),
                add constraint chk_audit_event_payload_hash
                    check (regexp_like(payload_hash, '^[0-9a-f]{64}$', 'c')),
                add constraint chk_audit_event_current_hash
                    check (regexp_like(current_hash, '^[0-9a-f]{64}$', 'c')),
                add constraint uk_audit_event_tenant_sequence
                    unique (tenant_id, tenant_sequence),
                add constraint uk_audit_event_tenant_hash
                    unique (tenant_id, current_hash)
            """.strip(),
            "audit integrity constraints"
        );
    }

    private static String normalizeMigrationExecutionNullability(String command) {
        if (command.stripLeading().startsWith(
            "alter table ap_process_migration_attempt\n add column lease_actor"
        )) {
            return requireReplace(
                command,
                "add column failure_class varchar(32),",
                "add column failure_class varchar(32) not null,",
                "migration attempt failure class nullability"
            );
        }
        if (command.stripLeading().startsWith(
            "alter table ap_process_migration_attempt_event\n add column engine_outcome"
        )) {
            String normalized = requireReplace(
                command,
                "add column engine_outcome varchar(32),",
                "add column engine_outcome varchar(32) not null,",
                "migration event outcome nullability"
            );
            return requireReplace(
                normalized,
                "add column failure_class varchar(32),",
                "add column failure_class varchar(32) not null,",
                "migration event failure class nullability"
            );
        }
        return command;
    }

    private static boolean createsTable(String command, String tableName) {
        String normalized = command.stripLeading();
        return normalized.startsWith("create table " + tableName + " (")
            || normalized.startsWith("create table if not exists " + tableName + " (");
    }

    private static String appendTableConstraints(String command, String constraints) {
        int marker = command.lastIndexOf(TABLE_OPTIONS_MARKER);
        if (marker < 0) {
            throw new FlywayException(
                "MySQL baseline table options marker is missing for governed foreign key"
            );
        }
        return command.substring(0, marker)
            + ",\n"
            + constraints.stripTrailing()
            + command.substring(marker);
    }

    private static String requireReplace(
        String command,
        String expected,
        String replacement,
        String boundary
    ) {
        if (!command.contains(expected)) {
            throw new FlywayException(
                "MySQL baseline drift at " + boundary
            );
        }
        return command.replace(expected, replacement);
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
