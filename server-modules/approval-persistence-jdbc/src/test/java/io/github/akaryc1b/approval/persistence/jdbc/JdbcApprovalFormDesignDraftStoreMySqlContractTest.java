package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalFormDesignDraftStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mySqlStoreRetainsCasLockEvidenceAndTenantBoundaries() throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalFormDesignDraftStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        assertTrue(store.contains("CANONICAL_JSON_TEXT_V1"));
        assertTrue(store.contains("JdbcMySqlUiSchemaCodec"));
        assertTrue(store.contains("STRICT_DUPLICATE_DETECTION"));
        assertTrue(store.contains("cast(:formJson as json)"));
        assertTrue(store.contains("cast(:uiJson as json)"));
        assertTrue(store.contains("concat('%', lower(:keyword), '%')"));
        assertTrue(store.contains("order by updated_at desc, draft_id"));
        assertTrue(store.contains("for update"));
        assertTrue(store.contains("revision = :expectedRevision"));
        assertTrue(store.contains("status in ('DRAFT', 'VALIDATED')"));
        assertTrue(store.contains("values.bindUuid"));
        assertTrue(store.contains("values.bindInstant(canonicalInstant("));
        assertTrue(store.contains("values.instant(resultSet, \"updated_at\")"));

        for (String forbidden : List.of(
            "jsonb",
            "insert ignore",
            "replace into",
            "on duplicate key update",
            "foreign_key_checks",
            "pg_advisory_lock",
            "pg_advisory_xact_lock",
            "activatedefaulttyping",
            "@jsontypeinfo"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL Form Design Draft boundary contains forbidden token: " + forbidden
            );
        }
    }

    @Test
    void factoryServerBindingAndPostgreSqlReferenceRemainExplicit() throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalFormDesignDraftStoreFactory.java")
        );
        String postgreSqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalFormDesignDraftStore.java")
        ).toLowerCase(Locale.ROOT);
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalFormConfiguration.java"
        ));
        String service = Files.readString(ROOT.resolve(
            "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
                + "application/ApprovalFormDesignService.java"
        )).toLowerCase(Locale.ROOT);

        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalFormDesignDraftStore(source, mapper)"
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalFormDesignDraftStore(source, mapper)"
        ));
        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));

        assertTrue(postgreSqlStore.contains("cast(:formjson as jsonb)"));
        assertTrue(postgreSqlStore.contains("cast(:uijson as jsonb)"));
        assertTrue(postgreSqlStore.contains("for update"));
        assertTrue(postgreSqlStore.contains("revision = :expectedrevision"));

        assertTrue(server.contains("JdbcApprovalFormDesignDraftStoreFactory.create("));
        assertFalse(server.contains("new JdbcApprovalFormDesignDraftStore("));
        assertFalse(service.contains("mysql"));
        assertFalse(service.contains("postgresql"));
    }

    @Test
    void permanentContractRetainsScopeAndHonestNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_F3_FORM_DESIGN_DRAFT_STORE_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);

        for (String required : List.of(
            "MYSQL_P3_F3_FORM_DESIGN_DRAFT_STORE_PROVEN",
            "CANONICAL_JSON_TEXT_V1",
            "CANONICAL_UI_SCHEMA_TYPED_JSON_V1",
            "DRAFTREVISIONCONFLICTEXCEPTION",
            "FOR UPDATE",
            "P3-F4 FORM PACKAGE STORE",
            "P3-F5 FORM SUBMISSION STORE",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN"
        )) {
            assertTrue(
                upper.contains(required),
                () -> "P3-F3 contract is missing required marker: " + required
            );
        }
        assertFalse(contract.contains("MYSQL_P3_F3_FORM_DESIGN_DRAFT_STORE_STAGED"));
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
