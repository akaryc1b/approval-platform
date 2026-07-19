package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferExceptions;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportCommand;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalDefinitionHasher;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.FormPackageHasher;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalArtifactTransferIntegrationTest {

    private static final String SOURCE_TENANT = "tenant-transfer-source";
    private static final String TARGET_TENANT = "tenant-transfer-target";
    private static final String OTHER_TENANT = "tenant-transfer-other";
    private static final Instant NOW = Instant.parse("2026-07-19T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("approval")
            .withUsername("approval")
            .withPassword("approval");

    private static DataSource dataSource;
    private static ObjectMapper objectMapper;

    private JdbcTemplate jdbc;
    private ApprovalDesignService designService;
    private ApprovalReleasePreflightService preflight;
    private ApprovalArtifactTransferService transfer;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource configured = new DriverManagerDataSource();
        configured.setDriverClassName("org.postgresql.Driver");
        configured.setUrl(POSTGRES.getJdbcUrl());
        configured.setUsername(POSTGRES.getUsername());
        configured.setPassword(POSTGRES.getPassword());
        dataSource = configured;
        objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
        ApprovalDefinitionJacksonSupport.configure(objectMapper);
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void reset() {
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
        seedFormPackage(SOURCE_TENANT, 1);
        seedFormPackage(TARGET_TENANT, 1);
        designService = designService();
        preflight = preflight();
        transfer = transferService();
        publishSourceRelease();
    }

    @Test
    void exportsThroughRealJdbcWithZeroWritesAndNoTenantIdentity() throws Exception {
        Map<String, Integer> before = tableCounts();

        var dsl = transfer.exportDefinition(
            SOURCE_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION
        );
        var release = transfer.exportRelease(
            SOURCE_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY,
            1
        );

        assertEquals(before, tableCounts());
        String json = objectMapper.writeValueAsString(Map.of("dsl", dsl, "release", release));
        assertFalse(json.contains(SOURCE_TENANT));
        assertFalse(json.contains("source-publisher"));
        assertFalse(json.contains("sourceDraftId"));
        assertFalse(json.contains("engineDeploymentId"));
        assertFalse(json.contains("engineDefinitionId"));
        assertThrows(
            ApprovalArtifactTransferExceptions.SourceNotFound.class,
            () -> transfer.exportDefinition(
                OTHER_TENANT,
                PurchasePaymentTemplate.DEFINITION_KEY,
                PurchasePaymentTemplate.PROCESS_VERSION
            )
        );
        assertEquals(before, tableCounts());
    }

    @Test
    void importsAsTargetTenantRevisionOneDraftOnlyAndReplaysIdempotently() {
        var envelope = transfer.exportRelease(
            SOURCE_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY,
            1
        );
        ImportCommand command = importCommand(envelope, "jdbc-import-key");

        var imported = transfer.importArtifact(command);
        var replay = transfer.importArtifact(command);

        assertEquals(imported, replay);
        assertEquals(1, imported.revision());
        assertEquals(ApprovalDesignDraft.Status.DRAFT, imported.status());
        assertEquals(
            1,
            tenantCount("ap_approval_design_draft", TARGET_TENANT)
        );
        assertEquals(0, tenantCount("ap_approval_definition", TARGET_TENANT));
        assertEquals(0, tenantCount("ap_approval_release_package", TARGET_TENANT));
        assertEquals(0, tenantCount("ap_approval_release_deployment", TARGET_TENANT));
        assertEquals(0, tenantCount("ap_approval_effective_release", TARGET_TENANT));
        assertEquals(
            0,
            tenantCount("ap_approval_release_activation_history", TARGET_TENANT)
        );
        assertEquals(
            1,
            jdbc.queryForObject(
                "select revision from ap_approval_design_draft "
                    + "where tenant_id = ? and draft_id = ?",
                Integer.class,
                TARGET_TENANT,
                imported.draftId()
            )
        );
        assertEquals(
            "DRAFT",
            jdbc.queryForObject(
                "select status from ap_approval_design_draft "
                    + "where tenant_id = ? and draft_id = ?",
                String.class,
                TARGET_TENANT,
                imported.draftId()
            )
        );
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from ap_audit_event where tenant_id = ? "
                    + "and action = 'APPROVAL_DESIGN_DRAFT_IMPORTED'",
                Integer.class,
                TARGET_TENANT
            )
        );
        String attributes = jdbc.queryForObject(
            "select attributes_json::text from ap_audit_event where tenant_id = ? "
                + "and action = 'APPROVAL_DESIGN_DRAFT_IMPORTED'",
            String.class,
            TARGET_TENANT
        );
        assertFalse(attributes.contains(SOURCE_TENANT));
        assertFalse(attributes.contains("bpmnArtifact"));
        assertFalse(attributes.contains("source-publisher"));
    }

    @Test
    void failedImportRollsBackDraftAuditIdempotencyAndAllImmutableArtifacts() {
        var envelope = transfer.exportDefinition(
            SOURCE_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION
        );
        jdbc.update(
            "delete from ap_form_package where tenant_id = ? and form_key = ?",
            TARGET_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY
        );
        Map<String, Integer> before = tableCounts();

        assertThrows(
            ApprovalArtifactTransferExceptions.FormPackageIncompatible.class,
            () -> transfer.importArtifact(importCommand(envelope, "rollback-key"))
        );

        assertEquals(before, tableCounts());
        assertEquals(0, tenantCount("ap_approval_design_draft", TARGET_TENANT));
        assertEquals(0, tenantCount("ap_approval_definition", TARGET_TENANT));
        assertEquals(0, tenantCount("ap_approval_release_package", TARGET_TENANT));
        assertEquals(0, tenantCount("ap_approval_release_deployment", TARGET_TENANT));
        assertEquals(0, tenantCount("ap_approval_effective_release", TARGET_TENANT));
        assertEquals(
            0,
            tenantCount("ap_approval_release_activation_history", TARGET_TENANT)
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from ap_command_idempotency where tenant_id = ?",
                Integer.class,
                TARGET_TENANT
            )
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from ap_audit_event where tenant_id = ?",
                Integer.class,
                TARGET_TENANT
            )
        );
    }

    @Test
    void sameIdempotencyKeyWithDifferentTargetContentConflictsWithoutExtraDraft() {
        var envelope = transfer.exportDefinition(
            SOURCE_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION
        );
        transfer.importArtifact(importCommand(envelope, "conflict-key"));
        ImportCommand different = new ImportCommand(
            context(TARGET_TENANT, "different-request", "conflict-key"),
            envelope,
            PurchasePaymentTemplate.DEFINITION_KEY,
            2,
            1,
            "Different imported name"
        );

        assertThrows(
            ApprovalArtifactTransferExceptions.ImportConflict.class,
            () -> transfer.importArtifact(different)
        );
        assertEquals(1, tenantCount("ap_approval_design_draft", TARGET_TENANT));
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from ap_audit_event where tenant_id = ? "
                    + "and action = 'APPROVAL_DESIGN_DRAFT_IMPORTED'",
                Integer.class,
                TARGET_TENANT
            )
        );
    }

    private void publishSourceRelease() {
        ApprovalDesignDraft draft = designService.createFromPurchasePaymentTemplate(
            new ApprovalDesignCommands.Create(
                context(SOURCE_TENANT, "source-create", "source-create-key"),
                PurchasePaymentTemplate.DEFINITION_KEY,
                "Source purchase payment",
                PurchasePaymentTemplate.PROCESS_VERSION,
                1
            )
        );
        var report = preflight.preflightPublication(
            new ApprovalReleasePreflightService.PublicationRequest(
                SOURCE_TENANT,
                draft.draftId(),
                draft.revision(),
                PurchasePaymentTemplate.DEFINITION_KEY,
                PurchasePaymentTemplate.PROCESS_VERSION,
                1,
                "default",
                ApprovalDefinitionSimulator.Scenario.empty()
            )
        );
        designService.publish(new ApprovalDesignCommands.Publish(
            context(SOURCE_TENANT, "source-publish", "source-publish-key"),
            draft.draftId(),
            draft.revision(),
            PurchasePaymentTemplate.PROCESS_VERSION,
            1,
            "default",
            report.preflightHash(),
            report.warningCodes(),
            ApprovalDefinitionSimulator.Scenario.empty()
        ));
    }

    private ApprovalArtifactTransferService transferService() {
        ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
        return new ApprovalArtifactTransferService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new DataSourceTransactionManager(dataSource),
                CLOCK
            ),
            new JdbcApprovalDesignDraftStore(dataSource, objectMapper),
            new JdbcApprovalDefinitionVersionStore(dataSource, objectMapper),
            new JdbcApprovalReleasePackageStore(dataSource),
            new JdbcApprovalFormPackageStore(dataSource),
            new JdbcApprovalFormStore(dataSource, objectMapper),
            new JdbcApprovalUiSchemaStore(dataSource, objectMapper),
            new JdbcAuditEventSink(dataSource, objectMapper),
            validator,
            new ApprovalDslCompiler(validator),
            new ApprovalDefinitionHasher(),
            new ApprovalReleasePackageHasher(),
            CLOCK,
            UUID::randomUUID
        );
    }

    private ApprovalDesignService designService() {
        ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
        return new ApprovalDesignService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new DataSourceTransactionManager(dataSource),
                CLOCK
            ),
            new JdbcApprovalDesignDraftStore(dataSource, objectMapper),
            new JdbcApprovalDefinitionVersionStore(dataSource, objectMapper),
            new JdbcApprovalCompiledArtifactStore(dataSource),
            new JdbcApprovalReleasePackageStore(dataSource),
            new JdbcApprovalFormPackageStore(dataSource),
            new JdbcApprovalFormStore(dataSource, objectMapper),
            new JdbcApprovalUiSchemaStore(dataSource, objectMapper),
            new JdbcAuditEventSink(dataSource, objectMapper),
            validator,
            new ApprovalDefinitionSimulator(validator),
            new ApprovalDslCompiler(validator),
            new ApprovalDefinitionHasher(),
            new ApprovalReleasePackageHasher(),
            CLOCK,
            UUID::randomUUID
        );
    }

    private ApprovalReleasePreflightService preflight() {
        ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
        return new ApprovalReleasePreflightService(
            new JdbcApprovalDesignDraftStore(dataSource, objectMapper),
            new JdbcApprovalDefinitionVersionStore(dataSource, objectMapper),
            new JdbcApprovalReleasePackageStore(dataSource),
            new JdbcApprovalReleaseDeploymentStore(dataSource),
            new JdbcApprovalFormPackageStore(dataSource),
            new JdbcApprovalFormStore(dataSource, objectMapper),
            new JdbcApprovalUiSchemaStore(dataSource, objectMapper),
            validator,
            new ApprovalDefinitionSimulator(validator),
            new ApprovalDslCompiler(validator),
            new ApprovalDefinitionHasher(),
            new ApprovalReleasePackageHasher()
        );
    }

    private void seedFormPackage(String tenantId, int packageVersion) {
        var form = PurchasePaymentTemplate.formDefinition();
        var uiSchema = PurchasePaymentTemplate.uiSchemaDefinition();
        String formHash = new FormSchemaHasher().hash(form);
        String uiHash = new UiSchemaHasher().hash(uiSchema);
        new JdbcApprovalFormStore(dataSource, objectMapper).save(new PublishedForm(
            tenantId,
            form,
            formHash,
            "form-publisher",
            NOW
        ));
        new JdbcApprovalUiSchemaStore(dataSource, objectMapper).save(
            new PublishedUiSchema(
                tenantId,
                uiSchema,
                uiHash,
                "form-publisher",
                NOW
            )
        );
        UUID formDraftId = UUID.randomUUID();
        jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name, form_version,
                ui_schema_version, form_schema_json, ui_schema_json,
                revision, status, created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
                      1, 'DRAFT', 'form-publisher', 'form-publisher', ?, ?)
            """,
            tenantId,
            formDraftId,
            form.formKey(),
            form.name(),
            form.version(),
            uiSchema.uiSchemaVersion(),
            writeJson(form),
            writeJson(uiSchema),
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
        String packageHash = new FormPackageHasher().hash(
            form.formKey(),
            packageVersion,
            form.version(),
            formHash,
            uiSchema.uiSchemaVersion(),
            uiHash
        );
        new JdbcApprovalFormPackageStore(dataSource).save(new FormPackage(
            tenantId,
            form.formKey(),
            packageVersion,
            form.version(),
            formHash,
            uiSchema.uiSchemaVersion(),
            uiHash,
            packageHash,
            formDraftId,
            "form-publisher",
            NOW
        ));
        jdbc.update(
            "update ap_form_design_draft set status = 'PUBLISHED', "
                + "published_package_version = ? where tenant_id = ? and draft_id = ?",
            packageVersion,
            tenantId,
            formDraftId
        );
    }

    private ImportCommand importCommand(
        ApprovalArtifactTransferService.TransferEnvelope envelope,
        String idempotencyKey
    ) {
        return new ImportCommand(
            context(TARGET_TENANT, "request-" + idempotencyKey, idempotencyKey),
            envelope,
            PurchasePaymentTemplate.DEFINITION_KEY,
            2,
            1,
            "Imported purchase payment"
        );
    }

    private Map<String, Integer> tableCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List.of(
            "ap_approval_release_activation_history",
            "ap_approval_effective_release",
            "ap_approval_release_deployment",
            "ap_approval_release_package",
            "ap_approval_compiled_artifact",
            "ap_approval_definition",
            "ap_approval_design_draft",
            "ap_form_package",
            "ap_form_design_draft",
            "ap_form_ui_schema",
            "ap_form_definition",
            "ap_command_idempotency",
            "ap_audit_event"
        ).forEach(table -> counts.put(table, count(table)));
        return Map.copyOf(counts);
    }

    private int tenantCount(String table, String tenantId) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id = ?",
            Integer.class,
            tenantId
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static RequestContext context(
        String tenantId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            tenantId,
            "operator",
            requestId,
            idempotencyKey,
            requestId + "-trace"
        );
    }

    private static String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("unable to encode test JSON", exception);
        }
    }
}
