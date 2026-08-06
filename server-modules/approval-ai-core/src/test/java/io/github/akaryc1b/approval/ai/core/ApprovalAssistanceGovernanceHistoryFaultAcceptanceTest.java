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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalAssistanceGovernanceHistoryFaultAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");
    private static final HistoryWindow WINDOW = new HistoryWindow(
        "tenant-p7-history",
        NOW.minusSeconds(3_600),
        NOW,
        NOW
    );

    @Test
    void activeAndTombstonedCountsMustExactlyEqualTotal() {
        assertThrows(
            IllegalArgumentException.class,
            () -> summary(2, 1, 0, 0, 0, 0, 0, outcomes(2), useCases(2, 0, 0))
        );
    }

    @Test
    void attemptAndInvocationCountsCannotDiverge() {
        assertThrows(
            IllegalArgumentException.class,
            () -> summary(1, 1, 0, 1, 0, 0, 0, outcomes(1), useCases(1, 1, 0))
        );
    }

    @Test
    void retentionCannotExceedActiveEvidence() {
        assertThrows(
            IllegalArgumentException.class,
            () -> summary(1, 0, 1, 0, 0, 0, 1, outcomes(1), useCases(1, 0, 0))
        );
    }

    @Test
    void outcomeAndUseCaseAggregatesMustMatchExactDurableTotals() {
        List<OutcomeCount> wrongOutcomes = outcomes(1);
        wrongOutcomes = replaceOutcome(
            wrongOutcomes,
            AiOutcomeClassification.SUCCESS,
            0
        );
        List<UseCaseCount> wrongUseCases = List.of(
            new UseCaseCount(
                UseCase.SUMMARY,
                1,
                0,
                0,
                1,
                VersionStability.SINGLE_VERSION_BUNDLE
            ),
            UseCaseCount.empty(UseCase.MATERIAL_COMPLETENESS),
            UseCaseCount.empty(UseCase.RISK_REVIEW)
        );

        List<OutcomeCount> exactWrongOutcomes = wrongOutcomes;
        assertThrows(
            IllegalArgumentException.class,
            () -> summary(
                1,
                1,
                0,
                0,
                0,
                0,
                0,
                exactWrongOutcomes,
                useCases(1, 0, 0)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> summary(
                2,
                2,
                0,
                0,
                0,
                0,
                0,
                outcomes(2),
                wrongUseCases
            )
        );
    }

    @Test
    void aggregateAdditionOverflowFailsClosed() {
        List<OutcomeCount> overflow = Arrays.stream(AiOutcomeClassification.values())
            .map(classification -> new OutcomeCount(classification, 0))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        overflow.set(
            AiOutcomeClassification.SUCCESS.ordinal(),
            new OutcomeCount(AiOutcomeClassification.SUCCESS, Long.MAX_VALUE)
        );
        overflow.set(
            AiOutcomeClassification.DISABLED.ordinal(),
            new OutcomeCount(AiOutcomeClassification.DISABLED, 1)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> summary(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                0,
                0,
                0,
                0,
                0,
                overflow,
                useCases(Long.MAX_VALUE, 0, 0)
            )
        );
    }

    private static HistorySummary summary(
        long total,
        long active,
        long tombstoned,
        long invocations,
        long attempts,
        long advisories,
        long retention,
        List<OutcomeCount> outcomes,
        List<UseCaseCount> useCases
    ) {
        Instant first = total == 0 ? null : NOW.minusSeconds(60);
        Instant last = total == 0 ? null : NOW.minusSeconds(1);
        return new HistorySummary(
            WINDOW,
            total,
            active,
            tombstoned,
            invocations,
            attempts,
            advisories,
            0,
            0,
            retention,
            first,
            last,
            outcomes,
            useCases
        );
    }

    private static List<OutcomeCount> outcomes(long success) {
        return Arrays.stream(AiOutcomeClassification.values())
            .map(classification -> new OutcomeCount(
                classification,
                classification == AiOutcomeClassification.SUCCESS ? success : 0
            ))
            .toList();
    }

    private static List<OutcomeCount> replaceOutcome(
        List<OutcomeCount> source,
        AiOutcomeClassification classification,
        long count
    ) {
        List<OutcomeCount> output = new ArrayList<>(source);
        output.set(classification.ordinal(), new OutcomeCount(classification, count));
        return List.copyOf(output);
    }

    private static List<UseCaseCount> useCases(
        long evidence,
        long invocations,
        long advisories
    ) {
        UseCaseCount summary = evidence == 0
            ? UseCaseCount.empty(UseCase.SUMMARY)
            : new UseCaseCount(
                UseCase.SUMMARY,
                evidence,
                invocations,
                advisories,
                1,
                VersionStability.SINGLE_VERSION_BUNDLE
            );
        return List.of(
            summary,
            UseCaseCount.empty(UseCase.MATERIAL_COMPLETENESS),
            UseCaseCount.empty(UseCase.RISK_REVIEW)
        );
    }
}
