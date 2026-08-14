package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.ApprovalAttachment;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalAttachmentStoreMySqlIntegrationTest {

    private static final String TENANT = "tenant-attachment-mysql";
    private static final String OTHER_TENANT = "tenant-attachment-other";
    private static final String DEFINITION_KEY = "attachment-definition";
    private static final UUID INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008401"
    );
    private static final UUID OTHER_INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008402"
    );
    private static final Instant CREATED_AT = Instant.parse(
        "2026-08-07T09:21:22.123456Z"
    );
    private static final Instant BOUND_AT = Instant.parse(
        "2026-08-07T09:22:23.654321Z"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_attachment")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(
            "--default-time-zone=+00:00",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_0900_as_cs",
            "--transaction-isolation=READ-COMMITTED",
            "--innodb-strict-mode=ON",
            "--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,"
                + "NO_ENGINE_SUBSTITUTION"
        );

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private JdbcApprovalAttachmentStore store;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        MySqlTestDatabaseAuthority.flyway(MYSQL, dataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from ap_approval_attachment");
        jdbc.update("delete from ap_approval_instance");
        jdbc.update("delete from ap_definition_version");
        seedDefinition(TENANT);
        seedInstance(TENANT, INSTANCE_ID, "instance-a", "business-a");
        seedInstance(TENANT, OTHER_INSTANCE_ID, "instance-b", "business-b");
        store = new JdbcApprovalAttachmentStore(dataSource);
    }

    @Test
    void roundTripsBinaryUuidAndMicrosecondTimestampWithoutTenantLeakage() {
        UUID attachmentId = UUID.fromString("00000000-0000-0000-0000-000000008411");
        byte[] content = new byte[] {
            0x00, 0x01, 0x02, 0x7f, (byte) 0x80, (byte) 0xfe, (byte) 0xff
        };
        ApprovalAttachment attachment = attachment(
            attachmentId,
            "Uploader-A",
            "a".repeat(64),
            content
        );

        store.save(attachment);

        ApprovalAttachment loaded = store.find(TENANT, attachmentId).orElseThrow();
        assertEquals(attachmentId, loaded.attachmentId());
        assertEquals(CREATED_AT, loaded.createdAt());
        assertEquals("a".repeat(64), loaded.sha256());
        assertArrayEquals(content, loaded.content());
        assertFalse(store.find(OTHER_TENANT, attachmentId).isPresent());
        assertEquals(
            "0001027F80FEFF",
            jdbc.queryForObject(
                "select hex(content) from ap_approval_attachment where attachment_id = ?",
                String.class,
                attachmentId.toString()
            )
        );
    }

    @Test
    void preservesRequestedSummaryOrderAndOmitsMissingIds() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000008421");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000008422");
        UUID missing = UUID.fromString("00000000-0000-0000-0000-000000008423");
        store.save(attachment(first, "uploader-a", "b".repeat(64), new byte[] {1}));
        store.save(attachment(second, "uploader-a", "c".repeat(64), new byte[] {2}));

        var summaries = store.findSummaries(TENANT, List.of(second, missing, first));

        assertEquals(List.of(second, first), summaries.stream()
            .map(summary -> summary.attachmentId())
            .toList());
        assertTrue(store.findSummaries(TENANT, List.of()).isEmpty());
    }

    @Test
    void bindsOnlyExactCaseSensitiveOwnerAndKeepsTheFirstBindingTimestamp() {
        UUID attachmentId = UUID.fromString("00000000-0000-0000-0000-000000008431");
        store.save(attachment(
            attachmentId,
            "Uploader-A",
            "d".repeat(64),
            new byte[] {3, 4}
        ));

        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.bindToInstance(
                TENANT,
                "uploader-a",
                INSTANCE_ID,
                List.of(attachmentId),
                BOUND_AT
            )
        );

        store.bindToInstance(
            TENANT,
            "Uploader-A",
            INSTANCE_ID,
            List.of(attachmentId),
            BOUND_AT
        );
        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.bindToInstance(
                TENANT,
                "different-owner-after-bind",
                INSTANCE_ID,
                List.of(attachmentId),
                BOUND_AT.plusSeconds(60)
            )
        );
        store.bindToInstance(
            TENANT,
            "Uploader-A",
            INSTANCE_ID,
            List.of(attachmentId),
            BOUND_AT.plusSeconds(60)
        );

        ApprovalAttachment bound = store.find(TENANT, attachmentId).orElseThrow();
        assertEquals(INSTANCE_ID, bound.instanceId());
        assertEquals(BOUND_AT, bound.boundAt());

        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.bindToInstance(
                TENANT,
                "Uploader-A",
                OTHER_INSTANCE_ID,
                List.of(attachmentId),
                BOUND_AT.plusSeconds(120)
            )
        );
        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.bindToInstance(
                OTHER_TENANT,
                "Uploader-A",
                INSTANCE_ID,
                List.of(attachmentId),
                BOUND_AT
            )
        );
    }

    @Test
    void reportsTheResolvedMySqlValueContract() {
        JdbcDatabaseValueAdapter values = JdbcDatabaseValueAdapter.resolve(dataSource);

        assertEquals(ApprovalDatabaseVendor.MYSQL, values.vendor());
        assertEquals(INSTANCE_ID.toString(), values.bindUuid(INSTANCE_ID));
        assertEquals(
            java.time.LocalDateTime.ofInstant(CREATED_AT, java.time.ZoneOffset.UTC),
            values.bindInstant(CREATED_AT)
        );
    }

    private static ApprovalAttachment attachment(
        UUID attachmentId,
        String uploaderId,
        String sha256,
        byte[] content
    ) {
        return new ApprovalAttachment(
            attachmentId,
            TENANT,
            uploaderId,
            null,
            "evidence.bin",
            "application/octet-stream",
            content.length,
            sha256,
            content,
            CREATED_AT,
            null
        );
    }

    private static void seedDefinition(String tenantId) {
        jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version,
                form_key, form_version, compiler_version, content_hash,
                deployment_id, engine_definition_id, engine_version,
                published_by, published_at
            ) values (?, ?, 1, ?, 1, 'compiler-1', ?, ?, ?, 1, 'publisher', ?)
            """,
            tenantId,
            DEFINITION_KEY,
            DEFINITION_KEY,
            "e".repeat(64),
            "deployment-attachment",
            "definition-attachment",
            Timestamp.from(CREATED_AT)
        );
    }

    private static void seedInstance(
        String tenantId,
        UUID instanceId,
        String engineInstanceId,
        String businessKey
    ) {
        jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id,
                amount, supplier, purchase_order_reference,
                attachment_ids_json, assignee_snapshot_json, request_hash,
                status, version, created_at, updated_at
            ) values (
                ?, ?, ?, ?, ?, 1, ?, 1,
                'compiler-1', ?, 'initiator',
                ?, 'supplier', 'purchase-order',
                cast(? as json), cast(? as json), ?,
                'RUNNING', 1, ?, ?
            )
            """,
            instanceId.toString(),
            tenantId,
            businessKey,
            engineInstanceId,
            DEFINITION_KEY,
            DEFINITION_KEY,
            "f".repeat(64),
            new BigDecimal("10.00"),
            "[]",
            "{}",
            "1".repeat(64),
            Timestamp.from(CREATED_AT),
            Timestamp.from(CREATED_AT)
        );
    }

    private static String configuredJdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true";
    }
}
