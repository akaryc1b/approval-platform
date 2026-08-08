package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL definition, instance and initial-task persistence behind ApprovalProjectionStore. */
final class JdbcMySqlDefinitionInstanceProjectionStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcMySqlProjectionCodec codec;
    private final JdbcMySqlTransactionLockManager locks;

    JdbcMySqlDefinitionInstanceProjectionStore(
        DataSource dataSource,
        JdbcMySqlProjectionCodec codec,
        JdbcMySqlTransactionLockManager locks
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
    }

    void lockDefinition(String tenantId, String definitionKey, int definitionVersion) {
        String exactTenant = JdbcMySqlProjectionCodec.requireText(tenantId, "tenantId");
        String exactDefinitionKey = JdbcMySqlProjectionCodec.requireText(
            definitionKey,
            "definitionKey"
        );
        if (definitionVersion < 1) {
            throw new IllegalArgumentException("definitionVersion must be positive");
        }
        locks.acquire(
            "definition:" + exactTenant + ':' + exactDefinitionKey + ':' + definitionVersion
        );
    }

    Optional<PublishedDefinition> findDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion
    ) {
        String exactTenant = JdbcMySqlProjectionCodec.requireText(tenantId, "tenantId");
        String exactDefinitionKey = JdbcMySqlProjectionCodec.requireText(
            definitionKey,
            "definitionKey"
        );
        if (definitionVersion < 1) {
            throw new IllegalArgumentException("definitionVersion must be positive");
        }
        return jdbc.query(
            """
            select * from ap_definition_version
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and definition_version = :definitionVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("definitionKey", exactDefinitionKey)
                .addValue("definitionVersion", definitionVersion),
            codec.definitionMapper()
        ).stream().findFirst();
    }

    void saveDefinition(PublishedDefinition definition) {
        requireTransaction("definition projection save");
        int inserted = jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version,
                form_key, form_version, compiler_version, content_hash,
                deployment_id, engine_definition_id, engine_version,
                published_by, published_at
            ) values (
                :tenantId, :definitionKey, :definitionVersion,
                :formKey, :formVersion, :compilerVersion, :contentHash,
                :deploymentId, :engineDefinitionId, :engineVersion,
                :publishedBy, :publishedAt
            )
            """,
            codec.definitionParameters(definition)
        );
        if (inserted != 1) {
            throw new IllegalStateException("definition projection was not inserted");
        }
    }

    void lockBusinessKey(String tenantId, String businessKey) {
        locks.acquire(
            "business:"
                + JdbcMySqlProjectionCodec.requireText(tenantId, "tenantId")
                + ':'
                + JdbcMySqlProjectionCodec.requireText(businessKey, "businessKey")
        );
    }

    Optional<InstanceProjection> findByBusinessKey(
        String tenantId,
        String businessKey
    ) {
        String exactTenant = JdbcMySqlProjectionCodec.requireText(tenantId, "tenantId");
        String exactBusinessKey = JdbcMySqlProjectionCodec.requireText(
            businessKey,
            "businessKey"
        );
        return queryInstances(
            """
            select * from ap_approval_instance
            where tenant_id = :tenantId and business_key = :businessKey
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("businessKey", exactBusinessKey)
        ).stream().findFirst();
    }

    void createInstance(InstanceProjection instance, List<TaskProjection> tasks) {
        requireTransaction("instance projection creation");
        InstanceProjection exactInstance = codec.requireInstance(instance);
        List<TaskProjection> exactTasks = codec.validateTasks(
            exactInstance.tenantId(),
            exactInstance.instanceId(),
            tasks
        );
        int inserted = jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash,
                release_version, release_package_hash,
                form_package_version, form_package_hash,
                ui_schema_version, ui_schema_hash, engine_definition_id,
                initiator_id,
                amount, supplier, purchase_order_reference,
                attachment_ids_json, assignee_snapshot_json, request_hash,
                status, version, created_at, updated_at
            ) values (
                :instanceId, :tenantId, :businessKey, :engineInstanceId,
                :definitionKey, :definitionVersion, :formKey, :formVersion,
                :compilerVersion, :contentHash,
                :releaseVersion, :releasePackageHash,
                :formPackageVersion, :formPackageHash,
                :uiSchemaVersion, :uiSchemaHash, :engineDefinitionId,
                :initiatorId,
                :amount, :supplier, :purchaseOrderReference,
                cast(:attachmentIdsJson as json), cast(:assigneeSnapshotJson as json),
                :requestHash, :status, :version, :createdAt, :updatedAt
            )
            """,
            codec.instanceParameters(exactInstance)
        );
        if (inserted != 1) {
            throw new IllegalStateException("instance projection was not inserted");
        }
        for (TaskProjection task : exactTasks) {
            insertTask(task);
        }
    }

    Optional<InstanceProjection> findInstance(String tenantId, UUID instanceId) {
        String exactTenant = JdbcMySqlProjectionCodec.requireText(tenantId, "tenantId");
        UUID exactInstanceId = Objects.requireNonNull(
            instanceId,
            "instanceId must not be null"
        );
        return queryInstances(
            """
            select * from ap_approval_instance
            where tenant_id = :tenantId and instance_id = :instanceId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exactTenant)
                .addValue("instanceId", codec.values().bindUuid(exactInstanceId))
        ).stream().findFirst();
    }

    private List<InstanceProjection> queryInstances(
        String sql,
        MapSqlParameterSource parameters
    ) {
        return jdbc.query(sql, parameters, codec.instanceMapper());
    }

    private void insertTask(TaskProjection task) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id,
                status, version, created_at, updated_at, completed_at
            ) values (
                :taskId, :instanceId, :tenantId, :engineTaskId,
                :taskDefinitionKey, :taskName, :assigneeId,
                :status, :version, :createdAt, :updatedAt, :completedAt
            )
            """,
            codec.taskParameters(task)
        );
        if (inserted != 1) {
            throw new IllegalStateException("task projection was not inserted");
        }
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " requires an active transaction");
        }
    }
}
