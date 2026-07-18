package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL immutable UI Schema repository. */
public final class JdbcApprovalUiSchemaStore implements ApprovalUiSchemaStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalUiSchemaStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void lockVersion(
        String tenantId,
        String formKey,
        int formVersion,
        int uiSchemaVersion
    ) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource().addValue(
                "lockKey",
                "ui-schema:" + tenantId + ':' + formKey + ':' + formVersion + ':' + uiSchemaVersion
            ),
            resultSet -> null
        );
    }

    @Override
    public Optional<PublishedUiSchema> find(
        String tenantId,
        String formKey,
        int formVersion,
        int uiSchemaVersion
    ) {
        return query(
            """
            tenant_id = :tenantId and form_key = :formKey
            and form_version = :formVersion and ui_schema_version = :uiSchemaVersion
            """,
            parameters(tenantId, formKey, formVersion)
                .addValue("uiSchemaVersion", uiSchemaVersion),
            ""
        );
    }

    @Override
    public Optional<PublishedUiSchema> findLatest(
        String tenantId,
        String formKey,
        int formVersion
    ) {
        return query(
            "tenant_id = :tenantId and form_key = :formKey and form_version = :formVersion",
            parameters(tenantId, formKey, formVersion),
            "order by ui_schema_version desc limit 1"
        );
    }

    @Override
    public void save(PublishedUiSchema schema) {
        UiSchemaDefinition definition = schema.definition();
        int inserted = jdbc.update(
            """
            insert into ap_form_ui_schema (
                tenant_id, form_key, form_version, ui_schema_version, schema_version,
                name, section_count, schema_json, content_hash, published_by, published_at
            ) values (
                :tenantId, :formKey, :formVersion, :uiSchemaVersion, :schemaVersion,
                :name, :sectionCount, cast(:schemaJson as jsonb), :contentHash,
                :publishedBy, :publishedAt
            )
            """,
            parameters(schema.tenantId(), definition.formKey(), definition.formVersion())
                .addValue("uiSchemaVersion", definition.version())
                .addValue("schemaVersion", definition.schemaVersion())
                .addValue("name", definition.name())
                .addValue("sectionCount", definition.sections().size())
                .addValue("schemaJson", encode(definition))
                .addValue("contentHash", schema.contentHash())
                .addValue("publishedBy", schema.publishedBy())
                .addValue("publishedAt", offset(schema.publishedAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("UI Schema was not inserted");
        }
    }

    private Optional<PublishedUiSchema> query(
        String predicate,
        MapSqlParameterSource parameters,
        String suffix
    ) {
        return jdbc.query(
            """
            select tenant_id, schema_json, content_hash, published_by, published_at
            from ap_form_ui_schema where %s %s
            """.formatted(predicate, suffix),
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    private PublishedUiSchema item(ResultSet resultSet) throws SQLException {
        return new PublishedUiSchema(
            resultSet.getString("tenant_id"),
            decode(resultSet.getString("schema_json")),
            resultSet.getString("content_hash"),
            resultSet.getString("published_by"),
            resultSet.getObject("published_at", OffsetDateTime.class).toInstant()
        );
    }

    private MapSqlParameterSource parameters(String tenantId, String formKey, int formVersion) {
        return new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("formKey", formKey)
            .addValue("formVersion", formVersion);
    }

    private String encode(UiSchemaDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode UI Schema", exception);
        }
    }

    private UiSchemaDefinition decode(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, UiSchemaDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode UI Schema", exception);
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
