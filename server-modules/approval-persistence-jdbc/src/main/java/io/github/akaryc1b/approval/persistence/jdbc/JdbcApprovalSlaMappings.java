package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.EscalationTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChange;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChangeSource;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaNotFoundException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

final class JdbcApprovalSlaMappings {

    static MapSqlParameterSource instanceParameters(SlaInstance value) {
        return params(
            "slaInstanceId", value.slaInstanceId(),
            "tenantId", value.tenantId(),
            "approvalInstanceId", value.approvalInstanceId(),
            "taskId", value.taskId(),
            "collaborationParticipantId", value.collaborationParticipantId(),
            "definitionKey", value.definitionKey(),
            "taskDefinitionKey", value.taskDefinitionKey(),
            "targetType", value.targetType().name(),
            "policyId", value.policyId(),
            "policyVersion", value.policyVersion(),
            "calendarId", value.calendarId(),
            "calendarVersion", value.calendarVersion(),
            "timeZone", value.timeZone(),
            "responsibleUserId", value.responsibleUserId(),
            "originalResponsibleUserId", value.originalResponsibleUserId(),
            "startedAt", offset(value.startedAt()),
            "dueAt", offset(value.dueAt()),
            "nextReminderAt", offset(value.nextReminderAt()),
            "overdueAt", offset(value.overdueAt()),
            "pausedAt", offset(value.pausedAt()),
            "pauseReason", value.pauseReason(),
            "accumulatedPausedMillis", value.accumulatedPausedDuration().toMillis(),
            "terminalAt", offset(value.terminalAt()),
            "terminalReason", value.terminalReason() == null ? null : value.terminalReason().name(),
            "status", value.status().name(),
            "lastActionSequence", value.lastActionSequence(),
            "requestId", value.requestId(),
            "traceId", value.traceId(),
            "version", value.version(),
            "createdAt", offset(value.createdAt()),
            "updatedAt", offset(value.updatedAt())
        );
    }

    static MapSqlParameterSource responsibilityChangeParameters(ResponsibilityChange value) {
        return params(
            "responsibilityChangeId", value.responsibilityChangeId(),
            "slaInstanceId", value.slaInstanceId(),
            "tenantId", value.tenantId(),
            "previousResponsibleUserId", value.previousResponsibleUserId(),
            "newResponsibleUserId", value.newResponsibleUserId(),
            "source", value.source().name(),
            "reason", value.reason(),
            "changedBy", value.changedBy(),
            "changedAt", offset(value.changedAt()),
            "requestId", value.requestId(),
            "traceId", value.traceId()
        );
    }

    static RowMapper<CalendarIdentity> calendarIdentityMapper() {
        return (rs, rowNumber) -> new CalendarIdentity(
            rs.getObject("calendar_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getString("calendar_key"),
            rs.getString("display_name"),
            rs.getString("time_zone"),
            CalendarStatus.valueOf(rs.getString("status")),
            nullableInteger(rs, "active_version"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            rs.getLong("version")
        );
    }

    static RowMapper<CalendarVersionRow> calendarVersionRowMapper() {
        return (rs, rowNumber) -> new CalendarVersionRow(
            rs.getObject("calendar_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getInt("calendar_version"),
            rs.getString("time_zone"),
            instant(rs, "effective_from"),
            instant(rs, "effective_to"),
            rs.getString("content_hash"),
            CalendarStatus.valueOf(rs.getString("status")),
            rs.getBoolean("immutable"),
            rs.getString("published_by"),
            instant(rs, "published_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at")
        );
    }

    static RowMapper<SlaPolicyIdentity> policyIdentityMapper() {
        return (rs, rowNumber) -> new SlaPolicyIdentity(
            rs.getObject("policy_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getString("policy_key"),
            rs.getString("display_name"),
            PolicyStatus.valueOf(rs.getString("status")),
            nullableInteger(rs, "active_version"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            rs.getLong("version")
        );
    }

    static RowMapper<SlaPolicyVersion> policyVersionMapper() {
        return (rs, rowNumber) -> new SlaPolicyVersion(
            rs.getObject("policy_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getInt("policy_version"),
            rs.getString("definition_key"),
            nullableInteger(rs, "release_version"),
            rs.getString("task_definition_key"),
            SlaTargetType.valueOf(rs.getString("target_type")),
            SlaDurationMode.valueOf(rs.getString("duration_mode")),
            Duration.ofMillis(rs.getLong("duration_millis")),
            rs.getObject("calendar_id", UUID.class),
            nullableInteger(rs, "calendar_version"),
            nullableDuration(rs, "first_reminder_offset_millis"),
            nullableDuration(rs, "repeat_reminder_interval_millis"),
            rs.getInt("maximum_reminder_count"),
            nullableDuration(rs, "overdue_offset_millis"),
            nullableEscalation(rs.getString("escalation_strategy")),
            rs.getString("escalation_target"),
            AutomaticAction.valueOf(rs.getString("automatic_action_policy")),
            pauseRule(rs.getString("pause_rules_json")),
            rs.getString("content_hash"),
            PolicyStatus.valueOf(rs.getString("status")),
            rs.getBoolean("immutable"),
            rs.getString("published_by"),
            instant(rs, "published_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at")
        );
    }

    static RowMapper<SlaInstance> slaInstanceMapper() {
        return (rs, rowNumber) -> new SlaInstance(
            rs.getObject("sla_instance_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getObject("approval_instance_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("collaboration_participant_id", UUID.class),
            rs.getString("definition_key"),
            rs.getString("task_definition_key"),
            SlaTargetType.valueOf(rs.getString("target_type")),
            rs.getObject("policy_id", UUID.class),
            rs.getInt("policy_version"),
            rs.getObject("calendar_id", UUID.class),
            nullableInteger(rs, "calendar_version"),
            rs.getString("time_zone"),
            rs.getString("responsible_user_id"),
            rs.getString("original_responsible_user_id"),
            instant(rs, "started_at"),
            instant(rs, "due_at"),
            instant(rs, "next_reminder_at"),
            instant(rs, "overdue_at"),
            instant(rs, "paused_at"),
            rs.getString("pause_reason"),
            Duration.ofMillis(rs.getLong("accumulated_paused_millis")),
            instant(rs, "terminal_at"),
            nullableTerminalReason(rs.getString("terminal_reason")),
            SlaStatus.valueOf(rs.getString("status")),
            rs.getLong("last_action_sequence"),
            rs.getString("request_id"),
            rs.getString("trace_id"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at")
        );
    }

    static RowMapper<ResponsibilityChange> responsibilityChangeMapper() {
        return (rs, rowNumber) -> new ResponsibilityChange(
            rs.getObject("responsibility_change_id", UUID.class),
            rs.getObject("sla_instance_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getString("previous_responsible_user_id"),
            rs.getString("new_responsible_user_id"),
            ResponsibilityChangeSource.valueOf(rs.getString("source")),
            rs.getString("reason"),
            rs.getString("changed_by"),
            instant(rs, "changed_at"),
            rs.getString("request_id"),
            rs.getString("trace_id")
        );
    }

    static LocalDate localDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        throw new IllegalStateException("database date value is unsupported");
    }

    static java.time.LocalTime localTime(Object value) {
        if (value instanceof java.time.LocalTime time) {
            return time;
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        throw new IllegalStateException("database time value is unsupported");
    }

    static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(Objects.toString(value));
    }

    static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Duration nullableDuration(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Duration.ofMillis(value);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    static Long millis(Duration value) {
        return value == null ? null : value.toMillis();
    }

    static EscalationTargetType nullableEscalation(String value) {
        return value == null ? null : EscalationTargetType.valueOf(value);
    }

    static SlaTerminalReason nullableTerminalReason(String value) {
        return value == null ? null : SlaTerminalReason.valueOf(value);
    }

    static boolean pauseRule(String json) {
        return json != null && json.contains("true");
    }

    static MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) {
            parameters.addValue(Objects.toString(values[index]), values[index + 1]);
        }
        return parameters;
    }

    static void page(int limit, int offset) {
        if (limit < 1 || limit > 500 || offset < 0) {
            throw new IllegalArgumentException("pagination values are invalid");
        }
    }

    static SlaConflictException conflict(String code, String message) {
        return new SlaConflictException(code, message);
    }

    static SlaNotFoundException notFound(String code, String message) {
        return new SlaNotFoundException(code, message);
    }

    static <T> T required(T value) {
        return Objects.requireNonNull(value, "transaction result must not be null");
    }

    private JdbcApprovalSlaMappings() {
    }
}

record CalendarVersionRow(
    UUID calendarId,
    String tenantId,
    int calendarVersion,
    String timeZone,
    Instant effectiveFrom,
    Instant effectiveTo,
    String contentHash,
    CalendarStatus status,
    boolean immutable,
    String publishedBy,
    Instant publishedAt,
    Instant createdAt,
    Instant updatedAt
) {
    MapSqlParameterSource identity() {
        return JdbcApprovalSlaMappings.params(
            "tenantId", tenantId,
            "calendarId", calendarId,
            "calendarVersion", calendarVersion
        );
    }

    CalendarVersion toVersion(CalendarSnapshot snapshot) {
        return new CalendarVersion(
            calendarId,
            tenantId,
            calendarVersion,
            effectiveFrom,
            effectiveTo,
            snapshot,
            status,
            immutable,
            publishedBy,
            publishedAt,
            createdAt,
            updatedAt
        );
    }
}
