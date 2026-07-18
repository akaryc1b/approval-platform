package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
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

/** PostgreSQL immutable deployable Approval Release Package store. */
public final class JdbcApprovalReleasePackageStore
    implements ApprovalReleasePackageStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalReleasePackageStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
    }

    @Override
    public void lockVersion(String tenantId, String definitionKey, int releaseVersion) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource().addValue(
                "lockKey",
                "approval-release:" + tenantId + ':' + definitionKey + ':' + releaseVersion
            ),
            resultSet -> null
        );
    }

    @Override
    public Optional<ApprovalReleasePackage> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return query(
            "tenant_id = :tenantId and definition_key = :definitionKey "
                + "and release_version = :releaseVersion",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey)
                .addValue("releaseVersion", releaseVersion),
            ""
        );
    }

    @Override
    public Optional<ApprovalReleasePackage> findLatest(
        String tenantId,
        String definitionKey
    ) {
        return query(
            "tenant_id = :tenantId and definition_key = :definitionKey",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey),
            " order by release_version desc limit 1"
        );
    }

    @Override
    public Optional<ApprovalReleasePackage> findByDraft(String tenantId, UUID draftId) {
        return query(
            "tenant_id = :tenantId and source_draft_id = :draftId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("draftId", draftId),
            ""
        );
    }

    @Override
    public ReleasePage findReleases(ReleaseCriteria criteria) {
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
            "select count(*) from ap_approval_release_package where " + where,
            parameters,
            Long.class
        ));
        List<ApprovalReleasePackage> items = jdbc.query(
            selectRelease()
                + " where "
                + where
                + " order by definition_key, release_version desc"
                + " limit :limit offset :offset",
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        );
        return new ReleasePage(items, total, criteria.limit(), criteria.offset());
    }

    @Override
    public void save(ApprovalReleasePackage releasePackage) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_release_package (
                tenant_id, definition_key, release_version,
                definition_version, definition_hash,
                form_package_version, form_package_hash,
                form_version, form_hash, ui_schema_version, ui_schema_hash,
                compiler_version, bpmn_resource_name, bpmn_artifact,
                compiled_artifact_hash, bpmn_hash,
                dmn_artifact, dmn_hash, deployment_metadata_hash, package_hash,
                source_draft_id, published_by, published_at
            ) values (
                :tenantId, :definitionKey, :releaseVersion,
                :definitionVersion, :definitionHash,
                :formPackageVersion, :formPackageHash,
                :formVersion, :formHash, :uiSchemaVersion, :uiSchemaHash,
                :compilerVersion, :bpmnResourceName, :bpmnArtifact,
                :compiledArtifactHash, :bpmnHash,
                :dmnArtifact, :dmnHash, :deploymentMetadataHash, :packageHash,
                :sourceDraftId, :publishedBy, :publishedAt
            )
            """,
            parameters(releasePackage)
        );
        if (inserted != 1) {
            throw new IllegalStateException("Approval Release Package was not inserted");
        }
    }

    private Optional<ApprovalReleasePackage> query(
        String predicate,
        MapSqlParameterSource parameters,
        String suffix
    ) {
        return jdbc.query(
            selectRelease() + " where " + predicate + suffix,
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    private static MapSqlParameterSource parameters(ApprovalReleasePackage value) {
        return new MapSqlParameterSource()
            .addValue("tenantId", value.tenantId())
            .addValue("definitionKey", value.definitionKey())
            .addValue("releaseVersion", value.releaseVersion())
            .addValue("definitionVersion", value.definitionVersion())
            .addValue("definitionHash", value.definitionHash())
            .addValue("formPackageVersion", value.formPackageVersion())
            .addValue("formPackageHash", value.formPackageHash())
            .addValue("formVersion", value.formVersion())
            .addValue("formHash", value.formHash())
            .addValue("uiSchemaVersion", value.uiSchemaVersion())
            .addValue("uiSchemaHash", value.uiSchemaHash())
            .addValue("compilerVersion", value.compilerVersion())
            .addValue("bpmnResourceName", value.bpmnResourceName())
            .addValue("bpmnArtifact", value.bpmnArtifact())
            .addValue("compiledArtifactHash", value.compiledArtifactHash())
            .addValue("bpmnHash", value.bpmnHash())
            .addValue("dmnArtifact", value.dmnArtifact())
            .addValue("dmnHash", value.dmnHash())
            .addValue("deploymentMetadataHash", value.deploymentMetadataHash())
            .addValue("packageHash", value.packageHash())
            .addValue("sourceDraftId", value.sourceDraftId())
            .addValue("publishedBy", value.publishedBy())
            .addValue("publishedAt", offset(value.publishedAt()));
    }

    private static ApprovalReleasePackage item(ResultSet resultSet) throws SQLException {
        return new ApprovalReleasePackage(
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("release_version"),
            resultSet.getInt("definition_version"),
            resultSet.getString("definition_hash"),
            resultSet.getInt("form_package_version"),
            resultSet.getString("form_package_hash"),
            resultSet.getInt("form_version"),
            resultSet.getString("form_hash"),
            resultSet.getInt("ui_schema_version"),
            resultSet.getString("ui_schema_hash"),
            resultSet.getString("compiler_version"),
            resultSet.getString("bpmn_resource_name"),
            resultSet.getString("bpmn_artifact"),
            resultSet.getString("compiled_artifact_hash"),
            resultSet.getString("bpmn_hash"),
            resultSet.getString("dmn_artifact"),
            resultSet.getString("dmn_hash"),
            resultSet.getString("deployment_metadata_hash"),
            resultSet.getString("package_hash"),
            resultSet.getObject("source_draft_id", UUID.class),
            resultSet.getString("published_by"),
            instant(resultSet, "published_at")
        );
    }

    private static String selectRelease() {
        return """
            select tenant_id, definition_key, release_version,
                   definition_version, definition_hash,
                   form_package_version, form_package_hash,
                   form_version, form_hash, ui_schema_version, ui_schema_hash,
                   compiler_version, bpmn_resource_name, bpmn_artifact,
                   compiled_artifact_hash, bpmn_hash,
                   dmn_artifact, dmn_hash, deployment_metadata_hash, package_hash,
                   source_draft_id, published_by, published_at
            from ap_approval_release_package
            """;
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
