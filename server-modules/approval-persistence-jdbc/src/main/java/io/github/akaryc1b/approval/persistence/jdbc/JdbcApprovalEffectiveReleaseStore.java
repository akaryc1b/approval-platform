package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
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

/** PostgreSQL effective release projection with revision CAS and immutable history. */
public final class JdbcApprovalEffectiveReleaseStore implements ApprovalEffectiveReleaseStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalEffectiveReleaseStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public void lock(String tenantId, String definitionKey) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource().addValue(
                "lockKey",
                "approval-effective-release:" + tenantId + ':' + definitionKey
            ),
            resultSet -> null
        );
    }

    @Override
    public Optional<ApprovalEffectiveRelease> find(String tenantId, String definitionKey) {
        return jdbc.query(
            selectCurrent() + " where tenant_id = :tenantId and definition_key = :definitionKey",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey),
            (resultSet, rowNumber) -> current(resultSet)
        ).stream().findFirst();
    }

    @Override
    public void save(
        ApprovalEffectiveRelease effectiveRelease,
        ApprovalEffectiveRelease.Activation activation
    ) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_effective_release (
                tenant_id, definition_key, effective_release_version,
                previous_release_version, release_package_hash,
                definition_version, definition_hash,
                form_package_version, form_package_hash,
                form_schema_version, form_schema_hash,
                ui_schema_version, ui_schema_hash,
                compiler_version, compiled_artifact_hash, bpmn_hash,
                deployment_metadata_hash, engine_deployment_id,
                engine_definition_id, engine_version, status, revision,
                activated_by, activated_at, change_reason, request_id, trace_id
            ) values (
                :tenantId, :definitionKey, :effectiveReleaseVersion,
                :previousReleaseVersion, :releasePackageHash,
                :definitionVersion, :definitionHash,
                :formPackageVersion, :formPackageHash,
                :formSchemaVersion, :formSchemaHash,
                :uiSchemaVersion, :uiSchemaHash,
                :compilerVersion, :compiledArtifactHash, :bpmnHash,
                :deploymentMetadataHash, :engineDeploymentId,
                :engineDefinitionId, :engineVersion, :status, :revision,
                :activatedBy, :activatedAt, :changeReason, :requestId, :traceId
            )
            """,
            currentParameters(effectiveRelease)
        );
        if (inserted != 1) {
            throw new IllegalStateException("effective release projection was not inserted");
        }
        insertActivation(activation);
    }

    @Override
    public boolean update(
        ApprovalEffectiveRelease effectiveRelease,
        long expectedRevision,
        ApprovalEffectiveRelease.Activation activation
    ) {
        int updated = jdbc.update(
            """
            update ap_approval_effective_release
            set effective_release_version = :effectiveReleaseVersion,
                previous_release_version = :previousReleaseVersion,
                release_package_hash = :releasePackageHash,
                definition_version = :definitionVersion,
                definition_hash = :definitionHash,
                form_package_version = :formPackageVersion,
                form_package_hash = :formPackageHash,
                form_schema_version = :formSchemaVersion,
                form_schema_hash = :formSchemaHash,
                ui_schema_version = :uiSchemaVersion,
                ui_schema_hash = :uiSchemaHash,
                compiler_version = :compilerVersion,
                compiled_artifact_hash = :compiledArtifactHash,
                bpmn_hash = :bpmnHash,
                deployment_metadata_hash = :deploymentMetadataHash,
                engine_deployment_id = :engineDeploymentId,
                engine_definition_id = :engineDefinitionId,
                engine_version = :engineVersion,
                status = :status,
                revision = :revision,
                activated_by = :activatedBy,
                activated_at = :activatedAt,
                change_reason = :changeReason,
                request_id = :requestId,
                trace_id = :traceId
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and revision = :expectedRevision
            """,
            currentParameters(effectiveRelease).addValue("expectedRevision", expectedRevision)
        );
        if (updated != 1) {
            return false;
        }
        insertActivation(activation);
        return true;
    }

    @Override
    public boolean wasActivated(String tenantId, String definitionKey, int releaseVersion) {
        Integer count = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_release_activation_history
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and release_version = :releaseVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey)
                .addValue("releaseVersion", releaseVersion),
            Integer.class
        );
        return count != null && count > 0;
    }

    @Override
    public ActivationPage findHistory(ActivationCriteria criteria) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("definitionKey", criteria.definitionKey())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_approval_release_activation_history
            where tenant_id = :tenantId and definition_key = :definitionKey
            """,
            parameters,
            Long.class
        );
        List<ApprovalEffectiveRelease.Activation> items = jdbc.query(
            selectActivation()
                + " where tenant_id = :tenantId and definition_key = :definitionKey"
                + " order by revision desc, activation_id desc limit :limit offset :offset",
            parameters,
            (resultSet, rowNumber) -> activation(resultSet)
        );
        return new ActivationPage(
            items,
            total == null ? 0 : total,
            criteria.limit(),
            criteria.offset()
        );
    }

    private void insertActivation(ApprovalEffectiveRelease.Activation activation) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_release_activation_history (
                activation_id, tenant_id, definition_key, release_version,
                previous_release_version, release_package_hash,
                definition_version, form_package_version, compiler_version,
                engine_deployment_id, engine_definition_id, engine_version,
                action, revision, activated_by, activated_at,
                change_reason, request_id, trace_id
            ) values (
                :activationId, :tenantId, :definitionKey, :releaseVersion,
                :previousReleaseVersion, :releasePackageHash,
                :definitionVersion, :formPackageVersion, :compilerVersion,
                :engineDeploymentId, :engineDefinitionId, :engineVersion,
                :action, :revision, :activatedBy, :activatedAt,
                :changeReason, :requestId, :traceId
            )
            """,
            activationParameters(activation)
        );
        if (inserted != 1) {
            throw new IllegalStateException("effective release activation was not inserted");
        }
    }

    private static MapSqlParameterSource currentParameters(
        ApprovalEffectiveRelease value
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", value.tenantId())
            .addValue("definitionKey", value.definitionKey())
            .addValue("effectiveReleaseVersion", value.effectiveReleaseVersion())
            .addValue("previousReleaseVersion", value.previousReleaseVersion())
            .addValue("releasePackageHash", value.releasePackageHash())
            .addValue("definitionVersion", value.definitionVersion())
            .addValue("definitionHash", value.definitionHash())
            .addValue("formPackageVersion", value.formPackageVersion())
            .addValue("formPackageHash", value.formPackageHash())
            .addValue("formSchemaVersion", value.formSchemaVersion())
            .addValue("formSchemaHash", value.formSchemaHash())
            .addValue("uiSchemaVersion", value.uiSchemaVersion())
            .addValue("uiSchemaHash", value.uiSchemaHash())
            .addValue("compilerVersion", value.compilerVersion())
            .addValue("compiledArtifactHash", value.compiledArtifactHash())
            .addValue("bpmnHash", value.bpmnHash())
            .addValue("deploymentMetadataHash", value.deploymentMetadataHash())
            .addValue("engineDeploymentId", value.engineDeploymentId())
            .addValue("engineDefinitionId", value.engineDefinitionId())
            .addValue("engineVersion", value.engineVersion())
            .addValue("status", value.status().name())
            .addValue("revision", value.revision())
            .addValue("activatedBy", value.activatedBy())
            .addValue("activatedAt", offset(value.activatedAt()))
            .addValue("changeReason", value.changeReason())
            .addValue("requestId", value.requestId())
            .addValue("traceId", value.traceId());
    }

    private static MapSqlParameterSource activationParameters(
        ApprovalEffectiveRelease.Activation value
    ) {
        return new MapSqlParameterSource()
            .addValue("activationId", value.activationId())
            .addValue("tenantId", value.tenantId())
            .addValue("definitionKey", value.definitionKey())
            .addValue("releaseVersion", value.releaseVersion())
            .addValue("previousReleaseVersion", value.previousReleaseVersion())
            .addValue("releasePackageHash", value.releasePackageHash())
            .addValue("definitionVersion", value.definitionVersion())
            .addValue("formPackageVersion", value.formPackageVersion())
            .addValue("compilerVersion", value.compilerVersion())
            .addValue("engineDeploymentId", value.engineDeploymentId())
            .addValue("engineDefinitionId", value.engineDefinitionId())
            .addValue("engineVersion", value.engineVersion())
            .addValue("action", value.action().name())
            .addValue("revision", value.revision())
            .addValue("activatedBy", value.activatedBy())
            .addValue("activatedAt", offset(value.activatedAt()))
            .addValue("changeReason", value.changeReason())
            .addValue("requestId", value.requestId())
            .addValue("traceId", value.traceId());
    }

    private static ApprovalEffectiveRelease current(ResultSet resultSet)
        throws SQLException {
        return new ApprovalEffectiveRelease(
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("effective_release_version"),
            integer(resultSet, "previous_release_version"),
            resultSet.getString("release_package_hash"),
            resultSet.getInt("definition_version"),
            resultSet.getString("definition_hash"),
            resultSet.getInt("form_package_version"),
            resultSet.getString("form_package_hash"),
            resultSet.getInt("form_schema_version"),
            resultSet.getString("form_schema_hash"),
            resultSet.getInt("ui_schema_version"),
            resultSet.getString("ui_schema_hash"),
            resultSet.getString("compiler_version"),
            resultSet.getString("compiled_artifact_hash"),
            resultSet.getString("bpmn_hash"),
            resultSet.getString("deployment_metadata_hash"),
            resultSet.getString("engine_deployment_id"),
            resultSet.getString("engine_definition_id"),
            resultSet.getInt("engine_version"),
            ApprovalEffectiveRelease.Status.valueOf(resultSet.getString("status")),
            resultSet.getLong("revision"),
            resultSet.getString("activated_by"),
            instant(resultSet, "activated_at"),
            resultSet.getString("change_reason"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id")
        );
    }

    private static ApprovalEffectiveRelease.Activation activation(ResultSet resultSet)
        throws SQLException {
        return new ApprovalEffectiveRelease.Activation(
            resultSet.getObject("activation_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("release_version"),
            integer(resultSet, "previous_release_version"),
            resultSet.getString("release_package_hash"),
            resultSet.getInt("definition_version"),
            resultSet.getInt("form_package_version"),
            resultSet.getString("compiler_version"),
            resultSet.getString("engine_deployment_id"),
            resultSet.getString("engine_definition_id"),
            resultSet.getInt("engine_version"),
            ApprovalEffectiveRelease.Action.valueOf(resultSet.getString("action")),
            resultSet.getLong("revision"),
            resultSet.getString("activated_by"),
            instant(resultSet, "activated_at"),
            resultSet.getString("change_reason"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id")
        );
    }

    private static String selectCurrent() {
        return """
            select tenant_id, definition_key, effective_release_version,
                   previous_release_version, release_package_hash,
                   definition_version, definition_hash,
                   form_package_version, form_package_hash,
                   form_schema_version, form_schema_hash,
                   ui_schema_version, ui_schema_hash,
                   compiler_version, compiled_artifact_hash, bpmn_hash,
                   deployment_metadata_hash, engine_deployment_id,
                   engine_definition_id, engine_version, status, revision,
                   activated_by, activated_at, change_reason, request_id, trace_id
            from ap_approval_effective_release
            """;
    }

    private static String selectActivation() {
        return """
            select activation_id, tenant_id, definition_key, release_version,
                   previous_release_version, release_package_hash,
                   definition_version, form_package_version, compiler_version,
                   engine_deployment_id, engine_definition_id, engine_version,
                   action, revision, activated_by, activated_at,
                   change_reason, request_id, trace_id
            from ap_approval_release_activation_history
            """;
    }

    private static Integer integer(ResultSet resultSet, String column)
        throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column)
        throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
