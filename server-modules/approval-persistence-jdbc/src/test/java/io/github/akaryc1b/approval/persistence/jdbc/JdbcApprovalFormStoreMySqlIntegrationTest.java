package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormService;
import io.github.akaryc1b.approval.application.ApprovalFormService.FormVersionConflictException;
import io.github.akaryc1b.approval.application.ApprovalFormService.PublishCommand;
import io.github.akaryc1b.approval.application.FormDefinitionValidator;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.FormCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
class JdbcApprovalFormStoreMySqlIntegrationTest {

    private static final String TENANT = "Tenant-Form-MySQL";
    private static final String OTHER_TENANT = "tenant-form-other";
    private static final Instant NOW = Instant.parse(
        "2026-08-08T04:05:06.999999500Z"
    );
    private static final BigDecimal EXACT_MINIMUM = new BigDecimal(
        "123456789012.123456789"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_form_store")
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
    private ApprovalFormStore store;
    private ApprovalFormService service;
    private TransactionTemplate transactions;
    private FormSchemaHasher hasher;

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
        jdbc.update("delete from ap_form_definition");

        objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        hasher = new FormSchemaHasher();
        store = JdbcApprovalFormStoreFactory.create(dataSource, objectMapper);
        service = new ApprovalFormService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC)
            ),
            store,
            JdbcAuditEventStoreFactory.create(
                dataSource,
                objectMapper,
                transactionManager
            ),
            new FormDefinitionValidator(),
            hasher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            UUID::randomUUID
        );
    }

    @Test
    void publishesReplaysSearchesAndPreservesExactSchemaText() {
        assertInstanceOf(JdbcMySqlApprovalFormStore.class, store);
        FormDefinition definition = definition(
            "payment-precision",
            1,
            "付款审批✅ Straße İstanbul",
            EXACT_MINIMUM
        );
        PublishCommand command = new PublishCommand(
            context("publish-form", "publish-form-key"),
            definition
        );

        var published = service.publish(command);
        var replayed = service.publish(command);

        assertEquals(published, replayed);
        assertEquals(1, count("ap_form_definition"));
        assertEquals(1, count("ap_command_idempotency"));
        assertEquals(1, countAudit("FORM_PUBLISHED"));

        PublishedForm persisted = service.find(
            TENANT,
            definition.formKey(),
            definition.version()
        ).orElseThrow();
        assertEquals(definition, persisted.definition());
        assertEquals(hasher.hash(definition), persisted.contentHash());
        assertEquals(canonical(NOW), persisted.publishedAt());

        EnvelopeEvidence envelope = jdbc.queryForObject(
            """
            select
                json_unquote(json_extract(schema_json, '$.encoding')) as encoding,
                json_unquote(json_extract(schema_json, '$.payload')) as payload,
                json_length(schema_json) as member_count,
                microsecond(published_at) as published_microsecond,
                published_at
            from ap_form_definition
            where tenant_id = ? and form_key = ? and form_version = ?
            """,
            (resultSet, rowNumber) -> new EnvelopeEvidence(
                resultSet.getString("encoding"),
                resultSet.getString("payload"),
                resultSet.getInt("member_count"),
                resultSet.getInt("published_microsecond"),
                resultSet.getObject("published_at", LocalDateTime.class)
            ),
            TENANT,
            definition.formKey(),
            definition.version()
        );
        assertEquals(JdbcMySqlApprovalFormStore.JSON_ENCODING, envelope.encoding());
        assertEquals(2, envelope.memberCount());
        assertTrue(envelope.payload().contains(EXACT_MINIMUM.toPlainString()));
        assertTrue(envelope.payload().contains("付款审批✅"));
        assertEquals(0, envelope.publishedMicrosecond());
        assertEquals(
            LocalDateTime.ofInstant(canonical(NOW), ZoneOffset.UTC),
            envelope.publishedAt()
        );

        assertEquals(
            1,
            service.findForms(TENANT, "PAYMENT", 20, 0).total()
        );
        assertEquals(
            1,
            service.findForms(TENANT, "审批", 20, 0).total()
        );
        assertEquals(
            0,
            service.findForms(OTHER_TENANT, "payment", 20, 0).total()
        );
    }

    @Test
    void serializesConcurrentPublicationAndRejectsConflictingContent()
        throws Exception {
        FormDefinition original = definition(
            "serialized-form",
            1,
            "Serialized Form",
            new BigDecimal("0.000001")
        );
        FormDefinition conflicting = definition(
            original.formKey(),
            original.version(),
            "Changed Serialized Form",
            new BigDecimal("0.000002")
        );
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> inTransaction(() -> {
                store.lockVersion(TENANT, original.formKey(), original.version());
                firstLocked.countDown();
                await(releaseFirst);
                if (store.find(TENANT, original.formKey(), original.version()).isPresent()) {
                    return false;
                }
                store.save(published(TENANT, original, NOW));
                return true;
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return inTransaction(() -> {
                    store.lockVersion(TENANT, original.formKey(), original.version());
                    if (store.find(
                        TENANT,
                        original.formKey(),
                        original.version()
                    ).isPresent()) {
                        return false;
                    }
                    store.save(published(TENANT, original, NOW));
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

        assertEquals(1, count("ap_form_definition"));
        assertThrows(
            FormVersionConflictException.class,
            () -> service.publish(new PublishCommand(
                context("conflict-form", "conflict-form-key"),
                conflicting
            ))
        );
        assertEquals(1, count("ap_form_definition"));
        assertEquals(0, count("ap_command_idempotency"));
    }

    @Test
    void paginatesDeterministicallyAndSeparatesTenants() {
        Instant sameTime = Instant.parse("2026-08-08T05:00:00.123456Z");
        publishDirect(TENANT, definition("alpha", 1, "Alpha One", BigDecimal.ZERO), sameTime);
        publishDirect(TENANT, definition("alpha", 2, "Alpha Two", BigDecimal.ONE), sameTime);
        publishDirect(TENANT, definition("beta", 1, "Beta One", BigDecimal.TEN), sameTime);
        publishDirect(
            OTHER_TENANT,
            definition("alpha", 3, "Other Alpha", BigDecimal.ONE),
            sameTime
        );

        var first = store.findForms(new FormCriteria(TENANT, null, 2, 0));
        assertEquals(3, first.total());
        assertEquals(2, first.items().size());
        assertTrue(first.hasMore());
        assertEquals("alpha", first.items().get(0).formKey());
        assertEquals(2, first.items().get(0).version());
        assertEquals("alpha", first.items().get(1).formKey());
        assertEquals(1, first.items().get(1).version());

        var second = store.findForms(new FormCriteria(TENANT, null, 2, 2));
        assertEquals(3, second.total());
        assertEquals(1, second.items().size());
        assertFalse(second.hasMore());
        assertEquals("beta", second.items().getFirst().formKey());

        assertEquals(
            2,
            store.findForms(new FormCriteria(TENANT, "ALPHA", 20, 0)).total()
        );
        assertEquals(
            1,
            store.findForms(new FormCriteria(OTHER_TENANT, "alpha", 20, 0)).total()
        );
        assertEquals(
            0,
            store.findForms(new FormCriteria(
                TENANT.toLowerCase(),
                null,
                20,
                0
            )).total()
        );
    }

    @Test
    void rollbackRemovesPublicationAndReleasesTheVersionLock() {
        FormDefinition definition = definition(
            "rollback-form",
            1,
            "Rollback Form",
            BigDecimal.ZERO
        );

        assertThrows(
            IllegalStateException.class,
            () -> store.lockVersion(TENANT, definition.formKey(), definition.version())
        );
        assertThrows(RollbackMarker.class, () -> inTransaction(() -> {
            store.lockVersion(TENANT, definition.formKey(), definition.version());
            store.save(published(TENANT, definition, NOW));
            throw new RollbackMarker();
        }));

        assertTrue(store.find(
            TENANT,
            definition.formKey(),
            definition.version()
        ).isEmpty());

        publishDirect(TENANT, definition, NOW);
        assertEquals(1, count("ap_form_definition"));
        assertTrue(store.find(
            TENANT,
            definition.formKey(),
            definition.version()
        ).isPresent());
    }

    @Test
    void rejectsMalformedUnversionedAndExtendedSchemaEnvelopes() {
        assertEnvelopeRejected("[]");
        assertEnvelopeRejected("{\"payload\":\"{}\"}");
        assertEnvelopeRejected(
            "{\"encoding\":\"UNKNOWN\",\"payload\":\"{}\"}"
        );
        assertEnvelopeRejected(
            "{\"encoding\":\"CANONICAL_JSON_TEXT_V1\","
                + "\"payload\":\"{}\",\"extra\":true}"
        );
    }

    private void assertEnvelopeRejected(String envelope) {
        jdbc.update("delete from ap_form_definition");
        jdbc.update(
            """
            insert into ap_form_definition (
                tenant_id, form_key, form_version, schema_version, name,
                field_count, schema_json, content_hash, published_by, published_at
            ) values (?, ?, 1, '1.0', 'Malformed Form', 1, cast(? as json), ?, ?, ?)
            """,
            TENANT,
            "malformed-form",
            envelope,
            "f".repeat(64),
            "Form-Admin",
            Timestamp.from(canonical(NOW))
        );

        assertThrows(
            DataAccessException.class,
            () -> store.find(TENANT, "malformed-form", 1)
        );
    }

    private void publishDirect(
        String tenantId,
        FormDefinition definition,
        Instant publishedAt
    ) {
        inTransaction(() -> {
            store.lockVersion(tenantId, definition.formKey(), definition.version());
            store.save(published(tenantId, definition, publishedAt));
        });
    }

    private PublishedForm published(
        String tenantId,
        FormDefinition definition,
        Instant publishedAt
    ) {
        return new PublishedForm(
            tenantId,
            definition,
            hasher.hash(definition),
            "Form-Admin",
            publishedAt
        );
    }

    private static FormDefinition definition(
        String formKey,
        int version,
        String name,
        BigDecimal minimum
    ) {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            formKey,
            version,
            name,
            List.of(new FormField(
                "amount",
                FieldType.MONEY,
                "金额✅",
                true,
                FieldConstraints.money(9, minimum)
            ))
        );
    }

    private static RequestContext context(String requestId, String idempotencyKey) {
        return new RequestContext(
            TENANT,
            "Form-Admin",
            requestId,
            idempotencyKey,
            "trace-form-mysql"
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
