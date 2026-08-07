package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** MySQL 8.4 append-only versioned audit store with a tenant-isolated integrity chain. */
public final class JdbcMySqlAuditEventSink implements ApprovalAuditStore {

    private static final String ZERO_HASH = "0".repeat(64);
    private static final String CHAIN_STATE_ADMISSION_SQL = """
        insert into ap_audit_chain_state (
            tenant_id, last_sequence, last_hash, updated_at
        ) values (
            :tenantId, 0, :zeroHash, :updatedAt
        )
        on duplicate key update tenant_id = tenant_id
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final JdbcDatabaseValueAdapter values;

    public JdbcMySqlAuditEventSink(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, new JdbcTransactionManager(dataSource));
    }

    public JdbcMySqlAuditEventSink(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlAuditEventSink requires a MySQL 8.4 DataSource"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.transaction = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    public void append(AuditEvent event) {
        AuditEvent exact = Objects.requireNonNull(event, "event must not be null");
        Instant occurredAt = AuditHashCanonicalizer.canonicalInstant(
            exact.occurredAt()
        );
        String payloadHash = AuditHashCanonicalizer.payloadHash(exact);
        transaction.executeWithoutResult(status -> appendInTransaction(
            exact,
            occurredAt,
            payloadHash
        ));
    }

    @Override
    public AuditPage find(AuditCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("occurredFrom", values.bindInstant(criteria.occurredFrom()))
            .addValue("occurredTo", values.bindInstant(criteria.occurredTo()))
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        List<String> conditions = new ArrayList<>();
        conditions.add("event.tenant_id = :tenantId");
        conditions.add("event.occurred_at >= :occurredFrom");
        conditions.add("event.occurred_at < :occurredTo");
        optionalCondition(
            conditions,
            parameters,
            "operatorId",
            criteria.operatorId(),
            "event.operator_id = :operatorId"
        );
        optionalCondition(
            conditions,
            parameters,
            "action",
            criteria.action(),
            "event.action = :action"
        );
        optionalCondition(
            conditions,
            parameters,
            "aggregateType",
            criteria.aggregateType(),
            "event.aggregate_type = :aggregateType"
        );
        optionalCondition(
            conditions,
            parameters,
            "aggregateId",
            criteria.aggregateId(),
            "event.aggregate_id = :aggregateId"
        );
        optionalCondition(
            conditions,
            parameters,
            "requestId",
            criteria.requestId(),
            "event.request_id = :requestId"
        );
        optionalCondition(
            conditions,
            parameters,
            "traceId",
            criteria.traceId(),
            "event.trace_id = :traceId"
        );
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
                cast(event.attributes_json as char character set utf8mb4)
                    as attributes_json,
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
        List<StoredAudit> records = jdbc.query(
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
                cast(event.attributes_json as char character set utf8mb4)
                    as attributes_json,
                event.previous_hash,
                event.payload_hash,
                event.current_hash
            from ap_audit_event event
            where event.tenant_id = :tenantId
            order by event.tenant_sequence, event.event_id
            """,
            new MapSqlParameterSource("tenantId", criteria.tenantId()),
            (resultSet, rowNumber) -> storedAudit(resultSet)
        );
        long checked = 0;
        IntegrityFailure failure = null;
        long expectedSequence = 0;
        String expectedPreviousHash = ZERO_HASH;
        for (StoredAudit record : records) {
            expectedSequence++;
            String expectedPayloadHash = AuditHashCanonicalizer.payloadHash(record.payload());
            String failureCode = failureCode(
                record,
                expectedSequence,
                expectedPreviousHash,
                expectedPayloadHash
            );
            if (inRange(record.payload().occurredAt(), criteria)) {
                checked++;
                if (failure == null && failureCode != null) {
                    failure = new IntegrityFailure(
                        record.payload().eventId(),
                        record.tenantSequence(),
                        failureCode
                    );
                }
            }
            expectedPreviousHash = record.currentHash();
        }
        ChainState state = chainState(criteria.tenantId());
        ChainTail tail = chainTail(criteria.tenantId());
        if (failure == null
            && (state.lastSequence() != tail.lastSequence()
                || !state.lastHash().equals(tail.lastHash()))) {
            return new AuditIntegrityResult(
                false,
                checked,
                null,
                null,
                "CHAIN_STATE_MISMATCH",
                state.lastSequence(),
                state.lastHash()
            );
        }
        if (failure != null) {
            return new AuditIntegrityResult(
                false,
                checked,
                failure.eventId(),
                failure.tenantSequence(),
                failure.failureCode(),
                state.lastSequence(),
                state.lastHash()
            );
        }
        return new AuditIntegrityResult(
            true,
            checked,
            null,
            null,
            null,
            state.lastSequence(),
            state.lastHash()
        );
    }

    private void appendInTransaction(
        AuditEvent event,
        Instant occurredAt,
        String payloadHash
    ) {
        Instant updatedAt = AuditHashCanonicalizer.canonicalInstant(Instant.now());
        jdbc.update(
            CHAIN_STATE_ADMISSION_SQL,
            new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("zeroHash", ZERO_HASH)
                .addValue("updatedAt", values.bindInstant(updatedAt))
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
        String currentHash = AuditHashCanonicalizer.chainHash(
            state.lastHash(),
            payloadHash
        );
        MapSqlParameterSource parameters = eventParameters(event, occurredAt)
            .addValue("tenantSequence", tenantSequence)
            .addValue("previousHash", state.lastHash())
            .addValue("payloadHash", payloadHash)
            .addValue("currentHash", currentHash);
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
                :requestId, :traceId, :occurredAt, cast(:attributesJson as json),
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
                .addValue("updatedAt", values.bindInstant(updatedAt))
                .addValue("previousSequence", state.lastSequence())
        );
        if (updated != 1) {
            throw new IllegalStateException("audit chain state changed concurrently");
        }
    }

    static String chainStateAdmissionSql() {
        return CHAIN_STATE_ADMISSION_SQL;
    }

    private MapSqlParameterSource eventParameters(
        AuditEvent event,
        Instant occurredAt
    ) {
        return new MapSqlParameterSource()
            .addValue("eventId", values.bindUuid(event.eventId()))
            .addValue("tenantId", event.tenantId())
            .addValue("operatorId", event.operatorId())
            .addValue("action", event.action())
            .addValue("aggregateType", event.aggregateType())
            .addValue("aggregateId", event.aggregateId())
            .addValue("schemaName", event.schemaName())
            .addValue("schemaVersion", event.schemaVersion())
            .addValue("requestId", event.requestId())
            .addValue("traceId", event.traceId())
            .addValue("occurredAt", values.bindInstant(occurredAt))
            .addValue("attributesJson", encodeAttributes(event.attributes()));
    }

    private AuditRecord auditRecord(ResultSet resultSet) throws SQLException {
        return new AuditRecord(
            values.uuid(resultSet, "event_id"),
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
            values.instant(resultSet, "occurred_at"),
            decodeAttributes(resultSet.getString("attributes_json")),
            resultSet.getString("previous_hash"),
            resultSet.getString("payload_hash"),
            resultSet.getString("current_hash")
        );
    }

    private StoredAudit storedAudit(ResultSet resultSet) throws SQLException {
        return new StoredAudit(
            new AuditHashCanonicalizer.Payload(
                values.uuid(resultSet, "event_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("operator_id"),
                resultSet.getString("action"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("schema_name"),
                resultSet.getInt("schema_version"),
                resultSet.getString("request_id"),
                resultSet.getString("trace_id"),
                values.instant(resultSet, "occurred_at"),
                decodeAttributes(resultSet.getString("attributes_json"))
            ),
            resultSet.getLong("tenant_sequence"),
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

    private static String failureCode(
        StoredAudit record,
        long expectedSequence,
        String expectedPreviousHash,
        String expectedPayloadHash
    ) {
        if (record.tenantSequence() != expectedSequence) {
            return "SEQUENCE_MISMATCH";
        }
        if (!record.previousHash().equals(expectedPreviousHash)) {
            return "PREVIOUS_HASH_MISMATCH";
        }
        if (!record.payloadHash().equals(expectedPayloadHash)) {
            return "PAYLOAD_HASH_MISMATCH";
        }
        if (!record.currentHash().equals(AuditHashCanonicalizer.chainHash(
            record.previousHash(),
            record.payloadHash()
        ))) {
            return "CURRENT_HASH_MISMATCH";
        }
        return null;
    }

    private static boolean inRange(Instant value, AuditIntegrityCriteria criteria) {
        return !value.isBefore(criteria.occurredFrom())
            && value.isBefore(criteria.occurredTo());
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
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new SQLException("audit attributes must be a JSON object");
            }
            TreeMap<String, String> attributes = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getValue().isTextual()) {
                    throw new SQLException(
                        "audit attribute must be a JSON string: " + field.getKey()
                    );
                }
                attributes.put(field.getKey(), field.getValue().textValue());
            }
            return Map.copyOf(attributes);
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

    private record StoredAudit(
        AuditHashCanonicalizer.Payload payload,
        long tenantSequence,
        String previousHash,
        String payloadHash,
        String currentHash
    ) {
        private StoredAudit {
            payload = Objects.requireNonNull(payload, "payload must not be null");
            previousHash = requireHash(previousHash, "previousHash");
            payloadHash = requireHash(payloadHash, "payloadHash");
            currentHash = requireHash(currentHash, "currentHash");
        }
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

    private static String requireHash(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        if (!exact.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
        return exact;
    }
}
