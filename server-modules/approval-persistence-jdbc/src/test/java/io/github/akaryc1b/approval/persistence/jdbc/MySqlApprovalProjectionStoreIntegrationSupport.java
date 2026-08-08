package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Testcontainers(disabledWithoutDocker = true)
abstract class MySqlApprovalProjectionStoreIntegrationSupport {

    static final String TENANT = "Tenant-Projection-MySQL";
    static final String OTHER_TENANT = "tenant-projection-other";
    static final String DEFINITION_KEY = "purchase-payment";
    static final Instant DEFINITION_AT = Instant.parse(
        "2026-08-08T01:01:02.123456499Z"
    );
    static final Instant CREATED_AT = Instant.parse(
        "2026-08-08T01:02:03.654321500Z"
    );
    static final Instant CHANGED_AT = Instant.parse(
        "2026-08-08T01:03:04.111222499Z"
    );
    static final UUID INSTANCE_ID = uuid(8501);
    static final UUID OTHER_INSTANCE_ID = uuid(8502);
    static final UUID CROSS_TENANT_INSTANCE_ID = uuid(8503);
    static final UUID TASK_ID = uuid(8511);
    static final UUID EXISTING_ACTIVE_TASK_ID = uuid(8512);
    static final UUID STALE_TASK_ID = uuid(8513);
    static final UUID NEW_TASK_ID = uuid(8514);
    static final UUID FOREIGN_ENGINE_TASK_ID = uuid(8515);
    static final UUID GLOBAL_COLLISION_TASK_ID = uuid(8516);
    static final UUID REPLACEMENT_TASK_ID = uuid(8517);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_projection_store")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(
            "--default-time-zone=+00:00",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_0900_as_cs",
            "--transaction-isolation=READ-COMMITTED",
            "--innodb-strict-mode=ON",
            "--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,"
                + "NO_ENGINE_SUBSTITUTION"
        );

    static DataSource dataSource;
    static JdbcTemplate jdbc;

    ApprovalProjectionStore store;
    TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from ap_approval_task");
        jdbc.update("delete from ap_approval_instance");
        jdbc.update("delete from ap_definition_version");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        store = JdbcApprovalProjectionStoreFactory.create(dataSource, objectMapper);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    void saveDefinition(String tenantId) {
        inTransaction(() -> {
            store.lockDefinition(tenantId, DEFINITION_KEY, 1);
            store.saveDefinition(definition(tenantId));
        });
    }

    void seedInstanceWithTasks(
        InstanceProjection instance,
        List<TaskProjection> tasks
    ) {
        saveDefinition(instance.tenantId());
        inTransaction(() -> {
            store.lockBusinessKey(instance.tenantId(), instance.businessKey());
            store.createInstance(instance, tasks);
        });
    }

    static PublishedDefinition definition(String tenantId) {
        return new PublishedDefinition(
            tenantId,
            DEFINITION_KEY,
            1,
            DEFINITION_KEY,
            1,
            "compiler-1",
            "a".repeat(64),
            "deployment-1-" + tenantId,
            "engine-definition-1-" + tenantId,
            1,
            "Publisher-A",
            DEFINITION_AT
        );
    }

    static InstanceProjection instance(
        String tenantId,
        UUID instanceId,
        String engineInstanceId,
        String businessKey
    ) {
        return new InstanceProjection(
            instanceId,
            tenantId,
            businessKey,
            engineInstanceId,
            DEFINITION_KEY,
            1,
            DEFINITION_KEY,
            1,
            "compiler-1",
            "a".repeat(64),
            2,
            "b".repeat(64),
            3,
            "c".repeat(64),
            4,
            "d".repeat(64),
            "engine-definition-release-2",
            "Initiator-A",
            new BigDecimal("123456789012.123456"),
            "供应商-A",
            "PO-Projection-1",
            List.of("attachment-2", "attachment-1"),
            new AssigneeSnapshot(
                "Manager-A",
                "Finance-Reviewer",
                List.of("Finance-A", "Finance-B"),
                Map.of("connectorKey", "DingTalk-A", "unicode", "审批"),
                Map.of(
                    "Manager-A",
                    new UserIdentitySnapshot(
                        "external-manager-a",
                        "manager-a",
                        "经理-A",
                        "manager-a@example.com",
                        "+15550000001",
                        List.of("department-a"),
                        Set.of("MANAGER"),
                        Set.of("POSITION-A"),
                        Map.of("source", "connector")
                    )
                )
            ),
            "e".repeat(64),
            InstanceStatus.RUNNING,
            1,
            CREATED_AT,
            CREATED_AT
        );
    }

    static TaskProjection task(
        String tenantId,
        UUID taskId,
        UUID instanceId,
        String engineTaskId,
        String assigneeId,
        TaskStatus status,
        long version,
        Instant updatedAt
    ) {
        return new TaskProjection(
            taskId,
            instanceId,
            tenantId,
            engineTaskId,
            "managerApproval",
            "经理审批",
            assigneeId,
            status,
            version,
            CREATED_AT,
            updatedAt,
            status == TaskStatus.COMPLETED ? updatedAt : null
        );
    }

    static PublishedDefinition canonical(PublishedDefinition value) {
        return new PublishedDefinition(
            value.tenantId(),
            value.definitionKey(),
            value.definitionVersion(),
            value.formKey(),
            value.formVersion(),
            value.compilerVersion(),
            value.contentHash(),
            value.deploymentId(),
            value.engineDefinitionId(),
            value.engineVersion(),
            value.publishedBy(),
            canonicalInstant(value.publishedAt())
        );
    }

    static InstanceProjection canonical(InstanceProjection value) {
        return new InstanceProjection(
            value.instanceId(),
            value.tenantId(),
            value.businessKey(),
            value.engineInstanceId(),
            value.definitionKey(),
            value.definitionVersion(),
            value.formKey(),
            value.formVersion(),
            value.compilerVersion(),
            value.contentHash(),
            value.releaseVersion(),
            value.releasePackageHash(),
            value.formPackageVersion(),
            value.formPackageHash(),
            value.uiSchemaVersion(),
            value.uiSchemaHash(),
            value.engineDefinitionId(),
            value.initiatorId(),
            value.amount(),
            value.supplier(),
            value.purchaseOrderReference(),
            value.attachmentIds(),
            value.assigneeSnapshot(),
            value.requestHash(),
            value.status(),
            value.version(),
            canonicalInstant(value.createdAt()),
            canonicalInstant(value.updatedAt())
        );
    }

    static TaskProjection canonical(TaskProjection value) {
        return new TaskProjection(
            value.taskId(),
            value.instanceId(),
            value.tenantId(),
            value.engineTaskId(),
            value.taskDefinitionKey(),
            value.name(),
            value.assigneeId(),
            value.status(),
            value.version(),
            canonicalInstant(value.createdAt()),
            canonicalInstant(value.updatedAt()),
            value.completedAt() == null ? null : canonicalInstant(value.completedAt())
        );
    }

    static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(value);
    }

    <T> T inTransaction(Supplier<T> action) {
        T result = transactions.execute(status -> action.get());
        return Objects.requireNonNull(result, "transaction action must return a result");
    }

    void inTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> action.run());
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency wait interrupted", exception);
        }
    }

    static int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString(
            "00000000-0000-0000-0000-" + String.format("%012d", suffix)
        );
    }

    private static String configuredJdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true"
            + "&useAffectedRows=false";
    }

    static final class RollbackMarker extends RuntimeException {
    }
}
