package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL append-only versioned audit store with a tenant-isolated integrity chain. */
public final class JdbcAuditEventSink implements ApprovalAuditStore {

    private static final String ZERO_HASH = "0".repeat(64);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public JdbcAuditEventSink(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, new JdbcTransactionManager(dataSource));
    }

    public JdbcAuditEventSink(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transaction = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    public void append(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        transaction.executeWithoutResult(status -> appendInTransaction(event));
    }

    @Override
    public AuditPage find(AuditCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("occurredFrom", offset(criteria.occurredFrom()))
            .addValue("occurredTo", offset(criteria.occurredTo()))
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        List<String> conditions = new ArrayList<>();
        conditions.add("event.tenant_id = :tenantId");
        conditions.add("event.occurred_at >= :occurredFrom");
        conditions.add("event.occurred_at < :occurredTo");
        optionalCondition(conditions, parameters, "operatorId", criteria.operatorId(),
            "event.operator_id = :operatorId");
        optionalCondition(conditions, parameters, "action", criteria.action(),
            "event.action = :action");
        optionalCondition(conditions, parameters, "aggregateType", criteria.aggregateType(),
            "event.aggregate_type = :aggregateType");
        optionalCondition(conditions, parameters, "aggregateId", criteria.aggregateId(),
            "event.aggregate_id = :aggregateId");
        optionalCondition(conditions, parameters, "requestId", criteria.requestId(),
            "event.request_id = :requestId");
        optionalCondition(conditions, parameters, "traceId", criteria.traceId(),
            "event.trace_id = :traceId");
        String where = String.join(" and ", conditions);
        Long total = jdbc.queryForObject(
            "select count(*) from ap_audit_event event where " + where,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new AuditPage(List.of(), 0, criteria.limit(), criteria.offset());
        }
        List<AuditRecord> items = jdbc.query(
            """
            select
                event.event_id,
                event.tenant_id,
                event.tenant_sequence,
                event.operator_id,
                event.action,
                event.aggregate_type,
                event.aggregate_id,
                event.schema_name,
                event.schema_version,
                event.request_id,
                event.trace_id,
                event.occurred_at,
                event.attributes_json,
                event.previous_hash,
                event.payload_hash,
                event.current_hash
            from ap_audit_event event
            where %s
            order by event.tenant_sequence desc
            limit :limit offset :offset
            """.formatted(where),
            parameters,
            (resultSet, rowNumber) -> auditRecord(resultSet)
        );
        return new AuditPage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public AuditIntegrityResult verify(AuditIntegrityCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("occurredFrom", offset(criteria.occurredFrom()))
            .addValue("occurredTo", offset(criteria.occurredTo()));
        Long checked = jdbc.queryForObject(
            """
            select count(*)
            from ap_audit_event event
            where event.tenant_id = :tenantId
              and event.occurred_at >= :occurredFrom
              and event.occurred_at < :occurredTo
            """,
            parameters,
            Long.class
        );
        Optional<IntegrityFailure> failure = jdbc.query(
            integrityFailureSql(),
            parameters,
            (resultSet, rowNumber) -> new IntegrityFailure(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getLong("tenant_sequence"),
                resultSet.getString("failure_code")
            )
        ).stream().findFirst();
        ChainState state = chainState(criteria.tenantId());
        ChainTail tail = chainTail(criteria.tenantId());
        if (failure.isEmpty()
            && (state.lastSequence() != tail.lastSequence()
                || !state.lastHash().equals(tail.lastHash()))) {
            return new AuditIntegrityResult(
                false,
                checked == null ? 0 : checked,
                null,
                null,
                "CHAIN_STATE_MISMATCH",
                state.lastSequence(),
                state.lastHash()
            );
        }
        if (failure.isPresent()) {
            IntegrityFailure invalid = failure.orElseThrow();
            return new AuditIntegrityResult(
                false,
                checked == null ? 0 : checked,
                invalid.eventId(),
                invalid.tenantSequence(),
                invalid.failureCode(),
                state.lastSequence(),
                state.lastHash()
            );
        }
        return new AuditIntegrityResult(
            true,
            checked == null ? 0 : checked,
            null,
            null,
            null,
            state.lastSequence(),
            state.lastHash()
        );
    }

    private void appendInTransaction(AuditEvent event) {
        Instant now = Instant.now();
        jdbc.update(
            """
            insert into ap_audit_chain_state (
                tenant_id, last_sequence, last_hash, updated_at
            ) values (
                :tenantId, 0, :zeroHash, :updatedAt
            ) on conflict (tenant_id) do nothing
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("zeroHash", ZERO_HASH)
                .addValue("updatedAt", offset(now))
        );
        ChainState state = jdbc.queryForObject(
            """
            select last_sequence, last_hash
            from ap_audit_chain_state
            where tenant_id = :tenantId
            for update
            """,
            new MapSqlParameterSource("tenantId", event.tenantId()),
            (resultSet, rowNumber) -> new ChainState(
                resultSet.getLong("last_sequence"),
                resultSet.getString("last_hash")
            )
        );
        if (state == null) {
            throw new IllegalStateException("audit chain state was not created");
        }
        long tenantSequence = state.lastSequence() + 1;
        MapSqlParameterSource parameters = eventParameters(event)
            .addValue("tenantSequence", tenantSequence)
            .addValue("previousHash", state.lastHash());
        String payloadHash = jdbc.queryForObject(
            """
            select ap_audit_payload_hash(
                :eventId,
                :tenantId,
                :operatorId,
                :action,
                :aggregateType,
                :aggregateId,
                :schemaName,
                :schemaVersion,
                :requestId,
                :traceId,
                :occurredAt,
                cast(:attributesJson as jsonb)
            )
            """,
            parameters,
            String.class
        );
        if (payloadHash == null) {
            throw new IllegalStateException("audit payload hash was not calculated");
        }
        parameters.addValue("payloadHash", payloadHash);
        String currentHash = jdbc.queryForObject(
            "select ap_audit_chain_hash(:previousHash, :payloadHash)",
            parameters,
            String.class
        );
        if (currentHash == null) {
            throw new IllegalStateException("audit chain hash was not calculated");
        }
        parameters.addValue("currentHash", currentHash);
        int inserted = jdbc.update(
            """
            insert into ap_audit_event (
                event_id, tenant_id, operator_id, action,
                aggregate_type, aggregate_id, schema_name, schema_version,
                request_id, trace_id, occurred_at, attributes_json,
                tenant_sequence, previous_hash, payload_hash, current_hash
            ) values (
                :eventId, :tenantId, :operatorId, :action,
                :aggregateType, :aggregateId, :schemaName, :schemaVersion,
                :requestId, :traceId, :occurredAt, cast(:attributesJson as jsonb),
                :tenantSequence, :previousHash, :payloadHash, :currentHash
            )
            """,
            parameters
        );
        if (inserted != 1) {
            throw new IllegalStateException("audit event was not inserted");
        }
        int updated = jdbc.update(
            """
            update ap_audit_chain_state
            set last_sequence = :tenantSequence,
                last_hash = :currentHash,
                updated_at = :updatedAt
            where tenant_id = :tenantId
              and last_sequence = :previousSequence
              and last_hash = :previousHash
            """,
            parameters
                .addValue("updatedAt", offset(now))
                .addValue("previousSequence", state.lastSequence())
        );
        if (updated != 1) {
            throw new IllegalStateException("audit chain state changed concurrently");
        }
    }

    private MapSqlParameterSource eventParameters(AuditEvent event) {
        return new MapSqlParameterSource()
            .addValue("eventId", event.eventId())
            .addValue("tenantId", event.tenantId())
            .addValue("operatorId", event.operatorId())
            .addValue("action", event.action())
            .addValue("aggregateType", event.aggregateType())
            .addValue("aggregateId", event.aggregateId())
            .addValue("schemaName", event.schemaName())
            .addValue("schemaVersion", event.schemaVersion())
            .addValue("requestId", event.requestId())
            .addValue("traceId", event.traceId())
            .addValue("occurredAt", offset(event.occurredAt()))
            .addValue("attributesJson", encodeAttributes(event.attributes()));
    }

    private AuditRecord auditRecord(ResultSet resultSet) throws SQLException {
        return new AuditRecord(
            resultSet.getObject("event_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getLong("tenant_sequence"),
            resultSet.getString("operator_id"),
            resultSet.getString("action"),
            resultSet.getString("aggregate_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getString("schema_name"),
            resultSet.getInt("schema_version"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id"),
            resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
            decodeAttributes(resultSet.getString("attributes_json")),
            resultSet.getString("previous_hash"),
            resultSet.getString("payload_hash"),
            resultSet.getString("current_hash")
        );
    }

    private ChainState chainState(String tenantId) {
        return jdbc.query(
            """
            select last_sequence, last_hash
            from ap_audit_chain_state
            where tenant_id = :tenantId
            """,
            new MapSqlParameterSource("tenantId", tenantId),
            (resultSet, rowNumber) -> new ChainState(
                resultSet.getLong("last_sequence"),
                resultSet.getString("last_hash")
            )
        ).stream().findFirst().orElse(new ChainState(0, ZERO_HASH));
    }

    private ChainTail chainTail(String tenantId) {
        return jdbc.query(
            """
            select tenant_sequence, current_hash
            from ap_audit_event
            where tenant_id = :tenantId
            order by tenant_sequence desc
            limit 1
            """,
            new MapSqlParameterSource("tenantId", tenantId),
            (resultSet, rowNumber) -> new ChainTail(
                resultSet.getLong("tenant_sequence"),
                resultSet.getString("current_hash")
            )
        ).stream().findFirst().orElse(new ChainTail(0, ZERO_HASH));
    }

    private static String integrityFailureSql() {
        return """
            with ordered as (
                select
                    event.*,
                    row_number() over (
                        partition by event.tenant_id
                        order by event.tenant_sequence
                    ) as expected_sequence,
                    lag(event.current_hash, 1, repeat('0', 64)) over (
                        partition by event.tenant_id
                        order by event.tenant_sequence
                    ) as expected_previous_hash,
                    ap_audit_payload_hash(
                        event.event_id,
                        event.tenant_id,
                        event.operator_id,
                        event.action,
                        event.aggregate_type,
                        event.aggregate_id,
                        event.schema_name,
                        event.schema_version,
                        event.request_id,
                        event.trace_id,
                        event.occurred_at,
                        event.attributes_json
                    ) as expected_payload_hash
                from ap_audit_event event
                where event.tenant_id = :tenantId
            ), evaluated as (
                select
                    ordered.*,
                    case
                        when ordered.tenant_sequence <> ordered.expected_sequence
                            then 'SEQUENCE_MISMATCH'
                        when ordered.previous_hash <> ordered.expected_previous_hash
                            then 'PREVIOUS_HASH_MISMATCH'
                        when ordered.payload_hash <> ordered.expected_payload_hash
                            then 'PAYLOAD_HASH_MISMATCH'
                        when ordered.current_hash <> ap_audit_chain_hash(
                            ordered.previous_hash,
                            ordered.payload_hash
                        ) then 'CURRENT_HASH_MISMATCH'
                        else null
                    end as failure_code
                from ordered
            )
            select event_id, tenant_sequence, failure_code
            from evaluated
            where occurred_at >= :occurredFrom
              and occurred_at < :occurredTo
              and failure_code is not null
            order by tenant_sequence
            limit 1
            """;
    }

    private String encodeAttributes(Map<String, String> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode audit attributes", exception);
        }
    }

    private Map<String, String> decodeAttributes(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode audit attributes", exception);
        }
    }

    private static void optionalCondition(
        List<String> conditions,
        MapSqlParameterSource parameters,
        String parameterName,
        String value,
        String condition
    ) {
        if (value != null) {
            conditions.add(condition);
            parameters.addValue(parameterName, value);
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record ChainState(long lastSequence, String lastHash) {
    }

    private record ChainTail(long lastSequence, String lastHash) {
    }

    private record IntegrityFailure(
        UUID eventId,
        long tenantSequence,
        String failureCode
    ) {
    }
}
