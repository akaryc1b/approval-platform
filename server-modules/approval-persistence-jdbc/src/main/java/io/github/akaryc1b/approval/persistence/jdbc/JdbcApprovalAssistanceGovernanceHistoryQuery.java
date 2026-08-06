package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.OutcomeCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.UseCaseCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.VersionStability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** Read-only repeatable-read P6-E aggregation over immutable V49 evidence. */
public final class JdbcApprovalAssistanceGovernanceHistoryQuery
    implements ApprovalAssistanceGovernanceHistoryQuery {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcApprovalAssistanceGovernanceHistoryQuery(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.transactions = new TransactionTemplate(
            Objects.requireNonNull(
                transactionManager,
                "transactionManager must not be null"
            )
        );
        this.transactions.setReadOnly(true);
        this.transactions.setIsolationLevel(
            TransactionDefinition.ISOLATION_REPEATABLE_READ
        );
    }

    @Override
    public HistorySummary summarize(HistoryWindow window) {
        HistoryWindow exact = Objects.requireNonNull(window, "window must not be null");
        try {
            return Objects.requireNonNull(
                transactions.execute(status -> summarizeOnce(exact)),
                "history transaction must return a result"
            );
        } catch (DataAccessException exception) {
            throw new HistoryQueryException(
                "approval-assistance governance history query failed",
                exception
            );
        }
    }

    private HistorySummary summarizeOnce(HistoryWindow window) {
        MapSqlParameterSource parameters = parameters(window);
        SummaryRow summary = jdbc.query("""
            select count(*) as total_evidence,
                   count(*) filter (where s.state='ACTIVE') as active_evidence,
                   count(*) filter (where s.state='TOMBSTONED') as tombstoned_evidence,
                   count(*) filter (where e.provider_invocation_started)
                     as provider_invocation_count,
                   coalesce(sum(e.provider_attempts),0) as provider_attempt_count,
                   count(*) filter (where e.advisory_result_present)
                     as advisory_result_count,
                   count(*) filter (where e.retry_attempted) as unsafe_retry_count,
                   count(*) filter (where e.post_invocation_fallback_attempted)
                     as post_invocation_fallback_count,
                   count(*) filter (
                     where s.state='ACTIVE' and e.retention_until<=:observedAt
                   ) as retention_due_count,
                   min(e.recorded_at) as earliest_recorded_at,
                   max(e.recorded_at) as latest_recorded_at
            from ap_ai_approval_assistance_evidence e
            join ap_ai_approval_assistance_evidence_state s
              on s.tenant_id=e.tenant_id and s.evidence_id=e.evidence_id
            where e.tenant_id=:tenantId
              and e.recorded_at>=:fromInclusive
              and e.recorded_at<:toExclusive
            """, parameters, (result, rowNumber) -> summaryRow(result))
            .stream()
            .findFirst()
            .orElseThrow(() -> new HistoryQueryException(
                "governance history aggregate returned no row"
            ));

        EnumMap<AiOutcomeClassification, Long> outcomes =
            new EnumMap<>(AiOutcomeClassification.class);
        for (AiOutcomeClassification classification : AiOutcomeClassification.values()) {
            outcomes.put(classification, 0L);
        }
        jdbc.query("""
            select e.classification,count(*) as evidence_count
            from ap_ai_approval_assistance_evidence e
            where e.tenant_id=:tenantId
              and e.recorded_at>=:fromInclusive
              and e.recorded_at<:toExclusive
            group by e.classification
            """, parameters, (result, rowNumber) -> {
                AiOutcomeClassification classification = AiOutcomeClassification.valueOf(
                    result.getString("classification")
                );
                outcomes.put(classification, result.getLong("evidence_count"));
                return classification;
            });

        EnumMap<UseCase, UseCaseCount> useCases = new EnumMap<>(UseCase.class);
        for (UseCase useCase : UseCase.values()) {
            useCases.put(useCase, UseCaseCount.empty(useCase));
        }
        jdbc.query("""
            select e.use_case,
                   count(*) as evidence_count,
                   count(*) filter (where e.provider_invocation_started)
                     as provider_invocation_count,
                   count(*) filter (where e.advisory_result_present)
                     as advisory_result_count,
                   count(distinct e.version_evidence_hash)
                     as distinct_version_bundle_count
            from ap_ai_approval_assistance_evidence e
            where e.tenant_id=:tenantId
              and e.recorded_at>=:fromInclusive
              and e.recorded_at<:toExclusive
            group by e.use_case
            """, parameters, (result, rowNumber) -> {
                UseCase useCase = UseCase.valueOf(result.getString("use_case"));
                long evidenceCount = result.getLong("evidence_count");
                long distinctVersions = result.getLong("distinct_version_bundle_count");
                VersionStability stability = distinctVersions == 1
                    ? VersionStability.SINGLE_VERSION_BUNDLE
                    : VersionStability.MULTIPLE_VERSION_BUNDLES;
                UseCaseCount count = new UseCaseCount(
                    useCase,
                    evidenceCount,
                    result.getLong("provider_invocation_count"),
                    result.getLong("advisory_result_count"),
                    distinctVersions,
                    stability
                );
                useCases.put(useCase, count);
                return useCase;
            });

        List<OutcomeCount> outcomeCounts = Arrays.stream(
            AiOutcomeClassification.values()
        ).map(classification -> new OutcomeCount(
            classification,
            outcomes.get(classification)
        )).toList();
        List<UseCaseCount> useCaseCounts = Arrays.stream(UseCase.values())
            .map(useCases::get)
            .toList();

        return new HistorySummary(
            window,
            summary.totalEvidence(),
            summary.activeEvidence(),
            summary.tombstonedEvidence(),
            summary.providerInvocationCount(),
            summary.providerAttemptCount(),
            summary.advisoryResultCount(),
            summary.unsafeRetryCount(),
            summary.postInvocationFallbackCount(),
            summary.retentionDueCount(),
            summary.earliestRecordedAt(),
            summary.latestRecordedAt(),
            outcomeCounts,
            useCaseCounts
        );
    }

    private static SummaryRow summaryRow(ResultSet result) throws SQLException {
        return new SummaryRow(
            result.getLong("total_evidence"),
            result.getLong("active_evidence"),
            result.getLong("tombstoned_evidence"),
            result.getLong("provider_invocation_count"),
            result.getLong("provider_attempt_count"),
            result.getLong("advisory_result_count"),
            result.getLong("unsafe_retry_count"),
            result.getLong("post_invocation_fallback_count"),
            result.getLong("retention_due_count"),
            instant(result, "earliest_recorded_at"),
            instant(result, "latest_recorded_at")
        );
    }

    private static Instant instant(ResultSet result, String name) throws SQLException {
        Timestamp value = result.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static MapSqlParameterSource parameters(HistoryWindow window) {
        return new MapSqlParameterSource()
            .addValue("tenantId", window.tenantId())
            .addValue("fromInclusive", Timestamp.from(window.fromInclusive()))
            .addValue("toExclusive", Timestamp.from(window.toExclusive()))
            .addValue("observedAt", Timestamp.from(window.observedAt()));
    }

    private record SummaryRow(
        long totalEvidence,
        long activeEvidence,
        long tombstonedEvidence,
        long providerInvocationCount,
        long providerAttemptCount,
        long advisoryResultCount,
        long unsafeRetryCount,
        long postInvocationFallbackCount,
        long retentionDueCount,
        Instant earliestRecordedAt,
        Instant latestRecordedAt
    ) {
    }

    public static final class HistoryQueryException extends RuntimeException {

        public HistoryQueryException(String message) {
            super(message);
        }

        public HistoryQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
