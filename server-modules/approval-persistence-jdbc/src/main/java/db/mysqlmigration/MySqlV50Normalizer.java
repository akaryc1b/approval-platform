package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;

/** Applies reviewed PostgreSQL-to-MySQL clean-baseline translations. */
final class MySqlV50Normalizer {

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
    private static final Pattern HISTORICAL_COMMENT_PARENT_FOREIGN_KEY_DROP =
        Pattern.compile(
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

    private MySqlV50Normalizer() {
    }

    static Optional<String> executable(String command) {
        if (isHistoricalBackfill(command)) {
            return Optional.empty();
        }
        return Optional.of(normalize(command));
    }

    static boolean isHistoricalBackfill(String command) {
        return V32_TEMPORARY_RELEASE_EVENT.matcher(command).find()
            || V32_RELEASE_EVENT_BACKFILL.matcher(command).find();
    }

    static String normalize(String command) {
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
        normalized = normalizeSchemaUniqueConstraintNames(normalized);
        normalized = MySqlV50RelationalNormalizer.normalize(normalized);
        normalized = MySqlV50EvidenceNormalizer.normalize(normalized);
        normalized = PROCESS_RUNTIME_BINDING_TRIGGER_DROP.matcher(normalized)
            .replaceAll(
                "drop trigger if exists trg_process_runtime_binding_immutable"
            );
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

    private static String normalizeSchemaUniqueConstraintNames(String command) {
        if (!createsTable(command, "ap_sla_policy_version")) {
            return command;
        }
        return requireReplace(
            command,
            "constraint chk_sla_policy_timestamps check",
            "constraint chk_sla_policy_version_timestamps check",
            "SLA policy version timestamp check name"
        );
    }

    static boolean createsTable(String command, String tableName) {
        String normalized = command.stripLeading();
        return normalized.startsWith("create table " + tableName + " (")
            || normalized.startsWith(
                "create table if not exists " + tableName + " ("
            );
    }

    static String requireReplace(
        String command,
        String expected,
        String replacement,
        String boundary
    ) {
        if (!command.contains(expected)) {
            throw new FlywayException("MySQL baseline drift at " + boundary);
        }
        return command.replace(expected, replacement);
    }

    static boolean isIgnorableHistoricalForeignKeyDrop(
        String command,
        SQLException exception
    ) {
        return exception.getErrorCode() == MISSING_OBJECT_ERROR_CODE
            && HISTORICAL_COMMENT_PARENT_FOREIGN_KEY_DROP.matcher(command).find();
    }
}
