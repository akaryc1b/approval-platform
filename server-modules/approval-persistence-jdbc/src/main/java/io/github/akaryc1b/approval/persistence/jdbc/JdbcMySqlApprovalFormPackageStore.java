package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL 8.4 immutable Form Package store with transaction-bound version serialization. */
public final class JdbcMySqlApprovalFormPackageStore implements ApprovalFormPackageStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;

    public JdbcMySqlApprovalFormPackageStore(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalFormPackageStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.locks = new JdbcMySqlTransactionLockManager(source);
    }

    @Override
    public void lockVersion(String tenantId, String formKey, int packageVersion) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactFormKey = requireText(formKey, "formKey");
        int exactPackageVersion = requirePositive(packageVersion, "packageVersion");
        locks.acquire(
            "form-package:" + exactTenant + ':' + exactFormKey + ':' + exactPackageVersion
        );
    }

    @Override
    public Optional<FormPackage> find(
        String tenantId,
        String formKey,
        int packageVersion
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactFormKey = requireText(formKey, "formKey");
        int exactPackageVersion = requirePositive(packageVersion, "packageVersion");
        return jdbc.query(
            """
            select tenant_id, form_key, package_version, form_version, form_hash,
                   ui_schema_version, ui_schema_hash, package_hash, source_draft_id,
                   published_by, published_at
            from ap_form_package
            where tenant_id = :tenantId
              and form_key = :formKey
              and package_version = :packageVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("formKey", exactFormKey)
                .addValue("packageVersion", exactPackageVersion),
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    @Override
    public Optional<FormPackage> findByDraft(String tenantId, UUID draftId) {
        String exactTenant = requireText(tenantId, "tenantId");
        UUID exactDraftId = Objects.requireNonNull(
            draftId,
            "draftId must not be null"
        );
        return jdbc.query(
            """
            select tenant_id, form_key, package_version, form_version, form_hash,
                   ui_schema_version, ui_schema_hash, package_hash, source_draft_id,
                   published_by, published_at
            from ap_form_package
            where tenant_id = :tenantId
              and source_draft_id = :draftId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("draftId", values.bindUuid(exactDraftId)),
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    @Override
    public void save(FormPackage formPackage) {
        FormPackage exact = Objects.requireNonNull(
            formPackage,
            "formPackage must not be null"
        );
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
                .addValue("tenantId", exact.tenantId())
                .addValue("formKey", exact.formKey())
                .addValue("packageVersion", exact.packageVersion())
                .addValue("formVersion", exact.formVersion())
                .addValue("formHash", exact.formHash())
                .addValue("uiSchemaVersion", exact.uiSchemaVersion())
                .addValue("uiSchemaHash", exact.uiSchemaHash())
                .addValue("packageHash", exact.packageHash())
                .addValue("sourceDraftId", values.bindUuid(exact.sourceDraftId()))
                .addValue("publishedBy", exact.publishedBy())
                .addValue(
                    "publishedAt",
                    values.bindInstant(canonicalInstant(exact.publishedAt()))
                )
        );
        if (inserted != 1) {
            throw new IllegalStateException("Form Package was not inserted");
        }
    }

    private FormPackage item(ResultSet resultSet) throws SQLException {
        return new FormPackage(
            resultSet.getString("tenant_id"),
            resultSet.getString("form_key"),
            resultSet.getInt("package_version"),
            resultSet.getInt("form_version"),
            resultSet.getString("form_hash"),
            resultSet.getInt("ui_schema_version"),
            resultSet.getString("ui_schema_hash"),
            resultSet.getString("package_hash"),
            values.uuid(resultSet, "source_draft_id"),
            resultSet.getString("published_by"),
            values.instant(resultSet, "published_at")
        );
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
