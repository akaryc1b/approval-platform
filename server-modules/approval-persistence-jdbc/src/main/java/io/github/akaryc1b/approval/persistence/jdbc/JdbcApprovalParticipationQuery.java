package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
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
 * PostgreSQL participation views backed only by approval projections.
 */
public final class JdbcApprovalParticipationQuery implements ApprovalParticipationQuery {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalParticipationQuery(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public StartedInstancePage findStartedInstances(StartedInstanceCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = startedParameters(criteria);
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_instance instance
            where instance.tenant_id = :tenantId
              and instance.initiator_id = :initiatorId
              and (
                  :hasKeyword = false
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
            return new StartedInstancePage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        List<StartedInstanceItem> items = jdbc.query(
            """
            select
                instance.instance_id,
                instance.definition_key,
                instance.business_key,
                instance.initiator_id,
                instance.amount,
                instance.supplier,
                instance.purchase_order_reference,
                instance.attachment_ids_json,
                instance.status,
                instance.created_at,
                instance.updated_at,
                (
                    select task.task_definition_key
                    from ap_approval_task task
                    where task.tenant_id = instance.tenant_id
                      and task.instance_id = instance.instance_id
                      and task.status = 'PENDING'
                    order by task.created_at, task.task_id
                    limit 1
                ) as current_task_definition_key,
                (
                    select task.task_name
                    from ap_approval_task task
                    where task.tenant_id = instance.tenant_id
                      and task.instance_id = instance.instance_id
                      and task.status = 'PENDING'
                    order by task.created_at, task.task_id
                    limit 1
                ) as current_task_name,
                (
                    instance.status = 'RUNNING'
                    and exists (
                        select 1
                        from ap_approval_task active_task
                        where active_task.tenant_id = instance.tenant_id
                          and active_task.instance_id = instance.instance_id
                          and active_task.status = 'PENDING'
                    )
                    and not exists (
                        select 1
                        from ap_approval_task revision_task
                        where revision_task.tenant_id = instance.tenant_id
                          and revision_task.instance_id = instance.instance_id
                          and revision_task.task_definition_key = :revisionTaskKey
                          and revision_task.status in ('PENDING', 'COMPLETING')
                    )
                ) as withdrawable,
                (
                    select count(*)
                    from ap_approval_message message
                    where message.tenant_id = instance.tenant_id
                      and message.instance_id = instance.instance_id
                ) as message_count,
                (
                    select count(*)
                    from ap_approval_message message
                    where message.tenant_id = instance.tenant_id
                      and message.instance_id = instance.instance_id
                      and message.read_at is not null
                ) as read_count
            from ap_approval_instance instance
            where instance.tenant_id = :tenantId
              and instance.initiator_id = :initiatorId
              and (
                  :hasKeyword = false
                  or position(:keyword in lower(instance.business_key)) > 0
                  or position(:keyword in lower(instance.supplier)) > 0
                  or position(:keyword in lower(instance.purchase_order_reference)) > 0
              )
            order by instance.updated_at desc, instance.instance_id desc
            limit :limit offset :offset
            """,
            parameters,
            startedItemMapper()
        );
        return new StartedInstancePage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public ProcessedTaskPage findProcessedTasks(ProcessedTaskCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = processedParameters(criteria);
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = :tenantId
              and task.assignee_id = :operatorId
              and task.status = 'COMPLETED'
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
            return new ProcessedTaskPage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        List<ProcessedTaskItem> items = jdbc.query(
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
                instance.status as instance_status,
                task.completed_at,
                (
                    instance.status = 'RUNNING'
                    and task.task_definition_key <> :revisionTaskKey
                    and not exists (
                        select 1
                        from ap_approval_task later_task
                        where later_task.tenant_id = task.tenant_id
                          and later_task.instance_id = task.instance_id
                          and later_task.status = 'COMPLETED'
                          and later_task.completed_at is not null
                          and (
                              later_task.completed_at > task.completed_at
                              or (
                                  later_task.completed_at = task.completed_at
                                  and later_task.task_id::text > task.task_id::text
                              )
                          )
                    )
                    and not exists (
                        select 1
                        from ap_approval_task processing_task
                        where processing_task.tenant_id = task.tenant_id
                          and processing_task.instance_id = task.instance_id
                          and processing_task.status = 'COMPLETING'
                    )
                    and (
                        select count(*)
                        from ap_approval_task pending_task
                        where pending_task.tenant_id = task.tenant_id
                          and pending_task.instance_id = task.instance_id
                          and pending_task.status = 'PENDING'
                    ) = 1
                    and exists (
                        select 1
                        from ap_approval_task downstream_task
                        where downstream_task.tenant_id = task.tenant_id
                          and downstream_task.instance_id = task.instance_id
                          and downstream_task.status = 'PENDING'
                          and downstream_task.task_definition_key <> :revisionTaskKey
                          and downstream_task.task_definition_key <> task.task_definition_key
                          and downstream_task.created_at >= task.completed_at
                    )
                ) as retrievable
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = :tenantId
              and task.assignee_id = :operatorId
              and task.status = 'COMPLETED'
              and (
                  :hasKeyword = false
                  or position(:keyword in lower(task.task_name)) > 0
                  or position(:keyword in lower(instance.business_key)) > 0
                  or position(:keyword in lower(instance.supplier)) > 0
                  or position(:keyword in lower(instance.purchase_order_reference)) > 0
              )
            order by task.completed_at desc, task.task_id desc
            limit :limit offset :offset
            """,
            parameters,
            processedItemMapper()
        );
        return new ProcessedTaskPage(items, matched, criteria.limit(), criteria.offset());
    }

    private static MapSqlParameterSource startedParameters(StartedInstanceCriteria criteria) {
        return commonParameters(criteria.keyword(), criteria.limit(), criteria.offset())
            .addValue("tenantId", criteria.tenantId())
            .addValue("initiatorId", criteria.initiatorId())
            .addValue("revisionTaskKey", PurchasePaymentTemplate.REVISION_TASK_KEY);
    }

    private static MapSqlParameterSource processedParameters(ProcessedTaskCriteria criteria) {
        return commonParameters(criteria.keyword(), criteria.limit(), criteria.offset())
            .addValue("tenantId", criteria.tenantId())
            .addValue("operatorId", criteria.operatorId())
            .addValue("revisionTaskKey", PurchasePaymentTemplate.REVISION_TASK_KEY);
    }

    private static MapSqlParameterSource commonParameters(
        String keyword,
        int limit,
        int offset
    ) {
        boolean hasKeyword = keyword != null;
        return new MapSqlParameterSource()
            .addValue("hasKeyword", hasKeyword)
            .addValue("keyword", hasKeyword ? keyword.toLowerCase(Locale.ROOT) : "")
            .addValue("limit", limit)
            .addValue("offset", offset);
    }

    private RowMapper<StartedInstanceItem> startedItemMapper() {
        return (resultSet, rowNumber) -> new StartedInstanceItem(
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("definition_key"),
            resultSet.getString("business_key"),
            resultSet.getString("initiator_id"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("supplier"),
            resultSet.getString("purchase_order_reference"),
            decodeStringList(resultSet.getString("attachment_ids_json")),
            InstanceStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("current_task_definition_key"),
            resultSet.getString("current_task_name"),
            resultSet.getBoolean("withdrawable"),
            resultSet.getLong("message_count"),
            resultSet.getLong("read_count"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at")
        );
    }

    private static RowMapper<ProcessedTaskItem> processedItemMapper() {
        return (resultSet, rowNumber) -> new ProcessedTaskItem(
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
            InstanceStatus.valueOf(resultSet.getString("instance_status")),
            instant(resultSet, "completed_at"),
            resultSet.getBoolean("retrievable")
        );
    }

    private List<String> decodeStringList(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode started instance attachments", exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
