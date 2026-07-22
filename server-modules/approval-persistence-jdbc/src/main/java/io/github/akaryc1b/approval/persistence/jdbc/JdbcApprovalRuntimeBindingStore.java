package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
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

/** PostgreSQL immutable runtime release binding evidence store. */
public final class JdbcApprovalRuntimeBindingStore implements ApprovalRuntimeBindingStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalRuntimeBindingStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public Optional<ApprovalRuntimeBinding> find(
        String tenantId,
        UUID approvalInstanceId
    ) {
        return query(
            "tenant_id = :tenantId and approval_instance_id = :approvalInstanceId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("approvalInstanceId", approvalInstanceId),
            ""
        );
    }

    @Override
    public Optional<ApprovalRuntimeBinding> findByEngineInstance(
        String tenantId,
        String engineInstanceId
    ) {
        return query(
            "tenant_id = :tenantId and engine_instance_id = :engineInstanceId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("engineInstanceId", engineInstanceId),
            ""
        );
    }

    @Override
    public BindingPage findByRelease(BindingCriteria criteria) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("definitionKey", criteria.definitionKey())
            .addValue("releaseVersion", criteria.releaseVersion())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_process_runtime_binding
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and release_version = :releaseVersion
            """,
            parameters,
            Long.class
        );
        List<ApprovalRuntimeBinding> items = jdbc.query(
            selectBinding()
                + " where tenant_id = :tenantId"
                + " and definition_key = :definitionKey"
                + " and release_version = :releaseVersion"
                + " order by bound_at desc, approval_instance_id"
                + " limit :limit offset :offset",
            parameters,
            (resultSet, rowNumber) -> binding(resultSet)
        );
        return new BindingPage(
            items,
            total == null ? 0 : total,
            criteria.limit(),
            criteria.offset()
        );
    }

    @Override
    public long countReleaseUsage(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        Long count = jdbc.queryForObject(
            """
            select count(*)
            from ap_process_runtime_binding
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and release_version = :releaseVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey)
                .addValue("releaseVersion", releaseVersion),
            Long.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public void save(ApprovalRuntimeBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        int inserted = jdbc.update(
            """
            insert into ap_process_runtime_binding (
                tenant_id, approval_instance_id, business_key, engine_instance_id,
                definition_key, release_version, release_package_hash,
                definition_version, definition_hash,
                form_package_version, form_package_hash,
                form_version, form_hash, ui_schema_version, ui_schema_hash,
                compiler_version, compiled_artifact_hash, bpmn_hash,
                deployment_metadata_hash, engine_deployment_id,
                engine_definition_id, engine_version, binding_evidence_hash,
                bound_by, bound_at, request_id, trace_id, audit_chain_reference
            ) values (
                :tenantId, :approvalInstanceId, :businessKey, :engineInstanceId,
                :definitionKey, :releaseVersion, :releasePackageHash,
                :definitionVersion, :definitionHash,
                :formPackageVersion, :formPackageHash,
                :formVersion, :formHash, :uiSchemaVersion, :uiSchemaHash,
                :compilerVersion, :compiledArtifactHash, :bpmnHash,
                :deploymentMetadataHash, :engineDeploymentId,
                :engineDefinitionId, :engineVersion, :bindingEvidenceHash,
                :boundBy, :boundAt, :requestId, :traceId, :auditChainReference
            )
            """,
            parameters(binding)
        );
        if (inserted != 1) {
            throw new IllegalStateException("runtime binding evidence was not inserted");
        }
    }

    private Optional<ApprovalRuntimeBinding> query(
        String predicate,
        MapSqlParameterSource parameters,
        String suffix
    ) {
        return jdbc.query(
            selectBinding() + " where " + predicate + suffix,
            parameters,
            (resultSet, rowNumber) -> binding(resultSet)
        ).stream().findFirst();
    }

    private static MapSqlParameterSource parameters(ApprovalRuntimeBinding value) {
        return new MapSqlParameterSource()
            .addValue("tenantId", value.tenantId())
            .addValue("approvalInstanceId", value.approvalInstanceId())
            .addValue("businessKey", value.businessKey())
            .addValue("engineInstanceId", value.engineInstanceId())
            .addValue("definitionKey", value.definitionKey())
            .addValue("releaseVersion", value.releaseVersion())
            .addValue("releasePackageHash", value.releasePackageHash())
            .addValue("definitionVersion", value.definitionVersion())
            .addValue("definitionHash", value.definitionHash())
            .addValue("formPackageVersion", value.formPackageVersion())
            .addValue("formPackageHash", value.formPackageHash())
            .addValue("formVersion", value.formVersion())
            .addValue("formHash", value.formHash())
            .addValue("uiSchemaVersion", value.uiSchemaVersion())
            .addValue("uiSchemaHash", value.uiSchemaHash())
            .addValue("compilerVersion", value.compilerVersion())
            .addValue("compiledArtifactHash", value.compiledArtifactHash())
            .addValue("bpmnHash", value.bpmnHash())
            .addValue("deploymentMetadataHash", value.deploymentMetadataHash())
            .addValue("engineDeploymentId", value.engineDeploymentId())
            .addValue("engineDefinitionId", value.engineDefinitionId())
            .addValue("engineVersion", value.engineVersion())
            .addValue("bindingEvidenceHash", value.bindingEvidenceHash())
            .addValue("boundBy", value.boundBy())
            .addValue("boundAt", offset(value.boundAt()))
            .addValue("requestId", value.requestId())
            .addValue("traceId", value.traceId())
            .addValue("auditChainReference", value.auditChainReference());
    }

    private static ApprovalRuntimeBinding binding(ResultSet resultSet) throws SQLException {
        return new ApprovalRuntimeBinding(
            resultSet.getString("tenant_id"),
            resultSet.getObject("approval_instance_id", UUID.class),
            resultSet.getString("business_key"),
            resultSet.getString("engine_instance_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("release_version"),
            resultSet.getString("release_package_hash"),
            resultSet.getInt("definition_version"),
            resultSet.getString("definition_hash"),
            resultSet.getInt("form_package_version"),
            resultSet.getString("form_package_hash"),
            resultSet.getInt("form_version"),
            resultSet.getString("form_hash"),
            resultSet.getInt("ui_schema_version"),
            resultSet.getString("ui_schema_hash"),
            resultSet.getString("compiler_version"),
            resultSet.getString("compiled_artifact_hash"),
            resultSet.getString("bpmn_hash"),
            resultSet.getString("deployment_metadata_hash"),
            resultSet.getString("engine_deployment_id"),
            resultSet.getString("engine_definition_id"),
            resultSet.getInt("engine_version"),
            resultSet.getString("binding_evidence_hash"),
            resultSet.getString("bound_by"),
            instant(resultSet, "bound_at"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id"),
            resultSet.getString("audit_chain_reference")
        );
    }

    private static String selectBinding() {
        return """
            select tenant_id, approval_instance_id, business_key, engine_instance_id,
                   definition_key, release_version, release_package_hash,
                   definition_version, definition_hash,
                   form_package_version, form_package_hash,
                   form_version, form_hash, ui_schema_version, ui_schema_hash,
                   compiler_version, compiled_artifact_hash, bpmn_hash,
                   deployment_metadata_hash, engine_deployment_id,
                   engine_definition_id, engine_version, binding_evidence_hash,
                   bound_by, bound_at, request_id, trace_id, audit_chain_reference
            from ap_process_runtime_binding
            """;
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
