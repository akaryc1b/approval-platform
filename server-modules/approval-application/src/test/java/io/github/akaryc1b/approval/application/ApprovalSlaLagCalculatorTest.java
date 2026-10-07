package io.github.akaryc1b.approval.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalSlaLagCalculatorTest {
    @Test
    void calendarModesBoundariesAndDst() {
        assertEquals(21, ApprovalSlaLagCalculatorChecks.run());
    }
}
