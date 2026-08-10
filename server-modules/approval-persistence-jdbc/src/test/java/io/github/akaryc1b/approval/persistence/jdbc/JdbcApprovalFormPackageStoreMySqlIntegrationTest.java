package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
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
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore.DraftCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore.DraftPage;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalFormPackageStoreMySqlIntegrationTest {

    private static final String TENANT = "Tenant-Package-MySQL";
    private static final String OTHER_TENANT = "tenant-package-other";
    private static final Instant NOW = Instant.parse("2026-08-10T05:06:07.999999500Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_form_package")
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

    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private TransactionTemplate transactions;
    private Clock clock;
    private ApprovalFormDesignDraftStore drafts;
    private ApprovalFormPackageStore packages;
    private ApprovalFormStore forms;
    private ApprovalUiSchemaStore uiSchemas;
    private FormSchemaHasher formHasher;
    private UiSchemaHasher uiHasher;
    private FormPackageHasher packageHasher;
    private ApprovalFormDesignService service;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.update(
            """
            update ap_form_design_draft
            set published_package_version = null
            where published_package_version is not null
            """
        );
        jdbc.update("delete from ap_form_package");
        jdbc.update("delete from ap_form_design_draft");
        jdbc.update("delete from ap_form_ui_schema");
        jdbc.update("delete from ap_form_definition");
        jdbc.update("delete from ap_audit_event");
        jdbc.update("delete from ap_audit_chain_state");
        jdbc.update("delete from ap_command_idempotency");

        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        formHasher = new FormSchemaHasher();
        uiHasher = new UiSchemaHasher();
        packageHasher = new FormPackageHasher();
        drafts = JdbcApprovalFormDesignDraftStoreFactory.create(dataSource, objectMapper);
        packages = JdbcApprovalFormPackageStoreFactory.create(dataSource);
        forms = JdbcApprovalFormStoreFactory.create(dataSource, objectMapper);
        uiSchemas = JdbcApprovalUiSchemaStoreFactory.create(dataSource, objectMapper);
        service = service(drafts, packages);
    }

    @Test
    void publishesRoundTripsImmutablePackageAndReplaysByDraft() {
        assertInstanceOf(JdbcMySqlApprovalFormPackageStore.class, packages);
        FormDesignDraft draft = validatedTemplate(TENANT, "package-form", "package");
        PublishCommand publish = new PublishCommand(
            context(TENANT, "publish-package", "publish-package-key"),
            draft.draftId(),
            2,
            1
        );

        var published = service.publish(publish);
        var idempotentReplay = service.publish(publish);
        var semanticReplay = service.publish(new PublishCommand(
            context(TENANT, "publish-package-retry", "publish-package-key-2"),
            draft.draftId(),
            2,
            1
        ));

        assertEquals(published.packageHash(), idempotentReplay.packageHash());
        assertFalse(idempotentReplay.replayedExistingPackage());
        assertTrue(semanticReplay.replayedExistingPackage());
        assertEquals(published.packageHash(), semanticReplay.packageHash());
        assertEquals(1, countRows("ap_form_package"));
        assertEquals(1, countRows("ap_form_definition"));
        assertEquals(1, countRows("ap_form_ui_schema"));
        assertEquals(1, countAudit("FORM_PACKAGE_PUBLISHED"));

        FormPackage persisted = packages.find(TENANT, "package-form", 1).orElseThrow();
        FormPackage byDraft = packages.findByDraft(TENANT, draft.draftId()).orElseThrow();
        assertEquals(persisted, byDraft);
        assertEquals(
            packageHasher.hash(
                persisted.formKey(),
                persisted.packageVersion(),
                persisted.formVersion(),
                persisted.formHash(),
                persisted.uiSchemaVersion(),
                persisted.uiSchemaHash()
            ),
            persisted.packageHash()
        );
        assertEquals(
            forms.find(TENANT, persisted.formKey(), persisted.formVersion())
                .orElseThrow().contentHash(),
            persisted.formHash()
        );
        assertEquals(
            uiSchemas.find(
                TENANT,
                persisted.formKey(),
                persisted.formVersion(),
                persisted.uiSchemaVersion()
            ).orElseThrow().contentHash(),
            persisted.uiSchemaHash()
        );
        assertEquals(AuditHashCanonicalizer.canonicalInstant(NOW), persisted.publishedAt());
        assertTrue(packages.find(TENANT.toLowerCase(), "package-form", 1).isEmpty());
        assertTrue(packages.findByDraft(TENANT.toLowerCase(), draft.draftId()).isEmpty());
        assertEquals(
            0,
            jdbc.queryForObject(
                """
                select microsecond(published_at)
                from ap_form_package
                where tenant_id = ? and form_key = ? and package_version = ?
                """,
                Integer.class,
                TENANT,
                "package-form",
                1
            )
        );

        assertThrows(DataAccessException.class, () -> packages.save(persisted));
    }

    @Test
    void versionLockRequiresTransactionBlocksCompetitorAndReleasesAfterRollback()
        throws Exception {
        assertThrows(
            IllegalStateException.class,
            () -> packages.lockVersion(TENANT, "locked-form", 1)
        );

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> transactions.execute(status -> {
                packages.lockVersion(TENANT, "locked-form", 1);
                locked.countDown();
                await(release);
                throw new RollbackMarker();
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(locked);
                secondAttempting.countDown();
                return transactions.execute(status -> {
                    packages.lockVersion(TENANT, "locked-form", 1);
                    return Boolean.TRUE;
                });
            });

            assertTrue(locked.await(10, TimeUnit.SECONDS));
            assertTrue(secondAttempting.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> second.get(500, TimeUnit.MILLISECONDS)
            );
            release.countDown();
            ExecutionException rollback = assertThrows(
                ExecutionException.class,
                () -> first.get(20, TimeUnit.SECONDS)
            );
            assertInstanceOf(RollbackMarker.class, rollback.getCause());
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void samePackageVersionRejectsDifferentContentButRemainsTenantScoped() {
        FormDesignDraft first = validatedTemplate(TENANT, "conflict-form", "first");
        service.publish(new PublishCommand(
            context(TENANT, "publish-first", "publish-first-key"),
            first.draftId(),
            2,
            1
        ));

        FormDesignDraft second = service.createFromPurchasePaymentTemplate(
            createCommand(TENANT, "create-second", "create-second-key", "conflict-form")
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
            context(TENANT, "change-second", "change-second-key"),
            second.draftId(),
            1,
            "Changed content",
            changed,
            changedUi,
            SaveMode.EXPLICIT
        ));
        service.validate(new RevisionCommand(
            context(TENANT, "validate-second", "validate-second-key"),
            changedDraft.draftId(),
            2
        ));
        assertThrows(
            ApprovalFormDesignService.PackageVersionConflictException.class,
            () -> service.publish(new PublishCommand(
                context(TENANT, "publish-second", "publish-second-key"),
                changedDraft.draftId(),
                3,
                1
            ))
        );
        assertEquals(1, countRows("ap_form_package"));

        FormDesignDraft otherTenant = validatedTemplate(
            OTHER_TENANT,
            "conflict-form",
            "other"
        );
        service.publish(new PublishCommand(
            context(OTHER_TENANT, "publish-other", "publish-other-key"),
            otherTenant.draftId(),
            2,
            1
        ));
        assertEquals(2, countRows("ap_form_package"));
        assertTrue(packages.find(TENANT, "conflict-form", 1).isPresent());
        assertTrue(packages.find(OTHER_TENANT, "conflict-form", 1).isPresent());
    }

    @Test
    void rollbackAfterActualPackageInsertRestoresAllPublishStores() {
        FormDesignDraft draft = validatedTemplate(TENANT, "rollback-form", "rollback");
        ApprovalFormDesignDraftStore failPublishedTransition = new ApprovalFormDesignDraftStore() {
            @Override
            public void save(FormDesignDraft value) {
                drafts.save(value);
            }

            @Override
            public Optional<FormDesignDraft> find(String tenantId, UUID draftId) {
                return drafts.find(tenantId, draftId);
            }

            @Override
            public DraftPage findDrafts(DraftCriteria criteria) {
                return drafts.findDrafts(criteria);
            }

            @Override
            public void lock(String tenantId, UUID draftId) {
                drafts.lock(tenantId, draftId);
            }

            @Override
            public boolean update(FormDesignDraft value, long expectedRevision) {
                if (value.status() == FormDesignDraft.Status.PUBLISHED) {
                    return false;
                }
                return drafts.update(value, expectedRevision);
            }
        };
        ApprovalFormDesignService failingService = service(
            failPublishedTransition,
            packages
        );

        assertThrows(
            ApprovalFormDesignService.DraftRevisionConflictException.class,
            () -> failingService.publish(new PublishCommand(
                context(TENANT, "publish-rollback", "publish-rollback-key"),
                draft.draftId(),
                2,
                1
            ))
        );

        assertTrue(forms.find(TENANT, "rollback-form", 1).isEmpty());
        assertTrue(uiSchemas.find(TENANT, "rollback-form", 1, 1).isEmpty());
        assertTrue(packages.find(TENANT, "rollback-form", 1).isEmpty());
        assertEquals(0, countAudit("FORM_PACKAGE_PUBLISHED"));
        FormDesignDraft retained = drafts.find(TENANT, draft.draftId()).orElseThrow();
        assertEquals(FormDesignDraft.Status.VALIDATED, retained.status());
        assertEquals(2, retained.revision());
    }

    private ApprovalFormDesignService service(
        ApprovalFormDesignDraftStore draftStore,
        ApprovalFormPackageStore packageStore
    ) {
        return new ApprovalFormDesignService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactionManager,
                clock
            ),
            draftStore,
            packageStore,
            forms,
            uiSchemas,
            JdbcAuditEventStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager
            ),
            new FormDefinitionValidator(),
            new UiSchemaDefinitionValidator(),
            formHasher,
            uiHasher,
            packageHasher,
            new FormDefaultValueResolver(clock),
            clock,
            UUID::randomUUID
        );
    }

    private FormDesignDraft validatedTemplate(
        String tenantId,
        String formKey,
        String keyPrefix
    ) {
        FormDesignDraft draft = service.createFromPurchasePaymentTemplate(
            createCommand(
                tenantId,
                keyPrefix + "-create-request",
                keyPrefix + "-create-key",
                formKey
            )
        );
        service.validate(new RevisionCommand(
            context(
                tenantId,
                keyPrefix + "-validate-request",
                keyPrefix + "-validate-key"
            ),
            draft.draftId(),
            1
        ));
        return drafts.find(tenantId, draft.draftId()).orElseThrow();
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
            "Form package design",
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
            "designer-package",
            requestId,
            idempotencyKey,
            "trace-form-package"
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
            + "&preserveInstants=true"
            + "&useAffectedRows=false";
    }

    private static int countRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static int countAudit(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test gate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test gate interrupted", exception);
        }
    }

    private static final class RollbackMarker extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
