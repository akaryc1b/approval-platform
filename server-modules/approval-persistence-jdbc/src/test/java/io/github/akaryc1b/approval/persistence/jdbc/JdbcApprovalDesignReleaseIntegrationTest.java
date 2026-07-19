package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.akaryc1b.approval.application.ApprovalDefinitionHasher;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands;
import io.github.akaryc1b.approval.application.ApprovalDesignExceptions;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.FormPackageHasher;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalDesignReleaseIntegrationTest {

    private static final String TENANT = "tenant-approval-design";
    private static final String OTHER_TENANT = "tenant-other";
    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
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
    private JdbcApprovalDesignDraftStore drafts;
    private ApprovalDesignService service;
    private ApprovalReleasePreflightService preflight;

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
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
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
        seedFormPackage(TENANT, 1);
        drafts = new JdbcApprovalDesignDraftStore(dataSource, objectMapper);
        JdbcApprovalReleasePackageStore releaseStore =
            new JdbcApprovalReleasePackageStore(dataSource);
        service = service(releaseStore);
        preflight = preflight(releaseStore);
    }

    @Test
    void persistsTenantIsolationCasArchiveJsonAndExactPackageReference() {
        ApprovalDesignDraft first = service.createFromPurchasePaymentTemplate(
            new ApprovalDesignCommands.Create(
                context(TENANT, "create-1", "create-key"),
                PurchasePaymentTemplate.DEFINITION_KEY,
                "Purchase payment",
                PurchasePaymentTemplate.PROCESS_VERSION,
                1
            )
        );
        ApprovalDesignDraft replay = service.createFromPurchasePaymentTemplate(
            new ApprovalDesignCommands.Create(
                context(TENANT, "create-2", "create-key"),
                PurchasePaymentTemplate.DEFINITION_KEY,
                "Purchase payment",
                PurchasePaymentTemplate.PROCESS_VERSION,
                1
            )
        );

        assertEquals(first.draftId(), replay.draftId());
        assertTrue(drafts.find(OTHER_TENANT, first.draftId()).isEmpty());
        assertEquals(
            first.definition(),
            drafts.find(TENANT, first.draftId()).orElseThrow().definition()
        );

        ApprovalDesignDraft updated = service.update(new ApprovalDesignCommands.Update(
            context(TENANT, "update-1", "update-key"),
            first.draftId(),
            first.revision(),
            "Purchase payment revised",
            first.definition(),
            1,
            ApprovalDesignCommands.SaveMode.EXPLICIT
        ));
        assertEquals(2, updated.revision());
        assertThrows(
            ApprovalDesignExceptions.DraftRevisionConflict.class,
            () -> service.update(new ApprovalDesignCommands.Update(
                context(TENANT, "update-stale", "update-stale-key"),
                first.draftId(),
                first.revision(),
                "Stale update",
                first.definition(),
                1,
                ApprovalDesignCommands.SaveMode.EXPLICIT
            ))
        );

        ApprovalDesignDraft archived = service.archive(new ApprovalDesignCommands.Revision(
            context(TENANT, "archive-1", "archive-key"),
            first.draftId(),
            updated.revision()
        ));
        assertEquals(ApprovalDesignDraft.Status.ARCHIVED, archived.status());
        assertThrows(
            ApprovalDesignExceptions.DraftStateConflict.class,
            () -> service.update(new ApprovalDesignCommands.Update(
                context(TENANT, "update-archived", "update-archived-key"),
                archived.draftId(),
                archived.revision(),
                archived.name(),
                archived.definition(),
                1,
                ApprovalDesignCommands.SaveMode.EXPLICIT
            ))
        );

        ApprovalDesignDraft crossTenant = new ApprovalDesignDraft(
            UUID.randomUUID(),
            OTHER_TENANT,
            first.definitionKey(),
            first.name(),
            first.definition(),
            first.formPackage(),
            null,
            1,
            ApprovalDesignDraft.Status.DRAFT,
            null,
            null,
            "operator",
            "operator",
            NOW,
            NOW
        );
        assertThrows(DataIntegrityViolationException.class, () -> drafts.save(crossTenant));
    }

    @Test
    void publishesAtomicallySupportsRequestAndSemanticReplayAndStaysImmutable() {
        ApprovalDesignDraft draft = createDraft("publish-create", "publish-create-key");
        var validation = service.validate(new ApprovalDesignCommands.Revision(
            context(TENANT, "validate", "validate-key"),
            draft.draftId(),
            draft.revision()
        ));
        assertTrue(validation.valid());

        var report = publicationPreflight(
            draft.draftId(),
            validation.revision(),
            PurchasePaymentTemplate.PROCESS_VERSION,
            1
        );
        var published = service.publish(publishCommand(
            context(TENANT, "publish", "publish-key"),
            draft.draftId(),
            validation.revision(),
            PurchasePaymentTemplate.PROCESS_VERSION,
            1,
            report
        ));
        var requestReplay = service.publish(publishCommand(
            context(TENANT, "publish-retry", "publish-key"),
            draft.draftId(),
            validation.revision(),
            PurchasePaymentTemplate.PROCESS_VERSION,
            1,
            report
        ));
        var semanticReplay = service.publish(publishCommand(
            context(TENANT, "publish-semantic", "publish-semantic-key"),
            draft.draftId(),
            validation.revision(),
            PurchasePaymentTemplate.PROCESS_VERSION,
            1,
            report
        ));

        assertFalse(published.replayedExistingRelease());
        assertEquals(published, requestReplay);
        assertTrue(semanticReplay.replayedExistingRelease());
        assertEquals(
            published.releasePackage().packageHash(),
            semanticReplay.releasePackage().packageHash()
        );
        assertEquals(1, count("ap_approval_definition"));
        assertEquals(1, count("ap_approval_compiled_artifact"));
        assertEquals(1, count("ap_approval_release_package"));
        assertEquals(
            ApprovalDesignDraft.Status.PUBLISHED,
            drafts.find(TENANT, draft.draftId()).orElseThrow().status()
        );

        assertThrows(
            ApprovalDesignExceptions.ReleaseVersionConflict.class,
            () -> service.publish(publishCommand(
                context(TENANT, "publish-conflict", "publish-conflict-key"),
                draft.draftId(),
                validation.revision(),
                PurchasePaymentTemplate.PROCESS_VERSION,
                2,
                report
            ))
        );

        ApprovalReleasePackage changed = changedPackage(published.releasePackage());
        assertThrows(
            DataIntegrityViolationException.class,
            () -> new JdbcApprovalReleasePackageStore(dataSource).save(changed)
        );
    }

    @Test
    void requiresExactWarningAcknowledgementAndRejectsStalePreflightHash() {
        ApprovalDesignDraft draft = createDraft("preflight-create", "preflight-create-key");
        var report = publicationPreflight(
            draft.draftId(),
            draft.revision(),
            PurchasePaymentTemplate.PROCESS_VERSION,
            1
        );
        assertFalse(report.warningCodes().isEmpty());

        assertThrows(
            ApprovalDesignExceptions.WarningAcknowledgementRequired.class,
            () -> service.publish(new ApprovalDesignCommands.Publish(
                context(TENANT, "missing-warning", "missing-warning-key"),
                draft.draftId(),
                draft.revision(),
                PurchasePaymentTemplate.PROCESS_VERSION,
                1,
                "default",
                report.preflightHash(),
                List.of(),
                ApprovalDefinitionSimulator.Scenario.empty()
            ))
        );
        assertEquals(0, count("ap_approval_release_package"));

        ApprovalDesignDraft changed = service.update(new ApprovalDesignCommands.Update(
            context(TENANT, "preflight-change", "preflight-change-key"),
            draft.draftId(),
            draft.revision(),
            "Changed after preflight",
            draft.definition(),
            1,
            ApprovalDesignCommands.SaveMode.EXPLICIT
        ));
        assertThrows(
            ApprovalDesignExceptions.PreflightConflict.class,
            () -> service.publish(publishCommand(
                context(TENANT, "stale-preflight", "stale-preflight-key"),
                changed.draftId(),
                draft.revision(),
                PurchasePaymentTemplate.PROCESS_VERSION,
                1,
                report
            ))
        );
        assertEquals(0, count("ap_approval_definition"));
        assertEquals(0, count("ap_approval_compiled_artifact"));
        assertEquals(0, count("ap_approval_release_package"));
    }

    @Test
    void rollsBackDefinitionArtifactAndPackageWhenPublicationFails() {
        ApprovalDesignDraft draft = createDraft("rollback-create", "rollback-create-key");
        var validation = service.validate(new ApprovalDesignCommands.Revision(
            context(TENANT, "rollback-validate", "rollback-validate-key"),
            draft.draftId(),
            draft.revision()
        ));
        ApprovalReleasePackageStore delegate = new JdbcApprovalReleasePackageStore(dataSource);
        ApprovalReleasePackageStore failing = new FailingReleaseStore(delegate);
        ApprovalDesignService failingService = service(failing);
        var report = publicationPreflight(
            draft.draftId(),
            validation.revision(),
            PurchasePaymentTemplate.PROCESS_VERSION,
            1
        );

        assertThrows(
            IllegalStateException.class,
            () -> failingService.publish(publishCommand(
                context(TENANT, "rollback-publish", "rollback-publish-key"),
                draft.draftId(),
                validation.revision(),
                PurchasePaymentTemplate.PROCESS_VERSION,
                1,
                report
            ))
        );

        assertEquals(0, count("ap_approval_definition"));
        assertEquals(0, count("ap_approval_compiled_artifact"));
        assertEquals(0, count("ap_approval_release_package"));
        ApprovalDesignDraft stored = drafts.find(TENANT, draft.draftId()).orElseThrow();
        assertNotEquals(ApprovalDesignDraft.Status.PUBLISHED, stored.status());
    }

    @Test
    void rollsBackInvalidDslBeforeAnyImmutableArtifactIsWritten() {
        ApprovalDesignDraft draft = createDraft("invalid-create", "invalid-create-key");
        ApprovalDefinition invalid = new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            "Invalid",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "missing"),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );
        ApprovalDesignDraft updated = service.update(new ApprovalDesignCommands.Update(
            context(TENANT, "invalid-update", "invalid-update-key"),
            draft.draftId(),
            draft.revision(),
            "Invalid",
            invalid,
            1,
            ApprovalDesignCommands.SaveMode.EXPLICIT
        ));

        var report = publicationPreflight(
            updated.draftId(),
            updated.revision(),
            PurchasePaymentTemplate.PROCESS_VERSION,
            1
        );
        assertThrows(
            ApprovalDesignExceptions.PreflightConflict.class,
            () -> service.publish(publishCommand(
                context(TENANT, "invalid-publish", "invalid-publish-key"),
                updated.draftId(),
                updated.revision(),
                PurchasePaymentTemplate.PROCESS_VERSION,
                1,
                report
            ))
        );
        assertEquals(0, count("ap_approval_definition"));
        assertEquals(0, count("ap_approval_compiled_artifact"));
        assertEquals(0, count("ap_approval_release_package"));
    }

    private ApprovalReleasePreflightService.PreflightReport publicationPreflight(
        UUID draftId,
        long revision,
        int definitionVersion,
        int releaseVersion
    ) {
        return preflight.preflightPublication(
            new ApprovalReleasePreflightService.PublicationRequest(
                TENANT,
                draftId,
                revision,
                PurchasePaymentTemplate.DEFINITION_KEY,
                definitionVersion,
                releaseVersion,
                "default",
                ApprovalDefinitionSimulator.Scenario.empty()
            )
        );
    }

    private static ApprovalDesignCommands.Publish publishCommand(
        RequestContext context,
        UUID draftId,
        long revision,
        int definitionVersion,
        int releaseVersion,
        ApprovalReleasePreflightService.PreflightReport report
    ) {
        return new ApprovalDesignCommands.Publish(
            context,
            draftId,
            revision,
            definitionVersion,
            releaseVersion,
            "default",
            report.preflightHash(),
            report.warningCodes(),
            ApprovalDefinitionSimulator.Scenario.empty()
        );
    }

    private ApprovalReleasePreflightService preflight(
        ApprovalReleasePackageStore releaseStore
    ) {
        ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
        return new ApprovalReleasePreflightService(
            new JdbcApprovalDesignDraftStore(dataSource, objectMapper),
            new JdbcApprovalDefinitionVersionStore(dataSource, objectMapper),
            releaseStore,
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

    private ApprovalDesignDraft createDraft(String requestId, String idempotencyKey) {
        return service.createFromPurchasePaymentTemplate(new ApprovalDesignCommands.Create(
            context(TENANT, requestId, idempotencyKey),
            PurchasePaymentTemplate.DEFINITION_KEY,
            "Purchase payment",
            PurchasePaymentTemplate.PROCESS_VERSION,
            1
        ));
    }

    private ApprovalDesignService service(ApprovalReleasePackageStore releaseStore) {
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
            releaseStore,
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

    private void seedFormPackage(String tenantId, int packageVersion) {
        var form = PurchasePaymentTemplate.formDefinition();
        var uiSchema = PurchasePaymentTemplate.uiSchemaDefinition();
        String formHash = new FormSchemaHasher().hash(form);
        String uiHash = new UiSchemaHasher().hash(uiSchema);
        JdbcApprovalFormStore formStore = new JdbcApprovalFormStore(dataSource, objectMapper);
        JdbcApprovalUiSchemaStore uiStore = new JdbcApprovalUiSchemaStore(
            dataSource,
            objectMapper
        );
        formStore.save(new PublishedForm(tenantId, form, formHash, "publisher", NOW));
        uiStore.save(new PublishedUiSchema(tenantId, uiSchema, uiHash, "publisher", NOW));

        UUID formDraftId = UUID.randomUUID();
        jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name, form_version,
                ui_schema_version, form_schema_json, ui_schema_json,
                revision, status, created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
                      1, 'DRAFT', 'publisher', 'publisher', ?, ?)
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
        FormPackage formPackage = new FormPackage(
            tenantId,
            form.formKey(),
            packageVersion,
            form.version(),
            formHash,
            uiSchema.uiSchemaVersion(),
            uiHash,
            packageHash,
            formDraftId,
            "publisher",
            NOW
        );
        new JdbcApprovalFormPackageStore(dataSource).save(formPackage);
        jdbc.update(
            """
            update ap_form_design_draft
            set status = 'PUBLISHED', published_package_version = ?
            where tenant_id = ? and draft_id = ?
            """,
            packageVersion,
            tenantId,
            formDraftId
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

    private static ApprovalReleasePackage changedPackage(ApprovalReleasePackage source) {
        return new ApprovalReleasePackage(
            source.tenantId(),
            source.definitionKey(),
            source.releaseVersion(),
            source.definitionVersion(),
            source.definitionHash(),
            source.formPackageVersion(),
            source.formPackageHash(),
            source.formVersion(),
            source.formHash(),
            source.uiSchemaVersion(),
            source.uiSchemaHash(),
            source.compilerVersion(),
            source.bpmnResourceName(),
            source.bpmnArtifact(),
            source.compiledArtifactHash(),
            source.bpmnHash(),
            source.dmnArtifact(),
            source.dmnHash(),
            source.deploymentMetadataHash(),
            "0".repeat(64),
            UUID.randomUUID(),
            source.publishedBy(),
            source.publishedAt()
        );
    }

    private record FailingReleaseStore(ApprovalReleasePackageStore delegate)
        implements ApprovalReleasePackageStore {

        @Override
        public void lockVersion(String tenantId, String definitionKey, int releaseVersion) {
            delegate.lockVersion(tenantId, definitionKey, releaseVersion);
        }

        @Override
        public java.util.Optional<ApprovalReleasePackage> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return delegate.find(tenantId, definitionKey, releaseVersion);
        }

        @Override
        public java.util.Optional<ApprovalReleasePackage> findLatest(
            String tenantId,
            String definitionKey
        ) {
            return delegate.findLatest(tenantId, definitionKey);
        }

        @Override
        public java.util.Optional<ApprovalReleasePackage> findByDraft(
            String tenantId,
            UUID draftId
        ) {
            return delegate.findByDraft(tenantId, draftId);
        }

        @Override
        public ReleasePage findReleases(ReleaseCriteria criteria) {
            return delegate.findReleases(criteria);
        }

        @Override
        public void save(ApprovalReleasePackage releasePackage) {
            delegate.save(releasePackage);
            throw new IllegalStateException("forced package failure");
        }
    }
}
