package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMessageStoreMySqlContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path JDBC_ROOT = ROOT.resolve(
        "server-modules/approval-persistence-jdbc/src/main/java/"
            + "io/github/akaryc1b/approval/persistence/jdbc"
    );

    @Test
    void mySqlStoreRetainsStrictDedupReadReceiptAndQueryBoundaries()
        throws IOException {
        String store = Files.readString(
            JDBC_ROOT.resolve("JdbcMySqlApprovalMessageStore.java")
        );
        String lower = store.toLowerCase(Locale.ROOT);

        for (String required : List.of(
            "DuplicateKeyException",
            "isLegalDeduplicationReplay",
            "deduplicationOwner",
            "messageIdOwner",
            "dedupOwner.isPresent()",
            "messageOwner.isEmpty()",
            "messageOwner.equals(dedupOwner)",
            "tenant_id = :tenantId",
            "dedup_key = :dedupKey",
            "where message_id = :messageId",
            "and read_at is null",
            "transactions.execute",
            "values.bindInstant(canonicalInstant(",
            "values.instant(resultSet, \"created_at\")",
            "locate(:keyword, lower(instance.business_key)) > 0",
            "order by message.created_at desc, message.message_id desc",
            "order by created_at, message_id"
        )) {
            assertTrue(
                store.contains(required),
                () -> "missing MySQL approval-message marker: " + required
            );
        }

        assertFalse(store.contains("deduplicationKeyExists"));
        for (String forbidden : List.of(
            " on conflict ",
            " returning ",
            "jsonb",
            "insert ignore",
            "replace into",
            "on duplicate key update",
            "foreign_key_checks",
            "pg_advisory",
            "::"
        )) {
            assertFalse(
                lower.contains(forbidden),
                () -> "MySQL approval-message boundary contains forbidden token: "
                    + forbidden
            );
        }
    }

    @Test
    void trustedFactoryServerBindingAndPostgreSqlReferenceRemainExplicit()
        throws IOException {
        String factory = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalMessageStoreFactory.java")
        );
        String postgreSqlStore = Files.readString(
            JDBC_ROOT.resolve("JdbcApprovalMessageStore.java")
        ).toLowerCase(Locale.ROOT);
        String server = Files.readString(ROOT.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalPlatformConfiguration.java"
        ));

        assertTrue(factory.contains("ApprovalDatabaseVendorResolver"));
        assertTrue(factory.contains(
            "case POSTGRESQL -> new JdbcApprovalMessageStore(source, mapper)"
        ));
        assertTrue(factory.contains(
            "case MYSQL -> new JdbcMySqlApprovalMessageStore(source, mapper, manager)"
        ));

        assertTrue(postgreSqlStore.contains(
            "on conflict (tenant_id, dedup_key) do nothing"
        ));
        assertTrue(postgreSqlStore.contains("cast(:metadatajson as jsonb)"));
        assertTrue(postgreSqlStore.contains(
            "returning message_id, instance_id, message_type"
        ));

        assertTrue(server.contains("JdbcApprovalMessageStoreFactory.create("));
        assertFalse(server.contains("new JdbcApprovalMessageStore("));
    }

    @Test
    void stagedContractRetainsScopeAndHonestNonClaims() throws IOException {
        String contract = Files.readString(ROOT.resolve(
            "docs/database/MYSQL_8_4_P3_MSG1_APPROVAL_MESSAGE_STORE_CONTRACT.md"
        ));
        String upper = contract.toUpperCase(Locale.ROOT);

        for (String required : List.of(
            "MYSQL_P3_MSG1_APPROVAL_MESSAGE_STORE_STAGED",
            "STRICT DEDUPLICATION",
            "FIRSTREAD",
            "MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED",
            "PR_92_REMAINS_OPEN_DRAFT",
            "ISSUE_91_REMAINS_OPEN",
            "NO_READY",
            "NO_MAIN_MERGE"
        )) {
            assertTrue(
                upper.contains(required.toUpperCase(Locale.ROOT)),
                () -> "P3-MSG1 contract is missing required marker: " + required
            );
        }
        assertFalse(contract.contains("MYSQL_P3_MSG1_ACCEPTED"));
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
