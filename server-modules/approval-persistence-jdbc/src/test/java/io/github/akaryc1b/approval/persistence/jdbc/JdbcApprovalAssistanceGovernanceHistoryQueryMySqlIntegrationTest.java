package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.VersionStability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalAssistanceGovernanceHistoryQueryMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_ai_governance_history")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    private static NamedParameterJdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ApprovalAssistanceGovernanceHistoryQuery query;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
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
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        query = JdbcApprovalAssistanceGovernanceHistoryQueryFactory.create(
            dataSource,
            transactionManager
        );
    }

    @Test
    void selectsMySqlAuthorityAndReturnsCompleteClosedZeroCounts() {
        assertInstanceOf(JdbcMySqlApprovalAssistanceGovernanceHistoryQuery.class, query);

        var summary = query.summarize(window("Tenant-H9-Empty"));

        assertEquals(0, summary.totalEvidence());
        assertEquals(AiOutcomeClassification.values().length, summary.outcomeCounts().size());
        assertEquals(UseCase.values().length, summary.useCaseCounts().size());
        assertNull(summary.earliestRecordedAt());
        assertNull(summary.latestRecordedAt());
        assertFalse(summary.versionDriftDetected());
    }

    @Test
    void aggregatesExactTenantHalfOpenWindowStateRetentionAndVersionStability() {
        String tenant = "Tenant-H9-Aggregate";
        insertEvidence(
            tenant,
            "summary-success-v1",
            UseCase.SUMMARY,
            AiOutcomeClassification.SUCCESS,
            true,
            true,
            "summary-version-v1",
            NOW.minusSeconds(1_000),
            NOW.plusSeconds(10_000),
            false
        );
        insertEvidence(
            tenant,
            "summary-rejected-v2",
            UseCase.SUMMARY,
            AiOutcomeClassification.REJECTED,
            true,
            false,
            "summary-version-v2",
            NOW.minusSeconds(800),
            NOW.plusSeconds(10_000),
            true
        );
        insertEvidence(
            tenant,
            "material-retention-due",
            UseCase.MATERIAL_COMPLETENESS,
            AiOutcomeClassification.REJECTED,
            false,
            false,
            "material-version-v1",
            NOW.minusSeconds(700),
            NOW.minusSeconds(100),
            false
        );
        insertEvidence(
            "tenant-h9-aggregate",
            "case-distinct-tenant",
            UseCase.RISK_REVIEW,
            AiOutcomeClassification.SUCCESS,
            true,
            true,
            "risk-version-v1",
            NOW.minusSeconds(600),
            NOW.plusSeconds(10_000),
            false
        );
        insertEvidence(
            tenant,
            "outside-before",
            UseCase.RISK_REVIEW,
            AiOutcomeClassification.SUCCESS,
            true,
            true,
            "risk-version-v1",
            NOW.minusSeconds(3_601),
            NOW.plusSeconds(10_000),
            false
        );
        insertEvidence(
            tenant,
            "outside-at-exclusive-end",
            UseCase.RISK_REVIEW,
            AiOutcomeClassification.SUCCESS,
            true,
            true,
            "risk-version-v1",
            NOW,
            NOW.plusSeconds(10_000),
            false
        );

        int evidenceBefore = count("ap_ai_approval_assistance_evidence", tenant);
        int eventBefore = count("ap_ai_approval_assistance_evidence_event", tenant);
        int stateBefore = count("ap_ai_approval_assistance_evidence_state", tenant);

        var summary = query.summarize(window(tenant));

        assertEquals(3, summary.totalEvidence());
        assertEquals(2, summary.activeEvidence());
        assertEquals(1, summary.tombstonedEvidence());
        assertEquals(2, summary.providerInvocationCount());
        assertEquals(2, summary.providerAttemptCount());
        assertEquals(1, summary.advisoryResultCount());
        assertEquals(0, summary.unsafeRetryCount());
        assertEquals(0, summary.postInvocationFallbackCount());
        assertEquals(1, summary.retentionDueCount());
        assertEquals(NOW.minusSeconds(1_000), summary.earliestRecordedAt());
        assertEquals(NOW.minusSeconds(700), summary.latestRecordedAt());
        assertEquals(
            1,
            outcomeCount(summary, AiOutcomeClassification.SUCCESS)
        );
        assertEquals(
            2,
            outcomeCount(summary, AiOutcomeClassification.REJECTED)
        );

        var summaryUseCase = summary.useCaseCounts().stream()
            .filter(count -> count.useCase() == UseCase.SUMMARY)
            .findFirst()
            .orElseThrow();
        assertEquals(2, summaryUseCase.evidenceCount());
        assertEquals(2, summaryUseCase.providerInvocationCount());
        assertEquals(1, summaryUseCase.advisoryResultCount());
        assertEquals(2, summaryUseCase.distinctVersionBundleCount());
        assertEquals(
            VersionStability.MULTIPLE_VERSION_BUNDLES,
            summaryUseCase.versionStability()
        );

        var materialUseCase = summary.useCaseCounts().stream()
            .filter(count -> count.useCase() == UseCase.MATERIAL_COMPLETENESS)
            .findFirst()
            .orElseThrow();
        assertEquals(1, materialUseCase.evidenceCount());
        assertEquals(
            VersionStability.SINGLE_VERSION_BUNDLE,
            materialUseCase.versionStability()
        );

        var riskUseCase = summary.useCaseCounts().stream()
            .filter(count -> count.useCase() == UseCase.RISK_REVIEW)
            .findFirst()
            .orElseThrow();
        assertEquals(0, riskUseCase.evidenceCount());
        assertEquals(VersionStability.EMPTY, riskUseCase.versionStability());
        assertTrue(summary.versionDriftDetected());

        assertEquals(evidenceBefore, count("ap_ai_approval_assistance_evidence", tenant));
        assertEquals(eventBefore, count("ap_ai_approval_assistance_evidence_event", tenant));
        assertEquals(stateBefore, count("ap_ai_approval_assistance_evidence_state", tenant));
    }

    private static long outcomeCount(
        ApprovalAssistanceGovernanceHistoryQuery.HistorySummary summary,
        AiOutcomeClassification classification
    ) {
        return summary.outcomeCounts().stream()
            .filter(count -> count.classification() == classification)
            .findFirst()
            .orElseThrow()
            .evidenceCount();
    }

    private static HistoryWindow window(String tenantId) {
        return new HistoryWindow(
            tenantId,
            NOW.minusSeconds(3_600),
            NOW,
            NOW
        );
    }

    private static void insertEvidence(
        String tenantId,
        String seed,
        UseCase useCase,
        AiOutcomeClassification classification,
        boolean providerInvocation,
        boolean advisoryResult,
        String versionSeed,
        Instant recordedAt,
        Instant retentionUntil,
        boolean tombstoned
    ) {
        UUID evidenceId = uuid(seed + "-evidence");
        UUID storedEventId = uuid(seed + "-stored-event");
        String storedEventHash = hash(seed + "-stored-event-hash");
        MapSqlParameterSource parameters = evidenceParameters(
            tenantId,
            evidenceId,
            seed,
            useCase,
            classification,
            providerInvocation,
            advisoryResult,
            versionSeed,
            recordedAt,
            retentionUntil
        ).addValue("storedEventId", storedEventId.toString())
            .addValue("storedEventHash", storedEventHash)
            .addValue("zeroHash", "0".repeat(64));

        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                insert into ap_ai_approval_assistance_evidence (
                  tenant_id,evidence_id,request_evidence_hash,subject_evidence_hash,
                  resource_evidence_hash,projection_evidence_hash,execution_evidence_hash,
                  route_evidence_hash,version_evidence_hash,outcome_evidence_hash,
                  use_case,classification,provider_id,provider_version,model_provider_id,
                  model_id,model_version,prompt_template_id,prompt_template_version,
                  prompt_template_hash,knowledge_source_id,knowledge_source_version,
                  knowledge_source_hash,knowledge_contains_customer_data,policy_id,
                  policy_version,policy_hash,output_schema_id,output_schema_version,
                  provider_attempts,provider_invocation_started,retry_attempted,
                  post_invocation_fallback_attempted,kill_switch_generation,
                  advisory_result_present,observation_count,risk_signal_count,
                  missing_material_count,recommendation_count,evidence_reference_count,
                  limitation_count,confidence_score,confidence_band,requested_at,
                  recorded_at,retention_until,evidence_hash
                ) values (
                  :tenantId,:evidenceId,:requestHash,:subjectHash,:resourceHash,
                  :projectionHash,:executionHash,:routeHash,:versionHash,:outcomeHash,
                  :useCase,:classification,'openai-responses','2025-08-07',
                  'openai-responses','gpt-5-mini','2025-08-07',:promptTemplateId,
                  'v1',:promptHash,'none','none','none',false,'approval-assistance',
                  'v1',:policyHash,'approval-assistance',1,:providerAttempts,
                  :providerInvocation,false,false,7,:advisoryResult,:observationCount,
                  0,0,:recommendationCount,:evidenceReferenceCount,0,:confidenceScore,
                  :confidenceBand,:requestedAt,:recordedAt,:retentionUntil,:evidenceHash
                )
                """, parameters);
            jdbc.update("""
                insert into ap_ai_approval_assistance_evidence_event (
                  tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
                  event_hash,happened_at,delete_reason,deletion_request_hash,tombstone_hash
                ) values (
                  :tenantId,:storedEventId,:evidenceId,1,'STORED',:zeroHash,
                  :storedEventHash,:recordedAt,null,null,null
                )
                """, parameters);
        });

        if (tombstoned) {
            transactions.executeWithoutResult(
                status -> tombstone(parameters, seed, recordedAt.plusSeconds(10))
            );
        }
    }

    private static void tombstone(
        MapSqlParameterSource parameters,
        String seed,
        Instant tombstonedAt
    ) {
        parameters
            .addValue("tombstoneEventId", uuid(seed + "-tombstone-event").toString())
            .addValue("deletionRequestHash", hash(seed + "-deletion-request"))
            .addValue("tombstoneHash", hash(seed + "-tombstone"))
            .addValue("tombstoneEventHash", hash(seed + "-tombstone-event-hash"))
            .addValue("tombstonedAt", local(tombstonedAt));
        jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash,tombstone_hash
            ) values (
              :tenantId,:tombstoneEventId,:evidenceId,2,'TOMBSTONED',
              :storedEventHash,:tombstoneEventHash,:tombstonedAt,
              'DATA_SUBJECT_REQUEST',:deletionRequestHash,:tombstoneHash
            )
            """, parameters);
    }

    private static MapSqlParameterSource evidenceParameters(
        String tenantId,
        UUID evidenceId,
        String seed,
        UseCase useCase,
        AiOutcomeClassification classification,
        boolean providerInvocation,
        boolean advisoryResult,
        String versionSeed,
        Instant recordedAt,
        Instant retentionUntil
    ) {
        String promptTemplateId = switch (useCase) {
            case SUMMARY -> "approval-summary";
            case MATERIAL_COMPLETENESS -> "approval-material-completeness";
            case RISK_REVIEW -> "approval-risk-review";
        };
        return new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("evidenceId", evidenceId.toString())
            .addValue("requestHash", hash(seed + "-request"))
            .addValue("subjectHash", hash(seed + "-subject"))
            .addValue("resourceHash", hash(seed + "-resource"))
            .addValue("projectionHash", hash(seed + "-projection"))
            .addValue("executionHash", hash(seed + "-execution"))
            .addValue(
                "routeHash",
                providerInvocation ? hash(seed + "-route") : null,
                Types.CHAR
            )
            .addValue("versionHash", hash(versionSeed))
            .addValue("outcomeHash", hash(seed + "-outcome"))
            .addValue("useCase", useCase.name())
            .addValue("classification", classification.name())
            .addValue("promptTemplateId", promptTemplateId)
            .addValue("promptHash", hash(seed + "-prompt"))
            .addValue("policyHash", hash(seed + "-policy"))
            .addValue("providerAttempts", providerInvocation ? 1 : 0)
            .addValue("providerInvocation", providerInvocation)
            .addValue("advisoryResult", advisoryResult)
            .addValue("observationCount", advisoryResult ? 1 : 0)
            .addValue("recommendationCount", advisoryResult ? 1 : 0)
            .addValue("evidenceReferenceCount", advisoryResult ? 1 : 0)
            .addValue("confidenceScore", advisoryResult ? 0.8d : null, Types.DOUBLE)
            .addValue("confidenceBand", advisoryResult ? "HIGH" : null, Types.VARCHAR)
            .addValue("requestedAt", local(recordedAt.minusSeconds(1)))
            .addValue("recordedAt", local(recordedAt))
            .addValue("retentionUntil", local(retentionUntil))
            .addValue("evidenceHash", hash(seed + "-evidence-hash"));
    }

    private static int count(String table, String tenantId) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where tenant_id=:tenantId",
            new MapSqlParameterSource("tenantId", tenantId),
            Integer.class
        );
    }

    private static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
