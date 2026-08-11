package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalReleaseFoundationMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void packageStoreRetainsImmutableIdentitySerializationAndTimeBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalReleasePackageStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(store.contains("approval-release:"));
        assertTrue(store.contains("insert into ap_approval_release_package"));
        assertTrue(store.contains("source_draft_id = :draftId"));
        assertTrue(store.contains("values.bindUuid(value.sourceDraftId())"));
        assertTrue(store.contains("values.uuid(resultSet, \"source_draft_id\")"));
        assertTrue(store.contains("values.bindInstant(canonicalInstant(value.publishedAt()))"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant("));
        assertTrue(store.contains("order by release_version desc limit 1"));
        assertTrue(store.contains("order by definition_key, release_version desc"));

        assertNoForbiddenMySqlMutation(lower, "Release Package");
    }

    @Test
    void deploymentStoreRetainsIndependentLockCasUuidAndTimeBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalReleaseDeploymentStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(store.contains("approval-release-deployment:"));
        assertTrue(store.contains("insert into ap_approval_release_deployment"));
        assertTrue(store.contains("update ap_approval_release_deployment"));
        assertTrue(store.contains("attempt_count = :expectedAttemptCount"));
        assertTrue(store.contains("values.bindUuid(deployment.deploymentRecordId())"));
        assertTrue(store.contains("values.uuid(resultSet, \"deployment_record_id\")"));
        assertTrue(store.contains("values.bindInstant(canonicalInstant(deployment.createdAt()))"));
        assertTrue(store.contains("values.bindInstant(canonicalInstant(deployment.updatedAt()))"));
        assertTrue(store.contains("values.nullableInstant(resultSet, \"deployed_at\")"));
        assertTrue(store.contains("order by release_version desc"));

        assertNoForbiddenMySqlMutation(lower, "Release Deployment");
    }

    @Test
    void factoriesServerBindingsServicesAndPermanentScopeRemainExplicit()
        throws IOException {
        String packageFactory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalReleasePackageStoreFactory.java")
        );
        String deploymentFactory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalReleaseDeploymentStoreFactory.java")
        );
        String postgreSqlPackage = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalReleasePackageStore.java")
        ).toLowerCase(Locale.ROOT);
        String postgreSqlDeployment = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalReleaseDeploymentStore.java")
        ).toLowerCase(Locale.ROOT);
        String designConfiguration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalDesignConfiguration.java"
        ));
        String deploymentConfiguration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalReleaseDeploymentConfiguration.java"
        ));
        String designService = Files.readString(ROOT.resolve(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalDesignService.java"
        )).toLowerCase(Locale.ROOT);
        String deploymentService = Files.readString(ROOT.resolve(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalReleaseDeploymentService.java"
        )).toLowerCase(Locale.ROOT);
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);

        assertTrue(packageFactory.contains(
            "case POSTGRESQL -> new JdbcApprovalReleasePackageStore(source)"
        ));
        assertTrue(packageFactory.contains(
            "case MYSQL -> new JdbcMySqlApprovalReleasePackageStore(source)"
        ));
        assertTrue(deploymentFactory.contains(
            "case POSTGRESQL -> new JdbcApprovalReleaseDeploymentStore(source)"
        ));
        assertTrue(deploymentFactory.contains(
            "case MYSQL -> new JdbcMySqlApprovalReleaseDeploymentStore(source)"
        ));
        assertTrue(packageFactory.contains("ApprovalDatabaseVendorResolver"));
        assertTrue(deploymentFactory.contains("ApprovalDatabaseVendorResolver"));

        assertTrue(postgreSqlPackage.contains("pg_advisory_xact_lock"));
        assertTrue(postgreSqlDeployment.contains("pg_advisory_xact_lock"));
        assertTrue(designConfiguration.contains(
            "JdbcApprovalReleasePackageStoreFactory.create(dataSource)"
        ));
        assertFalse(designConfiguration.contains(
            "new JdbcApprovalReleasePackageStore(dataSource)"
        ));
        assertTrue(deploymentConfiguration.contains(
            "JdbcApprovalReleaseDeploymentStoreFactory.create(dataSource)"
        ));
        assertFalse(deploymentConfiguration.contains(
            "new JdbcApprovalReleaseDeploymentStore(dataSource)"
        ));
        assertFalse(designService.contains("mysql"));
        assertFalse(designService.contains("postgresql"));
        assertFalse(deploymentService.contains("mysql"));
        assertFalse(deploymentService.contains("postgresql"));

        for (String required : List.of(
            "MYSQL_P3_G1_RELEASE_PACKAGE_DEPLOYMENT_",
            "APPROVAL-RELEASE:",
            "APPROVAL-RELEASE-DEPLOYMENT:",
            "ATTEMPT_COUNT = :EXPECTEDATTEMPTCOUNT",
            "P3-G2 PROCESS RELEASE LIFECYCLE + EFFECTIVE RELEASE",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-G1 contract is missing required marker: " + required
            );
        }
        assertFalse(hasStandaloneStatusLine(
            contract,
            "MYSQL_8_4_PRODUCTION_SUPPORTED"
        ));
    }

    private static void assertNoForbiddenMySqlMutation(String lower, String name) {
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
                () -> name + " MySQL boundary contains forbidden token: " + forbidden
            );
        }
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
