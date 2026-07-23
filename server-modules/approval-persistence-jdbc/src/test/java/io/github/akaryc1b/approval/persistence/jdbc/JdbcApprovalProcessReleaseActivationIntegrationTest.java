package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalProcessReleaseActivationIntegrationTest {

    private static final String TENANT = "tenant-release-activation";
    private static final String DEFINITION_KEY = "purchasePayment";
    private static final Instant NOW = Instant.parse("2026-07-23T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_release_activation_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactions;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_command_idempotency,
                ap_audit_event,
                ap_audit_chain_state,
                ap_process_release_lifecycle_history,
                ap_process_release_lifecycle,
                ap_approval_effective_release,
                ap_approval_release_activation_history,
                ap_approval_release_deployment,
                ap_approval_release_package
            cascade
            """);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactions = new JdbcTransactionManager(dataSource);
    }

    @Test
    void switchCommitsLifecycleEffectiveAuditAndIdempotencyEvidenceTogether() {
        ApprovalReleasePackage current = seedRelease(1, State.ACTIVE, true);
        seedEffective(current);
        ApprovalReleasePackage target = seedRelease(2, State.PUBLISHED, true);
        ApprovalProcessReleaseActivationService service = service();
        var command = command("switch-release-two", 2, 1L);

        var result = service.activate(command);
        var replay = service.activate(command);

        assertEquals(result, replay);
        assertEquals(State.DEPRECATED, lifecycle(1).lifecycleState());
        assertEquals(State.ACTIVE, lifecycle(2).lifecycleState());
        assertEquals(1, countActiveLifecycles());
        ApprovalEffectiveRelease effective = effective();
        assertEquals(2, effective.effectiveReleaseVersion());
        assertEquals(1, effective.previousReleaseVersion());
        assertEquals(2, effective.revision());
        assertEquals(target.packageHash(), effective.releasePackageHash());
        assertEquals(5, count("ap_process_release_lifecycle_history"));
        assertEquals(2, count("ap_approval_release_activation_history"));
        assertEquals(2, count("ap_audit_event"));
        assertEquals(1, count("ap_audit_chain_state"));
        assertEquals(2, count("ap_command_idempotency"));
        assertEquals(2, count("ap_approval_release_package"));
        assertEquals(2, count("ap_approval_release_deployment"));
        assertEquals(
            2,
            jdbc.queryForObject(
                "select count(*) from ap_command_idempotency where status = 'COMPLETED'",
                Integer.class
            )
        );
    }

    @Test
    void missingDeploymentRollsBackLifecycleAuditAndIdempotencyEvidence() {
        ApprovalReleasePackage current = seedRelease(1, State.ACTIVE, true);
        seedEffective(current);
        seedRelease(2, State.PUBLISHED, false);
        ApprovalProcessReleaseActivationService service = service();

        assertThrows(
            ApprovalEffectiveReleaseService.DeploymentNotReadyException.class,
            () -> service.activate(command("missing-target-deployment", 2, 1L))
        );

        assertEquals(State.ACTIVE, lifecycle(1).lifecycleState());
        assertEquals(State.PUBLISHED, lifecycle(2).lifecycleState());
        assertEquals(1, countActiveLifecycles());
        assertEquals(1, effective().effectiveReleaseVersion());
        assertEquals(1, effective().revision());
        assertEquals(3, count("ap_process_release_lifecycle_history"));
        assertEquals(1, count("ap_approval_release_activation_history"));
        assertEquals(0, count("ap_audit_event"));
        assertEquals(0, count("ap_audit_chain_state"));
        assertEquals(0, count("ap_command_idempotency"));
        assertEquals(2, count("ap_approval_release_package"));
        assertEquals(1, count("ap_approval_release_deployment"));
    }

    @Test
    void concurrentTargetsAllowExactlyOneGovernedSwitch() throws Exception {
        ApprovalReleasePackage current = seedRelease(1, State.ACTIVE, true);
        seedEffective(current);
        seedRelease(2, State.PUBLISHED, true);
        seedRelease(3, State.PUBLISHED, true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Attempt> first = executor.submit(
                () -> activateAfterSignal(ready, start, "concurrent-two", 2)
            );
            Future<Attempt> second = executor.submit(
                () -> activateAfterSignal(ready, start, "concurrent-three", 3)
            );
            ready.await();
            start.countDown();

            Attempt firstResult = get(first);
            Attempt secondResult = get(second);
            assertEquals(1, (firstResult.success() ? 1 : 0) + (secondResult.success() ? 1 : 0));
            RuntimeException failure = firstResult.success()
                ? secondResult.failure()
                : firstResult.failure();
            assertInstanceOf(
                ApprovalEffectiveReleaseService.ActivationConflictException.class,
                failure
            );
        }

        ApprovalEffectiveRelease effective = effective();
        int winner = effective.effectiveReleaseVersion();
        int loser = winner == 2 ? 3 : 2;
        assertTrue(winner == 2 || winner == 3);
        assertEquals(2, effective.revision());
        assertEquals(State.DEPRECATED, lifecycle(1).lifecycleState());
        assertEquals(State.ACTIVE, lifecycle(winner).lifecycleState());
        assertEquals(State.PUBLISHED, lifecycle(loser).lifecycleState());
        assertEquals(1, countActiveLifecycles());
        assertEquals(6, count("ap_process_release_lifecycle_history"));
        assertEquals(2, count("ap_approval_release_activation_history"));
        assertEquals(2, count("ap_audit_event"));
        assertEquals(1, count("ap_audit_chain_state"));
        assertEquals(2, count("ap_command_idempotency"));
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(distinct request_id) from ap_audit_event",
                Integer.class
            )
        );
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(distinct idempotency_key) from ap_command_idempotency",
                Integer.class
            )
        );
        assertEquals(3, count("ap_approval_release_package"));
        assertEquals(3, count("ap_approval_release_deployment"));
    }

    private Attempt activateAfterSignal(
        CountDownLatch ready,
        CountDownLatch start,
        String idempotencyKey,
        int releaseVersion
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            service().activate(command(idempotencyKey, releaseVersion, 1L));
            return new Attempt(true, null);
        } catch (RuntimeException exception) {
            return new Attempt(false, exception);
        }
    }

    private static Attempt get(Future<Attempt> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private ApprovalProcessReleaseActivationService service() {
        JdbcIdempotencyGuard idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            transactions,
            CLOCK
        );
        AuditEventSink audit = new JdbcAuditEventSink(dataSource, objectMapper, transactions);
        ApprovalEffectiveReleaseService effective = new ApprovalEffectiveReleaseService(
            idempotency,
            new JdbcApprovalReleasePackageStore(dataSource),
            new JdbcApprovalReleaseDeploymentStore(dataSource),
            new JdbcApprovalEffectiveReleaseStore(dataSource),
            audit,
            CLOCK,
            UUID::randomUUID
        );
        return new ApprovalProcessReleaseActivationService(
            idempotency,
            new JdbcApprovalProcessReleaseStore(dataSource),
            (command, operation) -> switch (operation) {
                case ACTIVATE -> effective.activate(command);
                case ROLLBACK -> effective.rollback(command);
            },
            audit,
            new ApprovalReleasePackageHasher(),
            CLOCK,
            UUID::randomUUID
        );
    }

    private ApprovalEffectiveReleaseService.ActivationCommand command(
        String idempotencyKey,
        int releaseVersion,
        Long expectedRevision
    ) {
        return new ApprovalEffectiveReleaseService.ActivationCommand(
            new RequestContext(
                TENANT,
                "operator-release-activation",
                "request-" + idempotencyKey,
                idempotencyKey,
                "trace-release-activation"
            ),
            DEFINITION_KEY,
            releaseVersion,
            expectedRevision,
            "Activate reviewed release through governed lifecycle"
        );
    }

    private ApprovalReleasePackage seedRelease(
        int version,
        State state,
        boolean deployed
    ) {
        ApprovalReleasePackage releasePackage = releasePackage(version);
        insertPackage(releasePackage);
        if (deployed) {
            new JdbcApprovalReleaseDeploymentStore(dataSource).save(deployment(releasePackage));
        }
        JdbcApprovalProcessReleaseStore releases = new JdbcApprovalProcessReleaseStore(dataSource);
        ApprovalProcessRelease.Transition publish = transition(
            releasePackage,
            State.DRAFT,
            State.PUBLISHED,
            1,
            "publish-" + version,
            NOW.minusSeconds(600 - version * 10L)
        );
        ApprovalProcessRelease lifecycle = ApprovalProcessRelease.published(
            releasePackage,
            publish
        );
        releases.savePublished(lifecycle, publish);
        if (state == State.ACTIVE) {
            ApprovalProcessRelease.Transition activate = transition(
                releasePackage,
                State.PUBLISHED,
                State.ACTIVE,
                2,
                "activate-" + version,
                NOW.minusSeconds(300 - version * 10L)
            );
            ApprovalProcessRelease active = lifecycle.transitioned(activate);
            assertTrue(releases.transition(active, lifecycle.revision(), activate));
        } else if (state != State.PUBLISHED) {
            throw new IllegalArgumentException("unsupported seed lifecycle state");
        }
        return releasePackage;
    }

    private void seedEffective(ApprovalReleasePackage releasePackage) {
        new JdbcApprovalEffectiveReleaseStore(dataSource).save(
            effectiveRelease(releasePackage),
            activation(releasePackage)
        );
    }

    private ApprovalProcessRelease lifecycle(int releaseVersion) {
        return new JdbcApprovalProcessReleaseStore(dataSource)
            .find(TENANT, DEFINITION_KEY, releaseVersion)
            .orElseThrow();
    }

    private ApprovalEffectiveRelease effective() {
        return new JdbcApprovalEffectiveReleaseStore(dataSource)
            .find(TENANT, DEFINITION_KEY)
            .orElseThrow();
    }

    private int countActiveLifecycles() {
        return jdbc.queryForObject(
            "select count(*) from ap_process_release_lifecycle "
                + "where tenant_id = ? and definition_key = ? and lifecycle_state = 'ACTIVE'",
            Integer.class,
            TENANT,
            DEFINITION_KEY
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void insertPackage(ApprovalReleasePackage release) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var control = connection.createStatement()) {
                control.execute("set session_replication_role = replica");
            }
            try (var statement = connection.prepareStatement("""
                insert into ap_approval_release_package (
                    tenant_id, definition_key, release_version,
                    definition_version, definition_hash,
                    form_package_version, form_package_hash,
                    form_version, form_hash, ui_schema_version, ui_schema_hash,
                    compiler_version, bpmn_resource_name, bpmn_artifact,
                    compiled_artifact_hash, bpmn_hash, dmn_artifact, dmn_hash,
                    deployment_metadata_hash, package_hash, source_draft_id,
                    published_by, published_at
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """)) {
                int index = 1;
                statement.setString(index++, release.tenantId());
                statement.setString(index++, release.definitionKey());
                statement.setInt(index++, release.releaseVersion());
                statement.setInt(index++, release.definitionVersion());
                statement.setString(index++, release.definitionHash());
                statement.setInt(index++, release.formPackageVersion());
                statement.setString(index++, release.formPackageHash());
                statement.setInt(index++, release.formVersion());
                statement.setString(index++, release.formHash());
                statement.setInt(index++, release.uiSchemaVersion());
                statement.setString(index++, release.uiSchemaHash());
                statement.setString(index++, release.compilerVersion());
                statement.setString(index++, release.bpmnResourceName());
                statement.setString(index++, release.bpmnArtifact());
                statement.setString(index++, release.compiledArtifactHash());
                statement.setString(index++, release.bpmnHash());
                statement.setString(index++, release.dmnArtifact());
                statement.setString(index++, release.dmnHash());
                statement.setString(index++, release.deploymentMetadataHash());
                statement.setString(index++, release.packageHash());
                statement.setObject(index++, release.sourceDraftId());
                statement.setString(index++, release.publishedBy());
                statement.setObject(index, offset(release.publishedAt()));
                assertEquals(1, statement.executeUpdate());
            } finally {
                try (var control = connection.createStatement()) {
                    control.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private static ApprovalReleasePackage releasePackage(int version) {
        return new ApprovalReleasePackage(
            TENANT,
            DEFINITION_KEY,
            version,
            version,
            hash(version + 1),
            version,
            hash(version + 2),
            version,
            hash(version + 3),
            version,
            hash(version + 4),
            "compiler-v1",
            "process-" + version + ".bpmn20.xml",
            "<definitions/>",
            hash(version + 5),
            hash(version + 6),
            null,
            null,
            hash(version + 7),
            hash(version + 8),
            new UUID(10, version),
            "publisher-release-activation",
            NOW.minusSeconds(700 - version * 10L)
        );
    }

    private static ApprovalReleaseDeployment deployment(ApprovalReleasePackage release) {
        return new ApprovalReleaseDeployment(
            new UUID(20, release.releaseVersion()),
            release.tenantId(),
            release.definitionKey(),
            release.releaseVersion(),
            release.packageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            1,
            "engine-deployment-" + release.releaseVersion(),
            "engine-definition-" + release.releaseVersion(),
            release.releaseVersion(),
            null,
            null,
            "deployer-release-activation",
            NOW.minusSeconds(500 - release.releaseVersion() * 10L),
            NOW.minusSeconds(490 - release.releaseVersion() * 10L),
            NOW.minusSeconds(490 - release.releaseVersion() * 10L)
        );
    }

    private static ApprovalProcessRelease.Transition transition(
        ApprovalReleasePackage release,
        State from,
        State to,
        long revision,
        String suffix,
        Instant happenedAt
    ) {
        return new ApprovalProcessRelease.Transition(
            UUID.nameUUIDFromBytes((suffix + '-' + release.releaseVersion()).getBytes()),
            release.tenantId(),
            release.definitionKey(),
            release.releaseVersion(),
            release.packageHash(),
            from,
            to,
            revision,
            "Governed lifecycle transition " + suffix,
            "seed-" + suffix + '-' + release.releaseVersion(),
            from == State.DRAFT ? release.publishedBy() : "operator-release-activation",
            "request-seed-" + suffix,
            "trace-release-activation",
            "audit-event:seed-" + suffix,
            happenedAt
        );
    }

    private static ApprovalEffectiveRelease effectiveRelease(ApprovalReleasePackage release) {
        ApprovalReleaseDeployment deployment = deployment(release);
        return new ApprovalEffectiveRelease(
            release.tenantId(),
            release.definitionKey(),
            release.releaseVersion(),
            null,
            release.packageHash(),
            release.definitionVersion(),
            release.definitionHash(),
            release.formPackageVersion(),
            release.formPackageHash(),
            release.formVersion(),
            release.formHash(),
            release.uiSchemaVersion(),
            release.uiSchemaHash(),
            release.compilerVersion(),
            release.compiledArtifactHash(),
            release.bpmnHash(),
            release.deploymentMetadataHash(),
            deployment.engineDeploymentId(),
            deployment.engineDefinitionId(),
            deployment.engineVersion(),
            ApprovalEffectiveRelease.Status.ACTIVE,
            1,
            "operator-release-activation",
            NOW.minusSeconds(250),
            "Seed current effective release",
            "request-seed-effective",
            "trace-release-activation"
        );
    }

    private static ApprovalEffectiveRelease.Activation activation(
        ApprovalReleasePackage release
    ) {
        ApprovalReleaseDeployment deployment = deployment(release);
        return new ApprovalEffectiveRelease.Activation(
            new UUID(30, release.releaseVersion()),
            release.tenantId(),
            release.definitionKey(),
            release.releaseVersion(),
            null,
            release.packageHash(),
            release.definitionVersion(),
            release.formPackageVersion(),
            release.compilerVersion(),
            deployment.engineDeploymentId(),
            deployment.engineDefinitionId(),
            deployment.engineVersion(),
            ApprovalEffectiveRelease.Action.ACTIVATE,
            1,
            "operator-release-activation",
            NOW.minusSeconds(250),
            "Seed current effective release",
            "request-seed-effective",
            "trace-release-activation"
        );
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String hash(int value) {
        return Integer.toHexString(Math.floorMod(value, 16)).repeat(64);
    }

    private record Attempt(boolean success, RuntimeException failure) {
    }
}
