package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalFormSubmissionStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mySqlStoreRetainsStrictJsonTenantTimeAndRevisionSerializationBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalFormSubmissionStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("CANONICAL_FORM_SUBMISSION_JSON_TEXT_V1"));
        assertTrue(store.contains("STRICT_DUPLICATE_DETECTION"));
        assertTrue(store.contains("insert into ap_form_submission ("));
        assertTrue(store.contains("insert into ap_form_submission_revision ("));
        assertTrue(store.contains("tenant_id = :tenantId and instance_id = :instanceId"));
        assertTrue(store.contains("tenant_id = :tenantId and business_key = :businessKey"));
        assertTrue(store.contains("order by revision_number desc"));
        assertTrue(store.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(store.contains("form-revision:"));
        assertTrue(store.contains("values.bindUuid(exact.submissionId())"));
        assertTrue(store.contains("values.bindUuid(exact.instanceId())"));
        assertTrue(store.contains("values.uuid(resultSet, \"submission_id\")"));
        assertTrue(store.contains("values.uuid(resultSet, \"revision_id\")"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant("));

        for (String forbidden : List.of(
            "pg_advisory_lock",
            "pg_advisory_xact_lock",
            "insert ignore",
            "replace into",
            "on duplicate key update",
            "foreign_key_checks"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL Form Submission boundary contains forbidden token: " + forbidden
            );
        }
    }

    @Test
    void factoryServerBindingAndPostgreSqlReferenceRemainExplicit() throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalFormSubmissionStoreFactory.java")
        );
        String postgreSqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalFormSubmissionStore.java")
        ).toLowerCase(Locale.ROOT);
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalFormConfiguration.java"
        ));
        String submissionService = Files.readString(ROOT.resolve(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalFormSubmissionService.java"
        ));
        String runtimeService = Files.readString(ROOT.resolve(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalFormRuntimeService.java"
        ));
        String serviceLower = (submissionService + runtimeService).toLowerCase(Locale.ROOT);

        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalFormSubmissionStore(source, mapper)"
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalFormSubmissionStore(source, mapper)"
        ));
        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));

        assertTrue(postgreSqlStore.contains("pg_advisory_xact_lock"));
        assertTrue(postgreSqlStore.contains("cast(:valuesjson as jsonb)"));
        assertTrue(postgreSqlStore.contains("insert into ap_form_submission_revision"));

        assertTrue(server.contains("JdbcApprovalFormSubmissionStoreFactory.create("));
        assertFalse(server.contains("new JdbcApprovalFormSubmissionStore(dataSource"));
        assertFalse(serviceLower.contains("mysql"));
        assertFalse(serviceLower.contains("postgresql"));
        assertTrue(submissionService.contains("submissions.save(submission)"));
        assertTrue(runtimeService.contains("submissions.lockInstance("));
        assertTrue(runtimeService.contains("submissions.findLatestRevision("));
        assertTrue(runtimeService.contains("submissions.saveRevision("));
    }

    @Test
    void permanentContractRetainsScopeAndHonestNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_F5_FORM_SUBMISSION_STORE_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);

        for (String required : List.of(
            "MYSQL_P3_F5_FORM_SUBMISSION_STORE_",
            "CANONICAL_FORM_SUBMISSION_JSON_TEXT_V1",
            "FORM-REVISION:",
            "FORMSUBMISSIONHASHER",
            "APPROVAL RELEASE LIFECYCLE",
            "FLOWABLE MYSQL EXECUTION",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-F5 contract is missing required marker: " + required
            );
        }
        assertFalse(hasStandaloneStatusLine(
            contract,
            "MYSQL_8_4_PRODUCTION_SUPPORTED"
        ));
    }

    private static boolean hasStandaloneStatusLine(String text, String marker) {
        return text.lines()
            .map(String::trim)
            .anyMatch(marker::equals);
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
