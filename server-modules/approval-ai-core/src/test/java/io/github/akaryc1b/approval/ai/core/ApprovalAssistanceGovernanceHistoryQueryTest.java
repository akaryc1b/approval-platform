package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.OutcomeCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.UseCaseCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.VersionStability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAssistanceGovernanceHistoryQueryTest {

    private static final Instant NOW = Instant.parse("2026-08-06T00:30:00Z");

    @Test
    void windowIsCanonicalBoundedAndCannotReachTheFuture() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoryWindow(
                " tenant-a",
                NOW.minusSeconds(60),
                NOW,
                NOW
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoryWindow("tenant-a", NOW, NOW, NOW)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoryWindow(
                "tenant-a",
                NOW.minusSeconds(32L * 86_400L),
                NOW,
                NOW
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoryWindow(
                "tenant-a",
                NOW.minusSeconds(60),
                NOW.plusSeconds(1),
                NOW
            )
        );
    }

    @Test
    void emptyHistoryCarriesCompleteClosedEnumCounts() {
        HistorySummary summary = HistorySummary.empty(window());

        assertEquals(0, summary.totalEvidence());
        assertEquals(AiOutcomeClassification.values().length, summary.outcomeCounts().size());
        assertEquals(UseCase.values().length, summary.useCaseCounts().size());
        assertFalse(summary.versionDriftDetected());
    }

    @Test
    void coherentHistoryDetectsUseCaseVersionDrift() {
        HistorySummary summary = new HistorySummary(
            window(),
            3,
            2,
            1,
            2,
            2,
            1,
            0,
            0,
            1,
            NOW.minusSeconds(1_200),
            NOW.minusSeconds(300),
            outcomes(2, 1),
            List.of(
                new UseCaseCount(
                    UseCase.SUMMARY,
                    2,
                    1,
                    1,
                    2,
                    VersionStability.MULTIPLE_VERSION_BUNDLES
                ),
                new UseCaseCount(
                    UseCase.MATERIAL_COMPLETENESS,
                    1,
                    1,
                    0,
                    1,
                    VersionStability.SINGLE_VERSION_BUNDLE
                ),
                UseCaseCount.empty(UseCase.RISK_REVIEW)
            )
        );

        assertTrue(summary.versionDriftDetected());
        assertEquals(1, summary.retentionDueCount());
    }

    @Test
    void summaryRejectsUnsafeRetryAndIncoherentAggregates() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new HistorySummary(
                window(),
                1,
                1,
                0,
                1,
                1,
                0,
                1,
                0,
                0,
                NOW.minusSeconds(100),
                NOW.minusSeconds(100),
                outcomes(1, 0),
                List.of(
                    new UseCaseCount(
                        UseCase.SUMMARY,
                        1,
                        1,
                        0,
                        1,
                        VersionStability.SINGLE_VERSION_BUNDLE
                    ),
                    UseCaseCount.empty(UseCase.MATERIAL_COMPLETENESS),
                    UseCaseCount.empty(UseCase.RISK_REVIEW)
                )
            )
        );
    }

    private static HistoryWindow window() {
        return new HistoryWindow(
            "tenant-a",
            NOW.minusSeconds(3_600),
            NOW,
            NOW
        );
    }

    private static List<OutcomeCount> outcomes(long success, long rejected) {
        return List.of(AiOutcomeClassification.values()).stream()
            .map(classification -> new OutcomeCount(
                classification,
                classification == AiOutcomeClassification.SUCCESS
                    ? success
                    : classification == AiOutcomeClassification.REJECTED
                        ? rejected
                        : 0
            ))
            .toList();
    }
}
