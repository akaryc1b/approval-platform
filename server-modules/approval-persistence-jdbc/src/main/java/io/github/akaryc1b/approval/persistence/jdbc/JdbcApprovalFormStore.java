package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL immutable form schema repository.
 */
public final class JdbcApprovalFormStore implements ApprovalFormStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalFormStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void lockVersion(String tenantId, String formKey, int version) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource().addValue(
                "lockKey",
                "form:" + tenantId + ':' + formKey + ':' + version
            ),
            resultSet -> null
        );
    }

    @Override
    public Optional<PublishedForm> find(String tenantId, String formKey, int version) {
        return jdbc.query(
            """
            select tenant_id, schema_json, content_hash, published_by, published_at
            from ap_form_definition
            where tenant_id = :tenantId
              and form_key = :formKey
              and form_version = :formVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("formKey", formKey)
                .addValue("formVersion", version),
            (resultSet, rowNumber) -> publishedForm(resultSet)
        ).stream().findFirst();
    }

    @Override
    public void save(PublishedForm form) {
        FormDefinition definition = form.definition();
        int inserted = jdbc.update(
            """
            insert into ap_form_definition (
                tenant_id, form_key, form_version, schema_version, name,
                field_count, schema_json, content_hash, published_by, published_at
            ) values (
                :tenantId, :formKey, :formVersion, :schemaVersion, :name,
                :fieldCount, cast(:schemaJson as jsonb), :contentHash, :publishedBy, :publishedAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", form.tenantId())
                .addValue("formKey", definition.formKey())
                .addValue("formVersion", definition.version())
                .addValue("schemaVersion", definition.schemaVersion())
                .addValue("name", definition.name())
                .addValue("fieldCount", definition.fields().size())
                .addValue("schemaJson", encode(definition))
                .addValue("contentHash", form.contentHash())
                .addValue("publishedBy", form.publishedBy())
                .addValue("publishedAt", offset(form.publishedAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("form definition was not inserted");
        }
    }

    @Override
    public FormPage findForms(FormCriteria criteria) {
        String keyword = criteria.keyword() == null ? "" : criteria.keyword();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("keyword", keyword)
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_form_definition
            where tenant_id = :tenantId
              and (
                :keyword = ''
                or lower(form_key) like lower('%' || :keyword || '%')
                or lower(name) like lower('%' || :keyword || '%')
              )
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new FormPage(List.of(), 0, criteria.limit(), criteria.offset());
        }
        List<FormSummary> items = jdbc.query(
            """
            select form_key, form_version, name, schema_version, field_count,
                   content_hash, published_by, published_at
            from ap_form_definition
            where tenant_id = :tenantId
              and (
                :keyword = ''
                or lower(form_key) like lower('%' || :keyword || '%')
                or lower(name) like lower('%' || :keyword || '%')
              )
            order by published_at desc, form_key, form_version desc
            limit :limit offset :offset
            """,
            parameters,
            (resultSet, rowNumber) -> new FormSummary(
                resultSet.getString("form_key"),
                resultSet.getInt("form_version"),
                resultSet.getString("name"),
                resultSet.getString("schema_version"),
                resultSet.getInt("field_count"),
                resultSet.getString("content_hash"),
                resultSet.getString("published_by"),
                instant(resultSet, "published_at")
            )
        );
        return new FormPage(items, matched, criteria.limit(), criteria.offset());
    }

    private PublishedForm publishedForm(ResultSet resultSet) throws SQLException {
        return new PublishedForm(
            resultSet.getString("tenant_id"),
            decode(resultSet.getString("schema_json")),
            resultSet.getString("content_hash"),
            resultSet.getString("published_by"),
            instant(resultSet, "published_at")
        );
    }

    private String encode(FormDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode form schema", exception);
        }
    }

    private FormDefinition decode(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, FormDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode form schema", exception);
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
