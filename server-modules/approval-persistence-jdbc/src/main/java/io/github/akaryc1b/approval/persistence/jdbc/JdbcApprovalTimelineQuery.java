package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL timeline read model backed by platform projections and immutable audit events.
 */
public final class JdbcApprovalTimelineQuery implements ApprovalTimelineQuery {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalTimelineQuery(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public Optional<ApprovalTimeline> findTimeline(TimelineIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", identity.tenantId())
            .addValue("operatorId", identity.operatorId())
            .addValue("instanceId", identity.instanceId())
            .addValue("instanceIdText", identity.instanceId().toString());

        Boolean authorized = jdbc.queryForObject(
            """
            select exists (
                select 1
                from ap_approval_instance instance
                where instance.tenant_id = :tenantId
                  and instance.instance_id = :instanceId
                  and (
                      instance.initiator_id = :operatorId
                      or exists (
                          select 1
                          from ap_approval_task task
                          where task.tenant_id = instance.tenant_id
                            and task.instance_id = instance.instance_id
                            and task.assignee_id = :operatorId
                      )
                      or exists (
                          select 1
                          from ap_approval_message message
                          where message.tenant_id = instance.tenant_id
                            and message.instance_id = instance.instance_id
                            and message.recipient_id = :operatorId
                      )
                      or exists (
                          select 1
                          from ap_audit_event event
                          where event.tenant_id = instance.tenant_id
                            and event.operator_id = :operatorId
                            and (
                                (
                                    event.aggregate_type = 'APPROVAL_INSTANCE'
                                    and event.aggregate_id = instance.instance_id::text
                                )
                                or (
                                    event.aggregate_type = 'APPROVAL_TASK'
                                    and exists (
                                        select 1
                                        from ap_approval_task historical_task
                                        where historical_task.tenant_id = event.tenant_id
                                          and historical_task.instance_id = instance.instance_id
                                          and (
                                              historical_task.task_id::text = event.aggregate_id
                                              or historical_task.engine_task_id = event.aggregate_id
                                          )
                                    )
                                )
                            )
                      )
                  )
            )
            """,
            parameters,
            Boolean.class
        );
        if (!Boolean.TRUE.equals(authorized)) {
            return Optional.empty();
        }

        List<TimelineItem> items = jdbc.query(
            """
            select
                event.event_id,
                event.action,
                event.schema_name,
                event.schema_version,
                event.operator_id,
                event.aggregate_type,
                event.aggregate_id,
                event.request_id,
                event.trace_id,
                event.occurred_at,
                event.attributes_json
            from ap_audit_event event
            where event.tenant_id = :tenantId
              and (
                  (
                      event.aggregate_type = 'APPROVAL_INSTANCE'
                      and event.aggregate_id = :instanceIdText
                  )
                  or (
                      event.aggregate_type = 'APPROVAL_TASK'
                      and exists (
                          select 1
                          from ap_approval_task task
                          where task.tenant_id = event.tenant_id
                            and task.instance_id = :instanceId
                            and (
                                task.task_id::text = event.aggregate_id
                                or task.engine_task_id = event.aggregate_id
                            )
                      )
                  )
              )
            order by event.tenant_sequence, event.event_id
            """,
            parameters,
            timelineItemMapper()
        );
        return Optional.of(new ApprovalTimeline(identity.instanceId(), items));
    }

    private RowMapper<TimelineItem> timelineItemMapper() {
        return (resultSet, rowNumber) -> {
            Map<String, String> attributes = decodeAttributes(
                resultSet.getString("attributes_json")
            );
            String action = resultSet.getString("action");
            String schemaName = resultSet.getString("schema_name");
            int schemaVersion = resultSet.getInt("schema_version");
            return new TimelineItem(
                resultSet.getObject("event_id", UUID.class),
                action,
                schemaName,
                schemaVersion,
                summary(action, schemaName, schemaVersion, attributes),
                resultSet.getString("operator_id"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("request_id"),
                resultSet.getString("trace_id"),
                instant(resultSet, "occurred_at"),
                attributes
            );
        };
    }

    private static String summary(
        String action,
        String schemaName,
        int schemaVersion,
        Map<String, String> attributes
    ) {
        String label = switch (action) {
            case "INSTANCE_STARTED" -> "发起审批";
            case "INSTANCE_COPIED" -> "抄送审批";
            case "INSTANCE_URGED" -> "催办审批";
            case "INSTANCE_WITHDRAWN" -> "撤回审批";
            case "INSTANCE_COMMENTED", "INSTANCE_COMMENT_CREATED" -> "发表审批评论";
            case "INSTANCE_COMMENT_EDITED" -> "编辑审批评论";
            case "INSTANCE_COMMENT_DELETED" -> "删除审批评论";
            case "TASK_APPROVED" -> "同意审批";
            case "TASK_REJECTED" -> "驳回审批";
            case "TASK_RESUBMITTED" -> "重新提交审批";
            case "TASK_RETRIEVED" -> "拿回审批任务";
            case "TASK_TRANSFERRED" -> "转办审批任务";
            case "TASK_DELEGATED" -> "代理审批任务";
            case "TASK_HANDOVER_APPLIED" -> "执行离职交接";
            case "TASK_COLLABORATION_STARTED" -> "发起协作审批";
            case "TASK_COLLABORATION_DECIDED" -> "提交协作审批决定";
            default -> schemaVersion == 0
                ? "历史审批事件 · " + action
                : "审批事件 · " + schemaName + " v" + schemaVersion;
        };
        String reason = normalized(attributes.get("reason"));
        if (reason == null) {
            return label;
        }
        return label + "：" + abbreviate(reason, 200);
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength - 3) + "...";
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Map<String, String> decodeAttributes(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode audit event attributes", exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
