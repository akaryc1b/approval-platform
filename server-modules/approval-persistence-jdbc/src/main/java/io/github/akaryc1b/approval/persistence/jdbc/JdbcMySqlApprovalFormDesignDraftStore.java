package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDesignDraft;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL 8.4 Form Design Draft Store with optimistic revision compare-and-swap. */
public final class JdbcMySqlApprovalFormDesignDraftStore implements ApprovalFormDesignDraftStore {

    static final String FORM_JSON_ENCODING = "CANONICAL_JSON_TEXT_V1";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectMapper strictObjectMapper;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlUiSchemaCodec uiSchemaCodec;

    public JdbcMySqlApprovalFormDesignDraftStore(
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
                "JdbcMySqlApprovalFormDesignDraftStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.strictObjectMapper = objectMapper.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.uiSchemaCodec = new JdbcMySqlUiSchemaCodec(objectMapper);
    }

    @Override
    public void save(FormDesignDraft draft) {
        FormDesignDraft exact = Objects.requireNonNull(
            draft,
            "draft must not be null"
        );
        int inserted = jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name, form_version, ui_schema_version,
                form_schema_json, ui_schema_json, source_form_version, source_ui_schema_version,
                revision, status, published_package_version, created_by, updated_by,
                created_at, updated_at
            ) values (
                :tenantId, :draftId, :formKey, :name, :formVersion, :uiSchemaVersion,
                cast(:formJson as json), cast(:uiJson as json), :sourceFormVersion,
                :sourceUiSchemaVersion, :revision, :status, :publishedPackageVersion,
                :createdBy, :updatedBy, :createdAt, :updatedAt
            )
            """,
            parameters(exact)
        );
        if (inserted != 1) {
            throw new IllegalStateException("form design draft was not inserted");
        }
    }

    @Override
    public Optional<FormDesignDraft> find(String tenantId, UUID draftId) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactDraftId = Objects.requireNonNull(draftId, "draftId must not be null");
        return jdbc.query(
            """
            select tenant_id, draft_id, form_key, name, form_schema_json, ui_schema_json,
                   source_form_version, source_ui_schema_version, revision, status,
                   published_package_version, created_by, updated_by, created_at, updated_at
            from ap_form_design_draft
            where tenant_id = :tenantId and draft_id = :draftId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("draftId", values.bindUuid(exactDraftId)),
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    @Override
    public DraftPage findDrafts(DraftCriteria criteria) {
        DraftCriteria exact = Objects.requireNonNull(
            criteria,
            "criteria must not be null"
        );
        String keyword = exact.keyword() == null ? "" : exact.keyword();
        String statusPredicate = exact.status() == null ? "" : "and status = :status";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", exact.tenantId())
            .addValue("keyword", keyword)
            .addValue("limit", exact.limit())
            .addValue("offset", exact.offset());
        if (exact.status() != null) {
            parameters.addValue("status", exact.status().name());
        }

        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_form_design_draft
            where tenant_id = :tenantId
              and (
                :keyword = ''
                or lower(form_key) like concat('%', lower(:keyword), '%')
                or lower(name) like concat('%', lower(:keyword), '%')
              )
              __STATUS__
            """.replace("__STATUS__", statusPredicate),
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new DraftPage(List.of(), 0, exact.limit(), exact.offset());
        }

        List<DraftSummary> items = jdbc.query(
            """
            select draft_id, form_key, name, form_version, ui_schema_version, revision,
                   status, published_package_version, updated_by, updated_at
            from ap_form_design_draft
            where tenant_id = :tenantId
              and (
                :keyword = ''
                or lower(form_key) like concat('%', lower(:keyword), '%')
                or lower(name) like concat('%', lower(:keyword), '%')
              )
              __STATUS__
            order by updated_at desc, draft_id
            limit :limit offset :offset
            """.replace("__STATUS__", statusPredicate),
            parameters,
            (resultSet, rowNumber) -> new DraftSummary(
                values.uuid(resultSet, "draft_id"),
                resultSet.getString("form_key"),
                resultSet.getString("name"),
                resultSet.getInt("form_version"),
                resultSet.getInt("ui_schema_version"),
                resultSet.getLong("revision"),
                FormDesignDraft.Status.valueOf(resultSet.getString("status")),
                integer(resultSet, "published_package_version"),
                resultSet.getString("updated_by"),
                values.instant(resultSet, "updated_at")
            )
        );
        return new DraftPage(items, matched, exact.limit(), exact.offset());
    }

    @Override
    public void lock(String tenantId, UUID draftId) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactDraftId = Objects.requireNonNull(draftId, "draftId must not be null");
        jdbc.query(
            """
            select revision
            from ap_form_design_draft
            where tenant_id = :tenantId and draft_id = :draftId
            for update
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("draftId", values.bindUuid(exactDraftId)),
            resultSet -> null
        );
    }

    @Override
    public boolean update(FormDesignDraft draft, long expectedRevision) {
        FormDesignDraft exact = Objects.requireNonNull(
            draft,
            "draft must not be null"
        );
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        int updated = jdbc.update(
            """
            update ap_form_design_draft
            set form_key = :formKey, name = :name, form_version = :formVersion,
                ui_schema_version = :uiSchemaVersion,
                form_schema_json = cast(:formJson as json),
                ui_schema_json = cast(:uiJson as json),
                source_form_version = :sourceFormVersion,
                source_ui_schema_version = :sourceUiSchemaVersion,
                revision = :revision, status = :status,
                published_package_version = :publishedPackageVersion,
                updated_by = :updatedBy, updated_at = :updatedAt
            where tenant_id = :tenantId and draft_id = :draftId
              and revision = :expectedRevision and status in ('DRAFT', 'VALIDATED')
            """,
            parameters(exact).addValue("expectedRevision", expectedRevision)
        );
        return updated == 1;
    }

    private MapSqlParameterSource parameters(FormDesignDraft draft) {
        return new MapSqlParameterSource()
            .addValue("tenantId", draft.tenantId())
            .addValue("draftId", values.bindUuid(draft.draftId()))
            .addValue("formKey", draft.formKey())
            .addValue("name", draft.name())
            .addValue("formVersion", draft.formDefinition().version())
            .addValue("uiSchemaVersion", draft.uiSchemaDefinition().version())
            .addValue("formJson", encodeForm(draft.formDefinition()))
            .addValue("uiJson", uiSchemaCodec.encode(draft.uiSchemaDefinition()))
            .addValue("sourceFormVersion", draft.sourceFormVersion())
            .addValue("sourceUiSchemaVersion", draft.sourceUiSchemaVersion())
            .addValue("revision", draft.revision())
            .addValue("status", draft.status().name())
            .addValue("publishedPackageVersion", draft.publishedPackageVersion())
            .addValue("createdBy", draft.createdBy())
            .addValue("updatedBy", draft.updatedBy())
            .addValue(
                "createdAt",
                values.bindInstant(canonicalInstant(draft.createdAt()))
            )
            .addValue(
                "updatedAt",
                values.bindInstant(canonicalInstant(draft.updatedAt()))
            );
    }

    private FormDesignDraft item(ResultSet resultSet) throws SQLException {
        return new FormDesignDraft(
            values.uuid(resultSet, "draft_id"),
            resultSet.getString("tenant_id"),
            resultSet.getString("form_key"),
            resultSet.getString("name"),
            decodeForm(resultSet.getString("form_schema_json")),
            uiSchemaCodec.decode(resultSet.getString("ui_schema_json")),
            integer(resultSet, "source_form_version"),
            integer(resultSet, "source_ui_schema_version"),
            resultSet.getLong("revision"),
            FormDesignDraft.Status.valueOf(resultSet.getString("status")),
            integer(resultSet, "published_package_version"),
            resultSet.getString("created_by"),
            resultSet.getString("updated_by"),
            values.instant(resultSet, "created_at"),
            values.instant(resultSet, "updated_at")
        );
    }

    private String encodeForm(FormDefinition definition) {
        try {
            String payload = objectMapper.writeValueAsString(definition);
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("encoding", FORM_JSON_ENCODING);
            envelope.put("payload", payload);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "unable to encode MySQL form design draft Form Schema envelope",
                exception
            );
        }
    }

    private FormDefinition decodeForm(String json) throws SQLException {
        if (json == null) {
            throw new SQLException("MySQL form design draft Form Schema envelope was null");
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
                || !FORM_JSON_ENCODING.equals(encoding.textValue())
                || payload == null
                || !payload.isTextual()) {
                throw new SQLException(
                    "invalid or unsupported MySQL form design draft Form Schema envelope"
                );
            }
            return strictObjectMapper.readValue(
                payload.textValue(),
                FormDefinition.class
            );
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new SQLException(
                "unable to decode MySQL form design draft Form Schema envelope",
                exception
            );
        }
    }

    private static Instant canonicalInstant(Instant value) {
        return Objects.requireNonNull(
            value,
            "instant must not be null"
        ).truncatedTo(ChronoUnit.MICROS);
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
