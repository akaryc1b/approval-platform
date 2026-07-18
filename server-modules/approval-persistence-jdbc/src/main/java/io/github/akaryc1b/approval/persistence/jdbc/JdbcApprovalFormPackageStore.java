package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.domain.form.FormPackage;
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
import java.util.UUID;

/** PostgreSQL immutable Form Package store. */
public final class JdbcApprovalFormPackageStore implements ApprovalFormPackageStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalFormPackageStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
    }

    @Override
    public void lockVersion(String tenantId, String formKey, int packageVersion) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource().addValue(
                "lockKey",
                "form-package:" + tenantId + ':' + formKey + ':' + packageVersion
            ),
            resultSet -> null
        );
    }

    @Override
    public Optional<FormPackage> find(String tenantId, String formKey, int packageVersion) {
        return query(
            "tenant_id = :tenantId and form_key = :formKey and package_version = :packageVersion",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("formKey", formKey)
                .addValue("packageVersion", packageVersion)
        );
    }

    @Override
    public Optional<FormPackage> findByDraft(String tenantId, UUID draftId) {
        return query(
            "tenant_id = :tenantId and source_draft_id = :draftId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("draftId", draftId)
        );
    }

    @Override
    public void save(FormPackage formPackage) {
        int inserted = jdbc.update(
            """
            insert into ap_form_package (
                tenant_id, form_key, package_version, form_version, form_hash,
                ui_schema_version, ui_schema_hash, package_hash, source_draft_id,
                published_by, published_at
            ) values (
                :tenantId, :formKey, :packageVersion, :formVersion, :formHash,
                :uiSchemaVersion, :uiSchemaHash, :packageHash, :sourceDraftId,
                :publishedBy, :publishedAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", formPackage.tenantId())
                .addValue("formKey", formPackage.formKey())
                .addValue("packageVersion", formPackage.packageVersion())
                .addValue("formVersion", formPackage.formVersion())
                .addValue("formHash", formPackage.formHash())
                .addValue("uiSchemaVersion", formPackage.uiSchemaVersion())
                .addValue("uiSchemaHash", formPackage.uiSchemaHash())
                .addValue("packageHash", formPackage.packageHash())
                .addValue("sourceDraftId", formPackage.sourceDraftId())
                .addValue("publishedBy", formPackage.publishedBy())
                .addValue("publishedAt", offset(formPackage.publishedAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("Form Package was not inserted");
        }
    }

    private Optional<FormPackage> query(String predicate, MapSqlParameterSource parameters) {
        return jdbc.query(
            """
            select tenant_id, form_key, package_version, form_version, form_hash,
                   ui_schema_version, ui_schema_hash, package_hash, source_draft_id,
                   published_by, published_at
            from ap_form_package where %s
            """.formatted(predicate),
            parameters,
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    private static FormPackage item(ResultSet resultSet) throws SQLException {
        return new FormPackage(
            resultSet.getString("tenant_id"),
            resultSet.getString("form_key"),
            resultSet.getInt("package_version"),
            resultSet.getInt("form_version"),
            resultSet.getString("form_hash"),
            resultSet.getInt("ui_schema_version"),
            resultSet.getString("ui_schema_hash"),
            resultSet.getString("package_hash"),
            resultSet.getObject("source_draft_id", UUID.class),
            resultSet.getString("published_by"),
            instant(resultSet, "published_at")
        );
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
