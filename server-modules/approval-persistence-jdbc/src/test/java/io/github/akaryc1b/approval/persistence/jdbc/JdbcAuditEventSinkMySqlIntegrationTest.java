package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditIntegrityCriteria;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAuditEventSinkMySqlIntegrationTest {

    private static final Instant START = Instant.parse("2026-08-07T12:00:00.123456Z");
    private static final String ZERO_HASH = "0".repeat(64);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_audit")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private ExecutorService executor;

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

    @AfterEach
    void clean() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        jdbc.update("delete from ap_audit_event");
        jdbc.update("delete from ap_audit_chain_state");
    }

    @Test
    void appendsTenantIsolatedChainsWithExactHashesAndCanonicalReadback() {
        ApprovalAuditStore store = store();
        AuditEvent first = event(
            "00000000-0000-0000-0000-000000000101",
            "tenant-a",
            "request-a-1",
            START.plusNanos(499),
            Map.of("reason", "审批✅", "b", "line1\nline2")
        );
        AuditEvent second = event(
            "00000000-0000-0000-0000-000000000102",
            "tenant-a",
            "request-a-2",
            START.plusNanos(500),
            Map.of("amount", "123456789012.123456", "sequence", "9223372036854775807")
        );
        AuditEvent otherTenant = event(
            "00000000-0000-0000-0000-000000000103",
            "tenant-b",
            "request-b-1",
            START,
            Map.of("reason", "independent")
        );

        store.append(first);
        store.append(second);
        store.append(otherTenant);

        var tenantA = store.find(criteria("tenant-a", null, 20, 0));
        var tenantB = store.find(criteria("tenant-b", null, 20, 0));

        assertEquals(2, tenantA.total());
        assertEquals(List.of(2L, 1L), tenantA.items().stream()
            .map(ApprovalAuditStore.AuditRecord::tenantSequence)
            .toList());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(second.occurredAt()),
            tenantA.items().getFirst().occurredAt()
        );
        assertEquals(
            AuditHashCanonicalizer.payloadHash(second),
            tenantA.items().getFirst().payloadHash()
        );
        assertEquals(
            tenantA.items().getLast().currentHash(),
            tenantA.items().getFirst().previousHash()
        );
        assertEquals("123456789012.123456", tenantA.items().getFirst()
            .attributes().get("amount"));
        assertEquals(1, tenantB.total());
        assertEquals(ZERO_HASH, tenantB.items().getFirst().previousHash());
        assertTrue(store.verify(integrity("tenant-a")).valid());
        assertTrue(store.verify(integrity("tenant-b")).valid());
    }

    @Test
    void serializesConcurrentTenantAppendsWithoutGaps() throws Exception {
        ApprovalAuditStore store = store();
        int eventCount = 16;
        executor = Executors.newFixedThreadPool(eventCount);
        CountDownLatch ready = new CountDownLatch(eventCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < eventCount; index++) {
                int eventIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch timed out");
                    }
                    store.append(event(
                        new UUID(0, eventIndex + 1L).toString(),
                        "tenant-concurrent",
                        "request-" + eventIndex,
                        START.plusNanos(eventIndex * 1_000L),
                        Map.of("index", Integer.toString(eventIndex))
                    ));
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor = null;
        }

        var page = store.find(criteria("tenant-concurrent", null, 100, 0));
        assertEquals(eventCount, page.total());
        assertEquals(eventCount, page.items().getFirst().tenantSequence());
        assertEquals(1L, page.items().getLast().tenantSequence());
        assertTrue(store.verify(integrity("tenant-concurrent")).valid());
    }

    @Test
    void chainStateAdmissionUsesOnlyThePrimaryKeySelfAssignment() {
        String admission = JdbcMySqlAuditEventSink.chainStateAdmissionSql()
            .toLowerCase(Locale.ROOT)
            .strip();
        int clauseStart = admission.indexOf("on duplicate key update");

        assertTrue(clauseStart > 0);
        assertEquals(
            "on duplicate key update tenant_id = tenant_id",
            admission.substring(clauseStart)
        );
        assertFalse(admission.contains("insert ignore"));
    }

    @Test
    void failedFirstAppendRollsBackZeroStateAndDoesNotConsumeSequence() {
        ApprovalAuditStore store = store();
        String conflictingEventId = "00000000-0000-0000-0000-000000000180";
        store.append(event(
            conflictingEventId,
            "tenant-existing",
            "request-existing",
            START,
            Map.of()
        ));

        assertThrows(DataIntegrityViolationException.class, () -> store.append(event(
            conflictingEventId,
            "tenant-first-failure",
            "request-conflict",
            START,
            Map.of()
        )));
        assertEquals(0, tenantRowCount("ap_audit_event", "tenant-first-failure"));
        assertEquals(0, tenantRowCount(
            "ap_audit_chain_state",
            "tenant-first-failure"
        ));

        store.append(event(
            "00000000-0000-0000-0000-000000000181",
            "tenant-first-failure",
            "request-recovered",
            START.plusSeconds(1),
            Map.of()
        ));

        var page = store.find(criteria("tenant-first-failure", null, 20, 0));
        assertEquals(1, page.total());
        assertEquals(1L, page.items().getFirst().tenantSequence());
        assertTrue(store.verify(integrity("tenant-first-failure")).valid());
    }

    @Test
    void detectsPayloadTamperingWithoutRepairingEvidence() {
        ApprovalAuditStore store = store();
        AuditEvent event = event(
            "00000000-0000-0000-0000-000000000201",
            "tenant-a",
            "request-tamper",
            START,
            Map.of("reason", "approved")
        );
        store.append(event);

        jdbc.update(
            """
            update ap_audit_event
            set attributes_json = json_set(attributes_json, '$.reason', ?)
            where tenant_id = ?
            """,
            "tampered",
            "tenant-a"
        );

        var result = store.verify(integrity("tenant-a"));
        assertFalse(result.valid());
        assertEquals("PAYLOAD_HASH_MISMATCH", result.failureCode());
        assertEquals(1L, result.firstInvalidSequence().longValue());
        assertEquals(1, rowCount("ap_audit_event"));
        assertEquals(1, rowCount("ap_audit_chain_state"));
    }

    @Test
    void rejectsNonStringStoredAttributesInsteadOfCoercingEvidence() {
        ApprovalAuditStore store = store();
        store.append(event(
            "00000000-0000-0000-0000-000000000203",
            "tenant-a",
            "request-type-tamper",
            START,
            Map.of("reason", "123")
        ));
        jdbc.update(
            """
            update ap_audit_event
            set attributes_json = json_set(attributes_json, '$.reason', cast(? as unsigned))
            where tenant_id = ?
            """,
            123,
            "tenant-a"
        );

        assertThrows(DataAccessException.class, () -> store.verify(integrity("tenant-a")));
        assertEquals(1, rowCount("ap_audit_event"));
        assertEquals(1, rowCount("ap_audit_chain_state"));
    }

    @Test
    void detectsChainStateTamperingWithoutRepairingEvidence() {
        ApprovalAuditStore store = store();
        store.append(event(
            "00000000-0000-0000-0000-000000000202",
            "tenant-a",
            "request-state-tamper",
            START,
            Map.of()
        ));
        String tamperedHash = "f".repeat(64);
        jdbc.update(
            """
            update ap_audit_chain_state
            set last_hash = ?
            where tenant_id = ?
            """,
            tamperedHash,
            "tenant-a"
        );

        var result = store.verify(integrity("tenant-a"));
        assertFalse(result.valid());
        assertEquals("CHAIN_STATE_MISMATCH", result.failureCode());
        assertEquals(tamperedHash, result.chainStateHash());
        assertEquals(tamperedHash, jdbc.queryForObject(
            "select last_hash from ap_audit_chain_state where tenant_id = ?",
            String.class,
            "tenant-a"
        ));
    }

    @Test
    void duplicateFailureDoesNotAdvanceTheTenantChain() {
        ApprovalAuditStore store = store();
        AuditEvent first = event(
            "00000000-0000-0000-0000-000000000301",
            "tenant-a",
            "request-duplicate-1",
            START,
            Map.of()
        );
        store.append(first);

        assertThrows(DataIntegrityViolationException.class, () -> store.append(first));

        store.append(event(
            "00000000-0000-0000-0000-000000000302",
            "tenant-a",
            "request-duplicate-2",
            START.plusSeconds(1),
            Map.of()
        ));

        var page = store.find(criteria("tenant-a", null, 20, 0));
        assertEquals(2, page.total());
        assertEquals(List.of(2L, 1L), page.items().stream()
            .map(ApprovalAuditStore.AuditRecord::tenantSequence)
            .toList());
        assertEquals(2L, Objects.requireNonNull(jdbc.queryForObject(
            "select last_sequence from ap_audit_chain_state where tenant_id = ?",
            Long.class,
            "tenant-a"
        )).longValue());
        assertTrue(store.verify(integrity("tenant-a")).valid());
    }

    @Test
    void queryFiltersRemainTenantBoundedAndPaginated() {
        ApprovalAuditStore store = store();
        store.append(event(
            "00000000-0000-0000-0000-000000000401",
            "tenant-a",
            "query-1",
            START,
            Map.of()
        ));
        store.append(new AuditEvent(
            UUID.fromString("00000000-0000-0000-0000-000000000402"),
            "tenant-a",
            "operator-a",
            "TASK_REJECTED",
            "APPROVAL_TASK",
            "task-a",
            "query-2",
            "trace-query-2",
            START.plusSeconds(1),
            Map.of("reason", "rejected")
        ));
        store.append(event(
            "00000000-0000-0000-0000-000000000403",
            "tenant-b",
            "query-3",
            START.plusSeconds(2),
            Map.of()
        ));

        var approved = store.find(criteria("tenant-a", "TASK_APPROVED", 10, 0));
        var firstPage = store.find(criteria("tenant-a", null, 1, 0));
        var secondPage = store.find(criteria("tenant-a", null, 1, 1));

        assertEquals(1, approved.total());
        assertEquals("query-1", approved.items().getFirst().requestId());
        assertEquals(2, firstPage.total());
        assertTrue(firstPage.hasMore());
        assertEquals(1, firstPage.items().size());
        assertFalse(secondPage.hasMore());
        assertEquals(1, secondPage.items().size());
    }

    @Test
    void factorySelectsTheMySqlAuditImplementation() {
        assertInstanceOf(JdbcMySqlAuditEventSink.class, store());
    }

    private static ApprovalAuditStore store() {
        return JdbcAuditEventStoreFactory.create(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            new JdbcTransactionManager(dataSource)
        );
    }

    private static AuditEvent event(
        String eventId,
        String tenantId,
        String requestId,
        Instant occurredAt,
        Map<String, String> attributes
    ) {
        return new AuditEvent(
            UUID.fromString(eventId),
            tenantId,
            "operator-a",
            "TASK_APPROVED",
            "APPROVAL_TASK",
            "task-a",
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
            START.plusSeconds(60),
            limit,
            offset
        );
    }

    private static AuditIntegrityCriteria integrity(String tenantId) {
        return new AuditIntegrityCriteria(
            tenantId,
            START.minusSeconds(1),
            START.plusSeconds(60)
        );
    }

    private static int tenantRowCount(String table, String tenantId) {
        if (!List.of("ap_audit_event", "ap_audit_chain_state").contains(table)) {
            throw new IllegalArgumentException("unsupported table");
        }
        Integer count = jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id = ?",
            Integer.class,
            tenantId
        );
        return count == null ? 0 : count;
    }

    private static int rowCount(String table) {
        if (!List.of("ap_audit_event", "ap_audit_chain_state").contains(table)) {
            throw new IllegalArgumentException("unsupported table");
        }
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static String configuredJdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true";
    }
}
