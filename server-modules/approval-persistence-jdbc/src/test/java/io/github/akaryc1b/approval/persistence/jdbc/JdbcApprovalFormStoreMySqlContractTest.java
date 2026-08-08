package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalFormStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mySqlStoreRetainsExactSerializationJsonTimeAndSearchBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalFormStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("CANONICAL_JSON_TEXT_V1"));
        assertTrue(store.contains("envelope.size() != 2"));
        assertTrue(store.contains("cast(:schemaJson as json)"));
        assertTrue(store.contains("values.bindInstant(canonicalInstant("));
        assertTrue(store.contains("values.instant(resultSet, \"published_at\")"));
        assertTrue(store.contains(
            "or lower(form_key) like concat('%', lower(:keyword), '%')"
        ));
        assertTrue(store.contains(
            "order by published_at desc, form_key, form_version desc"
        ));
        assertTrue(store.contains(
            "\"form:\" + exactTenant + ':' + exactFormKey + ':' + exactVersion"
        ));

        for (String forbidden : List.of(
            "pg_advisory",
            "jsonb",
            "lower('%' || :keyword || '%')",
            "insert ignore",
            "replace into",
            "on duplicate key update",
            "foreign_key_checks"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL form store contains forbidden boundary: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoryServerBindingAndPostgreSqlReferenceRemainExplicit()
        throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalFormStoreFactory.java")
        );
        String postgreSqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalFormStore.java")
        ).toLowerCase(Locale.ROOT);
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalFormConfiguration.java"
        ));

        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalFormStore(source, mapper)"
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalFormStore(source, mapper)"
        ));
        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));

        assertTrue(postgreSqlStore.contains("pg_advisory_xact_lock"));
        assertTrue(postgreSqlStore.contains("cast(:schemajson as jsonb)"));
        assertTrue(postgreSqlStore.contains("lower('%' || :keyword || '%')"));

        assertTrue(server.contains("JdbcApprovalFormStoreFactory.create("));
        assertFalse(server.contains("new JdbcApprovalFormStore("));
    }

    @Test
    void permanentContractRetainsScopeAndHonestNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_F1_FORM_DEFINITION_STORE_CONTRACT.md"
        ));

        for (String required : List.of(
            "MYSQL_P3_F1_FORM_DEFINITION_STORE_PROVEN",
            "CANONICAL_JSON_TEXT_V1",
            "nearest-microsecond",
            "INSERT IGNORE",
            "UI Schema Store",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                contract.contains(required),
                () -> "P3-F1 contract is missing required marker: " + required
            );
        }
        assertFalse(contract.contains("MYSQL_8_4_PRODUCTION_SUPPORTED"));
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
