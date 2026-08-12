package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationExactVerificationStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );
    private static final Path JDBC_TEST_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/test/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlVerificationUsesPortableValuesStrictReplayAndExactAttemptCas()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalMigrationExactVerificationStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcDatabaseValueAdapter"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant"));
        assertTrue(store.contains("JdbcMySqlApprovalInstanceCommandFence"));
        assertTrue(store.contains("acquireMigrationLock("));
        assertTrue(store.contains("requireLineageFence("));
        assertTrue(store.contains("request_source_definition_id"));
        assertTrue(store.contains("request_target_definition_id"));
        assertTrue(store.contains("attempt.sourceEngineDefinitionId().equals("));
        assertTrue(store.contains("attempt.targetEngineDefinitionId().equals("));
        assertTrue(store.contains(
            "!lineage.engineRequestId().equals(prepared.engineRequestId())"
        ));
        assertTrue(store.contains(
            "!lineage.engineOutcomeId().equals(prepared.engineOutcomeId())"
        ));
        assertTrue(store.contains(
            "insert into ap_process_migration_exact_verification ("
        ));
        assertTrue(store.contains(
            "insert into ap_process_migration_attempt_event ("
        ));
        assertTrue(store.contains("revision=:expectedRevision"));
        assertTrue(store.contains("status=:expectedStatus"));
        assertTrue(store.contains("m5-exact-verification-request-v1"));
        assertTrue(store.contains("m5-exact-verification-evidence-v1"));
        assertTrue(store.contains("requireExactReplay("));
        assertTrue(store.contains("ApprovalMigrationExactVerification.classify("));
        assertTrue(store.contains("CALL_RETURNED_AWAITING_VERIFICATION"));
        assertTrue(store.contains("EXACT_TARGET_RUNTIME"));
        assertTrue(store.contains("AttemptStatus.RECONCILING"));
        assertTrue(store.contains("EngineOutcome.VERIFICATION_MISMATCH"));
        assertTrue(store.contains("FailureClass.RECONCILIATION_REQUIRED"));
        assertTrue(store.contains(
            "migration attempt relational and payload evidence diverged"
        ));
        assertTrue(store.contains(
            "exact verification relational and payload evidence diverged"
        ));
        assertFalse(lower.contains("act_"));
        assertFalse(lower.contains("jdbcapprovalmigrationreconciliationexecutionstore"));

        for (String forbidden : List.of(
            "::text",
            "::jsonb",
            " as jsonb",
            "cast(:payload as jsonb)",
            "for update of ",
            "pg_advisory",
            "foreign_key_checks",
            "insert ignore",
            "replace into",
            "on duplicate key update",
            "on conflict (",
            "on conflict do ",
            "on conflict on constraint "
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL H5 verification store contains forbidden SQL: " + forbidden
            );
        }
    }

    @Test
    void immutableH4RequestAndOutcomeAreVerifiedAsCompleteEvidence()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalMigrationExactVerificationStore.java")
        );

        for (String required : List.of(
            "request_approval_instance_id",
            "request_attempt_revision",
            "request_engine_instance_id",
            "request_source_binding_evidence_hash",
            "request_target_release_version",
            "request_target_package_hash",
            "request_target_engine_deployment_id",
            "request_activity_mapping_json",
            "request_hash",
            "request_evidence_hash",
            "request_requested_at",
            "request_request_id",
            "request_trace_id",
            "engine_call_may_have_occurred",
            "stable_code",
            "bounded_summary",
            "pre_dispatch_snapshot_hash",
            "outcome_hash",
            "outcome_recorded_at",
            "outcome_request_id",
            "outcome_trace_id",
            "m5-engine-request-v1",
            "m5-engine-request-evidence-v1",
            "m5-engine-outcome-v1",
            "requireExactRequestPayload(",
            "requireExactOutcomePayload("
        )) {
            assertTrue(
                store.contains(required),
                () -> "H5 does not verify complete H4 evidence field: " + required
            );
        }
    }

    @Test
    void realH2ThroughH5TestsRetainPredecessorDriftGuardsWithoutFakeAttempts()
        throws IOException {
        String h4 = Files.readString(JDBC_TEST_ROOT.resolve(
            "JdbcApprovalMigrationEngineExecutionStoreMySqlIntegrationTest.java"
        ));
        String h5 = Files.readString(JDBC_TEST_ROOT.resolve(
            "JdbcApprovalMigrationExactVerificationStoreMySqlIntegrationTest.java"
        ));
        String h5Lower = h5.toLowerCase(Locale.ROOT);

        assertTrue(h4.contains(
            "staleTenantAttemptFenceRuntimeBindingAndTargetAuthorityFailClosed"
        ));
        assertTrue(h4.contains(
            "update ap_process_runtime_binding set binding_evidence_hash=?"
        ));
        assertTrue(h4.contains(
            "update ap_process_migration_plan set target_engine_definition_id=?"
        ));
        assertTrue(h5.contains("JdbcApprovalMigrationAttemptProvisioningStoreFactory.create("));
        assertTrue(h5.contains("JdbcApprovalMigrationAttemptClaimStoreFactory.create("));
        assertTrue(h5.contains("JdbcApprovalMigrationEngineExecutionStoreFactory.create("));
        assertTrue(h5.contains(
            "FinalDisposition.CALL_RETURNED_AWAITING_VERIFICATION"
        ));
        assertTrue(h5.contains("JdbcApprovalMigrationExactVerificationStoreFactory.create("));
        assertFalse(h5Lower.contains("insert into ap_process_migration_attempt"));
        assertFalse(h5Lower.contains("session_replication_role"));
        assertFalse(h5.contains("Thread.sleep"));
    }

    @Test
    void trustedFactoryOwnsServerVerificationVendorSelection() throws IOException {
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalMigrationExecutionConfiguration.java"
        ));
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalMigrationExactVerificationStoreFactory.java")
        );

        assertTrue(configuration.contains(
            "JdbcApprovalMigrationExactVerificationStoreFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalMigrationExactVerificationStore("
        ));
        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalMigrationExactVerificationStore("
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalMigrationExactVerificationStore("
        ));
        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));
    }

    @Test
    void applicationVerificationBoundaryAndH5ScopeRemainVendorNeutral()
        throws IOException {
        for (String relative : List.of(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/port/ApprovalMigrationExactVerificationStore.java",
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalMigrationExactVerificationService.java"
        )) {
            String source = Files.readString(ROOT.resolve(relative))
                .toLowerCase(Locale.ROOT);
            assertFalse(source.contains("mysql"));
            assertFalse(source.contains("postgresql"));
        }

        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_H5_MIGRATION_EXACT_VERIFICATION_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);
        for (String required : List.of(
            "MYSQL_P3_H5_MIGRATION_EXACT_VERIFICATION_STAGED",
            "APPROVALMIGRATIONEXACTVERIFICATIONSTORE",
            "EXACT_TARGET_RUNTIME",
            "TARGET_HISTORY_TERMINAL",
            "MIXED_SOURCE_TARGET_EVIDENCE",
            "VERIFYING",
            "RECONCILING",
            "VERIFICATION_MISMATCH",
            "MYSQL_8_4_PRODUCTION_SUPPORTED"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-H5 contract missing required marker: " + required
            );
        }
        assertFalse(hasStandaloneStatusLine(
            contract,
            "MYSQL_8_4_PRODUCTION_SUPPORTED"
        ));
    }

    private static boolean hasStandaloneStatusLine(String text, String marker) {
        return text.lines().map(String::trim).anyMatch(marker::equals);
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isDirectory(current.resolve("apps/server"))
                && Files.isDirectory(current.resolve("server-modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root could not be resolved");
    }
}
