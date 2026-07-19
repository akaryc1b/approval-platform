package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalEffectiveReleaseIntegrationTest {

    private static final String TENANT = "tenant-effective";
    private static final String DEFINITION_KEY = "purchase-payment";
    private static final Instant NOW = Instant.parse("2026-07-19T03:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("approval_effective_release")
            .withUsername("approval")
            .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ApprovalEffectiveReleaseStore store;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource configured = new DriverManagerDataSource();
        configured.setDriverClassName("org.postgresql.Driver");
        configured.setUrl(POSTGRES.getJdbcUrl());
        configured.setUsername(POSTGRES.getUsername());
        configured.setPassword(POSTGRES.getPassword());
        dataSource = configured;
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_approval_release_activation_history,
                ap_approval_effective_release,
                ap_approval_release_deployment,
                ap_approval_release_package,
                ap_approval_compiled_artifact,
                ap_approval_definition,
                ap_approval_design_draft,
                ap_form_package,
                ap_form_design_draft,
                ap_form_ui_schema,
                ap_form_definition,
                ap_command_idempotency,
                ap_audit_event
            cascade
            """);
        store = new JdbcApprovalEffectiveReleaseStore(dataSource);
    }

    @Test
    void savesSwitchesAndRollsBackWithTenantScopedHistory() {
        seedRelease(TENANT, 1);
        seedRelease(TENANT, 2);

        store.save(
            effective(TENANT, 1, null, 1),
            activation(TENANT, 1, null, 1, ApprovalEffectiveRelease.Action.ACTIVATE)
        );
        assertTrue(store.update(
            effective(TENANT, 2, 1, 2),
            1,
            activation(TENANT, 2, 1, 2, ApprovalEffectiveRelease.Action.ACTIVATE)
        ));
        assertTrue(store.update(
            effective(TENANT, 1, 2, 3),
            2,
            activation(TENANT, 1, 2, 3, ApprovalEffectiveRelease.Action.ROLLBACK)
        ));

        ApprovalEffectiveRelease current = store.find(TENANT, DEFINITION_KEY).orElseThrow();
        assertEquals(1, current.effectiveReleaseVersion());
        assertEquals(2, current.previousReleaseVersion());
        assertEquals(3, current.revision());
        assertTrue(store.find("tenant-other", DEFINITION_KEY).isEmpty());
        assertTrue(store.wasActivated(TENANT, DEFINITION_KEY, 1));
        assertTrue(store.wasActivated(TENANT, DEFINITION_KEY, 2));
        assertFalse(store.wasActivated("tenant-other", DEFINITION_KEY, 1));

        ApprovalEffectiveReleaseStore.ActivationPage history = store.findHistory(
            new ApprovalEffectiveReleaseStore.ActivationCriteria(
                TENANT,
                DEFINITION_KEY,
                10,
                0
            )
        );
        assertEquals(3L, history.total());
        assertEquals(
            List.of(
                ApprovalEffectiveRelease.Action.ROLLBACK,
                ApprovalEffectiveRelease.Action.ACTIVATE,
                ApprovalEffectiveRelease.Action.ACTIVATE
            ),
            history.items().stream().map(ApprovalEffectiveRelease.Activation::action).toList()
        );
        assertEquals(
            List.of(3L, 2L, 1L),
            history.items().stream()
                .map(ApprovalEffectiveRelease.Activation::revision)
                .toList()
        );
    }

    @Test
    void revisionCasAllowsOnlyOneConcurrentSwitch() throws Exception {
        seedRelease(TENANT, 1);
        seedRelease(TENANT, 2);
        seedRelease(TENANT, 3);
        store.save(
            effective(TENANT, 1, null, 1),
            activation(TENANT, 1, null, 1, ApprovalEffectiveRelease.Action.ACTIVATE)
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> switchToTwo = executor.submit(() -> updateAfterSignal(
                ready,
                start,
                2
            ));
            Future<Boolean> switchToThree = executor.submit(() -> updateAfterSignal(
                ready,
                start,
                3
            ));
            ready.await();
            start.countDown();

            int successes = (switchToTwo.get() ? 1 : 0) + (switchToThree.get() ? 1 : 0);
            assertEquals(1, successes);
        }

        ApprovalEffectiveRelease current = store.find(TENANT, DEFINITION_KEY).orElseThrow();
        assertTrue(current.effectiveReleaseVersion() == 2
            || current.effectiveReleaseVersion() == 3);
        assertEquals(2, current.revision());
        assertEquals(
            2L,
            store.findHistory(new ApprovalEffectiveReleaseStore.ActivationCriteria(
                TENANT,
                DEFINITION_KEY,
                10,
                0
            )).total()
        );
    }

    @Test
    void exactReleaseForeignKeyRejectsCrossTenantActivation() {
        seedRelease(TENANT, 1);

        assertThrows(DataAccessException.class, () -> store.save(
            effective("tenant-other", 1, null, 1),
            activation(
                "tenant-other",
                1,
                null,
                1,
                ApprovalEffectiveRelease.Action.ACTIVATE
            )
        ));
        assertTrue(store.find("tenant-other", DEFINITION_KEY).isEmpty());
    }

    private boolean updateAfterSignal(
        CountDownLatch ready,
        CountDownLatch start,
        int releaseVersion
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return store.update(
            effective(TENANT, releaseVersion, 1, 2),
            1,
            activation(
                TENANT,
                releaseVersion,
                1,
                2,
                ApprovalEffectiveRelease.Action.ACTIVATE
            )
        );
    }

    private void seedRelease(String tenantId, int version) {
        UUID formDraftId = UUID.randomUUID();
        UUID approvalDraftId = UUID.randomUUID();
        OffsetDateTime now = offset(NOW.plusSeconds(version));

        jdbc.update(
            """
            insert into ap_form_definition (
                tenant_id, form_key, form_version, schema_version, name,
                field_count, schema_json, content_hash, published_by, published_at
            ) values (?, ?, ?, '1.0', ?, 1, cast('{}' as jsonb), ?, 'publisher', ?)
            """,
            tenantId,
            DEFINITION_KEY,
            version,
            "Form " + version,
            formHash(version),
            now
        );
        jdbc.update(
            """
            insert into ap_form_ui_schema (
                tenant_id, form_key, form_version, ui_schema_version,
                schema_version, name, section_count, schema_json,
                content_hash, published_by, published_at
            ) values (?, ?, ?, ?, '1.0', ?, 1, cast('{}' as jsonb),
                      ?, 'publisher', ?)
            """,
            tenantId,
            DEFINITION_KEY,
            version,
            version,
            "UI " + version,
            uiHash(version),
            now
        );
        jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name, form_version,
                ui_schema_version, form_schema_json, ui_schema_json,
                revision, status, created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, cast('{}' as jsonb), cast('{}' as jsonb),
                      1, 'DRAFT', 'publisher', 'publisher', ?, ?)
            """,
            tenantId,
            formDraftId,
            DEFINITION_KEY,
            "Form draft " + version,
            version,
            version,
            now,
            now
        );
        jdbc.update(
            """
            insert into ap_form_package (
                tenant_id, form_key, package_version, form_version, form_hash,
                ui_schema_version, ui_schema_hash, package_hash,
                source_draft_id, published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'publisher', ?)
            """,
            tenantId,
            DEFINITION_KEY,
            version,
            version,
            formHash(version),
            version,
            uiHash(version),
            formPackageHash(version),
            formDraftId,
            now
        );
        jdbc.update(
            """
            update ap_form_design_draft
            set status = 'PUBLISHED', published_package_version = ?
            where tenant_id = ? and draft_id = ?
            """,
            version,
            tenantId,
            formDraftId
        );
        jdbc.update(
            """
            insert into ap_approval_design_draft (
                tenant_id, draft_id, definition_key, name, definition_version,
                approval_dsl_json, form_package_version, form_package_hash,
                revision, status, created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, ?, ?, cast('{}' as jsonb), ?, ?, 1, 'DRAFT',
                      'publisher', 'publisher', ?, ?)
            """,
            tenantId,
            approvalDraftId,
            DEFINITION_KEY,
            "Approval draft " + version,
            version,
            version,
            formPackageHash(version),
            now,
            now
        );
        jdbc.update(
            """
            insert into ap_approval_definition (
                tenant_id, definition_key, definition_version, definition_hash,
                form_package_version, form_package_hash, approval_dsl_json,
                source_draft_id, published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, cast('{}' as jsonb), ?, 'publisher', ?)
            """,
            tenantId,
            DEFINITION_KEY,
            version,
            definitionHash(version),
            version,
            formPackageHash(version),
            approvalDraftId,
            now
        );
        jdbc.update(
            """
            insert into ap_approval_compiled_artifact (
                tenant_id, definition_key, definition_version, definition_hash,
                form_version, form_hash, compiler_version, resource_name,
                bpmn_xml, compiled_artifact_hash, bpmn_hash, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, '<definitions />', ?, ?, ?)
            """,
            tenantId,
            DEFINITION_KEY,
            version,
            definitionHash(version),
            version,
            formHash(version),
            compilerVersion(version),
            DEFINITION_KEY + '-' + version + ".bpmn20.xml",
            compiledHash(version),
            bpmnHash(version),
            now
        );
        jdbc.update(
            """
            insert into ap_approval_release_package (
                tenant_id, definition_key, release_version,
                definition_version, definition_hash,
                form_package_version, form_package_hash,
                form_version, form_hash, ui_schema_version, ui_schema_hash,
                compiler_version, bpmn_resource_name, bpmn_artifact,
                compiled_artifact_hash, bpmn_hash,
                dmn_artifact, dmn_hash, deployment_metadata_hash, package_hash,
                source_draft_id, published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '<definitions />',
                      ?, ?, null, null, ?, ?, ?, 'publisher', ?)
            """,
            tenantId,
            DEFINITION_KEY,
            version,
            version,
            definitionHash(version),
            version,
            formPackageHash(version),
            version,
            formHash(version),
            version,
            uiHash(version),
            compilerVersion(version),
            DEFINITION_KEY + '-' + version + ".bpmn20.xml",
            compiledHash(version),
            bpmnHash(version),
            metadataHash(version),
            packageHash(version),
            approvalDraftId,
            now
        );
        jdbc.update(
            """
            update ap_approval_design_draft
            set status = 'PUBLISHED',
                published_definition_version = ?,
                published_release_version = ?
            where tenant_id = ? and draft_id = ?
            """,
            version,
            version,
            tenantId,
            approvalDraftId
        );
    }

    private static ApprovalEffectiveRelease effective(
        String tenantId,
        int releaseVersion,
        Integer previousReleaseVersion,
        long revision
    ) {
        return new ApprovalEffectiveRelease(
            tenantId,
            DEFINITION_KEY,
            releaseVersion,
            previousReleaseVersion,
            packageHash(releaseVersion),
            releaseVersion,
            definitionHash(releaseVersion),
            releaseVersion,
            formPackageHash(releaseVersion),
            releaseVersion,
            formHash(releaseVersion),
            releaseVersion,
            uiHash(releaseVersion),
            compilerVersion(releaseVersion),
            compiledHash(releaseVersion),
            bpmnHash(releaseVersion),
            metadataHash(releaseVersion),
            "deployment-" + releaseVersion,
            "definition-" + releaseVersion,
            releaseVersion,
            ApprovalEffectiveRelease.Status.ACTIVE,
            revision,
            "operator-a",
            NOW.plusSeconds(revision),
            "change-" + revision,
            "request-" + revision,
            "trace-" + revision
        );
    }

    private static ApprovalEffectiveRelease.Activation activation(
        String tenantId,
        int releaseVersion,
        Integer previousReleaseVersion,
        long revision,
        ApprovalEffectiveRelease.Action action
    ) {
        return new ApprovalEffectiveRelease.Activation(
            UUID.randomUUID(),
            tenantId,
            DEFINITION_KEY,
            releaseVersion,
            previousReleaseVersion,
            packageHash(releaseVersion),
            releaseVersion,
            releaseVersion,
            compilerVersion(releaseVersion),
            "deployment-" + releaseVersion,
            "definition-" + releaseVersion,
            releaseVersion,
            action,
            revision,
            "operator-a",
            NOW.plusSeconds(revision),
            "change-" + revision,
            "request-" + revision,
            "trace-" + revision
        );
    }

    private static String definitionHash(int version) {
        return versionHash(version, 0);
    }

    private static String formPackageHash(int version) {
        return versionHash(version, 1);
    }

    private static String formHash(int version) {
        return versionHash(version, 2);
    }

    private static String uiHash(int version) {
        return versionHash(version, 3);
    }

    private static String compiledHash(int version) {
        return versionHash(version, 4);
    }

    private static String bpmnHash(int version) {
        return versionHash(version, 5);
    }

    private static String metadataHash(int version) {
        return versionHash(version, 6);
    }

    private static String packageHash(int version) {
        return versionHash(version, 7);
    }

    private static String versionHash(int version, int offset) {
        int value = Math.floorMod(version * 8 + offset, 16);
        return Integer.toHexString(value).repeat(64);
    }

    private static String compilerVersion(int version) {
        return "approval-dsl-compiler/1." + version;
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
