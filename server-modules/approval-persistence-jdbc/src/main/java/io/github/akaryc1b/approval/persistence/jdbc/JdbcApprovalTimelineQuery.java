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
                                          and historical_task.task_id::text = event.aggregate_id
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
                            and task.task_id::text = event.aggregate_id
                      )
                  )
              )
            order by event.occurred_at, event.event_id
            """,
            parameters,
            timelineItemMapper()
        );
        return Optional.of(new ApprovalTimeline(identity.instanceId(), items));
    }

    private RowMapper<TimelineItem> timelineItemMapper() {
        return (resultSet, rowNumber) -> new TimelineItem(
            resultSet.getObject("event_id", UUID.class),
            resultSet.getString("action"),
            resultSet.getString("operator_id"),
            resultSet.getString("aggregate_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id"),
            instant(resultSet, "occurred_at"),
            decodeAttributes(resultSet.getString("attributes_json"))
        );
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
