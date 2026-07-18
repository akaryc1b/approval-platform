package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDesignDraft;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
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
import java.util.UUID;

/** PostgreSQL design draft store using revision-based compare-and-swap updates. */
public final class JdbcApprovalFormDesignDraftStore implements ApprovalFormDesignDraftStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalFormDesignDraftStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void save(FormDesignDraft draft) {
        int inserted = jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name, form_version, ui_schema_version,
                form_schema_json, ui_schema_json, source_form_version, source_ui_schema_version,
                revision, status, published_package_version, created_by, updated_by,
                created_at, updated_at
            ) values (
                :tenantId, :draftId, :formKey, :name, :formVersion, :uiSchemaVersion,
                cast(:formJson as jsonb), cast(:uiJson as jsonb), :sourceFormVersion,
                :sourceUiSchemaVersion, :revision, :status, :publishedPackageVersion,
                :createdBy, :updatedBy, :createdAt, :updatedAt
            )
            """,
            parameters(draft)
        );
        if (inserted != 1) {
            throw new IllegalStateException("form design draft was not inserted");
        }
    }

    @Override
    public Optional<FormDesignDraft> find(String tenantId, UUID draftId) {
        return jdbc.query(
            """
            select tenant_id, draft_id, form_key, name, form_schema_json, ui_schema_json,
                   source_form_version, source_ui_schema_version, revision, status,
                   published_package_version, created_by, updated_by, created_at, updated_at
            from ap_form_design_draft
            where tenant_id = :tenantId and draft_id = :draftId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("draftId", draftId),
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    @Override
    public DraftPage findDrafts(DraftCriteria criteria) {
        String keyword = criteria.keyword() == null ? "" : criteria.keyword();
        String statusPredicate = criteria.status() == null ? "" : "and status = :status";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("keyword", keyword)
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        if (criteria.status() != null) {
            parameters.addValue("status", criteria.status().name());
        }
        Long total = jdbc.queryForObject(
            """
            select count(*) from ap_form_design_draft
            where tenant_id = :tenantId
              and (:keyword = '' or lower(form_key) like lower('%' || :keyword || '%')
                   or lower(name) like lower('%' || :keyword || '%'))
              %s
            """.formatted(statusPredicate),
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        if (matched == 0) {
            return new DraftPage(List.of(), 0, criteria.limit(), criteria.offset());
        }
        List<DraftSummary> items = jdbc.query(
            """
            select draft_id, form_key, name, form_version, ui_schema_version, revision,
                   status, published_package_version, updated_by, updated_at
            from ap_form_design_draft
            where tenant_id = :tenantId
              and (:keyword = '' or lower(form_key) like lower('%' || :keyword || '%')
                   or lower(name) like lower('%' || :keyword || '%'))
              %s
            order by updated_at desc, draft_id
            limit :limit offset :offset
            """.formatted(statusPredicate),
            parameters,
            (resultSet, rowNumber) -> new DraftSummary(
                resultSet.getObject("draft_id", UUID.class),
                resultSet.getString("form_key"),
                resultSet.getString("name"),
                resultSet.getInt("form_version"),
                resultSet.getInt("ui_schema_version"),
                resultSet.getLong("revision"),
                FormDesignDraft.Status.valueOf(resultSet.getString("status")),
                integer(resultSet, "published_package_version"),
                resultSet.getString("updated_by"),
                instant(resultSet, "updated_at")
            )
        );
        return new DraftPage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public void lock(String tenantId, UUID draftId) {
        jdbc.query(
            """
            select revision from ap_form_design_draft
            where tenant_id = :tenantId and draft_id = :draftId
            for update
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("draftId", draftId),
            resultSet -> null
        );
    }

    @Override
    public boolean update(FormDesignDraft draft, long expectedRevision) {
        int updated = jdbc.update(
            """
            update ap_form_design_draft
            set form_key = :formKey, name = :name, form_version = :formVersion,
                ui_schema_version = :uiSchemaVersion,
                form_schema_json = cast(:formJson as jsonb),
                ui_schema_json = cast(:uiJson as jsonb),
                source_form_version = :sourceFormVersion,
                source_ui_schema_version = :sourceUiSchemaVersion,
                revision = :revision, status = :status,
                published_package_version = :publishedPackageVersion,
                updated_by = :updatedBy, updated_at = :updatedAt
            where tenant_id = :tenantId and draft_id = :draftId
              and revision = :expectedRevision and status in ('DRAFT', 'VALIDATED')
            """,
            parameters(draft).addValue("expectedRevision", expectedRevision)
        );
        return updated == 1;
    }

    private MapSqlParameterSource parameters(FormDesignDraft draft) {
        return new MapSqlParameterSource()
            .addValue("tenantId", draft.tenantId())
            .addValue("draftId", draft.draftId())
            .addValue("formKey", draft.formKey())
            .addValue("name", draft.name())
            .addValue("formVersion", draft.formDefinition().version())
            .addValue("uiSchemaVersion", draft.uiSchemaDefinition().version())
            .addValue("formJson", encode(draft.formDefinition()))
            .addValue("uiJson", encode(draft.uiSchemaDefinition()))
            .addValue("sourceFormVersion", draft.sourceFormVersion())
            .addValue("sourceUiSchemaVersion", draft.sourceUiSchemaVersion())
            .addValue("revision", draft.revision())
            .addValue("status", draft.status().name())
            .addValue("publishedPackageVersion", draft.publishedPackageVersion())
            .addValue("createdBy", draft.createdBy())
            .addValue("updatedBy", draft.updatedBy())
            .addValue("createdAt", offset(draft.createdAt()))
            .addValue("updatedAt", offset(draft.updatedAt()));
    }

    private FormDesignDraft item(ResultSet resultSet) throws SQLException {
        return new FormDesignDraft(
            resultSet.getObject("draft_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("form_key"),
            resultSet.getString("name"),
            decode(resultSet.getString("form_schema_json"), FormDefinition.class),
            decode(resultSet.getString("ui_schema_json"), UiSchemaDefinition.class),
            integer(resultSet, "source_form_version"),
            integer(resultSet, "source_ui_schema_version"),
            resultSet.getLong("revision"),
            FormDesignDraft.Status.valueOf(resultSet.getString("status")),
            integer(resultSet, "published_package_version"),
            resultSet.getString("created_by"),
            resultSet.getString("updated_by"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at")
        );
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode form design draft", exception);
        }
    }

    private <T> T decode(String json, Class<T> type) throws SQLException {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new SQLException("unable to decode form design draft", exception);
        }
    }

    private static Integer integer(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
