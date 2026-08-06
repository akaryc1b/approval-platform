package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.VersionStability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalAssistanceGovernanceHistoryQueryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-06T01:30:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_ai_governance_history_test")
        .withUsername("approval")
        .withPassword("approval");

    private static NamedParameterJdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static JdbcApprovalAssistanceGovernanceHistoryQuery query;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        var transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        query = new JdbcApprovalAssistanceGovernanceHistoryQuery(
            dataSource,
            transactionManager
        );
    }

    @Test
    void emptyWindowReturnsCompleteClosedZeroCounts() {
        var summary = query.summarize(window("tenant-history-empty"));

        assertEquals(0, summary.totalEvidence());
        assertEquals(AiOutcomeClassification.values().length, summary.outcomeCounts().size());
        assertEquals(UseCase.values().length, summary.useCaseCounts().size());
        assertNull(summary.earliestRecordedAt());
        assertNull(summary.latestRecordedAt());
        assertFalse(summary.versionDriftDetected());
    }

    @Test
    void aggregatesOnlyTheExactTenantWindowWithStateRetentionAndVersionStability() {
        String tenant = "tenant-history-aggregate";
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
            "tenant-history-other",
            "other-tenant",
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
            "outside-window",
            UseCase.RISK_REVIEW,
            AiOutcomeClassification.SUCCESS,
            true,
            true,
            "risk-version-v1",
            NOW.minusSeconds(7_200),
            NOW.plusSeconds(10_000),
            false
        );

        var summary = query.summarize(window(tenant));

        assertEquals(3, summary.totalEvidence());
        assertEquals(2, summary.activeEvidence());
        assertEquals(1, summary.tombstonedEvidence());
        assertEquals(2, summary.providerInvocationCount());
        assertEquals(2, summary.providerAttemptCount());
        assertEquals(1, summary.advisoryResultCount());
        assertEquals(1, summary.retentionDueCount());
        assertEquals(NOW.minusSeconds(1_000), summary.earliestRecordedAt());
        assertEquals(NOW.minusSeconds(700), summary.latestRecordedAt());
        assertEquals(
            1,
            summary.outcomeCounts().stream()
                .filter(count -> count.classification() == AiOutcomeClassification.SUCCESS)
                .findFirst()
                .orElseThrow()
                .evidenceCount()
        );
        assertEquals(
            2,
            summary.outcomeCounts().stream()
                .filter(count -> count.classification() == AiOutcomeClassification.REJECTED)
                .findFirst()
                .orElseThrow()
                .evidenceCount()
        );
        var summaryUseCase = summary.useCaseCounts().stream()
            .filter(count -> count.useCase() == UseCase.SUMMARY)
            .findFirst()
            .orElseThrow();
        assertEquals(2, summaryUseCase.evidenceCount());
        assertEquals(2, summaryUseCase.distinctVersionBundleCount());
        assertEquals(
            VersionStability.MULTIPLE_VERSION_BUNDLES,
            summaryUseCase.versionStability()
        );
        assertTrue(summary.versionDriftDetected());
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
        );
        parameters
            .addValue("storedEventId", storedEventId)
            .addValue("storedEventHash", storedEventHash);

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
                  event_hash,happened_at,delete_reason,deletion_request_hash
                ) values (
                  :tenantId,:storedEventId,:evidenceId,1,'STORED',:zeroHash,
                  :storedEventHash,:recordedAt,null,null
                )
                """, parameters.addValue("zeroHash", "0".repeat(64)));
            jdbc.update("""
                insert into ap_ai_approval_assistance_evidence_state (
                  tenant_id,evidence_id,revision,state,delete_reason,tombstoned_at,
                  deletion_request_hash,tombstone_hash,current_event_hash,updated_at
                ) values (
                  :tenantId,:evidenceId,1,'ACTIVE',null,null,null,null,
                  :storedEventHash,:recordedAt
                )
                """, parameters);
            if (tombstoned) {
                tombstone(parameters, seed, recordedAt.plusSeconds(10));
            }
        });
    }

    private static void tombstone(
        MapSqlParameterSource parameters,
        String seed,
        Instant tombstonedAt
    ) {
        parameters
            .addValue("tombstoneEventId", uuid(seed + "-tombstone-event"))
            .addValue("deletionRequestHash", hash(seed + "-deletion-request"))
            .addValue("tombstoneHash", hash(seed + "-tombstone"))
            .addValue("tombstoneEventHash", hash(seed + "-tombstone-event-hash"))
            .addValue("tombstonedAt", Timestamp.from(tombstonedAt));
        jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash
            ) values (
              :tenantId,:tombstoneEventId,:evidenceId,2,'TOMBSTONED',
              :storedEventHash,:tombstoneEventHash,:tombstonedAt,
              'DATA_SUBJECT_REQUEST',:deletionRequestHash
            )
            """, parameters);
        jdbc.update("""
            update ap_ai_approval_assistance_evidence_state
            set revision=2,state='TOMBSTONED',delete_reason='DATA_SUBJECT_REQUEST',
                tombstoned_at=:tombstonedAt,
                deletion_request_hash=:deletionRequestHash,
                tombstone_hash=:tombstoneHash,
                current_event_hash=:tombstoneEventHash,
                updated_at=:tombstonedAt
            where tenant_id=:tenantId and evidence_id=:evidenceId
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
        Double confidence = advisoryResult ? 0.8 : null;
        String confidenceBand = advisoryResult ? "HIGH" : null;
        return new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("evidenceId", evidenceId)
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
            .addValue("confidenceScore", confidence, Types.DOUBLE)
            .addValue("confidenceBand", confidenceBand, Types.VARCHAR)
            .addValue("requestedAt", Timestamp.from(recordedAt.minusSeconds(1)))
            .addValue("recordedAt", Timestamp.from(recordedAt))
            .addValue("retentionUntil", Timestamp.from(retentionUntil))
            .addValue("evidenceHash", hash(seed + "-evidence-hash"));
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
}
