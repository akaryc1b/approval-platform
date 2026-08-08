package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalUiSchemaStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mySqlStoreRetainsSerializationReadTimeAndAdmissionBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalUiSchemaStore.java")
        );
        String codec = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlUiSchemaCodec.java")
        );
        String lower = (store + codec).toLowerCase(Locale.ROOT);

        assertTrue(store.contains(
            "\"ui-schema:\""
        ));
        assertTrue(store.contains("cast(:schemaJson as json)"));
        assertTrue(store.contains("values.bindInstant(canonicalInstant("));
        assertTrue(store.contains("values.instant(resultSet, \"published_at\")"));
        assertTrue(store.contains("order by ui_schema_version desc"));
        assertTrue(store.contains("limit 1"));

        assertTrue(codec.contains("CANONICAL_UI_SCHEMA_TYPED_JSON_V1"));
        assertTrue(codec.contains("STRICT_DUPLICATE_DETECTION"));
        assertTrue(codec.contains("typed UI values were not consumed exactly once"));
        for (String marker : List.of(
            "case \"NULL\"",
            "case \"STRING\"",
            "case \"BOOLEAN\"",
            "case \"NUMBER\"",
            "case \"LIST\"",
            "case \"MAP\"",
            "\"BIG_INTEGER\"",
            "\"BIG_DECIMAL\""
        )) {
            assertTrue(codec.contains(marker), () -> "missing typed-value marker: " + marker);
        }

        for (String forbidden : List.of(
            "pg_advisory",
            "cast(:schemajson as jsonb)",
            "insert ignore",
            "replace into",
            "on duplicate key update",
            "foreign_key_checks",
            "activatedefaulttyping",
            "@jsontypeinfo"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL UI Schema boundary contains forbidden token: " + forbidden
            );
        }
    }

    @Test
    void trustedFactoryServerBindingAndPostgreSqlReferenceRemainExplicit()
        throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalUiSchemaStoreFactory.java")
        );
        String postgreSqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalUiSchemaStore.java")
        ).toLowerCase(Locale.ROOT);
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalFormConfiguration.java"
        ));

        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalUiSchemaStore(source, mapper)"
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalUiSchemaStore(source, mapper)"
        ));
        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));

        assertTrue(postgreSqlStore.contains("pg_advisory_xact_lock"));
        assertTrue(postgreSqlStore.contains("cast(:schemajson as jsonb)"));
        assertTrue(postgreSqlStore.contains("order by ui_schema_version desc limit 1"));

        assertTrue(server.contains("JdbcApprovalUiSchemaStoreFactory.create("));
        assertFalse(server.contains("new JdbcApprovalUiSchemaStore("));
    }

    @Test
    void permanentContractRetainsScopeAndHonestNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_F2_UI_SCHEMA_STORE_CONTRACT.md"
        ));

        for (String required : List.of(
            "MYSQL_P3_F2_UI_SCHEMA_STORE_STAGED",
            "CANONICAL_UI_SCHEMA_TYPED_JSON_V1",
            "BigDecimal(\"123.4500\")",
            "STRICT INSERT",
            "Form Design Draft Store",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                contract.toUpperCase(Locale.ROOT).contains(
                    required.toUpperCase(Locale.ROOT)
                ),
                () -> "P3-F2 contract is missing required marker: " + required
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
