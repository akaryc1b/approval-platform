package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalAssistanceDurableEvidenceStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path MAIN = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void usesTrustedMetadataAndBoundedMySqlTransactionProtocols() throws IOException {
        String store = Files.readString(MAIN.resolve(
            "JdbcMySqlApprovalAssistanceDurableEvidenceStore.java"
        ));
        String factory = Files.readString(MAIN.resolve(
            "JdbcApprovalAssistanceDurableEvidenceStoreFactory.java"
        ));
        String configuration = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalAssistanceProductionConfiguration.java"
        ));

        assertTrue(store.contains("JdbcDatabaseValueAdapter.resolve(source)"));
        assertTrue(store.contains("values.vendor() != ApprovalDatabaseVendor.MYSQL"));
        assertTrue(store.contains("JdbcMySqlTransactionLockManager"));
        assertTrue(store.contains("acquireIdentityLocks(exact)"));
        assertTrue(store.contains("for update"));
        assertTrue(store.contains("tombstone_hash"));
        assertTrue(store.contains("requireCanonicalEvidence"));
        assertTrue(store.contains("AuditHashCanonicalizer.canonicalInstant"));
        assertTrue(store.contains("MySQL evidence event/state authority diverged"));

        assertFalse(store.contains("on conflict"));
        assertFalse(store.contains("for update of"));
        assertFalse(store.contains("insert ignore"));
        assertFalse(store.contains("on duplicate key update"));
        assertFalse(store.contains("replace into"));
        assertFalse(store.contains("foreign_key_checks"));
        assertFalse(store.contains("pg_advisory"));
        assertFalse(store.contains("::jsonb"));

        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));
        assertTrue(factory.contains("case POSTGRESQL"));
        assertTrue(factory.contains("case MYSQL"));
        assertTrue(factory.contains("JdbcMySqlApprovalAssistanceDurableEvidenceStore"));
        assertTrue(configuration.contains(
            "JdbcApprovalAssistanceDurableEvidenceStoreFactory.create("
        ));
        assertFalse(configuration.contains(
            "new JdbcApprovalAssistanceDurableEvidenceStore("
        ));
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
