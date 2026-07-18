package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL tenant-isolated Approval DSL draft store. */
public final class JdbcApprovalDesignDraftStore implements ApprovalDesignDraftStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalDesignDraftStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.objectMapper = ApprovalDefinitionJacksonSupport.configure(
            Objects.requireNonNull(objectMapper).copy()
        );
    }

    @Override
    public void save(ApprovalDesignDraft draft) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_design_draft (
                tenant_id, draft_id, definition_key, name, definition_version,
                approval_dsl_json, form_package_version, form_package_hash,
                source_definition_version, revision, status,
                published_definition_version, published_release_version,
                created_by, updated_by, created_at, updated_at
            ) values (
                :tenantId, :draftId, :definitionKey, :name, :definitionVersion,
                cast(:definitionJson as jsonb), :formPackageVersion, :formPackageHash,
                :sourceDefinitionVersion, :revision, :status,
                :publishedDefinitionVersion, :publishedReleaseVersion,
                :createdBy, :updatedBy, :createdAt, :updatedAt
            )
            """,
            parameters(draft)
        );
        if (inserted != 1) {
            throw new IllegalStateException("Approval DSL draft was not inserted");
        }
    }

    @Override
    public Optional<ApprovalDesignDraft> find(String tenantId, UUID draftId) {
        return jdbc.query(
            selectDraft() + " where tenant_id = :tenantId and draft_id = :draftId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("draftId", draftId),
            (resultSet, rowNumber) -> draft(resultSet)
        ).stream().findFirst();
    }

    @Override
    public DraftPage findDrafts(DraftCriteria criteria) {
        List<String> predicates = new ArrayList<>();
        predicates.add("tenant_id = :tenantId");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        if (criteria.keyword() != null) {
            predicates.add("(lower(name) like :keyword or lower(definition_key) like :keyword)");
            parameters.addValue("keyword", '%' + criteria.keyword().toLowerCase() + '%');
        }
        if (criteria.status() != null) {
            predicates.add("status = :status");
            parameters.addValue("status", criteria.status().name());
        }
        String where = String.join(" and ", predicates);
        long total = Objects.requireNonNull(jdbc.queryForObject(
            "select count(*) from ap_approval_design_draft where " + where,
            parameters,
            Long.class
        ));
        List<DraftSummary> items = jdbc.query(
            """
            select draft_id, definition_key, name, definition_version,
                   form_package_version, revision, status,
                   published_definition_version, published_release_version,
                   updated_by, updated_at
            from ap_approval_design_draft
            where %s
            order by updated_at desc, draft_id
            limit :limit offset :offset
            """.formatted(where),
            parameters,
            (resultSet, rowNumber) -> new DraftSummary(
                resultSet.getObject("draft_id", UUID.class),
                resultSet.getString("definition_key"),
                resultSet.getString("name"),
                resultSet.getInt("definition_version"),
                resultSet.getInt("form_package_version"),
                resultSet.getLong("revision"),
                ApprovalDesignDraft.Status.valueOf(resultSet.getString("status")),
                integer(resultSet, "published_definition_version"),
                integer(resultSet, "published_release_version"),
                resultSet.getString("updated_by"),
                instant(resultSet, "updated_at")
            )
        );
        return new DraftPage(items, total, criteria.limit(), criteria.offset());
    }

    @Override
    public void lock(String tenantId, UUID draftId) {
        jdbc.query(
            """
            select draft_id from ap_approval_design_draft
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
    public boolean update(ApprovalDesignDraft draft, long expectedRevision) {
        MapSqlParameterSource parameters = parameters(draft)
            .addValue("expectedRevision", expectedRevision);
        return jdbc.update(
            """
            update ap_approval_design_draft
            set name = :name,
                definition_version = :definitionVersion,
                approval_dsl_json = cast(:definitionJson as jsonb),
                form_package_version = :formPackageVersion,
                form_package_hash = :formPackageHash,
                source_definition_version = :sourceDefinitionVersion,
                revision = :revision,
                status = :status,
                published_definition_version = :publishedDefinitionVersion,
                published_release_version = :publishedReleaseVersion,
                updated_by = :updatedBy,
                updated_at = :updatedAt
            where tenant_id = :tenantId
              and draft_id = :draftId
              and revision = :expectedRevision
              and status in ('DRAFT', 'VALIDATED')
            """,
            parameters
        ) == 1;
    }

    private MapSqlParameterSource parameters(ApprovalDesignDraft draft) {
        return new MapSqlParameterSource()
            .addValue("tenantId", draft.tenantId())
            .addValue("draftId", draft.draftId())
            .addValue("definitionKey", draft.definitionKey())
            .addValue("name", draft.name())
            .addValue("definitionVersion", draft.definition().version())
            .addValue("definitionJson", encode(draft.definition()))
            .addValue("formPackageVersion", draft.formPackage().packageVersion())
            .addValue("formPackageHash", draft.formPackage().packageHash())
            .addValue("sourceDefinitionVersion", draft.sourceDefinitionVersion())
            .addValue("revision", draft.revision())
            .addValue("status", draft.status().name())
            .addValue("publishedDefinitionVersion", draft.publishedDefinitionVersion())
            .addValue("publishedReleaseVersion", draft.publishedReleaseVersion())
            .addValue("createdBy", draft.createdBy())
            .addValue("updatedBy", draft.updatedBy())
            .addValue("createdAt", offset(draft.createdAt()))
            .addValue("updatedAt", offset(draft.updatedAt()));
    }

    private ApprovalDesignDraft draft(ResultSet resultSet) throws SQLException {
        ApprovalDefinition definition = decode(
            resultSet.getString("approval_dsl_json"),
            ApprovalDefinition.class
        );
        return new ApprovalDesignDraft(
            resultSet.getObject("draft_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getString("name"),
            definition,
            new ApprovalDesignDraft.FormPackageReference(
                resultSet.getString("definition_key"),
                resultSet.getInt("form_package_version"),
                resultSet.getString("form_package_hash")
            ),
            integer(resultSet, "source_definition_version"),
            resultSet.getLong("revision"),
            ApprovalDesignDraft.Status.valueOf(resultSet.getString("status")),
            integer(resultSet, "published_definition_version"),
            integer(resultSet, "published_release_version"),
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
            throw new IllegalArgumentException("unable to encode Approval DSL", exception);
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to decode Approval DSL", exception);
        }
    }

    private static String selectDraft() {
        return """
            select tenant_id, draft_id, definition_key, name, definition_version,
                   approval_dsl_json::text as approval_dsl_json,
                   form_package_version, form_package_hash, source_definition_version,
                   revision, status, published_definition_version, published_release_version,
                   created_by, updated_by, created_at, updated_at
            from ap_approval_design_draft
            """;
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
