package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DayOverride;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.CalendarVersionRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.calendarIdentityMapper;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.calendarVersionRowMapper;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.localDate;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.localTime;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.millis;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.offset;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.params;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.policyIdentityMapper;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.slaInstanceMapper;

abstract class JdbcApprovalSlaStoreSupport {

    protected final NamedParameterJdbcTemplate jdbc;
    protected final TransactionTemplate transactions;

    JdbcApprovalSlaStoreSupport(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.transactions = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    protected Optional<CalendarVersion> loadCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion
    ) {
        List<CalendarVersionRow> rows = jdbc.query(
            """
            select * from ap_work_calendar_version
            where tenant_id=:tenantId and calendar_id=:calendarId and calendar_version=:calendarVersion
            """,
            params(
                "tenantId", tenantId,
                "calendarId", calendarId,
                "calendarVersion", calendarVersion
            ),
            calendarVersionRowMapper()
        );
        return rows.stream().findFirst().map(row -> row.toVersion(loadCalendarSnapshot(row)));
    }


    protected void persistCalendarContent(CalendarVersion version) {
        for (Map.Entry<DayOfWeek, List<WorkingInterval>> entry
            : version.snapshot().weeklySchedule().entrySet()) {
            int sequence = 0;
            for (WorkingInterval interval : entry.getValue()) {
                insertInterval(version, "WEEKLY", entry.getKey().getValue(), null, ++sequence, interval);
            }
        }
        for (Map.Entry<LocalDate, DayOverride> entry : version.snapshot().overrides().entrySet()) {
            String type = entry.getValue().working() ? "WORKING" : "HOLIDAY";
            int inserted = jdbc.update(
                """
                insert into ap_work_calendar_date_override (
                    tenant_id,calendar_id,calendar_version,calendar_date,override_type
                ) values (:tenantId,:calendarId,:calendarVersion,:calendarDate,:overrideType)
                """,
                calendarVersionIdentity(version)
                    .addValue("calendarDate", entry.getKey())
                    .addValue("overrideType", type)
            );
            if (inserted != 1) {
                throw new IllegalStateException("calendar override was not inserted");
            }
            int sequence = 0;
            for (WorkingInterval interval : entry.getValue().intervals()) {
                insertInterval(version, "DATE_OVERRIDE", null, entry.getKey(), ++sequence, interval);
            }
        }
    }

    protected void insertInterval(
        CalendarVersion version,
        String scope,
        Integer dayOfWeek,
        LocalDate date,
        int sequence,
        WorkingInterval interval
    ) {
        int inserted = jdbc.update(
            """
            insert into ap_work_calendar_interval (
                interval_id,tenant_id,calendar_id,calendar_version,scope_type,day_of_week,
                calendar_date,sequence_no,start_time,end_time
            ) values (
                :intervalId,:tenantId,:calendarId,:calendarVersion,:scopeType,:dayOfWeek,
                :calendarDate,:sequenceNo,:startTime,:endTime
            )
            """,
            calendarVersionIdentity(version)
                .addValue("intervalId", UUID.randomUUID())
                .addValue("scopeType", scope)
                .addValue("dayOfWeek", dayOfWeek)
                .addValue("calendarDate", date)
                .addValue("sequenceNo", sequence)
                .addValue("startTime", interval.start())
                .addValue("endTime", interval.end())
        );
        if (inserted != 1) {
            throw new IllegalStateException("calendar interval was not inserted");
        }
    }

    protected CalendarSnapshot loadCalendarSnapshot(CalendarVersionRow row) {
        Map<DayOfWeek, List<WorkingInterval>> weekly = new EnumMap<>(DayOfWeek.class);
        Map<LocalDate, String> overrideTypes = new java.util.LinkedHashMap<>();
        Map<LocalDate, DayOverride> overrides = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> overrideRows = jdbc.queryForList(
            """
            select calendar_date,override_type from ap_work_calendar_date_override
            where tenant_id=:tenantId and calendar_id=:calendarId and calendar_version=:calendarVersion
            order by calendar_date
            """,
            row.identity()
        );
        for (Map<String, Object> override : overrideRows) {
            LocalDate date = localDate(override.get("calendar_date"));
            overrideTypes.put(date, Objects.toString(override.get("override_type")));
        }
        List<Map<String, Object>> intervalRows = jdbc.queryForList(
            """
            select scope_type,day_of_week,calendar_date,start_time,end_time
            from ap_work_calendar_interval
            where tenant_id=:tenantId and calendar_id=:calendarId and calendar_version=:calendarVersion
            order by scope_type,day_of_week,calendar_date,sequence_no
            """,
            row.identity()
        );
        Map<LocalDate, List<WorkingInterval>> dateIntervals = new java.util.LinkedHashMap<>();
        for (Map<String, Object> interval : intervalRows) {
            WorkingInterval value = new WorkingInterval(
                localTime(interval.get("start_time")),
                localTime(interval.get("end_time"))
            );
            if (Objects.equals(interval.get("scope_type"), "WEEKLY")) {
                DayOfWeek day = DayOfWeek.of(((Number) interval.get("day_of_week")).intValue());
                weekly.computeIfAbsent(day, ignored -> new ArrayList<>()).add(value);
            } else {
                LocalDate date = localDate(interval.get("calendar_date"));
                dateIntervals.computeIfAbsent(date, ignored -> new ArrayList<>()).add(value);
            }
        }
        for (Map.Entry<LocalDate, String> entry : overrideTypes.entrySet()) {
            List<WorkingInterval> intervals = dateIntervals.getOrDefault(entry.getKey(), List.of());
            boolean working = entry.getValue().equals("WORKING")
                || entry.getValue().equals("COMPENSATORY_WORKING");
            overrides.put(entry.getKey(), working
                ? DayOverride.workingDay(intervals)
                : DayOverride.holiday());
        }
        return CalendarSnapshot.of(
            row.calendarId(),
            row.tenantId(),
            row.calendarVersion(),
            row.timeZone(),
            weekly,
            overrides,
            row.contentHash()
        );
    }

    protected SlaInstancePage boundedPage(
        String condition,
        MapSqlParameterSource parameters,
        int limit,
        int offsetValue
    ) {
        page(limit, offsetValue);
        long total = count(
            "select count(*) from ap_sla_instance where tenant_id=:tenantId and " + condition,
            parameters
        );
        parameters.addValue("limit", limit).addValue("offset", offsetValue);
        List<SlaInstance> items = queryInstances(
            "select * from ap_sla_instance where tenant_id=:tenantId and " + condition
                + " order by due_at,sla_instance_id limit :limit offset :offset",
            parameters
        );
        return new SlaInstancePage(items, total, limit, offsetValue);
    }

    protected int terminal(
        String targetCondition,
        MapSqlParameterSource parameters,
        SlaTerminalReason reason,
        Instant terminalAt
    ) {
        parameters.addValue("reason", reason.name()).addValue("terminalAt", offset(terminalAt));
        return jdbc.update(
            """
            update ap_sla_instance set status='TERMINAL',terminal_at=:terminalAt,
                terminal_reason=:reason,paused_at=null,pause_reason=null,
                updated_at=:terminalAt,version=version+1
            where tenant_id=:tenantId and status in ('ACTIVE','PAUSED') and
            """ + targetCondition,
            parameters
        );
    }

    protected CalendarIdentity lockCalendar(String tenantId, UUID calendarId) {
        return jdbc.query(
            """
            select * from ap_work_calendar
            where tenant_id=:tenantId and calendar_id=:calendarId for update
            """,
            params("tenantId", tenantId, "calendarId", calendarId),
            calendarIdentityMapper()
        ).stream().findFirst().orElseThrow(() ->
            notFound("APPROVAL_CALENDAR_NOT_FOUND", "calendar was not found")
        );
    }

    protected SlaPolicyIdentity lockPolicy(String tenantId, UUID policyId) {
        return jdbc.query(
            "select * from ap_sla_policy where tenant_id=:tenantId and policy_id=:policyId for update",
            params("tenantId", tenantId, "policyId", policyId),
            policyIdentityMapper()
        ).stream().findFirst().orElseThrow(() ->
            notFound("APPROVAL_SLA_POLICY_NOT_FOUND", "policy was not found")
        );
    }

    protected void updateCalendarVersion(CalendarIdentity identity, Instant updatedAt) {
        int changed = jdbc.update(
            """
            update ap_work_calendar set updated_at=:updatedAt,version=version+1
            where tenant_id=:tenantId and calendar_id=:calendarId and version=:expectedVersion
            """,
            params(
                "tenantId", identity.tenantId(),
                "calendarId", identity.calendarId(),
                "expectedVersion", identity.version(),
                "updatedAt", offset(updatedAt)
            )
        );
        if (changed != 1) {
            throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar identity changed concurrently");
        }
    }

    protected void updatePolicyVersion(SlaPolicyIdentity identity, Instant updatedAt) {
        int changed = jdbc.update(
            """
            update ap_sla_policy set updated_at=:updatedAt,version=version+1
            where tenant_id=:tenantId and policy_id=:policyId and version=:expectedVersion
            """,
            params(
                "tenantId", identity.tenantId(),
                "policyId", identity.policyId(),
                "expectedVersion", identity.version(),
                "updatedAt", offset(updatedAt)
            )
        );
        if (changed != 1) {
            throw conflict("APPROVAL_SLA_POLICY_VERSION_CONFLICT", "policy identity changed concurrently");
        }
    }

    protected void advisoryLock(String key) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:key,0))",
            params("key", key),
            resultSet -> null
        );
    }

    protected static String policyTargetLock(SlaPolicyVersion policy) {
        return "sla-policy-active:" + policy.tenantId() + ':' + policy.definitionKey() + ':'
            + policy.releaseVersion() + ':' + policy.taskDefinitionKey() + ':' + policy.targetType();
    }

    protected List<SlaInstance> queryInstances(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, slaInstanceMapper());
    }

    protected long count(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0 : value;
    }

    protected static MapSqlParameterSource calendarIdentityParameters(CalendarIdentity value) {
        return params(
            "calendarId", value.calendarId(),
            "tenantId", value.tenantId(),
            "calendarKey", value.calendarKey(),
            "displayName", value.displayName(),
            "timeZone", value.timeZone(),
            "status", value.status().name(),
            "activeVersion", value.activeVersion(),
            "createdBy", value.createdBy(),
            "createdAt", offset(value.createdAt()),
            "updatedAt", offset(value.updatedAt()),
            "version", value.version()
        );
    }

    protected static MapSqlParameterSource calendarVersionIdentity(CalendarVersion value) {
        return params(
            "calendarId", value.calendarId(),
            "tenantId", value.tenantId(),
            "calendarVersion", value.calendarVersion()
        );
    }

    protected static MapSqlParameterSource calendarVersionParameters(CalendarVersion value) {
        return calendarVersionIdentity(value)
            .addValue("timeZone", value.snapshot().zoneId().getId())
            .addValue("effectiveFrom", offset(value.effectiveFrom()))
            .addValue("effectiveTo", offset(value.effectiveTo()))
            .addValue("contentHash", value.snapshot().contentHash())
            .addValue("createdAt", offset(value.createdAt()))
            .addValue("updatedAt", offset(value.updatedAt()));
    }

    protected static MapSqlParameterSource policyIdentityParameters(SlaPolicyIdentity value) {
        return params(
            "policyId", value.policyId(),
            "tenantId", value.tenantId(),
            "policyKey", value.policyKey(),
            "displayName", value.displayName(),
            "status", value.status().name(),
            "activeVersion", value.activeVersion(),
            "createdBy", value.createdBy(),
            "createdAt", offset(value.createdAt()),
            "updatedAt", offset(value.updatedAt()),
            "version", value.version()
        );
    }

    protected MapSqlParameterSource policyVersionParameters(SlaPolicyVersion value) {
        String calendarHash = null;
        String timeZone = "UTC";
        if (value.durationMode() == SlaDurationMode.WORKING_TIME) {
            CalendarVersion calendar = loadCalendarVersion(
                value.tenantId(), value.calendarId(), value.calendarVersion()
            ).orElseThrow(() -> notFound(
                "APPROVAL_CALENDAR_NOT_FOUND", "calendar version was not found"
            ));
            if (!calendar.immutable()) {
                throw conflict(
                    "APPROVAL_SLA_POLICY_VERSION_CONFLICT",
                    "working-time policy requires a published calendar version"
                );
            }
            calendarHash = calendar.snapshot().contentHash();
            timeZone = calendar.snapshot().zoneId().getId();
        }
        return params(
            "policyId", value.policyId(),
            "tenantId", value.tenantId(),
            "policyVersion", value.policyVersion(),
            "definitionKey", value.definitionKey(),
            "releaseVersion", value.releaseVersion(),
            "taskDefinitionKey", value.taskDefinitionKey(),
            "targetType", value.targetType().name(),
            "durationMode", value.durationMode().name(),
            "durationMillis", value.duration().toMillis(),
            "calendarId", value.calendarId(),
            "calendarVersion", value.calendarVersion(),
            "calendarContentHash", calendarHash,
            "timeZone", timeZone,
            "firstReminderOffsetMillis", millis(value.firstReminderOffset()),
            "repeatReminderIntervalMillis", millis(value.repeatReminderInterval()),
            "maximumReminderCount", value.maximumReminderCount(),
            "overdueOffsetMillis", millis(value.overdueOffset()),
            "escalationStrategy", value.escalationTargetType() == null
                ? null : value.escalationTargetType().name(),
            "escalationTarget", value.escalationTarget(),
            "automaticActionPolicy", value.automaticAction().name(),
            "pauseRulesJson", "{\"naturalTimePauses\":" + value.naturalTimePauses() + "}",
            "contentHash", value.contentHash(),
            "createdAt", offset(value.createdAt()),
            "updatedAt", offset(value.updatedAt())
        );
    }

}
