package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.DEFINITION_KEY;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_migration_protocol_test")
        .withUsername("approval")
        .withPassword("approval");

    static DataSource dataSource;
    JdbcTemplate jdbc;
    JdbcApprovalMigrationProtocolStore store;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUpMigrationProtocol() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table ap_process_migration_reconciliation,
              ap_process_migration_verification,ap_process_migration_attempt_event,
              ap_process_migration_attempt,ap_process_migration_intent_event,
              ap_process_migration_intent,ap_approval_release_package cascade
            """);
        seedReleasePackages();
        store = new JdbcApprovalMigrationProtocolStore(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            new JdbcTransactionManager(dataSource)
        );
    }

    int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void seedReleasePackages() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement control = connection.createStatement()) {
                control.execute("set session_replication_role = replica");
            }
            try (Statement statement = connection.createStatement()) {
                seedReleasePackage(statement, 1, hash('b'));
                seedReleasePackage(statement, 2, hash('c'));
            } finally {
                try (Statement control = connection.createStatement()) {
                    control.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private static void seedReleasePackage(
        Statement statement,
        int version,
        String packageHash
    ) throws SQLException {
        int inserted = statement.executeUpdate("""
            insert into ap_approval_release_package (
              tenant_id,definition_key,release_version,definition_version,definition_hash,
              form_package_version,form_package_hash,form_version,form_hash,
              ui_schema_version,ui_schema_hash,compiler_version,bpmn_resource_name,bpmn_artifact,
              compiled_artifact_hash,bpmn_hash,dmn_artifact,dmn_hash,deployment_metadata_hash,
              package_hash,source_draft_id,published_by,published_at
            ) values (
              '%s','%s',%d,%d,'%s',
              %d,'%s',%d,'%s',
              %d,'%s','compiler-v1','process-%d.bpmn20.xml','<definitions/>',
              '%s','%s',null,null,'%s',
              '%s','%s'::uuid,'migration-protocol-publisher',timestamptz '%s'
            )
            """.formatted(
                TENANT, DEFINITION_KEY, version, version, hash(hex(version + 1)),
                version, hash(hex(version + 3)), version, hash(hex(version + 5)),
                version, hash(hex(version + 7)), version,
                hash(hex(version + 9)), hash(hex(version + 11)), hash(hex(version + 13)),
                packageHash, new UUID(70, version), offset(NOW.minusSeconds(100 - version))
            ));
        assertEquals(1, inserted);
    }

    private static char hex(int value) {
        return Integer.toHexString(Math.floorMod(value, 16)).charAt(0);
    }

    private static OffsetDateTime offset(java.time.Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
