package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.CopyPublishedCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.CreateCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.PublishCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.RevisionCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.SaveMode;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.UpdateCommand;
import io.github.akaryc1b.approval.application.FormDefaultValueResolver;
import io.github.akaryc1b.approval.application.FormDefinitionValidator;
import io.github.akaryc1b.approval.application.FormPackageHasher;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.UiSchemaDefinitionValidator;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDesignDraft;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalFormDesignIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_form_design_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private ObjectMapper objectMapper;
    private Clock clock;
    private JdbcTemplate jdbc;
    private JdbcApprovalFormStore forms;
    private JdbcApprovalUiSchemaStore uiSchemas;
    private JdbcApprovalFormDesignDraftStore drafts;
    private JdbcApprovalFormPackageStore packages;
    private JdbcIdempotencyGuard idempotency;
    private ApprovalFormDesignService service;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_form_package, ap_form_design_draft, ap_form_submission_revision,
                ap_form_submission, ap_form_ui_schema, ap_form_definition,
                ap_audit_event, ap_command_idempotency
            cascade
            """);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        forms = new JdbcApprovalFormStore(dataSource, objectMapper);
        uiSchemas = new JdbcApprovalUiSchemaStore(dataSource, objectMapper);
        drafts = new JdbcApprovalFormDesignDraftStore(dataSource, objectMapper);
        packages = new JdbcApprovalFormPackageStore(dataSource);
        idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            new JdbcTransactionManager(dataSource),
            clock
        );
        service = service(packages);
    }

    @Test
    void createsCopiesUpdatesWithCasAndIsolatesTenants() {
        CreateCommand create = createCommand("tenant-a", "create-1", "key-1", "draft-form");
        FormDesignDraft created = service.createBlank(create);
        FormDesignDraft replayed = service.createBlank(create);
        assertEquals(created.draftId(), replayed.draftId());
        assertEquals(1, countRows("ap_form_design_draft"));
        assertTrue(service.find("tenant-a", created.draftId()).isPresent());
        assertTrue(service.find("tenant-b", created.draftId()).isEmpty());

        FormDefinition form = templateForm("draft-form", 1, "Edited draft");
        UiSchemaDefinition uiSchema = templateUi("draft-form", 1, 1, "Edited draft UI");
        FormDesignDraft updated = service.update(new UpdateCommand(
            context("tenant-a", "update-1", "key-2"),
            created.draftId(),
            1,
            "Edited draft",
            form,
            uiSchema,
            SaveMode.AUTO_SAVE
        ));
        assertEquals(2, updated.revision());
        assertEquals(FormDesignDraft.Status.DRAFT, updated.status());
        assertThrows(
            ApprovalFormDesignService.DraftRevisionConflictException.class,
            () -> service.update(new UpdateCommand(
                context("tenant-a", "update-stale", "key-3"),
                created.draftId(),
                1,
                "Stale draft",
                form,
                uiSchema,
                SaveMode.AUTO_SAVE
            ))
        );
        assertThrows(
            ApprovalFormDesignService.DraftNotFoundException.class,
            () -> service.update(new UpdateCommand(
                context("tenant-b", "cross-tenant", "key-4"),
                created.draftId(),
                2,
                "Cross tenant",
                form,
                uiSchema,
                SaveMode.AUTO_SAVE
            ))
        );

        seedPublished("tenant-a", "published-form", 1, 1);
        FormDesignDraft copied = service.createFromPublished(new CopyPublishedCommand(
            context("tenant-a", "copy-1", "copy-key"),
            "published-form",
            1,
            1,
            2,
            2,
            "Published copy"
        ));
        assertEquals(1, copied.sourceFormVersion());
        assertEquals(1, copied.sourceUiSchemaVersion());
        assertEquals(2, copied.formDefinition().version());
        assertEquals(2, copied.uiSchemaDefinition().version());
    }

    @Test
    void validatesAndPublishesIdempotentImmutablePackage() {
        FormDesignDraft draft = service.createFromPurchasePaymentTemplate(
            createCommand("tenant-a", "create-package", "create-package-key", "package-form")
        );
        var validation = service.validate(new RevisionCommand(
            context("tenant-a", "validate-package", "validate-package-key"),
            draft.draftId(),
            1
        ));
        assertTrue(validation.valid());
        assertEquals(2, validation.revision());
        assertEquals(FormDesignDraft.Status.VALIDATED, validation.status());

        PublishCommand publish = new PublishCommand(
            context("tenant-a", "publish-package", "publish-package-key"),
            draft.draftId(),
            2,
            1
        );
        var published = service.publish(publish);
        var idempotentReplay = service.publish(publish);
        assertEquals(published.packageHash(), idempotentReplay.packageHash());
        assertFalse(idempotentReplay.replayedExistingPackage());
        assertEquals(1, countRows("ap_form_package"));
        assertEquals(1, countRows("ap_form_definition"));
        assertEquals(1, countRows("ap_form_ui_schema"));

        var semanticReplay = service.publish(new PublishCommand(
            context("tenant-a", "publish-package-retry", "publish-package-key-2"),
            draft.draftId(),
            2,
            1
        ));
        assertTrue(semanticReplay.replayedExistingPackage());
        assertEquals(published.packageHash(), semanticReplay.packageHash());
        FormDesignDraft publishedDraft = service.find("tenant-a", draft.draftId()).orElseThrow();
        assertEquals(FormDesignDraft.Status.PUBLISHED, publishedDraft.status());
        assertEquals(3, publishedDraft.revision());
        assertThrows(
            ApprovalFormDesignService.DraftStateConflictException.class,
            () -> service.update(new UpdateCommand(
                context("tenant-a", "mutate-published", "mutate-published-key"),
                draft.draftId(),
                3,
                publishedDraft.name(),
                publishedDraft.formDefinition(),
                publishedDraft.uiSchemaDefinition(),
                SaveMode.EXPLICIT
            ))
        );
    }

    @Test
    void rejectsSamePackageVersionWithDifferentContent() {
        FormDesignDraft first = validatedTemplate("conflict-form", "first");
        service.publish(new PublishCommand(
            context("tenant-a", "publish-first", "publish-first-key"),
            first.draftId(),
            2,
            1
        ));

        FormDesignDraft second = service.createFromPurchasePaymentTemplate(
            createCommand("tenant-a", "create-second", "create-second-key", "conflict-form")
        );
        FormDefinition changed = new FormDefinition(
            second.formDefinition().schemaVersion(),
            second.formKey(),
            second.formDefinition().version(),
            "Changed content",
            second.formDefinition().fields()
        );
        UiSchemaDefinition changedUi = templateUi(
            second.formKey(),
            changed.version(),
            second.uiSchemaDefinition().version(),
            "Changed content UI"
        );
        FormDesignDraft changedDraft = service.update(new UpdateCommand(
            context("tenant-a", "change-second", "change-second-key"),
            second.draftId(),
            1,
            "Changed content",
            changed,
            changedUi,
            SaveMode.EXPLICIT
        ));
        service.validate(new RevisionCommand(
            context("tenant-a", "validate-second", "validate-second-key"),
            changedDraft.draftId(),
            2
        ));
        assertThrows(
            ApprovalFormDesignService.PackageVersionConflictException.class,
            () -> service.publish(new PublishCommand(
                context("tenant-a", "publish-second", "publish-second-key"),
                changedDraft.draftId(),
                3,
                1
            ))
        );
        assertEquals(1, countRows("ap_form_package"));
    }

    @Test
    void rollsBackFormAndUiWhenPackageSaveFails() {
        FormDesignDraft draft = validatedTemplate("rollback-form", "rollback");
        ApprovalFormPackageStore failingPackages = new ApprovalFormPackageStore() {
            @Override
            public void lockVersion(String tenantId, String formKey, int packageVersion) {
                packages.lockVersion(tenantId, formKey, packageVersion);
            }

            @Override
            public Optional<FormPackage> find(
                String tenantId,
                String formKey,
                int packageVersion
            ) {
                return packages.find(tenantId, formKey, packageVersion);
            }

            @Override
            public Optional<FormPackage> findByDraft(String tenantId, UUID draftId) {
                return packages.findByDraft(tenantId, draftId);
            }

            @Override
            public void save(FormPackage formPackage) {
                throw new IllegalStateException("simulated package persistence failure");
            }
        };
        ApprovalFormDesignService failingService = service(failingPackages);

        assertThrows(
            IllegalStateException.class,
            () -> failingService.publish(new PublishCommand(
                context("tenant-a", "publish-rollback", "publish-rollback-key"),
                draft.draftId(),
                2,
                1
            ))
        );
        assertTrue(forms.find("tenant-a", "rollback-form", 1).isEmpty());
        assertTrue(uiSchemas.find("tenant-a", "rollback-form", 1, 1).isEmpty());
        assertEquals(0, countRows("ap_form_package"));
        FormDesignDraft retained = service.find("tenant-a", draft.draftId()).orElseThrow();
        assertEquals(FormDesignDraft.Status.VALIDATED, retained.status());
        assertEquals(2, retained.revision());
    }

    private ApprovalFormDesignService service(ApprovalFormPackageStore packageStore) {
        return new ApprovalFormDesignService(
            idempotency,
            drafts,
            packageStore,
            forms,
            uiSchemas,
            new JdbcAuditEventSink(dataSource, objectMapper),
            new FormDefinitionValidator(),
            new UiSchemaDefinitionValidator(),
            new FormSchemaHasher(),
            new UiSchemaHasher(),
            new FormPackageHasher(),
            new FormDefaultValueResolver(clock),
            clock,
            UUID::randomUUID
        );
    }

    private FormDesignDraft validatedTemplate(String formKey, String keyPrefix) {
        FormDesignDraft draft = service.createFromPurchasePaymentTemplate(
            createCommand(
                "tenant-a",
                keyPrefix + "-create-request",
                keyPrefix + "-create-key",
                formKey
            )
        );
        service.validate(new RevisionCommand(
            context(
                "tenant-a",
                keyPrefix + "-validate-request",
                keyPrefix + "-validate-key"
            ),
            draft.draftId(),
            1
        ));
        return service.find("tenant-a", draft.draftId()).orElseThrow();
    }

    private void seedPublished(
        String tenantId,
        String formKey,
        int formVersion,
        int uiSchemaVersion
    ) {
        FormDefinition definition = templateForm(formKey, formVersion, "Published form");
        UiSchemaDefinition uiSchema = templateUi(
            formKey,
            formVersion,
            uiSchemaVersion,
            "Published UI"
        );
        forms.save(new PublishedForm(
            tenantId,
            definition,
            new FormSchemaHasher().hash(definition),
            "publisher",
            NOW
        ));
        uiSchemas.save(new PublishedUiSchema(
            tenantId,
            uiSchema,
            new UiSchemaHasher().hash(uiSchema),
            "publisher",
            NOW
        ));
    }

    private static FormDefinition templateForm(String formKey, int version, String name) {
        FormDefinition source = PurchasePaymentTemplate.formDefinition();
        return new FormDefinition(
            source.schemaVersion(),
            formKey,
            version,
            name,
            source.fields()
        );
    }

    private static UiSchemaDefinition templateUi(
        String formKey,
        int formVersion,
        int uiSchemaVersion,
        String name
    ) {
        UiSchemaDefinition source = PurchasePaymentTemplate.uiSchemaDefinition();
        return new UiSchemaDefinition(
            source.schemaVersion(),
            formKey,
            formVersion,
            uiSchemaVersion,
            name,
            source.sections(),
            source.nodePermissions()
        );
    }

    private static CreateCommand createCommand(
        String tenantId,
        String requestId,
        String idempotencyKey,
        String formKey
    ) {
        return new CreateCommand(
            context(tenantId, requestId, idempotencyKey),
            formKey,
            "Form design",
            1,
            1
        );
    }

    private static RequestContext context(
        String tenantId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            tenantId,
            "designer-1",
            requestId,
            idempotencyKey,
            "trace-form-design"
        );
    }

    private int countRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
