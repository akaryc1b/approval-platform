package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
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

/** PostgreSQL immutable Approval DSL version store. */
public final class JdbcApprovalDefinitionVersionStore
    implements ApprovalDefinitionVersionStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcApprovalDefinitionVersionStore(
        DataSource dataSource,
        ObjectMapper objectMapper
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
        this.objectMapper = ApprovalDefinitionJacksonSupport.configure(
            Objects.requireNonNull(objectMapper).copy()
        );
    }

    @Override
    public void lockVersion(String tenantId, String definitionKey, int version) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource().addValue(
                "lockKey",
                "approval-definition:" + tenantId + ':' + definitionKey + ':' + version
            ),
            resultSet -> null
        );
    }

    @Override
    public Optional<ApprovalDefinitionVersion> find(
        String tenantId,
        String definitionKey,
        int version
    ) {
        return query(
            "tenant_id = :tenantId and definition_key = :definitionKey "
                + "and definition_version = :version",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey)
                .addValue("version", version),
            ""
        );
    }

    @Override
    public Optional<ApprovalDefinitionVersion> findLatest(
        String tenantId,
        String definitionKey
    ) {
        return query(
            "tenant_id = :tenantId and definition_key = :definitionKey",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey),
            " order by definition_version desc limit 1"
        );
    }

    @Override
    public Optional<ApprovalDefinitionVersion> findByDraft(String tenantId, UUID draftId) {
        return query(
            "tenant_id = :tenantId and source_draft_id = :draftId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("draftId", draftId),
            ""
        );
    }

    @Override
    public VersionPage findVersions(VersionCriteria criteria) {
        List<String> predicates = new ArrayList<>();
        predicates.add("tenant_id = :tenantId");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        if (criteria.definitionKey() != null) {
            predicates.add("definition_key = :definitionKey");
            parameters.addValue("definitionKey", criteria.definitionKey());
        }
        String where = String.join(" and ", predicates);
        long total = Objects.requireNonNull(jdbc.queryForObject(
            "select count(*) from ap_approval_definition where " + where,
            parameters,
            Long.class
        ));
        List<ApprovalDefinitionVersion> items = jdbc.query(
            selectDefinition()
                + " where "
                + where
                + " order by definition_key, definition_version desc"
                + " limit :limit offset :offset",
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        );
        return new VersionPage(items, total, criteria.limit(), criteria.offset());
    }

    @Override
    public void save(ApprovalDefinitionVersion version) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_definition (
                tenant_id, definition_key, definition_version, definition_hash,
                form_package_version, form_package_hash, approval_dsl_json,
                source_draft_id, published_by, published_at
            ) values (
                :tenantId, :definitionKey, :definitionVersion, :definitionHash,
                :formPackageVersion, :formPackageHash, cast(:definitionJson as jsonb),
                :sourceDraftId, :publishedBy, :publishedAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", version.tenantId())
                .addValue("definitionKey", version.definitionKey())
                .addValue("definitionVersion", version.version())
                .addValue("definitionHash", version.contentHash())
                .addValue("formPackageVersion", version.formPackageVersion())
                .addValue("formPackageHash", version.formPackageHash())
                .addValue("definitionJson", encode(version.definition()))
                .addValue("sourceDraftId", version.sourceDraftId())
                .addValue("publishedBy", version.publishedBy())
                .addValue("publishedAt", offset(version.publishedAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("Approval DSL version was not inserted");
        }
    }

    private Optional<ApprovalDefinitionVersion> query(
        String predicate,
        MapSqlParameterSource parameters,
        String suffix
    ) {
        return jdbc.query(
            selectDefinition() + " where " + predicate + suffix,
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    private ApprovalDefinitionVersion item(ResultSet resultSet) throws SQLException {
        return new ApprovalDefinitionVersion(
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("definition_version"),
            resultSet.getString("definition_hash"),
            resultSet.getInt("form_package_version"),
            resultSet.getString("form_package_hash"),
            decode(resultSet.getString("approval_dsl_json"), ApprovalDefinition.class),
            resultSet.getObject("source_draft_id", UUID.class),
            resultSet.getString("published_by"),
            instant(resultSet, "published_at")
        );
    }

    private String encode(ApprovalDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
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

    private static String selectDefinition() {
        return """
            select tenant_id, definition_key, definition_version, definition_hash,
                   form_package_version, form_package_hash,
                   approval_dsl_json::text as approval_dsl_json,
                   source_draft_id, published_by, published_at
            from ap_approval_definition
            """;
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
