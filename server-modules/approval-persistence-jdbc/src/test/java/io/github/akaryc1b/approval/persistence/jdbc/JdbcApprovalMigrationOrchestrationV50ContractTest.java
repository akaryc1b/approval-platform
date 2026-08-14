package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationOrchestrationV50ContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path MIGRATIONS = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/resources/db/migration"
    );

    @Test
    void postgresqlV50AllowsOnlyExactDispatchObservationPredecessor()
        throws IOException {
        String v47 = Files.readString(MIGRATIONS.resolve(
            "V47__create_canary_bounded_orchestration.sql"
        ));
        String v50 = Files.readString(MIGRATIONS.resolve(
            "V50__correct_d7_dispatch_observation_predecessor.sql"
        ));
        String lower = v50.toLowerCase(Locale.ROOT);

        assertFalse(v47.contains("observation_hash_value"));
        assertTrue(v50.contains(
            "create or replace function ap_guard_process_migration_d7_evidence()"
        ));
        assertTrue(v50.contains("observation_hash_value char(64)"));
        assertTrue(v50.contains(
            "from ap_process_migration_kill_switch_observation observation"
        ));
        assertTrue(v50.contains("observation.attempt_id=new.attempt_id"));
        assertTrue(v50.contains(
            "observation.observation_evidence_hash=new.predecessor_hash"
        ));
        assertTrue(v50.contains(
            "new.predecessor_hash is distinct from observation_hash_value"
        ));

        for (String forbidden : List.of(
            "create table ",
            "alter table ",
            "drop table ",
            "drop trigger ",
            "create trigger ",
            "create index ",
            "ap_process_migration_plan_aggregate",
            "ap_ai_"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "PostgreSQL D7 correction exceeds bounded scope: " + forbidden
            );
        }
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
