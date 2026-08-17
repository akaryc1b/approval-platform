package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.DeleteReason;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.EvidenceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.StoreDisposition;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.TombstoneCommand;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.TombstoneDisposition;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalAssistanceDurableEvidenceJdbcTestFixture.Fixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalAssistanceDurableEvidenceStoreMySqlIntegrationTest {

    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_ai_evidence")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ApprovalAssistanceDurableEvidenceStore store;

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
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        store = JdbcApprovalAssistanceDurableEvidenceStoreFactory.create(
            dataSource,
            transactionManager,
            JdbcApprovalAssistanceDurableEvidenceStoreMySqlIntegrationTest::nextEventId
        );
    }

    @Test
    void selectsMySqlAuthorityAndStoresReplaysAndReadsExactEvidence() {
        assertInstanceOf(JdbcMySqlApprovalAssistanceDurableEvidenceStore.class, store);
        Fixture fixture = fixture("store-replay");

        var stored = store.store(fixture.evidence());
        var replayed = store.store(fixture.evidence());
        var snapshot = store.find(fixture.tenantId(), fixture.evidence().evidenceId())
            .orElseThrow();

        assertEquals(StoreDisposition.STORED, stored.disposition());
        assertEquals(StoreDisposition.REPLAYED, replayed.disposition());
        assertEquals(stored.eventHash(), replayed.eventHash());
        assertEquals(fixture.evidence(), snapshot.evidence());
        assertEquals(EvidenceState.ACTIVE, snapshot.state());
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void classifiesRequestContentAndTenantIdentityWithoutPartialWrites() {
        String tenant = "tenant-h8-collision";
        String request = "request-h8-shared";
        Fixture first = ApprovalAssistanceDurableEvidenceJdbcTestFixture.fixture(
            tenant,
            ApprovalAssistanceDurableEvidenceJdbcTestFixture.uuid("h8-collision-first"),
            request,
            "1000.00",
            "First summary"
        );
        Fixture sameRequest = ApprovalAssistanceDurableEvidenceJdbcTestFixture.fixture(
            tenant,
            ApprovalAssistanceDurableEvidenceJdbcTestFixture.uuid("h8-collision-second"),
            request,
            "1000.00",
            "First summary"
        );
        Fixture otherTenant = ApprovalAssistanceDurableEvidenceJdbcTestFixture.fixture(
            "Tenant-H8-Collision",
            first.evidence().evidenceId(),
            "request-h8-other-tenant",
            "2000.00",
            "Other tenant summary"
        );

        assertEquals(StoreDisposition.STORED, store.store(first.evidence()).disposition());
        assertEquals(StoreDisposition.CONFLICT, store.store(sameRequest.evidence()).disposition());
        assertEquals(StoreDisposition.STORED, store.store(otherTenant.evidence()).disposition());
        assertEquals(0, count(sameRequest, "ap_ai_approval_assistance_evidence"));
        assertTrue(store.find(first.tenantId(), first.evidence().evidenceId()).isPresent());
        assertTrue(store.find(
            otherTenant.tenantId(),
            otherTenant.evidence().evidenceId()
        ).isPresent());
        assertFalse(store.find(tenant.toUpperCase(), first.evidence().evidenceId()).isPresent());
    }

    @Test
    void retentionRevisionAndCanonicalTombstoneReplayRemainFailClosed() {
        Fixture fixture = fixture("tombstone");
        store.store(fixture.evidence());
        Instant beforeRetention = fixture.evidence().recordedAt().plusSeconds(10);

        var blocked = store.tombstone(new TombstoneCommand(
            fixture.tenantId(),
            fixture.evidence().evidenceId(),
            1,
            DeleteReason.RETENTION_EXPIRED,
            beforeRetention,
            hash('a')
        ));
        var stale = store.tombstone(new TombstoneCommand(
            fixture.tenantId(),
            fixture.evidence().evidenceId(),
            2,
            DeleteReason.TENANT_POLICY,
            beforeRetention,
            hash('b')
        ));
        Instant nonCanonical = beforeRetention.plusNanos(999_999_500L);
        TombstoneCommand command = new TombstoneCommand(
            fixture.tenantId(),
            fixture.evidence().evidenceId(),
            1,
            DeleteReason.DATA_SUBJECT_REQUEST,
            nonCanonical,
            hash('c')
        );
        var tombstoned = store.tombstone(command);
        var replayed = store.tombstone(command);
        var snapshot = store.find(fixture.tenantId(), fixture.evidence().evidenceId())
            .orElseThrow();

        assertEquals(TombstoneDisposition.RETENTION_BLOCKED, blocked.disposition());
        assertEquals(TombstoneDisposition.REVISION_CONFLICT, stale.disposition());
        assertEquals(TombstoneDisposition.TOMBSTONED, tombstoned.disposition());
        assertEquals(TombstoneDisposition.REPLAYED, replayed.disposition());
        assertEquals(tombstoned.eventHash(), replayed.eventHash());
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(nonCanonical),
            tombstoned.tombstonedAt()
        );
        assertEquals(EvidenceState.TOMBSTONED, snapshot.state());
        assertEquals(2, snapshot.revision());
        assertNotNull(snapshot.tombstoneHash());
        assertCounts(fixture, 1, 1, 2);
    }

    @Test
    void concurrentStoreAndTombstoneHaveOneWinnerAndExactReplay() throws Exception {
        Fixture storeFixture = fixture("concurrent-store");
        List<StoreDisposition> stores = concurrently(
            () -> store.store(storeFixture.evidence()).disposition()
        );
        assertEquals(
            List.of(StoreDisposition.REPLAYED, StoreDisposition.STORED),
            stores.stream().sorted(Comparator.comparing(Enum::name)).toList()
        );
        assertCounts(storeFixture, 1, 1, 1);

        Fixture tombstoneFixture = fixture("concurrent-tombstone");
        store.store(tombstoneFixture.evidence());
        TombstoneCommand command = new TombstoneCommand(
            tombstoneFixture.tenantId(),
            tombstoneFixture.evidence().evidenceId(),
            1,
            DeleteReason.SECURITY_INCIDENT,
            tombstoneFixture.evidence().recordedAt().plusSeconds(10),
            hash('d')
        );
        List<TombstoneDisposition> tombstones = concurrently(
            () -> store.tombstone(command).disposition()
        );
        assertEquals(
            List.of(TombstoneDisposition.REPLAYED, TombstoneDisposition.TOMBSTONED),
            tombstones.stream().sorted(Comparator.comparing(Enum::name)).toList()
        );
        assertCounts(tombstoneFixture, 1, 1, 2);
    }

    @Test
    void outerRollbackRemovesEvidenceEventAndTriggerMaterializedState() {
        Fixture fixture = fixture("rollback");

        assertThrows(RollbackMarker.class, () -> transactions.executeWithoutResult(status -> {
            store.store(fixture.evidence());
            throw new RollbackMarker();
        }));

        assertTrue(store.find(fixture.tenantId(), fixture.evidence().evidenceId()).isEmpty());
        assertCounts(fixture, 0, 0, 0);
        assertEquals(StoreDisposition.STORED, store.store(fixture.evidence()).disposition());
    }

    @Test
    void physicalMutationAndStructurallyInvalidEvidenceFailClosed() {
        Fixture fixture = fixture("immutability");
        store.store(fixture.evidence());
        Object[] key = {fixture.tenantId(), fixture.evidence().evidenceId().toString()};

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_ai_approval_assistance_evidence
            set classification='UNKNOWN'
            where tenant_id=? and evidence_id=?
            """, key));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            delete from ap_ai_approval_assistance_evidence_event
            where tenant_id=? and evidence_id=?
            """, key));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            delete from ap_ai_approval_assistance_evidence_state
            where tenant_id=? and evidence_id=?
            """, key));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_ai_approval_assistance_evidence_state
            set revision=2,state='TOMBSTONED',delete_reason='TENANT_POLICY',
                tombstoned_at=updated_at,deletion_request_hash=?,tombstone_hash=?,
                current_event_hash=?
            where tenant_id=? and evidence_id=?
            """, hash('a'), hash('b'), hash('c'), key[0], key[1]));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash,tombstone_hash
            ) values (?, ?, ?, 2, 'TOMBSTONED', ?, ?, ?, 'TENANT_POLICY', ?, ?)
            """,
            key[0], nextEventId().toString(), key[1], hash('f'), hash('a'),
            Timestamp.from(fixture.evidence().recordedAt().plusSeconds(10)),
            hash('b'), hash('c')
        ));
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void schemaRetainsHashOnlyTypesAndExactMySqlP4Triggers() {
        Integer rawColumns = jdbc.queryForObject("""
            select count(*)
            from information_schema.columns
            where table_schema=database()
              and table_name in (
                'ap_ai_approval_assistance_evidence',
                'ap_ai_approval_assistance_evidence_state',
                'ap_ai_approval_assistance_evidence_event'
              )
              and (
                data_type in ('text','json','blob','longblob','mediumblob')
                or column_name regexp
                  '(raw|payload|body|content|summary|observation|risk|recommendation|limitation)'
              )
            """, Integer.class);
        Integer triggers = jdbc.queryForObject("""
            select count(*)
            from information_schema.triggers
            where trigger_schema=database()
              and trigger_name like 'trg_ai_assistance_%_v49'
            """, Integer.class);
        String tombstoneHashType = jdbc.queryForObject("""
            select column_type
            from information_schema.columns
            where table_schema=database()
              and table_name='ap_ai_approval_assistance_evidence_event'
              and column_name='tombstone_hash'
            """, String.class);

        assertEquals(0, rawColumns);
        assertEquals(9, triggers);
        assertEquals("char(64)", tombstoneHashType.toLowerCase());
    }

    private static Fixture fixture(String key) {
        return ApprovalAssistanceDurableEvidenceJdbcTestFixture.fixture(
            key,
            "1000.00",
            "Bounded H8 summary"
        );
    }

    private static String hash(char value) {
        return ApprovalAssistanceDurableEvidenceJdbcTestFixture.hash(value);
    }

    private static void assertCounts(
        Fixture fixture,
        int evidence,
        int state,
        int events
    ) {
        assertEquals(evidence, count(fixture, "ap_ai_approval_assistance_evidence"));
        assertEquals(state, count(fixture, "ap_ai_approval_assistance_evidence_state"));
        assertEquals(events, count(fixture, "ap_ai_approval_assistance_evidence_event"));
    }

    private static int count(Fixture fixture, String table) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id=? and evidence_id=?",
            Integer.class,
            fixture.tenantId(),
            fixture.evidence().evidenceId().toString()
        );
    }

    private static <T> List<T> concurrently(Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static UUID nextEventId() {
        return new UUID(0x5000000000000000L, EVENT_SEQUENCE.incrementAndGet());
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

    private static final class RollbackMarker extends RuntimeException {
    }
}
