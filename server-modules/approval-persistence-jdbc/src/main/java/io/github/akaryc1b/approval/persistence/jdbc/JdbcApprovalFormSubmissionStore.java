package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL immutable form submission snapshot store. */
public final class JdbcApprovalFormSubmissionStore implements ApprovalFormSubmissionStore {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalFormSubmissionStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void save(FormSubmission submission) {
        int inserted = jdbc.update(
            """
            insert into ap_form_submission (
                submission_id, tenant_id, form_key, form_version, schema_hash,
                business_key, values_json, start_parameters_json, instance_id,
                submitted_by, submitted_at, request_hash
            ) values (
                :submissionId, :tenantId, :formKey, :formVersion, :schemaHash,
                :businessKey, cast(:valuesJson as jsonb), cast(:startParametersJson as jsonb),
                :instanceId, :submittedBy, :submittedAt, :requestHash
            )
            """,
            new MapSqlParameterSource()
                .addValue("submissionId", submission.submissionId())
                .addValue("tenantId", submission.tenantId())
                .addValue("formKey", submission.formKey())
                .addValue("formVersion", submission.formVersion())
                .addValue("schemaHash", submission.schemaHash())
                .addValue("businessKey", submission.businessKey())
                .addValue("valuesJson", encode(submission.values()))
                .addValue("startParametersJson", encode(submission.startParameters()))
                .addValue("instanceId", submission.instanceId())
                .addValue("submittedBy", submission.submittedBy())
                .addValue("submittedAt", offset(submission.submittedAt()))
                .addValue("requestHash", submission.requestHash())
        );
        if (inserted != 1) throw new IllegalStateException("form submission was not inserted");
    }

    @Override
    public Optional<FormSubmission> findByInstance(String tenantId, UUID instanceId) {
        return find(
            "tenant_id = :tenantId and instance_id = :identity",
            new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("identity", instanceId)
        );
    }

    @Override
    public Optional<FormSubmission> findByBusinessKey(String tenantId, String businessKey) {
        return find(
            "tenant_id = :tenantId and business_key = :identity",
            new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("identity", businessKey)
        );
    }

    private Optional<FormSubmission> find(String predicate, MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            select submission_id, tenant_id, form_key, form_version, schema_hash,
                   business_key, values_json, start_parameters_json, instance_id,
                   submitted_by, submitted_at, request_hash
            from ap_form_submission where %s
            """.formatted(predicate),
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    private FormSubmission item(ResultSet resultSet) throws SQLException {
        return new FormSubmission(
            resultSet.getObject("submission_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("form_key"),
            resultSet.getInt("form_version"),
            resultSet.getString("schema_hash"),
            resultSet.getString("business_key"),
            decode(resultSet.getString("values_json")),
            decode(resultSet.getString("start_parameters_json")),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getString("submitted_by"),
            resultSet.getObject("submitted_at", OffsetDateTime.class).toInstant(),
            resultSet.getString("request_hash")
        );
    }

    private String encode(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode form submission values", exception);
        }
    }

    private Map<String, Object> decode(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode form submission values", exception);
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
