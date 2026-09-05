package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** MySQL 8.4 immutable Form Definition Store with exact JSON text preservation. */
public final class JdbcMySqlApprovalFormStore implements ApprovalFormStore {

    static final String JSON_ENCODING = "CANONICAL_JSON_TEXT_V1";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;

    public JdbcMySqlApprovalFormStore(
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
                "JdbcMySqlApprovalFormStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.locks = new JdbcMySqlTransactionLockManager(source);
    }

    @Override
    public void lockVersion(String tenantId, String formKey, int version) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactFormKey = requireText(formKey, "formKey");
        int exactVersion = requirePositive(version, "version");
        locks.acquire(
            "form:" + exactTenant + ':' + exactFormKey + ':' + exactVersion
        );
    }

    @Override
    public Optional<PublishedForm> find(
        String tenantId,
        String formKey,
        int version
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactFormKey = requireText(formKey, "formKey");
        int exactVersion = requirePositive(version, "version");
        return jdbc.query(
            """
            select tenant_id, schema_json, content_hash, published_by, published_at
            from ap_form_definition
            where tenant_id = :tenantId
              and form_key = :formKey
              and form_version = :formVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("formKey", exactFormKey)
                .addValue("formVersion", exactVersion),
            (resultSet, rowNumber) -> publishedForm(resultSet)
        ).stream().findFirst();
    }

    @Override
    public void save(PublishedForm form) {
        PublishedForm exact = Objects.requireNonNull(
            form,
            "form must not be null"
        );
        FormDefinition definition = exact.definition();
        int inserted = jdbc.update(
            """
            insert into ap_form_definition (
                tenant_id, form_key, form_version, schema_version, name,
                field_count, schema_json, content_hash, published_by, published_at
            ) values (
                :tenantId, :formKey, :formVersion, :schemaVersion, :name,
                :fieldCount, cast(:schemaJson as json),
                :contentHash, :publishedBy, :publishedAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exact.tenantId())
                .addValue("formKey", definition.formKey())
                .addValue("formVersion", definition.version())
                .addValue("schemaVersion", definition.schemaVersion())
                .addValue("name", definition.name())
                .addValue("fieldCount", definition.fields().size())
                .addValue("schemaJson", encodeEnvelope(definition))
                .addValue("contentHash", exact.contentHash())
                .addValue("publishedBy", exact.publishedBy())
                .addValue(
                    "publishedAt",
                    values.bindInstant(canonicalInstant(exact.publishedAt()))
                )
        );
        if (inserted != 1) {
            throw new IllegalStateException("form definition was not inserted");
        }
    }

    @Override
    public FormPage findForms(FormCriteria criteria) {
        FormCriteria exact = Objects.requireNonNull(
            criteria,
            "criteria must not be null"
        );
        String keyword = exact.keyword() == null ? "" : exact.keyword();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", exact.tenantId())
            .addValue("keyword", keyword)
            .addValue("limit", exact.limit())
            .addValue("offset", exact.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_form_definition
            where tenant_id = :tenantId
              and (
                :keyword = ''
                or lower(form_key) like concat('%', lower(:keyword), '%')
                or lower(name) like concat('%', lower(:keyword), '%')
              )
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new FormPage(
                List.of(),
                0,
                exact.limit(),
                exact.offset()
            );
        }
        List<FormSummary> items = jdbc.query(
            """
            select form_key, form_version, name, schema_version, field_count,
                   content_hash, published_by, published_at
            from ap_form_definition
            where tenant_id = :tenantId
              and (
                :keyword = ''
                or lower(form_key) like concat('%', lower(:keyword), '%')
                or lower(name) like concat('%', lower(:keyword), '%')
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
                values.instant(resultSet, "published_at")
            )
        );
        return new FormPage(
            items,
            matched,
            exact.limit(),
            exact.offset()
        );
    }

    private PublishedForm publishedForm(ResultSet resultSet) throws SQLException {
        return new PublishedForm(
            resultSet.getString("tenant_id"),
            decodeEnvelope(resultSet.getString("schema_json")),
            resultSet.getString("content_hash"),
            resultSet.getString("published_by"),
            values.instant(resultSet, "published_at")
        );
    }

    private String encodeEnvelope(FormDefinition definition) {
        try {
            String payload = objectMapper.writeValueAsString(definition);
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("encoding", JSON_ENCODING);
            envelope.put("payload", payload);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "unable to encode MySQL form schema envelope",
                exception
            );
        }
    }

    private FormDefinition decodeEnvelope(String json) throws SQLException {
        if (json == null) {
            throw new SQLException("MySQL form schema envelope was null");
        }
        try {
            JsonNode envelope = objectMapper.readTree(json);
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
                    "invalid or unsupported MySQL form schema envelope"
                );
            }
            return objectMapper.readValue(
                payload.textValue(),
                FormDefinition.class
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException(
                "unable to decode MySQL form schema envelope",
                exception
            );
        }
    }

    private static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(value, "publishedAt must not be null")
        );
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

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
