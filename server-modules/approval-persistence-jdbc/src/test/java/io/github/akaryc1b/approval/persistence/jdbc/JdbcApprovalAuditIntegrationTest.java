package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalAuditService;
import io.github.akaryc1b.approval.application.ApprovalAuditService.AuditOperationException;
import io.github.akaryc1b.approval.application.ApprovalAuditService.AuditQuery;
import io.github.akaryc1b.approval.application.ApprovalAuditService.ExportCommand;
import io.github.akaryc1b.approval.application.ApprovalAuditService.ExportFormat;
import io.github.akaryc1b.approval.application.ApprovalAuditService.VerifyCommand;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditIntegrityCriteria;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalAuditIntegrationTest {

    private static final Instant START = Instant.parse("2026-07-20T12:00:00Z");
    private static final String ZERO_HASH = "0".repeat(64);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_audit_governance_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalAuditFixture fixture;

    @BeforeAll
    static void migrateDatabase() {
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
                ap_audit_event,
                ap_audit_chain_state,
                ap_command_idempotency
            cascade
            """);
        fixture = new JdbcApprovalAuditFixture(dataSource);
    }

    @Test
    void assignsVersionedSchemasAndIsolatesTenantChains() {
        fixture.store().append(event(
            "tenant-a",
            "TASK_APPROVED",
            "request-a-1",
            START,
            Map.of("reason", "approved")
        ));
        fixture.store().append(event(
            "tenant-a",
            "TASK_REJECTED",
            "request-a-2",
            START.plusSeconds(1),
            Map.of("reason", "missing receipt")
        ));
        fixture.store().append(event(
            "tenant-b",
            "INSTANCE_STARTED",
            "request-b-1",
            START,
            Map.of()
        ));

        var tenantA = fixture.store().find(criteria("tenant-a", null, 20, 0));
        var tenantB = fixture.store().find(criteria("tenant-b", null, 20, 0));

        assertEquals(2, tenantA.total());
        assertEquals(List.of(2L, 1L), tenantA.items().stream()
            .map(item -> item.tenantSequence())
            .toList());
        assertEquals("approval.task-lifecycle", tenantA.items().getFirst().schemaName());
        assertEquals(1, tenantA.items().getFirst().schemaVersion());
        assertEquals(ZERO_HASH, tenantA.items().getLast().previousHash());
        assertEquals(
            tenantA.items().getLast().currentHash(),
            tenantA.items().getFirst().previousHash()
        );
        assertEquals(1, tenantB.total());
        assertEquals(1, tenantB.items().getFirst().tenantSequence());
        assertEquals(ZERO_HASH, tenantB.items().getFirst().previousHash());
        assertNotEquals(
            tenantA.items().getLast().currentHash(),
            tenantB.items().getFirst().currentHash()
        );
        assertTrue(fixture.store().verify(integrity("tenant-a")).valid());
        assertTrue(fixture.store().verify(integrity("tenant-b")).valid());
    }

    @Test
    void serializesConcurrentTenantAppendsWithoutGaps() throws Exception {
        int eventCount = 24;
        ExecutorService executor = Executors.newFixedThreadPool(eventCount);
        CountDownLatch ready = new CountDownLatch(eventCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < eventCount; index++) {
                int eventIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    fixture.store().append(event(
                        "tenant-concurrent",
                        "TASK_APPROVED",
                        "concurrent-" + eventIndex,
                        START.plusMillis(eventIndex),
                        Map.of("index", Integer.toString(eventIndex))
                    ));
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        var page = fixture.store().find(criteria("tenant-concurrent", null, 100, 0));
        assertEquals((long) eventCount, page.total());
        assertEquals((long) eventCount, page.items().getFirst().tenantSequence());
        assertEquals(1L, page.items().getLast().tenantSequence());
        assertTrue(fixture.store().verify(integrity("tenant-concurrent")).valid());
    }

    @Test
    void detectsPayloadTampering() {
        fixture.store().append(event(
            "tenant-a",
            "TASK_APPROVED",
            "tamper-1",
            START,
            Map.of("reason", "approved")
        ));
        jdbc.update(
            """
            update ap_audit_event
            set attributes_json = jsonb_set(
                attributes_json,
                '{reason}',
                to_jsonb(cast(? as text))
            )
            where tenant_id = ?
            """,
            "tampered",
            "tenant-a"
        );

        var result = fixture.store().verify(integrity("tenant-a"));
        assertFalse(result.valid());
        assertEquals("PAYLOAD_HASH_MISMATCH", result.failureCode());
        assertEquals(1L, result.firstInvalidSequence());
        assertNotNull(result.firstInvalidEventId());
    }

    @Test
    void duplicateFailureDoesNotAdvanceChainStateOrLeaveHalfWrite() {
        AuditEvent first = event(
            "tenant-a",
            "TASK_APPROVED",
            "duplicate-1",
            START,
            Map.of()
        );
        fixture.store().append(first);
        assertThrows(DataIntegrityViolationException.class, () -> fixture.store().append(first));
        fixture.store().append(event(
            "tenant-a",
            "TASK_REJECTED",
            "duplicate-2",
            START.plusSeconds(1),
            Map.of()
        ));

        var page = fixture.store().find(criteria("tenant-a", null, 20, 0));
        assertEquals(2, page.total());
        assertEquals(List.of(2L, 1L), page.items().stream()
            .map(item -> item.tenantSequence())
            .toList());
        assertEquals(2L, jdbc.queryForObject(
            "select last_sequence from ap_audit_chain_state where tenant_id = ?",
            Long.class,
            "tenant-a"
        ));
        assertTrue(fixture.store().verify(integrity("tenant-a")).valid());
    }

    @Test
    void queryFiltersRemainTenantBoundedAndPaginated() {
        fixture.store().append(event(
            "tenant-a",
            "TASK_APPROVED",
            "query-1",
            START,
            Map.of()
        ));
        fixture.store().append(event(
            "tenant-a",
            "TASK_REJECTED",
            "query-2",
            START.plusSeconds(1),
            Map.of()
        ));
        fixture.store().append(event(
            "tenant-b",
            "TASK_APPROVED",
            "query-3",
            START.plusSeconds(2),
            Map.of()
        ));

        var approved = fixture.store().find(new AuditCriteria(
            "tenant-a",
            null,
            "TASK_APPROVED",
            null,
            null,
            null,
            null,
            START.minusSeconds(1),
            START.plus(Duration.ofDays(1)),
            10,
            0
        ));
        var firstPage = fixture.store().find(criteria("tenant-a", null, 1, 0));
        var secondPage = fixture.store().find(criteria("tenant-a", null, 1, 1));

        assertEquals(1, approved.total());
        assertEquals("query-1", approved.items().getFirst().requestId());
        assertEquals(2, firstPage.total());
        assertTrue(firstPage.hasMore());
        assertEquals(1, firstPage.items().size());
        assertFalse(secondPage.hasMore());
        assertEquals(1, secondPage.items().size());
        assertNotEquals(
            firstPage.items().getFirst().eventId(),
            secondPage.items().getFirst().eventId()
        );
    }

    @Test
    void serviceRedactsSensitiveAttributesAndBoundsRanges() {
        fixture.store().append(event(
            "tenant-a",
            "TASK_APPROVED",
            "redact-1",
            START,
            Map.of(
                "body", "private body",
                "plain", "visible",
                "secretToken", "private token"
            )
        ));

        var page = fixture.service().find(query(
            "tenant-a",
            START.minusSeconds(1),
            START.plus(Duration.ofDays(1))
        ));
        Map<String, String> attributes = page.items().getFirst().attributes();
        assertEquals("[REDACTED]", attributes.get("body"));
        assertEquals("[REDACTED]", attributes.get("secretToken"));
        assertEquals("visible", attributes.get("plain"));

        AuditOperationException error = assertThrows(
            AuditOperationException.class,
            () -> fixture.service().find(query(
                "tenant-a",
                START.minus(Duration.ofDays(32)),
                START.plusSeconds(1)
            ))
        );
        assertEquals("APPROVAL_AUDIT_RANGE_TOO_LARGE", error.code());
    }

    @Test
    void exportAndIntegrityVerificationAreIdempotentAndAudited() {
        fixture.store().append(event(
            "tenant-a",
            "TASK_APPROVED",
            "admin-1",
            START,
            Map.of("plain", "visible")
        ));
        AuditQuery query = query(
            "tenant-a",
            START.minusSeconds(1),
            START.plus(Duration.ofDays(1))
        );
        ExportCommand exportCommand = new ExportCommand(
            context("tenant-a", "auditor", "export-request", "export-key"),
            query,
            ExportFormat.JSON,
            100
        );
        var export = fixture.service().prepareExport(exportCommand);
        assertEquals(1, export.records().size());
        assertEquals(export, fixture.service().prepareExport(exportCommand));
        assertEquals(1, countAction("tenant-a", "AUDIT_EXPORTED"));

        VerifyCommand verifyCommand = new VerifyCommand(
            context("tenant-a", "auditor", "verify-request", "verify-key"),
            START.minusSeconds(1),
            START.plus(Duration.ofDays(1))
        );
        var report = fixture.service().verify(verifyCommand);
        assertTrue(report.valid());
        assertTrue(report.assuranceStatement().contains(
            "not a claim of legal non-repudiation"
        ));
        assertEquals(report, fixture.service().verify(verifyCommand));
        assertEquals(1, countAction("tenant-a", "AUDIT_INTEGRITY_VERIFIED"));
        assertTrue(fixture.store().verify(integrity("tenant-a")).valid());
    }

    @Test
    void currentContractsRejectMissingRequiredCommentEvidence() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> new AuditEvent(
                UUID.randomUUID(),
                "tenant-a",
                "manager-1",
                "INSTANCE_COMMENT_CREATED",
                "APPROVAL_INSTANCE",
                UUID.randomUUID().toString(),
                "contract-request",
                "contract-trace",
                START,
                Map.of("commentId", UUID.randomUUID().toString())
            )
        );
        assertTrue(error.getMessage().contains("audit attribute is required"));
    }

    private int countAction(String tenantId, String action) {
        Integer count = jdbc.queryForObject(
            "select count(*) from ap_audit_event where tenant_id = ? and action = ?",
            Integer.class,
            tenantId,
            action
        );
        return count == null ? 0 : count;
    }

    private static AuditEvent event(
        String tenantId,
        String action,
        String requestId,
        Instant occurredAt,
        Map<String, String> attributes
    ) {
        return new AuditEvent(
            UUID.randomUUID(),
            tenantId,
            "operator-1",
            action,
            action.startsWith("INSTANCE_") ? "APPROVAL_INSTANCE" : "APPROVAL_TASK",
            UUID.randomUUID().toString(),
            requestId,
            "trace-" + requestId,
            occurredAt,
            attributes
        );
    }

    private static AuditCriteria criteria(
        String tenantId,
        String action,
        int limit,
        int offset
    ) {
        return new AuditCriteria(
            tenantId,
            null,
            action,
            null,
            null,
            null,
            null,
            START.minusSeconds(1),
            START.plus(Duration.ofDays(1)),
            limit,
            offset
        );
    }

    private static AuditIntegrityCriteria integrity(String tenantId) {
        return new AuditIntegrityCriteria(
            tenantId,
            START.minusSeconds(1),
            START.plus(Duration.ofDays(1))
        );
    }

    private static AuditQuery query(String tenantId, Instant from, Instant to) {
        return new AuditQuery(
            tenantId,
            null,
            null,
            null,
            null,
            null,
            null,
            from,
            to,
            100,
            0
        );
    }

    private static RequestContext context(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            "trace-" + requestId
        );
    }

    private static final class JdbcApprovalAuditFixture {
        private final JdbcAuditEventSink store;
        private final ApprovalAuditService service;

        private JdbcApprovalAuditFixture(DataSource dataSource) {
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
            Clock clock = Clock.fixed(START.plus(Duration.ofHours(1)), ZoneOffset.UTC);
            store = new JdbcAuditEventSink(dataSource, objectMapper, transactionManager);
            JdbcIdempotencyGuard idempotency = new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactionManager,
                clock
            );
            service = new ApprovalAuditService(
                idempotency,
                store,
                store,
                clock,
                UUID::randomUUID
            );
        }

        private JdbcAuditEventSink store() {
            return store;
        }

        private ApprovalAuditService service() {
            return service;
        }
    }
}
