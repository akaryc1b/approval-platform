package io.github.akaryc1b.approval.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalSlaLifecycleTimingCalculatorTest {
    @Test
    void distinguishesGrossElapsedFromPauseAdjustedPolicyTime() {
        assertEquals(10, ApprovalSlaLifecycleTimingCalculatorChecks.lifecycleAndPolicySemantics());
    }

    @Test
    void handlesWeekendsOverridesOvernightAndDst() {
        assertEquals(8, ApprovalSlaLifecycleTimingCalculatorChecks.calendarSemantics());
    }

    @Test
    void rejectsUnavailableAndInvalidEvidence() {
        assertEquals(13, ApprovalSlaLifecycleTimingCalculatorChecks.rejectsUnavailableOrInvalidEvidence());
    }

    @Test
    void rejectsVersionAndIdentityDriftWithoutBreakingPolicyInheritance() {
        assertEquals(16, ApprovalSlaLifecycleTimingCalculatorChecks.rejectsVersionAndIdentityDrift());
    }

    @Test
    void reusesPinnedVersionsDeterministicallyWithoutSideEffects() {
        assertEquals(7, ApprovalSlaLifecycleTimingCalculatorChecks.pinnedVersionsAndPureReplay());
    }

    @Test
    void validatesResultContracts() {
        assertEquals(9, ApprovalSlaLifecycleTimingCalculatorChecks.validatesResultContracts());
    }

    @Test
    void usesLifecycleTimestampsForSuccessfulAndOtherTerminalOutcomes() {
        assertEquals(4, ApprovalSlaLifecycleTimingCalculatorChecks.usesLifecycleTimestampsForEveryTerminalOutcome());
    }
}
