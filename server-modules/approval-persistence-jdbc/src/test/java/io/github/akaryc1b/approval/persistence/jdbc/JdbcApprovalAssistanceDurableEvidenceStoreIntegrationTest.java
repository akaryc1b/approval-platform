package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.AiAdvisoryExecutionEvidence;
import io.github.akaryc1b.approval.ai.core.AiAuthorizedResource;
import io.github.akaryc1b.approval.ai.core.AiCoordinatedAdvisoryOutcome;
import io.github.akaryc1b.approval.ai.core.AiInvocationBudget;
import io.github.akaryc1b.approval.ai.core.AiProviderCircuitBreaker;
import io.github.akaryc1b.approval.ai.core.AiProviderRoute;
import io.github.akaryc1b.approval.ai.core.AiServerRequestContext;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.ProjectionProvenance;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Request;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Result;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.ResultLimits;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.FormSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProjectionEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProviderRequirements;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.DeleteReason;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.EvidenceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.StoreDisposition;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.TombstoneCommand;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.TombstoneDisposition;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.InvocationMode;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.Outcome;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalAssistanceDurableEvidenceStoreIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-08-01T08:00:00Z");
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static final PolicyVersion POLICY = new PolicyVersion(
        "approval-assistance",
        "v1",
        "policy-hash-v1"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_ai_assistance_evidence_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcApprovalAssistanceDurableEvidenceStore store;

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
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcApprovalAssistanceDurableEvidenceStore(
            dataSource,
            new DataSourceTransactionManager(dataSource),
            JdbcApprovalAssistanceDurableEvidenceStoreIntegrationTest::nextEventId
        );
    }

    @Test
    void storesAndReplaysExactEvidenceAsOneActiveRevision() {
        Fixture fixture = fixture("store-replay", "1000.00", "Bounded summary");

        var stored = store.store(fixture.evidence());
        var replayed = store.store(fixture.evidence());
        var snapshot = store.find(fixture.tenantId(), fixture.evidence().evidenceId())
            .orElseThrow();

        assertEquals(StoreDisposition.STORED, stored.disposition());
        assertEquals(StoreDisposition.REPLAYED, replayed.disposition());
        assertEquals(stored.eventHash(), replayed.eventHash());
        assertEquals(1, snapshot.revision());
        assertEquals(EvidenceState.ACTIVE, snapshot.state());
        assertEquals(fixture.evidence(), snapshot.evidence());
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void sameRequestWithDifferentEvidenceIdentityConflictsWithoutPartialWrites() {
        String tenant = "tenant-p4-request-conflict";
        String requestId = "request-shared";
        Fixture first = fixture(
            tenant,
            uuid("request-conflict-first"),
            requestId,
            "1000.00",
            "Bounded summary"
        );
        Fixture second = fixture(
            tenant,
            uuid("request-conflict-second"),
            requestId,
            "1000.00",
            "Bounded summary"
        );

        assertEquals(StoreDisposition.STORED, store.store(first.evidence()).disposition());
        var conflict = store.store(second.evidence());

        assertEquals(StoreDisposition.CONFLICT, conflict.disposition());
        assertEquals(first.evidence().evidenceId(), conflict.evidenceId());
        assertCounts(first, 1, 1, 1);
        assertEquals(0, count(second, "ap_ai_approval_assistance_evidence"));
    }

    @Test
    void sameEvidenceIdentityWithDifferentContentConflicts() {
        String tenant = "tenant-p4-identity-conflict";
        UUID evidenceId = uuid("identity-conflict");
        Fixture first = fixture(
            tenant,
            evidenceId,
            "request-first",
            "1000.00",
            "First summary"
        );
        Fixture second = fixture(
            tenant,
            evidenceId,
            "request-second",
            "2000.00",
            "Second summary"
        );

        assertEquals(StoreDisposition.STORED, store.store(first.evidence()).disposition());
        assertEquals(StoreDisposition.CONFLICT, store.store(second.evidence()).disposition());
        assertCounts(first, 1, 1, 1);
    }

    @Test
    void sameEvidenceIdentityIsIsolatedByTenant() {
        UUID sharedId = uuid("tenant-isolation-shared-id");
        Fixture first = fixture(
            "tenant-p4-isolation-a",
            sharedId,
            "request-a",
            "1000.00",
            "Summary A"
        );
        Fixture second = fixture(
            "tenant-p4-isolation-b",
            sharedId,
            "request-b",
            "2000.00",
            "Summary B"
        );

        assertEquals(StoreDisposition.STORED, store.store(first.evidence()).disposition());
        assertEquals(StoreDisposition.STORED, store.store(second.evidence()).disposition());
        assertTrue(store.find(first.tenantId(), sharedId).isPresent());
        assertTrue(store.find(second.tenantId(), sharedId).isPresent());
        assertFalse(store.find("tenant-p4-isolation-missing", sharedId).isPresent());
    }

    @Test
    void retentionExpiredReasonIsBlockedBeforeRetentionWithoutCreatingEvent() {
        Fixture fixture = fixture("retention-blocked", "1000.00", "Bounded summary");
        store.store(fixture.evidence());
        TombstoneCommand command = tombstone(
            fixture,
            DeleteReason.RETENTION_EXPIRED,
            fixture.evidence().recordedAt().plusSeconds(10),
            hash('a')
        );

        var result = store.tombstone(command);

        assertEquals(TombstoneDisposition.RETENTION_BLOCKED, result.disposition());
        assertEquals(EvidenceState.ACTIVE, result.state());
        assertEquals(1, result.revision());
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void permittedEarlyTombstoneIsDurableAndExactlyReplayable() {
        Fixture fixture = fixture("tombstone-replay", "1000.00", "Bounded summary");
        store.store(fixture.evidence());
        TombstoneCommand command = tombstone(
            fixture,
            DeleteReason.DATA_SUBJECT_REQUEST,
            fixture.evidence().recordedAt().plusSeconds(10),
            hash('b')
        );

        var tombstoned = store.tombstone(command);
        var replayed = store.tombstone(command);
        var snapshot = store.find(fixture.tenantId(), fixture.evidence().evidenceId())
            .orElseThrow();

        assertEquals(TombstoneDisposition.TOMBSTONED, tombstoned.disposition());
        assertEquals(TombstoneDisposition.REPLAYED, replayed.disposition());
        assertEquals(tombstoned.eventHash(), replayed.eventHash());
        assertEquals(EvidenceState.TOMBSTONED, snapshot.state());
        assertEquals(2, snapshot.revision());
        assertEquals(DeleteReason.DATA_SUBJECT_REQUEST, snapshot.deleteReason());
        assertEquals(command.deletionRequestHash(), snapshot.deletionRequestHash());
        assertNotNull(snapshot.tombstoneHash());
        assertCounts(fixture, 1, 1, 2);
    }

    @Test
    void staleTombstoneRevisionIsRejectedWithoutEvent() {
        Fixture fixture = fixture("revision-conflict", "1000.00", "Bounded summary");
        store.store(fixture.evidence());
        TombstoneCommand command = new TombstoneCommand(
            fixture.tenantId(),
            fixture.evidence().evidenceId(),
            2,
            DeleteReason.TENANT_POLICY,
            fixture.evidence().recordedAt().plusSeconds(10),
            hash('c')
        );

        var result = store.tombstone(command);

        assertEquals(TombstoneDisposition.REVISION_CONFLICT, result.disposition());
        assertEquals(EvidenceState.ACTIVE, result.state());
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void concurrentExactStoreProducesOneStoreAndOneReplay() throws Exception {
        Fixture fixture = fixture("concurrent-store", "1000.00", "Bounded summary");

        List<StoreDisposition> dispositions = concurrently(
            () -> store.store(fixture.evidence()).disposition()
        );

        assertEquals(
            List.of(StoreDisposition.REPLAYED, StoreDisposition.STORED),
            dispositions.stream().sorted(Comparator.comparing(Enum::name)).toList()
        );
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void concurrentExactTombstoneProducesOneTransitionAndOneReplay() throws Exception {
        Fixture fixture = fixture("concurrent-tombstone", "1000.00", "Bounded summary");
        store.store(fixture.evidence());
        TombstoneCommand command = tombstone(
            fixture,
            DeleteReason.SECURITY_INCIDENT,
            fixture.evidence().recordedAt().plusSeconds(10),
            hash('d')
        );

        List<TombstoneDisposition> dispositions = concurrently(
            () -> store.tombstone(command).disposition()
        );

        assertEquals(
            List.of(TombstoneDisposition.REPLAYED, TombstoneDisposition.TOMBSTONED),
            dispositions.stream().sorted(Comparator.comparing(Enum::name)).toList()
        );
        assertCounts(fixture, 1, 1, 2);
    }

    @Test
    void evidenceEventsAndStateRejectPhysicalMutationOrDeletion() {
        Fixture fixture = fixture("immutability", "1000.00", "Bounded summary");
        store.store(fixture.evidence());
        Object[] key = key(fixture);

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_ai_approval_assistance_evidence
            set classification='UNKNOWN'
            where tenant_id=? and evidence_id=?
            """, key));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            delete from ap_ai_approval_assistance_evidence
            where tenant_id=? and evidence_id=?
            """, key));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_ai_approval_assistance_evidence_event
            set happened_at=happened_at+interval '1 second'
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
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void stateWithoutMatchingEventIsRejectedAtCommit() {
        Fixture fixture = fixture("orphan-state", "1000.00", "Bounded summary");
        store.store(fixture.evidence());
        Instant tombstonedAt = fixture.evidence().recordedAt().plusSeconds(10);

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_ai_approval_assistance_evidence_state
            set revision=2,state='TOMBSTONED',delete_reason='TENANT_POLICY',
                tombstoned_at=?,deletion_request_hash=?,tombstone_hash=?,
                current_event_hash=?,updated_at=?
            where tenant_id=? and evidence_id=?
            """,
            Timestamp.from(tombstonedAt),
            hash('a'), hash('b'), hash('c'), Timestamp.from(tombstonedAt),
            fixture.tenantId(), fixture.evidence().evidenceId()
        ));
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void eventWithoutMatchingStateIsRejectedAtCommit() {
        Fixture fixture = fixture("orphan-event", "1000.00", "Bounded summary");
        store.store(fixture.evidence());
        Instant happenedAt = fixture.evidence().recordedAt().plusSeconds(10);
        String predecessor = currentEventHash(fixture);

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash
            ) values (?,?,?,2,'TOMBSTONED',?,?,?,'TENANT_POLICY',?)
            """,
            fixture.tenantId(), nextEventId(), fixture.evidence().evidenceId(),
            predecessor, hash('d'), Timestamp.from(happenedAt), hash('e')
        ));
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void wrongPredecessorAndTimeInversionAreRejectedBeforeCommit() {
        Fixture fixture = fixture("invalid-order", "1000.00", "Bounded summary");
        store.store(fixture.evidence());
        Instant after = fixture.evidence().recordedAt().plusSeconds(10);
        Instant before = fixture.evidence().recordedAt().minusSeconds(1);

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash
            ) values (?,?,?,2,'TOMBSTONED',?,?,?,'TENANT_POLICY',?)
            """,
            fixture.tenantId(), nextEventId(), fixture.evidence().evidenceId(),
            hash('f'), hash('a'), Timestamp.from(after), hash('b')
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_ai_approval_assistance_evidence_state
            set revision=2,state='TOMBSTONED',delete_reason='TENANT_POLICY',
                tombstoned_at=?,deletion_request_hash=?,tombstone_hash=?,
                current_event_hash=?,updated_at=?
            where tenant_id=? and evidence_id=?
            """,
            Timestamp.from(before),
            hash('a'), hash('b'), hash('c'), Timestamp.from(before),
            fixture.tenantId(), fixture.evidence().evidenceId()
        ));
        assertCounts(fixture, 1, 1, 1);
    }

    @Test
    void schemaContainsNoRawPayloadTextJsonOrBinaryColumn() {
        List<Column> columns = jdbc.query("""
            select table_name,column_name,data_type
            from information_schema.columns
            where table_schema=current_schema()
              and table_name in (
                'ap_ai_approval_assistance_evidence',
                'ap_ai_approval_assistance_evidence_state',
                'ap_ai_approval_assistance_evidence_event'
              )
            order by table_name,ordinal_position
            """, (row, number) -> new Column(
                row.getString("table_name"),
                row.getString("column_name"),
                row.getString("data_type")
            ));

        assertFalse(columns.isEmpty());
        for (Column column : columns) {
            assertFalse(
                Set.of("text", "json", "jsonb", "bytea").contains(column.dataType()),
                column + " permits a raw payload type"
            );
            assertFalse(
                column.columnName().matches(
                    ".*(?:raw|payload|body|content|summary|observation_text|risk_text|recommendation_text|limitation_text).*"
                ),
                column + " exposes raw advisory content"
            );
        }
    }

    private static Fixture fixture(String key, String amount, String summary) {
        return fixture(
            "tenant-p4-" + key,
            uuid("evidence-" + key),
            "request-" + key,
            amount,
            summary
        );
    }

    private static Fixture fixture(
        String tenantId,
        UUID evidenceId,
        String requestId,
        String amount,
        String summary
    ) {
        Instant requestedAt = BASE.plusSeconds(Math.abs(requestId.hashCode() % 10_000L));
        AiVersionReferences versions = versions();
        ApprovalAssistanceContextProjection projection = projection(
            tenantId,
            requestId,
            amount,
            requestedAt
        );
        Request request = new Request(
            projection,
            UseCase.SUMMARY,
            versions,
            ResultLimits.conservativeDefaults(),
            ProjectionProvenance.from(projection),
            requestedAt
        );
        AiAdvisoryResult advisory = advisory(versions, summary);
        Result accepted = new Result(request, advisory);
        AiProviderRoute route = new AiProviderRoute(
            "route-p4",
            0,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofMillis(100), 4_000, 8, 0.5d)
        );
        AiCoordinatedAdvisoryOutcome coordinated = new AiCoordinatedAdvisoryOutcome(
            route,
            AiProviderOutcome.success(advisory),
            AiUsageEvidence.platformObserved(100, 5),
            0,
            true,
            false,
            AiProviderCircuitBreaker.State.CLOSED,
            AiProviderCircuitBreaker.State.CLOSED
        );
        Outcome outcome = new Outcome(
            request,
            coordinated,
            accepted,
            InvocationMode.DETERMINISTIC_TEST_ONLY,
            1,
            false,
            1
        );
        AiAdvisoryExecutionEvidence execution = AiAdvisoryExecutionEvidence.create(
            projection.requestContext(),
            projection.authorizedResource(),
            request.useCase().capability(),
            coordinated
        );
        Instant recordedAt = requestedAt.plusSeconds(1);
        ApprovalAssistanceDurableEvidence evidence = ApprovalAssistanceDurableEvidence.create(
            evidenceId,
            outcome,
            execution,
            recordedAt,
            recordedAt.plus(Duration.ofDays(30))
        );
        return new Fixture(tenantId, evidence);
    }

    private static ApprovalAssistanceContextProjection projection(
        String tenantId,
        String requestId,
        String amount,
        Instant requestedAt
    ) {
        return new ApprovalAssistanceContextProjection(
            new AiServerRequestContext(
                tenantId,
                "operator-p4",
                requestId,
                "trace-" + requestId
            ),
            new AiAuthorizedResource(
                tenantId,
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                "task-" + requestId,
                "authz-" + requestId,
                Set.of("amount")
            ),
            new ProcessSnapshot(
                "purchase-payment",
                2,
                "compiler-1.1.0",
                "definition-hash-v2",
                "purchase-form",
                3,
                5,
                "release-package-hash-v5"
            ),
            new ResourceStateSnapshot(
                tenantId,
                "instance-" + requestId,
                "task-" + requestId,
                "managerApproval",
                ResourceState.TASK_PENDING,
                7,
                requestedAt.minusSeconds(1)
            ),
            new FormSnapshot(
                "purchase-form",
                3,
                "1.0",
                "form-hash-v3",
                1,
                2,
                "ui-hash-v2",
                "managerApproval",
                4
            ),
            List.of(new AiProviderRequest.InputField(
                "amount",
                "NUMBER",
                amount,
                AiProviderRequest.MaskingDisposition.INCLUDED
            )),
            new ProviderRequirements(
                Set.of(AiCapability.APPROVAL_SUMMARY),
                8,
                1_000,
                4_000,
                8,
                3,
                true,
                true
            ),
            POLICY,
            new ProjectionEvidence(1, 1, 0, 0, 0, false)
        );
    }

    private static AiVersionReferences versions() {
        return new AiVersionReferences(
            new ProviderVersion("provider-a", "v1"),
            new ModelVersion("provider-a", "model-a", "v1"),
            new PromptTemplateVersion(
                "approval-summary",
                "v1",
                "prompt-hash-v1"
            ),
            KnowledgeSourceVersion.none(),
            POLICY,
            new OutputSchemaVersion("approval-assistance", 1)
        );
    }

    private static AiAdvisoryResult advisory(
        AiVersionReferences versions,
        String summary
    ) {
        return new AiAdvisoryResult(
            summary,
            List.of(new AiAdvisoryResult.Observation(
                "observation-1",
                "Authorized amount is present",
                List.of("evidence-1")
            )),
            List.of(),
            List.of(),
            List.of(new AiAdvisoryResult.Recommendation(
                "recommendation-1",
                AiAdvisoryResult.RecommendationType.VERIFY_EVIDENCE,
                "Verify the amount evidence",
                List.of("evidence-1")
            )),
            List.of(new AiAdvisoryResult.EvidenceReference(
                "evidence-1",
                "amount",
                "Authorized amount"
            )),
            new AiAdvisoryResult.Confidence(
                0.90d,
                AiAdvisoryResult.ConfidenceBand.HIGH
            ),
            List.of("Human review is required"),
            true,
            versions,
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
    }

    private static TombstoneCommand tombstone(
        Fixture fixture,
        DeleteReason reason,
        Instant requestedAt,
        String deletionRequestHash
    ) {
        return new TombstoneCommand(
            fixture.tenantId(),
            fixture.evidence().evidenceId(),
            1,
            reason,
            requestedAt,
            deletionRequestHash
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
            fixture.evidence().evidenceId()
        );
    }

    private static String currentEventHash(Fixture fixture) {
        return jdbc.queryForObject("""
            select current_event_hash
            from ap_ai_approval_assistance_evidence_state
            where tenant_id=? and evidence_id=?
            """, String.class, fixture.tenantId(), fixture.evidence().evidenceId()).trim();
    }

    private static Object[] key(Fixture fixture) {
        return new Object[] {fixture.tenantId(), fixture.evidence().evidenceId()};
    }

    private static UUID nextEventId() {
        return new UUID(0x4000000000000000L, EVENT_SEQUENCE.incrementAndGet());
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private record Fixture(String tenantId, ApprovalAssistanceDurableEvidence evidence) {
    }

    private record Column(String tableName, String columnName, String dataType) {
    }
}
