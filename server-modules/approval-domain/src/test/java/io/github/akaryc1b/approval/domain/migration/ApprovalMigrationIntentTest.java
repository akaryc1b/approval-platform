package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.Test;

import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.NOW;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.intent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationIntentTest {

    @Test
    void intentUsesClosedTransitionsAndImmutableRevisionProgression() {
        ApprovalMigrationIntent running = intent().transitioned(IntentStatus.RUNNING, NOW.plusSeconds(1));
        ApprovalMigrationIntent completed = running.transitioned(
            IntentStatus.COMPLETED,
            NOW.plusSeconds(2)
        );

        assertEquals(3, completed.revision());
        assertTrue(completed.terminal());
        assertThrows(
            IllegalArgumentException.class,
            () -> completed.transitioned(IntentStatus.RUNNING, NOW.plusSeconds(3))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> intent().transitioned(IntentStatus.COMPLETED, NOW.plusSeconds(1))
        );
    }
}
