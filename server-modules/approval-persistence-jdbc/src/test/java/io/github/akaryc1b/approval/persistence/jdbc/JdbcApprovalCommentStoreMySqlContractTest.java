package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalCommentStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mySqlStoreRetainsLifecycleAudienceRevisionAndCasBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalCommentStore.java")
        ) + Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalCommentStoreContext.java")
        ) + Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalCommentReadSupport.java")
        ) + Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalCommentWriteSupport.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        for (String required : List.of(
            "JdbcMySqlApprovalCommentStore requires MySQL 8.4",
            "transactions.execute",
            "cast(:mentionIdsJson as json)",
            "cast(:attachmentIdsJson as json)",
            "json_contains(",
            "json_quote(:viewerId)",
            "json_quote(:attachmentId)",
            "and status = 'ACTIVE'",
            "and version = :expectedVersion",
            "version = version + 1",
            "appendRevision",
            "values.bindUuid(",
            "values.bindNullableUuid(",
            "values.bindInstant(",
            "values.nullableUuid(",
            "values.instant(",
            "values.nullableInstant(",
            "AuditHashCanonicalizer.canonicalInstant",
            "order by comment.created_at, comment.comment_id",
            "order by revision.revision_number",
            "APPROVAL_COMMENT_CONCURRENT_MODIFICATION"
        )) {
            assertTrue(
                store.contains(required),
                () -> "missing MySQL approval-comment marker: " + required
            );
        }

        for (String forbidden : List.of(
            "jsonb",
            "jsonb_exists",
            " on conflict ",
            " returning ",
            "insert ignore",
            "replace into",
            "on duplicate key update",
            "foreign_key_checks",
            "pg_advisory",
            "::"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL approval-comment boundary contains forbidden token: "
                    + forbidden
            );
        }
    }

    @Test
    void trustedFactoryServerBindingAndPostgreSqlReferenceRemainExplicit()
        throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalCommentStoreFactory.java")
        );
        String postgreSqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalCommentStore.java")
        ).toLowerCase(Locale.ROOT);
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalPlatformConfiguration.java"
        ));

        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));
        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalCommentStore("
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalCommentStore("
        ));

        assertTrue(postgreSqlStore.contains("jsonb_exists("));
        assertTrue(postgreSqlStore.contains(
            "cast(:mentionidsjson as jsonb)"
        ));
        assertTrue(postgreSqlStore.contains(
            "cast(:attachmentidsjson as jsonb)"
        ));

        assertTrue(server.contains(
            "JdbcApprovalCommentStoreFactory.create("
        ));
        assertFalse(server.contains("new JdbcApprovalCommentStore("));
    }

    @Test
    void stagedContractRetainsScopeAndHonestNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/"
                + "MYSQL_8_4_P3_CMT1_APPROVAL_COMMENT_STORE_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);

        for (String required : List.of(
            "MYSQL_P3_CMT1_APPROVAL_COMMENT_STORE_STAGED",
            "IMMUTABLE REVISION",
            "SERVER-SIDE AUDIENCE",
            "OPTIMISTIC CAS",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN",
            "NO_READY",
            "NO_MAIN_MERGE"
        )) {
            assertTrue(
                upper.contains(required.toUpperCase(Locale.ROOT)),
                () -> "P3-CMT1 contract is missing required marker: "
                    + required
            );
        }
        assertFalse(contract.contains(
            "MYSQL_P3_CMT1_APPROVAL_COMMENT_STORE_PROVEN"
        ));
        assertFalse(contract.contains(
            "MYSQL_8_4_PRODUCTION_SUPPORTED"
        ));
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty(
            "maven.multiModuleProjectDirectory"
        );
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured)
                .toAbsolutePath()
                .normalize();
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
        throw new IllegalStateException(
            "repository root could not be resolved"
        );
    }
}
