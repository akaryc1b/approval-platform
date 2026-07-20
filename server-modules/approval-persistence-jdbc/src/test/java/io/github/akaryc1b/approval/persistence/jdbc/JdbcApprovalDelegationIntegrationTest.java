package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalDelegationService;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationScope;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationStatus;
import io.github.akaryc1b.approval.domain.context.RequestContext;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalDelegationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_delegation_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ApprovalDelegationService service;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_delegation_rule,
                ap_audit_event,
                ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ApprovalDelegationService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            new JdbcApprovalDelegationStore(dataSource),
            new JdbcAuditEventSink(dataSource, objectMapper),
            clock,
            UUID::randomUUID
        );
    }

    @Test
    void createIsDurablyIdempotentAndTenantIsolated() {
        var command = createCommand(
            "tenant-a",
            "manager-1",
            "delegate-1",
            DelegationScope.ALL,
            null,
            NOW,
            NOW.plusSeconds(86_400),
            "annual leave",
            "create-global",
            "create-global-key"
        );

        var created = service.create(command);
        var replayed = service.create(command);

        assertEquals(created, replayed);
        assertEquals(DelegationStatus.ACTIVE, created.status());
        assertEquals(1, service.findMine("tenant-a", "manager-1", false).size());
        assertTrue(service.findMine("tenant-b", "manager-1", true).isEmpty());
        assertEquals(1, countAudit("DELEGATION_RULE_CREATED"));
    }

    @Test
    void overlapIsRejectedAndDefinitionRuleOverridesGlobalRule() {
        service.create(createCommand(
            "tenant-a",
            "manager-1",
            "delegate-global",
            DelegationScope.ALL,
            null,
            NOW,
            NOW.plusSeconds(259_200),
            "global coverage",
            "create-global",
            "create-global-key"
        ));
        service.create(createCommand(
            "tenant-a",
            "manager-1",
            "delegate-purchase",
            DelegationScope.DEFINITION,
            "purchase-payment",
            NOW,
            NOW.plusSeconds(172_800),
            "purchase specialist",
            "create-definition",
            "create-definition-key"
        ));

        assertThrows(
            ApprovalDelegationStore.DelegationConflictException.class,
            () -> service.create(createCommand(
                "tenant-a",
                "manager-1",
                "delegate-overlap",
                DelegationScope.ALL,
                null,
                NOW.plusSeconds(3_600),
                NOW.plusSeconds(86_400),
                "overlapping global coverage",
                "create-overlap",
                "create-overlap-key"
            ))
        );

        assertEquals(
            "delegate-purchase",
            service.resolve(
                "tenant-a",
                "manager-1",
                "purchase-payment",
                NOW.plusSeconds(7_200)
            ).orElseThrow().delegateId()
        );
        assertEquals(
            "delegate-global",
            service.resolve(
                "tenant-a",
                "manager-1",
                "travel-expense",
                NOW.plusSeconds(7_200)
            ).orElseThrow().delegateId()
        );
    }

    @Test
    void revokeIsIdempotentAndRestoresTheGlobalFallback() {
        service.create(createCommand(
            "tenant-a",
            "manager-1",
            "delegate-global",
            DelegationScope.ALL,
            null,
            NOW,
            NOW.plusSeconds(259_200),
            "global coverage",
            "create-global",
            "create-global-key"
        ));
        var definitionRule = service.create(createCommand(
            "tenant-a",
            "manager-1",
            "delegate-purchase",
            DelegationScope.DEFINITION,
            "purchase-payment",
            NOW,
            NOW.plusSeconds(172_800),
            "purchase specialist",
            "create-definition",
            "create-definition-key"
        ));
        var revoke = new ApprovalDelegationService.RevokeDelegationCommand(
            context(
                "tenant-a",
                "manager-1",
                "revoke-definition",
                "revoke-definition-key"
            ),
            definitionRule.ruleId(),
            "back from leave"
        );

        var revoked = service.revoke(revoke);
        var replayed = service.revoke(revoke);

        assertEquals(revoked, replayed);
        assertEquals(DelegationStatus.REVOKED, revoked.status());
        assertEquals(2, revoked.version());
        assertEquals(
            "delegate-global",
            service.resolve(
                "tenant-a",
                "manager-1",
                "purchase-payment",
                NOW.plusSeconds(7_200)
            ).orElseThrow().delegateId()
        );
        assertEquals(1, countAudit("DELEGATION_RULE_REVOKED"));
        assertEquals(1, service.findMine("tenant-a", "manager-1", false).size());
        assertEquals(2, service.findMine("tenant-a", "manager-1", true).size());
    }

    private long countAudit(String action) {
        Long count = jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Long.class,
            action
        );
        return count == null ? 0 : count;
    }

    private ApprovalDelegationService.CreateDelegationCommand createCommand(
        String tenantId,
        String principalId,
        String delegateId,
        DelegationScope scope,
        String definitionKey,
        Instant validFrom,
        Instant validUntil,
        String reason,
        String requestId,
        String idempotencyKey
    ) {
        return new ApprovalDelegationService.CreateDelegationCommand(
            context(tenantId, principalId, requestId, idempotencyKey),
            delegateId,
            scope,
            definitionKey,
            validFrom,
            validUntil,
            reason
        );
    }

    private RequestContext context(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            "trace-" + requestId
        );
    }
}
