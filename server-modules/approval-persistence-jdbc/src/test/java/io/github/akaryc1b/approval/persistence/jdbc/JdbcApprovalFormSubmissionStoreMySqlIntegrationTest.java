package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.FormSubmissionHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore.FormSubmission;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore.FormSubmissionRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalFormSubmissionStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final UUID SUBMISSION_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008601"
    );
    private static final UUID OTHER_SUBMISSION_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008602"
    );
    private static final UUID REVISION_ID_1 = UUID.fromString(
        "00000000-0000-0000-0000-000000008611"
    );
    private static final UUID REVISION_ID_2 = UUID.fromString(
        "00000000-0000-0000-0000-000000008612"
    );
    private static final Instant SUBMITTED_AT = Instant.parse(
        "2026-08-10T05:06:07.999999500Z"
    );
    private static final Instant REVISION_AT = Instant.parse(
        "2026-08-10T05:07:08.123456500Z"
    );
    private static final String BUSINESS_KEY = "F5-SUBMISSION-001";

    private ObjectMapper objectMapper;
    private ApprovalFormSubmissionStore submissions;
    private FormSubmissionHasher hasher;

    @BeforeEach
    @Override
    void reset() {
        jdbc.update("delete from ap_form_submission_revision");
        jdbc.update("delete from ap_form_submission");
        super.reset();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        submissions = JdbcApprovalFormSubmissionStoreFactory.create(
            dataSource,
            objectMapper
        );
        hasher = new FormSubmissionHasher(objectMapper);
        seedInstanceWithTasks(instance(
            TENANT,
            INSTANCE_ID,
            "engine-f5-submission",
            BUSINESS_KEY
        ), List.of());
        seedInstanceWithTasks(instance(
            OTHER_TENANT,
            OTHER_INSTANCE_ID,
            "engine-f5-submission-other",
            BUSINESS_KEY
        ), List.of());
    }

    @Test
    void roundTripsImmutableSubmissionHashJsonTenantAndCanonicalTime() {
        assertInstanceOf(JdbcMySqlApprovalFormSubmissionStore.class, submissions);
        FormSubmission original = submission(
            TENANT,
            SUBMISSION_ID,
            INSTANCE_ID,
            BUSINESS_KEY
        );
        FormSubmission otherTenant = submission(
            OTHER_TENANT,
            OTHER_SUBMISSION_ID,
            OTHER_INSTANCE_ID,
            BUSINESS_KEY
        );

        submissions.save(original);
        submissions.save(otherTenant);

        FormSubmission restored = submissions.findByInstance(
            TENANT,
            INSTANCE_ID
        ).orElseThrow();
        assertEquals(
            restored,
            submissions.findByBusinessKey(TENANT, BUSINESS_KEY).orElseThrow()
        );
        assertEquals(SUBMISSION_ID, restored.submissionId());
        assertEquals(INSTANCE_ID, restored.instanceId());
        assertEquals("供应商-F5", restored.values().get("supplier"));
        assertEquals(
            List.of("finance", "审批"),
            restored.startParameters().get("roles")
        );
        assertEquals(
            0,
            new BigDecimal(String.valueOf(restored.values().get("amount")))
                .compareTo(new BigDecimal("100.00"))
        );
        assertEquals(
            original.requestHash(),
            hasher.hash(
                restored.formKey(),
                restored.formVersion(),
                restored.schemaHash(),
                restored.businessKey(),
                restored.values(),
                restored.startParameters()
            )
        );
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(SUBMITTED_AT),
            restored.submittedAt()
        );
        assertEquals(0, jdbc.queryForObject(
            """
            select microsecond(submitted_at)
            from ap_form_submission
            where tenant_id = ? and instance_id = ?
            """,
            Integer.class,
            TENANT,
            INSTANCE_ID.toString()
        ));

        assertTrue(submissions.findByBusinessKey(OTHER_TENANT, BUSINESS_KEY).isPresent());
        assertFalse(submissions.findByInstance(TENANT.toLowerCase(), INSTANCE_ID).isPresent());
        assertFalse(submissions.findByBusinessKey(TENANT.toLowerCase(), BUSINESS_KEY).isPresent());
        assertThrows(DataAccessException.class, () -> submissions.save(original));
    }

    @Test
    void revisionsAreStrictAppendOnlyLatestTenantScopedAndCanonical() {
        submissions.save(submission(TENANT, SUBMISSION_ID, INSTANCE_ID, BUSINESS_KEY));
        FormSubmissionRevision first = revision(REVISION_ID_1, 1, "supplier-v1");
        FormSubmissionRevision second = revision(REVISION_ID_2, 2, "supplier-v2");

        submissions.saveRevision(first);
        submissions.saveRevision(second);

        FormSubmissionRevision latest = submissions.findLatestRevision(
            TENANT,
            INSTANCE_ID
        ).orElseThrow();
        assertEquals(2, latest.revisionNumber());
        assertEquals("supplier-v2", latest.values().get("supplier"));
        assertEquals(
            AuditHashCanonicalizer.canonicalInstant(REVISION_AT.plusSeconds(2)),
            latest.modifiedAt()
        );
        assertEquals(2, count(
            "select count(*) from ap_form_submission_revision "
                + "where tenant_id = ? and instance_id = ?",
            TENANT,
            INSTANCE_ID.toString()
        ));
        assertFalse(submissions.findLatestRevision(
            TENANT.toLowerCase(),
            INSTANCE_ID
        ).isPresent());
        assertThrows(DataAccessException.class, () -> submissions.saveRevision(
            revision(UUID.randomUUID(), 2, "duplicate")
        ));
    }

    @Test
    void instanceLockRequiresTransactionBlocksCompetitorAndReleasesAfterRollback()
        throws Exception {
        assertThrows(
            IllegalStateException.class,
            () -> submissions.lockInstance(TENANT, INSTANCE_ID)
        );

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> transactions.execute(status -> {
                submissions.lockInstance(TENANT, INSTANCE_ID);
                firstLocked.countDown();
                await(releaseFirst);
                throw new RollbackMarker();
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return transactions.execute(status -> {
                    submissions.lockInstance(TENANT, INSTANCE_ID);
                    return Boolean.TRUE;
                });
            });

            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            assertTrue(secondAttempting.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> second.get(500, TimeUnit.MILLISECONDS)
            );
            releaseFirst.countDown();
            ExecutionException rollback = assertThrows(
                ExecutionException.class,
                () -> first.get(20, TimeUnit.SECONDS)
            );
            assertInstanceOf(RollbackMarker.class, rollback.getCause());
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void concurrentNextRevisionHasOneWinnerAndRollbackRemovesRealInsert() throws Exception {
        submissions.save(submission(TENANT, SUBMISSION_ID, INSTANCE_ID, BUSINESS_KEY));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> concurrentRevision(
                UUID.fromString("00000000-0000-0000-0000-000000008621"),
                ready,
                start
            ));
            Future<String> second = executor.submit(() -> concurrentRevision(
                UUID.fromString("00000000-0000-0000-0000-000000008622"),
                ready,
                start
            ));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(
                Set.of("SUCCESS", "CONFLICT"),
                Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
            );
        }

        assertEquals(1, submissions.findLatestRevision(TENANT, INSTANCE_ID)
            .orElseThrow().revisionNumber());
        assertEquals(1, count(
            "select count(*) from ap_form_submission_revision "
                + "where tenant_id = ? and instance_id = ?",
            TENANT,
            INSTANCE_ID.toString()
        ));

        assertThrows(RollbackMarker.class, () -> transactions.executeWithoutResult(status -> {
            submissions.lockInstance(TENANT, INSTANCE_ID);
            submissions.saveRevision(revision(
                UUID.fromString("00000000-0000-0000-0000-000000008623"),
                2,
                "rolled-back"
            ));
            throw new RollbackMarker();
        }));
        assertEquals(1, submissions.findLatestRevision(TENANT, INSTANCE_ID)
            .orElseThrow().revisionNumber());

        transactions.executeWithoutResult(status -> {
            submissions.lockInstance(TENANT, INSTANCE_ID);
            submissions.saveRevision(revision(
                UUID.fromString("00000000-0000-0000-0000-000000008624"),
                2,
                "committed-after-rollback"
            ));
        });
        assertEquals(2, submissions.findLatestRevision(TENANT, INSTANCE_ID)
            .orElseThrow().revisionNumber());
    }

    @Test
    void malformedAndExtendedSubmissionAndRevisionEnvelopesFailClosed() {
        submissions.save(submission(TENANT, SUBMISSION_ID, INSTANCE_ID, BUSINESS_KEY));
        jdbc.update(
            """
            update ap_form_submission
            set values_json = cast(? as json)
            where tenant_id = ? and instance_id = ?
            """,
            "{\"encoding\":\"CANONICAL_FORM_SUBMISSION_JSON_TEXT_V1\","
                + "\"payload\":\"{}\",\"extra\":true}",
            TENANT,
            INSTANCE_ID.toString()
        );
        assertThrows(
            DataAccessException.class,
            () -> submissions.findByInstance(TENANT, INSTANCE_ID)
        );

        jdbc.update(
            """
            update ap_form_submission
            set values_json = start_parameters_json
            where tenant_id = ? and instance_id = ?
            """,
            TENANT,
            INSTANCE_ID.toString()
        );
        submissions.saveRevision(revision(REVISION_ID_1, 1, "supplier-v1"));
        jdbc.update(
            """
            update ap_form_submission_revision
            set values_json = cast(? as json)
            where tenant_id = ? and instance_id = ? and revision_number = 1
            """,
            "{\"encoding\":\"UNKNOWN\",\"payload\":\"{}\"}",
            TENANT,
            INSTANCE_ID.toString()
        );
        assertThrows(
            DataAccessException.class,
            () -> submissions.findLatestRevision(TENANT, INSTANCE_ID)
        );
    }

    private String concurrentRevision(
        UUID revisionId,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        return transactions.execute(status -> {
            submissions.lockInstance(TENANT, INSTANCE_ID);
            int current = submissions.findLatestRevision(TENANT, INSTANCE_ID)
                .map(FormSubmissionRevision::revisionNumber)
                .orElse(0);
            if (current + 1 != 1) {
                return "CONFLICT";
            }
            submissions.saveRevision(revision(revisionId, 1, "concurrent-winner"));
            return "SUCCESS";
        });
    }

    private FormSubmission submission(
        String tenantId,
        UUID submissionId,
        UUID instanceId,
        String businessKey
    ) {
        Map<String, Object> values = Map.of(
            "amount", new BigDecimal("100.00"),
            "supplier", "供应商-F5",
            "purchaseOrderReference", "PO-F5-001",
            "attachments", List.of(),
            "nested", Map.of("approved", true, "sequence", 7)
        );
        Map<String, Object> startParameters = Map.of(
            "connectorKey", "DingTalk-A",
            "roles", List.of("finance", "审批"),
            "retry", 1
        );
        String requestHash = hasher.hash(
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.FORM_VERSION,
            MySqlApprovalProjectionProvenanceFixture.FORM_HASH,
            businessKey,
            values,
            startParameters
        );
        return new FormSubmission(
            submissionId,
            tenantId,
            DEFINITION_KEY,
            MySqlApprovalProjectionProvenanceFixture.FORM_VERSION,
            MySqlApprovalProjectionProvenanceFixture.FORM_HASH,
            MySqlApprovalProjectionProvenanceFixture.UI_SCHEMA_VERSION,
            MySqlApprovalProjectionProvenanceFixture.UI_SCHEMA_HASH,
            businessKey,
            values,
            startParameters,
            instanceId,
            "Initiator-A",
            SUBMITTED_AT,
            requestHash
        );
    }

    private FormSubmissionRevision revision(
        UUID revisionId,
        int number,
        String supplier
    ) {
        return new FormSubmissionRevision(
            revisionId,
            TENANT,
            INSTANCE_ID,
            number,
            Map.of(
                "amount", new BigDecimal("100.00"),
                "supplier", supplier,
                "purchaseOrderReference", "PO-F5-001",
                "attachments", List.of(),
                "nested", Map.of("revision", number, "unicode", "审批")
            ),
            "Manager-A",
            REVISION_AT.plusSeconds(number),
            Integer.toString(number).repeat(64).substring(0, 64)
        );
    }
}
