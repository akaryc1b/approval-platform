package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL task-center read model backed only by platform projection tables.
 */
public final class JdbcApprovalTaskQuery implements ApprovalTaskQuery {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalTaskQuery(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public PendingTaskPage findPendingTasks(PendingTaskCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = parameters(criteria);
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = :tenantId
              and task.assignee_id = :assigneeId
              and task.status = 'PENDING'
              and instance.status = 'RUNNING'
              and (
                  :hasKeyword = false
                  or position(:keyword in lower(task.task_name)) > 0
                  or position(:keyword in lower(instance.business_key)) > 0
                  or position(:keyword in lower(instance.supplier)) > 0
                  or position(:keyword in lower(instance.purchase_order_reference)) > 0
              )
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new PendingTaskPage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        List<PendingTaskItem> items = jdbc.query(
            """
            select
                task.task_id,
                task.instance_id,
                instance.definition_key,
                task.task_definition_key,
                task.task_name,
                instance.business_key,
                instance.initiator_id,
                instance.amount,
                instance.supplier,
                instance.purchase_order_reference,
                task.created_at as task_created_at,
                task.updated_at as task_updated_at
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = :tenantId
              and task.assignee_id = :assigneeId
              and task.status = 'PENDING'
              and instance.status = 'RUNNING'
              and (
                  :hasKeyword = false
                  or position(:keyword in lower(task.task_name)) > 0
                  or position(:keyword in lower(instance.business_key)) > 0
                  or position(:keyword in lower(instance.supplier)) > 0
                  or position(:keyword in lower(instance.purchase_order_reference)) > 0
              )
            order by task.created_at, task.task_id
            limit :limit offset :offset
            """,
            parameters,
            pendingTaskMapper()
        );
        return new PendingTaskPage(items, matched, criteria.limit(), criteria.offset());
    }

    private static MapSqlParameterSource parameters(PendingTaskCriteria criteria) {
        boolean hasKeyword = criteria.keyword() != null;
        return new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("assigneeId", criteria.assigneeId())
            .addValue("hasKeyword", hasKeyword)
            .addValue(
                "keyword",
                hasKeyword ? criteria.keyword().toLowerCase(Locale.ROOT) : ""
            )
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
    }

    private static RowMapper<PendingTaskItem> pendingTaskMapper() {
        return (resultSet, rowNumber) -> new PendingTaskItem(
            resultSet.getObject("task_id", UUID.class),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("definition_key"),
            resultSet.getString("task_definition_key"),
            resultSet.getString("task_name"),
            resultSet.getString("business_key"),
            resultSet.getString("initiator_id"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            instant(resultSet, "task_created_at"),
            instant(resultSet, "task_updated_at")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
