package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch.Snapshot;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.DispatchRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PreparedOrchestration;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanaryGate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanarySelection;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationPhase;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationRun;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.RunEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalMigrationOrchestrationRequestCanonicalizationTest {

    private static final String TENANT = "Tenant-D7-Time";
    private static final UUID INTENT_ID = uuid("intent");
    private static final Snapshot KILL_SWITCH = new Snapshot(
        1,
        false,
        "CONFIGURED_OFF",
        hash('1')
    );

    @Test
    void canonicalizesEveryD7RequestInstantToNearestMicrosecond() {
        PrepareRequest prepare = new PrepareRequest(
            TENANT,
            INTENT_ID,
            1,
            1,
            KILL_SWITCH,
            Instant.parse("2026-08-14T08:00:00.123456500Z"),
            "request-d7-prepare",
            "trace-d7"
        );
        OrchestrationRun run = run(prepare.happenedAt());
        PreparedOrchestration prepared = prepared(run, prepare.happenedAt());
        DispatchRequest dispatch = new DispatchRequest(
            run,
            uuid("attempt"),
            1,
            1,
            KILL_SWITCH,
            Instant.parse("2026-08-14T08:00:00.123456499Z"),
            "request-d7-dispatch",
            "trace-d7"
        );
        FinalizeRequest finalize = new FinalizeRequest(
            prepared,
            null,
            List.of(),
            Instant.parse("2026-08-14T08:00:00.999999500Z"),
            "request-d7-finalize",
            "trace-d7"
        );

        assertEquals(
            Instant.parse("2026-08-14T08:00:00.123457Z"),
            prepare.happenedAt()
        );
        assertEquals(
            Instant.parse("2026-08-14T08:00:00.123456Z"),
            dispatch.happenedAt()
        );
        assertEquals(
            Instant.parse("2026-08-14T08:00:01Z"),
            finalize.happenedAt()
        );
    }

    private static PreparedOrchestration prepared(
        OrchestrationRun run,
        Instant happenedAt
    ) {
        CanarySelection canary = new CanarySelection(
            run.canarySelectionId(),
            TENANT,
            run.planId(),
            run.intentId(),
            "CANONICAL_FIRST_V1",
            1,
            uuid("instance"),
            hash('2'),
            hash('3'),
            hash('4'),
            happenedAt,
            "request-d7-canary",
            "trace-d7"
        );
        OrchestrationEvent event = new OrchestrationEvent(
            uuid("event"),
            TENANT,
            run.runId(),
            1,
            RunEventType.PREPARED,
            PauseReason.NONE,
            null,
            run.runEvidenceHash(),
            hash('5'),
            happenedAt,
            "request-d7-event",
            "trace-d7"
        );
        return new PreparedOrchestration(
            run,
            canary,
            CanaryGate.PENDING,
            PauseReason.NONE,
            event,
            true,
            false,
            false
        );
    }

    private static OrchestrationRun run(Instant happenedAt) {
        return new OrchestrationRun(
            uuid("run"),
            TENANT,
            uuid("plan"),
            INTENT_ID,
            1,
            OrchestrationPhase.CANARY,
            1,
            uuid("selection"),
            1,
            hash('0'),
            hash('6'),
            hash('7'),
            happenedAt,
            "request-d7-run",
            "trace-d7"
        );
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(("d7-time:" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String hash(char value) {
        return Character.toString(value).repeat(64);
    }
}
