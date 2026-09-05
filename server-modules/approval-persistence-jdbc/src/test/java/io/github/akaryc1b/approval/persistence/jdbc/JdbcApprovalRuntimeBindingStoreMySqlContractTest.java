package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalRuntimeBindingStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mysqlStoreRetainsStrictImmutableUuidTimeAndReadBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalRuntimeBindingStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("insert into ap_process_runtime_binding"));
        assertTrue(store.contains("values.bindUuid(value.approvalInstanceId())"));
        assertTrue(store.contains("values.uuid(resultSet, \"approval_instance_id\")"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant("));
        assertTrue(store.contains("tenant_id = :tenantId"));
        assertTrue(store.contains("engine_instance_id = :engineInstanceId"));
        assertTrue(store.contains("definition_key = :definitionKey"));
        assertTrue(store.contains("release_version = :releaseVersion"));
        assertTrue(store.contains("order by bound_at desc, approval_instance_id"));

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
                () -> "MySQL Runtime Binding store contains forbidden token: " + forbidden
            );
        }
    }

    @Test
    void factoryAndServerBindingKeepVendorSelectionInPersistenceInfrastructure()
        throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalRuntimeBindingStoreFactory.java")
        );
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalProcessReleaseLifecycleConfiguration.java"
        ));
        String postgreSql = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalRuntimeBindingStore.java")
        );

        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalRuntimeBindingStore(source)"
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalRuntimeBindingStore(source)"
        ));
        assertTrue(configuration.contains(
            "JdbcApprovalRuntimeBindingStoreFactory.create(dataSource)"
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalRuntimeBindingStore(dataSource)"
        ));
        assertTrue(postgreSql.contains(
            "resultSet.getObject(\"approval_instance_id\", UUID.class)"
        ));
        assertTrue(postgreSql.contains("OffsetDateTime.class"));
    }

    @Test
    void applicationWrappersAndPermanentScopeRemainDatabaseNeutralAndHonest()
        throws IOException {
        String enforcing = application("RuntimeBindingEnforcingProjectionStore.java");
        String recording = application("RuntimeBindingRecordingAuditEventSink.java");
        String assessment = application("ApprovalProcessReleaseMigrationAssessmentService.java");
        String disposition = application("ApprovalProcessReleaseDispositionService.java");
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_G3_RUNTIME_BINDING_STORE_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);

        for (String source : List.of(enforcing, recording, assessment, disposition)) {
            String lower = source.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("mysql"));
            assertFalse(lower.contains("postgresql"));
        }

        for (String required : List.of(
            "MYSQL_P3_G3_RUNTIME_BINDING_STORE_",
            "RUNTIMEBINDINGRECORDINGAUDITEVENTSINK",
            "RUNTIMEBINDINGENFORCINGPROJECTIONSTORE",
            "ORDER BY BOUND_AT DESC, APPROVAL_INSTANCE_ID",
            "APPROVALMIGRATIONRUNTIMEBINDINGCASSTORE",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-G3 contract is missing required marker: " + required
            );
        }
        assertFalse(hasStandaloneStatusLine(
            contract,
            "MYSQL_8_4_PRODUCTION_SUPPORTED"
        ));
    }

    private static String application(String file) throws IOException {
        return Files.readString(ROOT.resolve(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/"
                + file
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
