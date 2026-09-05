package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService.CreateCommand;
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
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDesignDraft;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalFormDesignDraftStoreMySqlIntegrationTest {

    private static final String TENANT = "Tenant-Draft-MySQL";
    private static final String OTHER_TENANT = "tenant-draft-other";
    private static final String FORM_KEY = "draft-precision";
    private static final Instant NOW = Instant.parse(
        "2026-08-10T05:06:07.999999500Z"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_form_design_draft")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactionManager;
    private TransactionTemplate transactions;
    private ApprovalFormDesignDraftStore drafts;
    private ApprovalFormDesignService service;
    private FormSchemaHasher formHasher;
    private UiSchemaHasher uiHasher;

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
        formHasher = new FormSchemaHasher();
        uiHasher = new UiSchemaHasher();
        drafts = JdbcApprovalFormDesignDraftStoreFactory.create(
            dataSource,
            objectMapper
        );
        service = service(drafts);
    }

    @Test
    void roundTripsEvidenceListsDeterministicallyAndIsolatesTenants() {
        assertInstanceOf(JdbcMySqlApprovalFormDesignDraftStore.class, drafts);
        FormDesignDraft created = service.createBlank(
            createCommand(TENANT, "create-a", "key-a")
        );
        FormDesignDraft updated = service.update(new UpdateCommand(
            context(TENANT, "update-a", "key-b"),
            created.draftId(),
            1,
            "Draft precision updated",
            formDefinition(),
            uiSchema(),
            SaveMode.EXPLICIT
        ));
        FormDesignDraft persisted = service.find(
            TENANT,
            created.draftId()
        ).orElseThrow();

        assertEquals(2, persisted.revision());
        assertEquals(
            formHasher.hash(updated.formDefinition()),
            formHasher.hash(persisted.formDefinition())
        );
        assertEquals(
            uiHasher.hash(updated.uiSchemaDefinition()),
            uiHasher.hash(persisted.uiSchemaDefinition())
        );
        assertEquals(updated.uiSchemaDefinition(), persisted.uiSchemaDefinition());
        assertEquals(canonical(NOW), persisted.createdAt());
        assertEquals(canonical(NOW), persisted.updatedAt());
        assertTrue(service.find(OTHER_TENANT, created.draftId()).isEmpty());
        assertTrue(service.find(
            TENANT.toLowerCase(),
            created.draftId()
        ).isEmpty());

        FormDesignDraft other = draft(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            TENANT,
            "draft-other",
            "Other matching draft",
            1,
            NOW.minusSeconds(1)
        );
        drafts.save(other);
        drafts.save(draft(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            OTHER_TENANT,
            "draft-other",
            "Other tenant draft",
            1,
            NOW.minusSeconds(1)
        ));
        var page = drafts.findDrafts(new DraftCriteria(
            TENANT,
            "draft",
            null,
            10,
            0
        ));
        assertEquals(2, page.total());
        assertEquals(created.draftId(), page.items().get(0).draftId());
        assertEquals(other.draftId(), page.items().get(1).draftId());

        Evidence evidence = jdbc.queryForObject(
            """
            select
                json_unquote(json_extract(form_schema_json, '$.encoding')) as form_encoding,
                json_unquote(json_extract(ui_schema_json, '$.encoding')) as ui_encoding,
                json_length(form_schema_json) as form_members,
                json_length(ui_schema_json) as ui_members,
                microsecond(updated_at) as updated_microsecond
            from ap_form_design_draft
            where tenant_id = ? and draft_id = ?
            """,
            (resultSet, rowNumber) -> new Evidence(
                resultSet.getString("form_encoding"),
                resultSet.getString("ui_encoding"),
                resultSet.getInt("form_members"),
                resultSet.getInt("ui_members"),
                resultSet.getInt("updated_microsecond")
            ),
            TENANT,
            created.draftId().toString()
        );
        assertEquals(
            JdbcMySqlApprovalFormDesignDraftStore.FORM_JSON_ENCODING,
            evidence.formEncoding()
        );
        assertEquals(JdbcMySqlUiSchemaCodec.JSON_ENCODING, evidence.uiEncoding());
        assertEquals(2, evidence.formMembers());
        assertEquals(2, evidence.uiMembers());
        assertEquals(999999, evidence.updatedMicrosecond());
    }

    @Test
    void concurrentServiceUpdatesAdmitExactlyOneCasWinner() throws Exception {
        FormDesignDraft created = service.createBlank(
            createCommand(TENANT, "cas-create", "cas-create-key")
        );
        ApprovalFormDesignService concurrentService = service(
            new GateStore(drafts, 2)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> updateOutcome(
                concurrentService,
                created,
                "CAS winner one",
                "cas-one",
                "cas-key-one"
            ));
            Future<String> second = executor.submit(() -> updateOutcome(
                concurrentService,
                created,
                "CAS winner two",
                "cas-two",
                "cas-key-two"
            ));
            List<String> outcomes = new ArrayList<>(List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
            ));
            outcomes.sort(String::compareTo);
            assertEquals(List.of("CONFLICT", "SUCCESS"), outcomes);
        } finally {
            executor.shutdownNow();
        }

        FormDesignDraft persisted = drafts.find(
            TENANT,
            created.draftId()
        ).orElseThrow();
        assertEquals(2, persisted.revision());
        assertEquals(1, countAudit("FORM_DESIGN_DRAFT_SAVED"));
    }

    @Test
    void rowLockBlocksConcurrentWriterAndRollbackRestoresStateAndReleasesLock()
        throws Exception {
        FormDesignDraft created = service.createBlank(
            createCommand(TENANT, "lock-create", "lock-create-key")
        );
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> transactions.execute(status -> {
                drafts.lock(TENANT, created.draftId());
                locked.countDown();
                await(release);
                FormDesignDraft current = drafts.find(
                    TENANT,
                    created.draftId()
                ).orElseThrow();
                assertTrue(drafts.update(
                    copy(current, current.revision() + 1, "rolled back"),
                    1
                ));
                throw new RollbackMarker();
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(locked);
                secondAttempting.countDown();
                return transactions.execute(status -> {
                    drafts.lock(TENANT, created.draftId());
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

        FormDesignDraft retained = drafts.find(
            TENANT,
            created.draftId()
        ).orElseThrow();
        assertEquals(1, retained.revision());
        assertEquals(created.name(), retained.name());
        transactions.executeWithoutResult(status -> {
            drafts.lock(TENANT, created.draftId());
            FormDesignDraft current = drafts.find(
                TENANT,
                created.draftId()
            ).orElseThrow();
            assertTrue(drafts.update(copy(current, 2, "after rollback"), 1));
        });
        assertEquals(
            2,
            drafts.find(TENANT, created.draftId()).orElseThrow().revision()
        );
    }

    @Test
    void malformedFormAndUiEvidenceFailClosed() {
        FormDesignDraft first = service.createBlank(
            createCommand(TENANT, "bad-form", "bad-form-key")
        );
        FormDesignDraft second = service.createBlank(
            createCommand(TENANT, "bad-ui", "bad-ui-key")
        );
        jdbc.update(
            """
            update ap_form_design_draft
            set form_schema_json = cast(? as json)
            where tenant_id = ? and draft_id = ?
            """,
            "{\"encoding\":\"CANONICAL_JSON_TEXT_V1\","
                + "\"payload\":\"{}\",\"extra\":true}",
            TENANT,
            first.draftId().toString()
        );
        jdbc.update(
            """
            update ap_form_design_draft
            set ui_schema_json = cast(? as json)
            where tenant_id = ? and draft_id = ?
            """,
            "{\"encoding\":\"UNKNOWN\",\"payload\":\"{}\"}",
            TENANT,
            second.draftId().toString()
        );
        assertThrows(
            DataAccessException.class,
            () -> drafts.find(TENANT, first.draftId())
        );
        assertThrows(
            DataAccessException.class,
            () -> drafts.find(TENANT, second.draftId())
        );
    }

    private ApprovalFormDesignService service(
        ApprovalFormDesignDraftStore draftStore
    ) {
        ApprovalFormStore forms = JdbcApprovalFormStoreFactory.create(
            dataSource,
            objectMapper
        );
        ApprovalUiSchemaStore uiSchemas = JdbcApprovalUiSchemaStoreFactory.create(
            dataSource,
            objectMapper
        );
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new ApprovalFormDesignService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactionManager,
                clock
            ),
            draftStore,
            unsupportedPackages(),
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
            new FormPackageHasher(),
            new FormDefaultValueResolver(clock),
            clock,
            UUID::randomUUID
        );
    }

    private String updateOutcome(
        ApprovalFormDesignService target,
        FormDesignDraft created,
        String name,
        String requestId,
        String idempotencyKey
    ) {
        try {
            target.update(new UpdateCommand(
                context(TENANT, requestId, idempotencyKey),
                created.draftId(),
                1,
                name,
                created.formDefinition(),
                created.uiSchemaDefinition(),
                SaveMode.EXPLICIT
            ));
            return "SUCCESS";
        } catch (ApprovalFormDesignService.DraftRevisionConflictException exception) {
            return "CONFLICT";
        }
    }

    private static ApprovalFormPackageStore unsupportedPackages() {
        return new ApprovalFormPackageStore() {
            @Override
            public void lockVersion(
                String tenantId,
                String formKey,
                int packageVersion
            ) {
                throw unsupported();
            }

            @Override
            public Optional<FormPackage> find(
                String tenantId,
                String formKey,
                int packageVersion
            ) {
                throw unsupported();
            }

            @Override
            public Optional<FormPackage> findByDraft(
                String tenantId,
                UUID draftId
            ) {
                throw unsupported();
            }

            @Override
            public void save(FormPackage formPackage) {
                throw unsupported();
            }

            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException(
                    "P3-F4 Form Package Store is out of scope"
                );
            }
        };
    }

    private static CreateCommand createCommand(
        String tenantId,
        String requestId,
        String key
    ) {
        return new CreateCommand(
            context(tenantId, requestId, key),
            FORM_KEY,
            "Draft precision",
            1,
            1
        );
    }

    private static RequestContext context(
        String tenantId,
        String requestId,
        String key
    ) {
        return new RequestContext(
            tenantId,
            "Draft-Admin",
            requestId,
            key,
            "trace-draft-mysql"
        );
    }

    private static FormDefinition formDefinition() {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            FORM_KEY,
            1,
            "Draft precision form",
            List.of(new FormDefinition.FormField(
                "amount",
                FormDefinition.FieldType.MONEY,
                "金额✅",
                true,
                FormDefinition.FieldConstraints.money(4, BigDecimal.ZERO),
                FormDefinition.DefaultValue.literal(new BigDecimal("123.4500")),
                List.of()
            ))
        );
    }

    private static UiSchemaDefinition uiSchema() {
        return new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            FORM_KEY,
            1,
            1,
            "Draft precision UI",
            List.of(new UiSchemaDefinition.Section(
                "main",
                "主要信息✅",
                "typed evidence",
                false,
                List.of(new UiSchemaDefinition.FieldLayout(
                    "amount",
                    "请输入金额",
                    null,
                    24,
                    new UiSchemaDefinition.ComponentDefinition(
                        "MONEY",
                        1,
                        Map.of(
                            "prefix",
                            new BigDecimal("123456789012.12345678900"),
                            "suffix",
                            List.of(
                                Integer.valueOf(7),
                                Long.valueOf(8L),
                                new BigInteger(
                                    "92233720368547758081234567890"
                                )
                            )
                        ),
                        UiSchemaDefinition.FallbackRenderer.READONLY_TEXT
                    )
                )),
                0,
                1,
                true,
                UiSchemaDefinition.SectionVisibility.always(),
                false,
                List.of()
            )),
            List.of(new UiSchemaDefinition.NodePermissions(
                UiSchemaDefinition.START_CONTEXT,
                List.of(new UiSchemaDefinition.FieldPermission(
                    "amount",
                    UiSchemaDefinition.FieldAccess.EDITABLE,
                    UiSchemaDefinition.RequiredOverride.INHERIT
                ))
            ))
        );
    }

    private static FormDesignDraft draft(
        UUID id,
        String tenantId,
        String formKey,
        String name,
        long revision,
        Instant updatedAt
    ) {
        FormDefinition form = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            formKey,
            1,
            name,
            List.of()
        );
        UiSchemaDefinition ui = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            formKey,
            1,
            1,
            name + " UI",
            List.of(),
            List.of()
        );
        return new FormDesignDraft(
            id,
            tenantId,
            formKey,
            name,
            form,
            ui,
            null,
            null,
            revision,
            FormDesignDraft.Status.DRAFT,
            null,
            "Draft-Admin",
            "Draft-Admin",
            updatedAt,
            updatedAt
        );
    }

    private static FormDesignDraft copy(
        FormDesignDraft source,
        long revision,
        String name
    ) {
        return new FormDesignDraft(
            source.draftId(),
            source.tenantId(),
            source.formKey(),
            name,
            source.formDefinition(),
            source.uiSchemaDefinition(),
            source.sourceFormVersion(),
            source.sourceUiSchemaVersion(),
            revision,
            FormDesignDraft.Status.DRAFT,
            null,
            source.createdBy(),
            "Draft-Admin",
            source.createdAt(),
            source.updatedAt()
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "concurrency wait interrupted",
                exception
            );
        }
    }

    private static int countAudit(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
        );
    }

    private static Instant canonical(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
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

    private record Evidence(
        String formEncoding,
        String uiEncoding,
        int formMembers,
        int uiMembers,
        int updatedMicrosecond
    ) {
    }

    private static final class RollbackMarker extends RuntimeException {
    }

    private static final class GateStore implements ApprovalFormDesignDraftStore {

        private final ApprovalFormDesignDraftStore delegate;
        private final CountDownLatch updateArrivals;

        private GateStore(
            ApprovalFormDesignDraftStore delegate,
            int updateCount
        ) {
            this.delegate = Objects.requireNonNull(delegate);
            this.updateArrivals = new CountDownLatch(updateCount);
        }

        @Override
        public void save(FormDesignDraft draft) {
            delegate.save(draft);
        }

        @Override
        public Optional<FormDesignDraft> find(String tenantId, UUID draftId) {
            return delegate.find(tenantId, draftId);
        }

        @Override
        public DraftPage findDrafts(DraftCriteria criteria) {
            return delegate.findDrafts(criteria);
        }

        @Override
        public void lock(String tenantId, UUID draftId) {
            delegate.lock(tenantId, draftId);
        }

        @Override
        public boolean update(FormDesignDraft draft, long expectedRevision) {
            updateArrivals.countDown();
            await(updateArrivals);
            return delegate.update(draft, expectedRevision);
        }
    }
}
