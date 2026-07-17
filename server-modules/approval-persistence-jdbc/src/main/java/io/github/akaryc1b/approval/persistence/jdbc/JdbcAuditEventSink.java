package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public final class JdbcAuditEventSink implements AuditEventSink {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditEventSink(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void append(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        int inserted = jdbc.update(
            """
            insert into ap_audit_event (
                event_id, tenant_id, operator_id, action,
                aggregate_type, aggregate_id, request_id, trace_id,
                occurred_at, attributes_json
            ) values (
                :eventId, :tenantId, :operatorId, :action,
                :aggregateType, :aggregateId, :requestId, :traceId,
                :occurredAt, cast(:attributesJson as jsonb)
            )
            """,
            new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("tenantId", event.tenantId())
                .addValue("operatorId", event.operatorId())
                .addValue("action", event.action())
                .addValue("aggregateType", event.aggregateType())
                .addValue("aggregateId", event.aggregateId())
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("occurredAt", OffsetDateTime.ofInstant(
                    event.occurredAt(),
                    ZoneOffset.UTC
                ))
                .addValue("attributesJson", encode(event))
        );
        if (inserted != 1) {
            throw new IllegalStateException("audit event was not inserted");
        }
    }

    private String encode(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event.attributes());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode audit attributes", exception);
        }
    }
}
