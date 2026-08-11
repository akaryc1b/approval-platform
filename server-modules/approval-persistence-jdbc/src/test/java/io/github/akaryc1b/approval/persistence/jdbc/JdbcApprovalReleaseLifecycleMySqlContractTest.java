package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalReleaseLifecycleMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void lifecycleStoreRetainsLockCasAppendOnlyHistoryUuidAndTimeBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalProcessReleaseStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(store.contains("approval-process-release:"));
        assertTrue(store.contains("insert into ap_process_release_lifecycle"));
        assertTrue(store.contains("insert into ap_process_release_lifecycle_history"));
        assertTrue(store.contains("update ap_process_release_lifecycle"));
        assertTrue(store.contains("revision = :expectedRevision"));
        assertTrue(store.contains("lifecycle_state = 'ACTIVE'"));
        assertTrue(store.contains("values.bindUuid(value.transitionId())"));
        assertTrue(store.contains("values.uuid(resultSet, \"transition_id\")"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant("));
        assertTrue(store.contains("order by revision desc, transition_id desc"));

        assertNoForbiddenMySqlMutation(lower, "Process Release lifecycle");
    }

    @Test
    void effectiveStoreAndDeactivationRetainIndependentLockCasAndHistory()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalEffectiveReleaseStore.java")
        );
        String deactivation = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalEffectiveReleaseDeactivationPort.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);
        String deactivationLower = deactivation.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(store.contains("approval-effective-release:"));
        assertTrue(store.contains("insert into ap_approval_effective_release"));
        assertTrue(store.contains("insert into ap_approval_release_activation_history"));
        assertTrue(store.contains("update ap_approval_effective_release"));
        assertTrue(store.contains("revision = :expectedRevision"));
        assertTrue(store.contains("values.bindUuid(value.activationId())"));
        assertTrue(store.contains("values.uuid(resultSet, \"activation_id\")"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant("));
        assertTrue(store.contains("order by revision desc, activation_id desc"));
        assertTrue(deactivation.contains("delete from ap_approval_effective_release"));
        assertTrue(deactivation.contains("revision = :expectedRevision"));
        assertFalse(deactivationLower.contains("ap_approval_release_activation_history"));

        assertNoForbiddenMySqlMutation(lower, "Effective Release");
        assertNoForbiddenMySqlMutation(deactivationLower, "Effective deactivation");
    }

    @Test
    void factoriesServerBindingsServicesAndPermanentScopeRemainExplicit()
        throws IOException {
        String lifecycleFactory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalProcessReleaseStoreFactory.java")
        );
        String effectiveFactory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalEffectiveReleaseStoreFactory.java")
        );
        String deactivationFactory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalEffectiveReleaseDeactivationPortFactory.java")
        );
        String postgreSqlLifecycle = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalProcessReleaseStore.java")
        ).toLowerCase(Locale.ROOT);
        String postgreSqlEffective = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalEffectiveReleaseStore.java")
        ).toLowerCase(Locale.ROOT);
        String lifecycleConfiguration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalProcessReleaseLifecycleConfiguration.java"
        ));
        String deploymentConfiguration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalReleaseDeploymentConfiguration.java"
        ));
        String activationService = application("ApprovalProcessReleaseActivationService.java");
        String effectiveService = application("ApprovalEffectiveReleaseService.java");
        String dispositionService = application("ApprovalProcessReleaseDispositionService.java");
        String contract = Files.readString(ROOT.resolve(
            "docs/database/"
                + "MYSQL_8_4_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_RELEASE_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);

        assertTrue(lifecycleFactory.contains(
            "case POSTGRESQL -> new JdbcApprovalProcessReleaseStore(source)"
        ));
        assertTrue(lifecycleFactory.contains(
            "case MYSQL -> new JdbcMySqlApprovalProcessReleaseStore(source)"
        ));
        assertTrue(effectiveFactory.contains(
            "case POSTGRESQL -> new JdbcApprovalEffectiveReleaseStore(source)"
        ));
        assertTrue(effectiveFactory.contains(
            "case MYSQL -> new JdbcMySqlApprovalEffectiveReleaseStore(source)"
        ));
        assertTrue(deactivationFactory.contains(
            "case POSTGRESQL -> new JdbcApprovalEffectiveReleaseDeactivationPort(source)"
        ));
        assertTrue(deactivationFactory.contains(
            "case MYSQL -> new JdbcMySqlApprovalEffectiveReleaseDeactivationPort(source)"
        ));
        assertTrue(postgreSqlLifecycle.contains("pg_advisory_xact_lock"));
        assertTrue(postgreSqlEffective.contains("pg_advisory_xact_lock"));

        assertTrue(lifecycleConfiguration.contains(
            "JdbcApprovalProcessReleaseStoreFactory.create(dataSource)"
        ));
        assertTrue(lifecycleConfiguration.contains(
            "JdbcApprovalEffectiveReleaseDeactivationPortFactory.create(dataSource)"
        ));
        assertFalse(lifecycleConfiguration.contains(
            "new JdbcApprovalProcessReleaseStore(dataSource)"
        ));
        assertFalse(lifecycleConfiguration.contains(
            "new JdbcApprovalEffectiveReleaseDeactivationPort(dataSource)"
        ));
        assertTrue(deploymentConfiguration.contains(
            "JdbcApprovalEffectiveReleaseStoreFactory.create(dataSource)"
        ));
        assertFalse(deploymentConfiguration.contains(
            "new JdbcApprovalEffectiveReleaseStore(dataSource)"
        ));

        for (String service : List.of(
            activationService,
            effectiveService,
            dispositionService
        )) {
            String lowerService = service.toLowerCase(Locale.ROOT);
            assertFalse(lowerService.contains("mysql"));
            assertFalse(lowerService.contains("postgresql"));
        }

        for (String required : List.of(
            "MYSQL_P3_G2_RELEASE_LIFECYCLE_EFFECTIVE_",
            "APPROVAL-PROCESS-RELEASE:",
            "APPROVAL-EFFECTIVE-RELEASE:",
            "REVISION = :EXPECTEDREVISION",
            "ACTIVATE",
            "ROLLBACK",
            "DEPRECATED",
            "RETIRED",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-G2 contract is missing required marker: " + required
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
