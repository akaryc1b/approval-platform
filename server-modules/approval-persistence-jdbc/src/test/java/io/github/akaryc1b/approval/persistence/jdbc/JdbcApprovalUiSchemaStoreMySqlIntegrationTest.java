package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService.PublishCommand;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService.UiSchemaVersionConflictException;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.UiSchemaDefinitionValidator;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.ComponentDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FallbackRenderer;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldPermission;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.RequiredOverride;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.SectionVisibility;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.VisibilityMode;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalUiSchemaStoreMySqlIntegrationTest {

    private static final String TENANT = "Tenant-Ui-MySQL";
    private static final String OTHER_TENANT = "tenant-ui-other";
    private static final String FORM_KEY = "ui-precision";
    private static final int FORM_VERSION = 1;
    private static final Instant NOW = Instant.parse(
        "2026-08-08T06:07:08.999999500Z"
    );
    private static final BigDecimal EXACT_DECIMAL = new BigDecimal(
        "123456789012.12345678900"
    );
    private static final BigInteger EXACT_BIG_INTEGER = new BigInteger(
        "92233720368547758081234567890"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_ui_schema_store")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private ObjectMapper objectMapper;
    private ApprovalFormStore formStore;
    private ApprovalUiSchemaStore uiSchemaStore;
    private ApprovalUiSchemaService service;
    private TransactionTemplate transactions;
    private FormSchemaHasher formHasher;
    private UiSchemaHasher uiSchemaHasher;

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
        jdbc.update("delete from ap_audit_event");
        jdbc.update("delete from ap_audit_chain_state");
        jdbc.update("delete from ap_command_idempotency");
        jdbc.update("delete from ap_form_ui_schema");
        jdbc.update("delete from ap_form_definition");

        objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        formHasher = new FormSchemaHasher();
        uiSchemaHasher = new UiSchemaHasher();
        formStore = JdbcApprovalFormStoreFactory.create(dataSource, objectMapper);
        uiSchemaStore = JdbcApprovalUiSchemaStoreFactory.create(
            dataSource,
            objectMapper
        );
        service = new ApprovalUiSchemaService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC)
            ),
            formStore,
            uiSchemaStore,
            JdbcAuditEventStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager
            ),
            new UiSchemaDefinitionValidator(),
            uiSchemaHasher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            UUID::randomUUID
        );
        seedForm(TENANT);
    }

    @Test
    void publishesReplaysAndPreservesClosedTypedValues() {
        assertInstanceOf(JdbcMySqlApprovalUiSchemaStore.class, uiSchemaStore);
        UiSchemaDefinition definition = definition(
            1,
            "付款界面✅ Straße İstanbul",
            EXACT_DECIMAL,
            Map.of(
                "prefix",
                EXACT_DECIMAL,
                "suffix",
                List.of(
                    Byte.valueOf((byte) 1),
                    Short.valueOf((short) 2),
                    Integer.valueOf(3),
                    Long.valueOf(4L),
                    EXACT_BIG_INTEGER,
                    Float.valueOf(1.25F),
                    Double.valueOf(2.5D),
                    new BigDecimal("3.1400"),
                    "审批✅",
                    Boolean.TRUE
                )
            )
        );
        PublishCommand command = new PublishCommand(
            context("publish-ui", "publish-ui-key"),
            definition
        );

        var published = service.publish(command);
        var replayed = service.publish(command);

        assertEquals(published, replayed);
        assertEquals(1, count("ap_form_ui_schema"));
        assertEquals(1, count("ap_command_idempotency"));
        assertEquals(1, countAudit("UI_SCHEMA_PUBLISHED"));

        PublishedUiSchema persisted = service.find(
            TENANT,
            FORM_KEY,
            FORM_VERSION,
            definition.version()
        ).orElseThrow();
        assertEquals(definition, persisted.definition());
        assertEquals(uiSchemaHasher.hash(definition), persisted.contentHash());
        assertEquals(
            uiSchemaHasher.hash(definition),
            uiSchemaHasher.hash(persisted.definition())
        );
        assertEquals(canonical(NOW), persisted.publishedAt());
        assertEquals(
            definition,
            service.findLatest(TENANT, FORM_KEY, FORM_VERSION)
                .orElseThrow()
                .definition()
        );

        Section persistedSection = persisted.definition().sections().getFirst();
        BigDecimal visibilityValue = assertInstanceOf(
            BigDecimal.class,
            persistedSection.visibility().expectedValue()
        );
        assertEquals(EXACT_DECIMAL, visibilityValue);

        Map<String, Object> properties = persistedSection.fields()
            .getFirst()
            .component()
            .properties();
        BigDecimal prefix = assertInstanceOf(
            BigDecimal.class,
            properties.get("prefix")
        );
        assertEquals(EXACT_DECIMAL, prefix);
        List<?> suffix = assertInstanceOf(List.class, properties.get("suffix"));
        assertInstanceOf(Byte.class, suffix.get(0));
        assertInstanceOf(Short.class, suffix.get(1));
        assertInstanceOf(Integer.class, suffix.get(2));
        assertInstanceOf(Long.class, suffix.get(3));
        assertEquals(EXACT_BIG_INTEGER, assertInstanceOf(BigInteger.class, suffix.get(4)));
        assertInstanceOf(Float.class, suffix.get(5));
        assertInstanceOf(Double.class, suffix.get(6));
        assertEquals(
            new BigDecimal("3.1400"),
            assertInstanceOf(BigDecimal.class, suffix.get(7))
        );
        assertEquals("审批✅", suffix.get(8));
        assertEquals(Boolean.TRUE, suffix.get(9));

        EnvelopeEvidence envelope = jdbc.queryForObject(
            """
            select
                json_unquote(json_extract(schema_json, '$.encoding')) as encoding,
                json_unquote(json_extract(schema_json, '$.payload')) as payload,
                json_length(schema_json) as member_count,
                microsecond(published_at) as published_microsecond,
                published_at
            from ap_form_ui_schema
            where tenant_id = ?
              and form_key = ?
              and form_version = ?
              and ui_schema_version = ?
            """,
            (resultSet, rowNumber) -> new EnvelopeEvidence(
                resultSet.getString("encoding"),
                resultSet.getString("payload"),
                resultSet.getInt("member_count"),
                resultSet.getInt("published_microsecond"),
                resultSet.getObject("published_at", LocalDateTime.class)
            ),
            TENANT,
            FORM_KEY,
            FORM_VERSION,
            definition.version()
        );
        assertEquals(JdbcMySqlUiSchemaCodec.JSON_ENCODING, envelope.encoding());
        assertEquals(2, envelope.memberCount());
        assertTrue(envelope.payload().contains("\"kind\":\"NUMBER\""));
        assertTrue(envelope.payload().contains("\"type\":\"BIG_DECIMAL\""));
        assertTrue(envelope.payload().contains(EXACT_DECIMAL.toString()));
        assertTrue(envelope.payload().contains("付款界面✅"));
        assertEquals(0, envelope.publishedMicrosecond());
        assertEquals(
            LocalDateTime.ofInstant(canonical(NOW), ZoneOffset.UTC),
            envelope.publishedAt()
        );
    }

    @Test
    void selectsLatestVersionAndSeparatesTenants() {
        seedForm(OTHER_TENANT);
        Instant sameTime = Instant.parse("2026-08-08T07:00:00.123456Z");
        publishDirect(TENANT, definition(1, "UI One", BigDecimal.ONE, properties(1)), sameTime);
        publishDirect(
            TENANT,
            definition(2, "UI Two", new BigDecimal("2"), properties(2)),
            sameTime
        );
        publishDirect(TENANT, definition(3, "UI Three", BigDecimal.TEN, properties(3)), sameTime);
        publishDirect(
            OTHER_TENANT,
            definition(7, "Other UI", BigDecimal.ZERO, properties(7)),
            sameTime
        );

        assertEquals(
            3,
            uiSchemaStore.findLatest(TENANT, FORM_KEY, FORM_VERSION)
                .orElseThrow()
                .definition()
                .version()
        );
        assertEquals(
            7,
            uiSchemaStore.findLatest(OTHER_TENANT, FORM_KEY, FORM_VERSION)
                .orElseThrow()
                .definition()
                .version()
        );
        assertEquals(
            1,
            uiSchemaStore.find(TENANT, FORM_KEY, FORM_VERSION, 1)
                .orElseThrow()
                .definition()
                .version()
        );
        assertTrue(uiSchemaStore.findLatest(
            TENANT.toLowerCase(),
            FORM_KEY,
            FORM_VERSION
        ).isEmpty());
        assertEquals(4, count("ap_form_ui_schema"));
    }

    @Test
    void serializesConcurrentPublicationAndRejectsConflictingContent()
        throws Exception {
        UiSchemaDefinition original = definition(
            4,
            "Serialized UI",
            new BigDecimal("4.0000"),
            properties(4)
        );
        UiSchemaDefinition conflicting = definition(
            original.version(),
            "Changed Serialized UI",
            new BigDecimal("4.0001"),
            properties(44)
        );
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> inTransaction(() -> {
                lock(original);
                firstLocked.countDown();
                await(releaseFirst);
                if (find(original).isPresent()) {
                    return false;
                }
                uiSchemaStore.save(published(TENANT, original, NOW));
                return true;
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return inTransaction(() -> {
                    lock(original);
                    if (find(original).isPresent()) {
                        return false;
                    }
                    uiSchemaStore.save(published(TENANT, original, NOW));
                    return true;
                });
            });

            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            assertTrue(secondAttempting.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> second.get(500, TimeUnit.MILLISECONDS)
            );
            releaseFirst.countDown();

            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertFalse(second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, count("ap_form_ui_schema"));
        assertThrows(
            UiSchemaVersionConflictException.class,
            () -> service.publish(new PublishCommand(
                context("conflict-ui", "conflict-ui-key"),
                conflicting
            ))
        );
        assertEquals(1, count("ap_form_ui_schema"));
        assertEquals(0, count("ap_command_idempotency"));
    }

    @Test
    void rollbackRemovesPublicationAndReleasesVersionLock() {
        UiSchemaDefinition definition = definition(
            5,
            "Rollback UI",
            BigDecimal.ZERO,
            properties(5)
        );

        assertThrows(IllegalStateException.class, () -> lock(definition));
        assertThrows(RollbackMarker.class, () -> inTransaction(() -> {
            lock(definition);
            uiSchemaStore.save(published(TENANT, definition, NOW));
            throw new RollbackMarker();
        }));

        assertTrue(find(definition).isEmpty());
        publishDirect(TENANT, definition, NOW);
        assertEquals(1, count("ap_form_ui_schema"));
        assertTrue(find(definition).isPresent());
    }

    @Test
    void rejectsMalformedUntypedUnknownDuplicateAndExtendedEnvelopes()
        throws JsonProcessingException {
        UiSchemaDefinition definition = definition(
            6,
            "Malformed UI",
            BigDecimal.ZERO,
            properties(6)
        );
        JdbcMySqlUiSchemaCodec codec = new JdbcMySqlUiSchemaCodec(objectMapper);
        String valid = codec.encode(definition);
        String validPayload = objectMapper.readTree(valid)
            .get("payload")
            .textValue();
        String unknownKindPayload = validPayload.replaceFirst(
            "\"kind\":\"NUMBER\"",
            "\"kind\":\"UNKNOWN\""
        );
        assertFalse(validPayload.equals(unknownKindPayload));

        assertEnvelopeRejected("[]");
        assertEnvelopeRejected("{\"payload\":\"{}\"}");
        assertEnvelopeRejected(
            "{\"encoding\":\"UNKNOWN\",\"payload\":\"{}\"}"
        );
        assertEnvelopeRejected(
            valid.substring(0, valid.length() - 1) + ",\"extra\":true}"
        );
        assertEnvelopeRejected(
            outerEnvelope(objectMapper.writeValueAsString(definition))
        );
        assertEnvelopeRejected(outerEnvelope(unknownKindPayload));
        assertEnvelopeRejected(
            outerEnvelope("{\"sections\":[],\"sections\":[]}")
        );
    }

    private void assertEnvelopeRejected(String envelope) {
        jdbc.update("delete from ap_form_ui_schema");
        jdbc.update(
            """
            insert into ap_form_ui_schema (
                tenant_id, form_key, form_version, ui_schema_version, schema_version,
                name, section_count, schema_json, content_hash, published_by, published_at
            ) values (?, ?, ?, 99, '1.0', 'Malformed UI', 1, cast(? as json), ?, ?, ?)
            """,
            TENANT,
            FORM_KEY,
            FORM_VERSION,
            envelope,
            "f".repeat(64),
            "Ui-Admin",
            Timestamp.from(canonical(NOW))
        );

        assertThrows(
            DataAccessException.class,
            () -> uiSchemaStore.find(TENANT, FORM_KEY, FORM_VERSION, 99)
        );
    }

    private String outerEnvelope(String payload) throws JsonProcessingException {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("encoding", JdbcMySqlUiSchemaCodec.JSON_ENCODING);
        envelope.put("payload", payload);
        return objectMapper.writeValueAsString(envelope);
    }

    private void seedForm(String tenantId) {
        FormDefinition definition = formDefinition();
        formStore.save(new PublishedForm(
            tenantId,
            definition,
            formHasher.hash(definition),
            "Form-Admin",
            NOW
        ));
    }

    private void publishDirect(
        String tenantId,
        UiSchemaDefinition definition,
        Instant publishedAt
    ) {
        inTransaction(() -> {
            uiSchemaStore.lockVersion(
                tenantId,
                definition.formKey(),
                definition.formVersion(),
                definition.version()
            );
            uiSchemaStore.save(published(tenantId, definition, publishedAt));
        });
    }

    private PublishedUiSchema published(
        String tenantId,
        UiSchemaDefinition definition,
        Instant publishedAt
    ) {
        return new PublishedUiSchema(
            tenantId,
            definition,
            uiSchemaHasher.hash(definition),
            "Ui-Admin",
            publishedAt
        );
    }

    private void lock(UiSchemaDefinition definition) {
        uiSchemaStore.lockVersion(
            TENANT,
            definition.formKey(),
            definition.formVersion(),
            definition.version()
        );
    }

    private java.util.Optional<PublishedUiSchema> find(
        UiSchemaDefinition definition
    ) {
        return uiSchemaStore.find(
            TENANT,
            definition.formKey(),
            definition.formVersion(),
            definition.version()
        );
    }

    private static FormDefinition formDefinition() {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            FORM_KEY,
            FORM_VERSION,
            "UI Precision Form",
            List.of(new FormField(
                "amount",
                FieldType.MONEY,
                "金额✅",
                true,
                FieldConstraints.money(9, BigDecimal.ZERO)
            ))
        );
    }

    private static UiSchemaDefinition definition(
        int version,
        String name,
        Object expectedValue,
        Map<String, Object> properties
    ) {
        return new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            FORM_KEY,
            FORM_VERSION,
            version,
            name,
            List.of(new Section(
                "main",
                "主要信息✅",
                "精确类型布局",
                false,
                List.of(new FieldLayout(
                    "amount",
                    "请输入金额",
                    "金额保持精确类型",
                    24,
                    new ComponentDefinition(
                        "MONEY",
                        1,
                        properties,
                        FallbackRenderer.READONLY_TEXT
                    )
                )),
                0,
                1,
                true,
                new SectionVisibility(
                    VisibilityMode.FIELD_EQUALS,
                    "amount",
                    expectedValue
                ),
                false,
                List.of()
            )),
            List.of(new NodePermissions(
                UiSchemaDefinition.START_CONTEXT,
                List.of(new FieldPermission(
                    "amount",
                    FieldAccess.EDITABLE,
                    RequiredOverride.INHERIT
                ))
            ))
        );
    }

    private static Map<String, Object> properties(int value) {
        return Map.of(
            "prefix",
            new BigDecimal(value + ".0000"),
            "suffix",
            List.of(Integer.valueOf(value), Long.valueOf(value))
        );
    }

    private static RequestContext context(String requestId, String idempotencyKey) {
        return new RequestContext(
            TENANT,
            "Ui-Admin",
            requestId,
            idempotencyKey,
            "trace-ui-mysql"
        );
    }

    private <T> T inTransaction(Supplier<T> action) {
        T result = transactions.execute(status -> action.get());
        return Objects.requireNonNull(
            result,
            "transaction action must return a result"
        );
    }

    private void inTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> action.run());
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

    private static Instant canonical(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(value);
    }

    private static int count(String table) {
        return jdbc.queryForObject(
            "select count(*) from " + table,
            Integer.class
        );
    }

    private static int countAudit(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
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

    private record EnvelopeEvidence(
        String encoding,
        String payload,
        int memberCount,
        int publishedMicrosecond,
        LocalDateTime publishedAt
    ) {
    }

    private static final class RollbackMarker extends RuntimeException {
    }
}
