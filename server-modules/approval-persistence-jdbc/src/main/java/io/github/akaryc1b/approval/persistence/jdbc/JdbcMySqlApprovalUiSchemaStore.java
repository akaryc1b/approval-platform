package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** MySQL 8.4 immutable UI Schema Store with closed typed-value preservation. */
public final class JdbcMySqlApprovalUiSchemaStore implements ApprovalUiSchemaStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;
    private final JdbcMySqlUiSchemaCodec codec;

    public JdbcMySqlApprovalUiSchemaStore(
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
                "JdbcMySqlApprovalUiSchemaStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.locks = new JdbcMySqlTransactionLockManager(source);
        this.codec = new JdbcMySqlUiSchemaCodec(
            Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
            )
        );
    }

    @Override
    public void lockVersion(
        String tenantId,
        String formKey,
        int formVersion,
        int uiSchemaVersion
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactFormKey = requireText(formKey, "formKey");
        int exactFormVersion = requirePositive(formVersion, "formVersion");
        int exactUiSchemaVersion = requirePositive(
            uiSchemaVersion,
            "uiSchemaVersion"
        );
        locks.acquire(
            "ui-schema:"
                + exactTenant
                + ':'
                + exactFormKey
                + ':'
                + exactFormVersion
                + ':'
                + exactUiSchemaVersion
        );
    }

    @Override
    public Optional<PublishedUiSchema> find(
        String tenantId,
        String formKey,
        int formVersion,
        int uiSchemaVersion
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactFormKey = requireText(formKey, "formKey");
        int exactFormVersion = requirePositive(formVersion, "formVersion");
        int exactUiSchemaVersion = requirePositive(
            uiSchemaVersion,
            "uiSchemaVersion"
        );
        return jdbc.query(
            """
            select tenant_id, schema_json, content_hash, published_by, published_at
            from ap_form_ui_schema
            where tenant_id = :tenantId
              and form_key = :formKey
              and form_version = :formVersion
              and ui_schema_version = :uiSchemaVersion
            """,
            parameters(exactTenant, exactFormKey, exactFormVersion)
                .addValue("uiSchemaVersion", exactUiSchemaVersion),
            (resultSet, rowNumber) -> publishedUiSchema(resultSet)
        ).stream().findFirst();
    }

    @Override
    public Optional<PublishedUiSchema> findLatest(
        String tenantId,
        String formKey,
        int formVersion
    ) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactFormKey = requireText(formKey, "formKey");
        int exactFormVersion = requirePositive(formVersion, "formVersion");
        return jdbc.query(
            """
            select tenant_id, schema_json, content_hash, published_by, published_at
            from ap_form_ui_schema
            where tenant_id = :tenantId
              and form_key = :formKey
              and form_version = :formVersion
            order by ui_schema_version desc
            limit 1
            """,
            parameters(exactTenant, exactFormKey, exactFormVersion),
            (resultSet, rowNumber) -> publishedUiSchema(resultSet)
        ).stream().findFirst();
    }

    @Override
    public void save(PublishedUiSchema schema) {
        PublishedUiSchema exact = Objects.requireNonNull(
            schema,
            "schema must not be null"
        );
        UiSchemaDefinition definition = exact.definition();
        int inserted = jdbc.update(
            """
            insert into ap_form_ui_schema (
                tenant_id, form_key, form_version, ui_schema_version, schema_version,
                name, section_count, schema_json, content_hash, published_by, published_at
            ) values (
                :tenantId, :formKey, :formVersion, :uiSchemaVersion, :schemaVersion,
                :name, :sectionCount, cast(:schemaJson as json), :contentHash,
                :publishedBy, :publishedAt
            )
            """,
            parameters(
                requireText(exact.tenantId(), "schema.tenantId"),
                requireText(definition.formKey(), "schema.formKey"),
                requirePositive(definition.formVersion(), "schema.formVersion")
            )
                .addValue(
                    "uiSchemaVersion",
                    requirePositive(
                        definition.version(),
                        "schema.uiSchemaVersion"
                    )
                )
                .addValue("schemaVersion", definition.schemaVersion())
                .addValue("name", definition.name())
                .addValue("sectionCount", definition.sections().size())
                .addValue("schemaJson", codec.encode(definition))
                .addValue("contentHash", exact.contentHash())
                .addValue("publishedBy", exact.publishedBy())
                .addValue(
                    "publishedAt",
                    values.bindInstant(canonicalInstant(exact.publishedAt()))
                )
        );
        if (inserted != 1) {
            throw new IllegalStateException("UI Schema was not inserted");
        }
    }

    private PublishedUiSchema publishedUiSchema(ResultSet resultSet)
        throws SQLException {
        return new PublishedUiSchema(
            resultSet.getString("tenant_id"),
            codec.decode(resultSet.getString("schema_json")),
            resultSet.getString("content_hash"),
            resultSet.getString("published_by"),
            values.instant(resultSet, "published_at")
        );
    }

    private static MapSqlParameterSource parameters(
        String tenantId,
        String formKey,
        int formVersion
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("formKey", formKey)
            .addValue("formVersion", formVersion);
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
