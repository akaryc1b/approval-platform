package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL 8.4 immutable form submission and revision snapshot store. */
public final class JdbcMySqlApprovalFormSubmissionStore implements ApprovalFormSubmissionStore {

    static final String JSON_ENCODING = "CANONICAL_FORM_SUBMISSION_JSON_TEXT_V1";
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectMapper strictObjectMapper;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;

    public JdbcMySqlApprovalFormSubmissionStore(
        DataSource dataSource,
        ObjectMapper objectMapper
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalFormSubmissionStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.strictObjectMapper = objectMapper.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.locks = new JdbcMySqlTransactionLockManager(source);
    }

    @Override
    public void save(FormSubmission submission) {
        FormSubmission exact = Objects.requireNonNull(
            submission,
            "submission must not be null"
        );
        int inserted = jdbc.update(
            """
            insert into ap_form_submission (
                submission_id, tenant_id, form_key, form_version, schema_hash,
                ui_schema_version, ui_schema_hash, business_key, values_json,
                start_parameters_json, instance_id, submitted_by, submitted_at, request_hash
            ) values (
                :submissionId, :tenantId, :formKey, :formVersion, :schemaHash,
                :uiSchemaVersion, :uiSchemaHash, :businessKey, cast(:valuesJson as json),
                cast(:startParametersJson as json), :instanceId, :submittedBy,
                :submittedAt, :requestHash
            )
            """,
            new MapSqlParameterSource()
                .addValue("submissionId", values.bindUuid(exact.submissionId()))
                .addValue("tenantId", exact.tenantId())
                .addValue("formKey", exact.formKey())
                .addValue("formVersion", exact.formVersion())
                .addValue("schemaHash", exact.schemaHash())
                .addValue("uiSchemaVersion", exact.uiSchemaVersion())
                .addValue("uiSchemaHash", exact.uiSchemaHash())
                .addValue("businessKey", exact.businessKey())
                .addValue("valuesJson", encodeEnvelope(exact.values()))
                .addValue("startParametersJson", encodeEnvelope(exact.startParameters()))
                .addValue("instanceId", values.bindUuid(exact.instanceId()))
                .addValue("submittedBy", exact.submittedBy())
                .addValue(
                    "submittedAt",
                    values.bindInstant(canonicalInstant(exact.submittedAt()))
                )
                .addValue("requestHash", exact.requestHash())
        );
        if (inserted != 1) {
            throw new IllegalStateException("form submission was not inserted");
        }
    }

    @Override
    public Optional<FormSubmission> findByInstance(String tenantId, UUID instanceId) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactInstance = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        return jdbc.query(
            """
            select submission_id, tenant_id, form_key, form_version, schema_hash,
                   ui_schema_version, ui_schema_hash, business_key, values_json,
                   start_parameters_json, instance_id, submitted_by, submitted_at, request_hash
            from ap_form_submission
            where tenant_id = :tenantId and instance_id = :instanceId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("instanceId", values.bindUuid(exactInstance)),
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    @Override
    public Optional<FormSubmission> findByBusinessKey(String tenantId, String businessKey) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactBusinessKey = requireText(businessKey, "businessKey");
        return jdbc.query(
            """
            select submission_id, tenant_id, form_key, form_version, schema_hash,
                   ui_schema_version, ui_schema_hash, business_key, values_json,
                   start_parameters_json, instance_id, submitted_by, submitted_at, request_hash
            from ap_form_submission
            where tenant_id = :tenantId and business_key = :businessKey
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("businessKey", exactBusinessKey),
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    @Override
    public void lockInstance(String tenantId, UUID instanceId) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactInstance = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        locks.acquire("form-revision:" + exactTenant + ':' + exactInstance);
    }

    @Override
    public void saveRevision(FormSubmissionRevision revision) {
        FormSubmissionRevision exact = Objects.requireNonNull(
            revision,
            "revision must not be null"
        );
        int inserted = jdbc.update(
            """
            insert into ap_form_submission_revision (
                revision_id, tenant_id, instance_id, revision_number,
                values_json, modified_by, modified_at, request_hash
            ) values (
                :revisionId, :tenantId, :instanceId, :revisionNumber,
                cast(:valuesJson as json), :modifiedBy, :modifiedAt, :requestHash
            )
            """,
            new MapSqlParameterSource()
                .addValue("revisionId", values.bindUuid(exact.revisionId()))
                .addValue("tenantId", exact.tenantId())
                .addValue("instanceId", values.bindUuid(exact.instanceId()))
                .addValue("revisionNumber", exact.revisionNumber())
                .addValue("valuesJson", encodeEnvelope(exact.values()))
                .addValue("modifiedBy", exact.modifiedBy())
                .addValue(
                    "modifiedAt",
                    values.bindInstant(canonicalInstant(exact.modifiedAt()))
                )
                .addValue("requestHash", exact.requestHash())
        );
        if (inserted != 1) {
            throw new IllegalStateException("form submission revision was not inserted");
        }
    }

    @Override
    public Optional<FormSubmissionRevision> findLatestRevision(
        String tenantId,
        UUID instanceId
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactInstance = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        return jdbc.query(
            """
            select revision_id, tenant_id, instance_id, revision_number,
                   values_json, modified_by, modified_at, request_hash
            from ap_form_submission_revision
            where tenant_id = :tenantId and instance_id = :instanceId
            order by revision_number desc
            limit 1
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("instanceId", values.bindUuid(exactInstance)),
            (resultSet, rowNumber) -> revision(resultSet)
        ).stream().findFirst();
    }

    private FormSubmission item(ResultSet resultSet) throws SQLException {
        return new FormSubmission(
            values.uuid(resultSet, "submission_id"),
            resultSet.getString("tenant_id"),
            resultSet.getString("form_key"),
            resultSet.getInt("form_version"),
            resultSet.getString("schema_hash"),
            integer(resultSet, "ui_schema_version"),
            resultSet.getString("ui_schema_hash"),
            resultSet.getString("business_key"),
            decodeEnvelope(resultSet.getString("values_json")),
            decodeEnvelope(resultSet.getString("start_parameters_json")),
            values.uuid(resultSet, "instance_id"),
            resultSet.getString("submitted_by"),
            values.instant(resultSet, "submitted_at"),
            resultSet.getString("request_hash")
        );
    }

    private FormSubmissionRevision revision(ResultSet resultSet) throws SQLException {
        return new FormSubmissionRevision(
            values.uuid(resultSet, "revision_id"),
            resultSet.getString("tenant_id"),
            values.uuid(resultSet, "instance_id"),
            resultSet.getInt("revision_number"),
            decodeEnvelope(resultSet.getString("values_json")),
            resultSet.getString("modified_by"),
            values.instant(resultSet, "modified_at"),
            resultSet.getString("request_hash")
        );
    }

    private String encodeEnvelope(Map<String, Object> map) {
        try {
            String payload = objectMapper.writeValueAsString(map);
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("encoding", JSON_ENCODING);
            envelope.put("payload", payload);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "unable to encode MySQL form submission JSON envelope",
                exception
            );
        }
    }

    private Map<String, Object> decodeEnvelope(String json) throws SQLException {
        if (json == null) {
            throw new SQLException("MySQL form submission JSON envelope was null");
        }
        try {
            JsonNode envelope = strictObjectMapper.readTree(json);
            JsonNode encoding = envelope == null ? null : envelope.get("encoding");
            JsonNode payload = envelope == null ? null : envelope.get("payload");
            if (envelope == null
                || !envelope.isObject()
                || envelope.size() != 2
                || encoding == null
                || !encoding.isTextual()
                || !JSON_ENCODING.equals(encoding.textValue())
                || payload == null
                || !payload.isTextual()) {
                throw new SQLException(
                    "invalid or unsupported MySQL form submission JSON envelope"
                );
            }
            return strictObjectMapper.readValue(payload.textValue(), OBJECT_MAP);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new SQLException(
                "unable to decode MySQL form submission JSON envelope",
                exception
            );
        }
    }

    private static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(value, "instant must not be null")
        );
    }

    private static Integer integer(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(
            value,
            name + " must not be null"
        );
        if (exact.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }
}
