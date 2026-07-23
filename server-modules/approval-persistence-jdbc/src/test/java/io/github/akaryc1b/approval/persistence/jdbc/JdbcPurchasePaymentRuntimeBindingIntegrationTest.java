package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.RuntimeBindingEnforcingProjectionStore;
import io.github.akaryc1b.approval.application.RuntimeBindingRecordingAuditEventSink;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPurchasePaymentRuntimeBindingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_runtime_binding_start")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactions;
    private ExactStartEngine engine;
    private ApprovalReleasePackage releasePackage;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        JdbcRuntimeBindingStartTestFixture.reset(jdbc);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactions = new JdbcTransactionManager(dataSource);
        engine = new ExactStartEngine();
        releasePackage = JdbcRuntimeBindingStartTestFixture.seedReleaseEvidence(dataSource);
    }

    @Test
    void exactStartCommitsProjectionBindingAuditAndIdempotencyOnce() {
        PurchasePaymentApplicationService service = service(
            new JdbcAuditEventSink(dataSource, objectMapper, transactions)
        );
        var command = startCommand("start-runtime-key");

        var first = service.start(command);
        var replay = service.start(command);

        assertEquals(first, replay);
        assertEquals(1, engine.starts.get());
        assertEquals(1, count("ap_approval_instance"));
        assertEquals(1, count("ap_approval_task"));
        assertEquals(1, count("ap_process_runtime_binding"));
        assertEquals(1, count("ap_audit_event"));
        assertEquals(1, count("ap_command_idempotency"));

        ApprovalRuntimeBinding binding = new JdbcApprovalRuntimeBindingStore(dataSource).find(
            JdbcRuntimeBindingStartTestFixture.TENANT,
            first.instanceId()
        ).orElseThrow();
        assertTrue(binding.binds(
            releasePackage,
            JdbcRuntimeBindingStartTestFixture.deployment()
        ));
        assertEquals("engine-instance-runtime-1", binding.engineInstanceId());
        String auditEventId = jdbc.queryForObject(
            "select event_id::text from ap_audit_event where action = 'INSTANCE_STARTED'",
            String.class
        );
        assertEquals("audit-event:" + auditEventId, binding.auditChainReference());
    }

    @Test
    void auditFailureRollsBackAllPlatformEvidenceButNotExternalEngineStart() {
        PurchasePaymentApplicationService service = service(event -> {
            throw new IllegalStateException("audit unavailable");
        });

        assertThrows(
            IllegalStateException.class,
            () -> service.start(startCommand("start-runtime-failure-key"))
        );

        assertEquals(1, engine.starts.get());
        assertEquals(0, count("ap_approval_instance"));
        assertEquals(0, count("ap_approval_task"));
        assertEquals(0, count("ap_process_runtime_binding"));
        assertEquals(0, count("ap_audit_event"));
        assertEquals(0, count("ap_command_idempotency"));
    }

    private PurchasePaymentApplicationService service(AuditEventSink delegateAudit) {
        ApprovalProjectionStore rawProjection = new JdbcApprovalProjectionStore(
            dataSource,
            objectMapper
        );
        ApprovalRuntimeBindingStore runtimeBindings = new JdbcApprovalRuntimeBindingStore(
            dataSource
        );
        ApprovalProjectionStore bindingProjection = new RuntimeBindingEnforcingProjectionStore(
            rawProjection,
            runtimeBindings
        );
        AuditEventSink bindingAudit = new RuntimeBindingRecordingAuditEventSink(
            delegateAudit,
            rawProjection,
            new JdbcApprovalReleasePackageStore(dataSource),
            new JdbcApprovalReleaseDeploymentStore(dataSource),
            runtimeBindings,
            new ApprovalReleasePackageHasher()
        );
        return new PurchasePaymentApplicationService(
            engine,
            new ApprovalDslCompiler(),
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactions,
                Clock.fixed(JdbcRuntimeBindingStartTestFixture.NOW, ZoneOffset.UTC)
            ),
            bindingProjection,
            bindingAudit,
            (context, rules) -> {
                throw new AssertionError("explicit assignees must bypass resolver");
            },
            ApprovalBusinessEventOutbox.noOp(),
            new JdbcApprovalEffectiveReleaseStore(dataSource),
            Clock.fixed(JdbcRuntimeBindingStartTestFixture.NOW, ZoneOffset.UTC),
            () -> JdbcRuntimeBindingStartTestFixture.INSTANCE_ID
        );
    }

    private PurchasePaymentApplicationService.StartCommand startCommand(String idempotencyKey) {
        return new PurchasePaymentApplicationService.StartCommand(
            new RequestContext(
                JdbcRuntimeBindingStartTestFixture.TENANT,
                "initiator-runtime",
                "request-" + idempotencyKey,
                idempotencyKey,
                "trace-runtime"
            ),
            "business-runtime-1",
            new BigDecimal("5000.00"),
            "Supplier Runtime",
            "PO-RUNTIME-1",
            List.of("attachment-runtime-1"),
            new AssigneeSnapshot(
                "manager-runtime",
                "finance-reviewer-runtime",
                List.of("finance-runtime-a", "finance-runtime-b"),
                Map.of("resolvedFrom", "runtime-binding-test")
            )
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static final class ExactStartEngine implements ApprovalEngine {

        private final AtomicInteger starts = new AtomicInteger();

        @Override
        public DeploymentResult deploy(DeployCommand command) {
            throw new AssertionError("release-bound start must not deploy");
        }

        @Override
        public StartResult start(StartCommand command) {
            throw new AssertionError("release-bound start must use startExact");
        }

        @Override
        public StartResult startExact(ExactStartCommand command) {
            int sequence = starts.incrementAndGet();
            assertEquals(
                JdbcRuntimeBindingStartTestFixture.ENGINE_DEFINITION_ID,
                command.engineDefinitionId()
            );
            assertEquals(
                JdbcRuntimeBindingStartTestFixture.PACKAGE_HASH,
                command.releasePackageHash()
            );
            return new StartResult("engine-instance-runtime-" + sequence);
        }

        @Override
        public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
            return List.of(new TaskSnapshot(
                "engine-task-runtime-1",
                query.processInstanceId(),
                "managerApproval",
                "Manager approval",
                "manager-runtime",
                JdbcRuntimeBindingStartTestFixture.NOW
            ));
        }

        @Override
        public TaskResult complete(CompleteTaskCommand command) {
            throw new AssertionError("start test must not complete tasks");
        }
    }
}
