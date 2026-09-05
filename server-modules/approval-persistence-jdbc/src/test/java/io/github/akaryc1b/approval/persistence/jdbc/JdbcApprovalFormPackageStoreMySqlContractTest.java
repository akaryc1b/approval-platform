package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalFormPackageStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mySqlStoreRetainsImmutableTenantTimeAndSerializationBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalFormPackageStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(store.contains("form-package:"));
        assertTrue(store.contains("insert into ap_form_package"));
        assertTrue(store.contains("tenant_id = :tenantId"));
        assertTrue(store.contains("package_version = :packageVersion"));
        assertTrue(store.contains("source_draft_id = :draftId"));
        assertTrue(store.contains("values.bindUuid(exact.sourceDraftId())"));
        assertTrue(store.contains("values.bindUuid(exactDraftId)"));
        assertTrue(store.contains("values.uuid(resultSet, \"source_draft_id\")"));
        assertTrue(store.contains("values.bindInstant(canonicalInstant(exact.publishedAt()))"));
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
                () -> "MySQL Form Package boundary contains forbidden token: " + forbidden
            );
        }
    }

    @Test
    void factoryServerBindingAndPostgreSqlReferenceRemainExplicit() throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalFormPackageStoreFactory.java")
        );
        String postgreSqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalFormPackageStore.java")
        ).toLowerCase(Locale.ROOT);
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalFormConfiguration.java"
        ));
        String service = Files.readString(ROOT.resolve(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalFormDesignService.java"
        ));
        String serviceLower = service.toLowerCase(Locale.ROOT);

        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalFormPackageStore(source)"
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalFormPackageStore(source)"
        ));
        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));

        assertTrue(postgreSqlStore.contains("pg_advisory_xact_lock"));
        assertTrue(postgreSqlStore.contains("insert into ap_form_package"));
        assertTrue(postgreSqlStore.contains("source_draft_id = :draftid"));

        assertTrue(server.contains("JdbcApprovalFormPackageStoreFactory.create(dataSource)"));
        assertFalse(server.contains("new JdbcApprovalFormPackageStore(dataSource)"));
        assertFalse(serviceLower.contains("mysql"));
        assertFalse(serviceLower.contains("postgresql"));
        assertTrue(service.contains("packages.lockVersion("));
        assertTrue(service.contains("packages.find("));
        assertTrue(service.contains("packages.save(formPackage)"));
        assertTrue(service.contains("packages.findByDraft("));
    }

    @Test
    void permanentContractRetainsScopeAndHonestNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_F4_FORM_PACKAGE_STORE_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);

        for (String required : List.of(
            "MYSQL_P3_F4_FORM_PACKAGE_STORE_",
            "FORM-PACKAGE:",
            "FORMPACKAGEHASHER",
            "P3-F5 FORM SUBMISSION STORE",
            "APPROVAL RELEASE LIFECYCLE",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-F4 contract is missing required marker: " + required
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
