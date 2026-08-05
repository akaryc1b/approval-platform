package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiResponsesTransportUsageAccountingTest {

    private static final Instant NOW = Instant.parse("2026-08-05T11:20:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TENANT_HASH = OpenAiResponsesProtocol.sha256Utf8("tenant-a");

    @Test
    void closingBeforeDispatchDoesNotConsumeUsage() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger();
        OpenAiResponsesTransportAdmission admission = admission(ledger);

        try (OpenAiResponsesTransportAdmission.Permit ignored =
                 admission.admit(request(), 100)) {
            // No dispatch.
        }

        assertEquals(0, ledger.snapshot(TENANT_HASH, NOW).committedRequests());
        assertEquals(0, ledger.snapshot(TENANT_HASH, NOW).committedUpperBoundMicros());
    }

    @Test
    void dispatchRecordsOneAdmittedUpperBoundAndTerminalResultDoesNotDoubleCount() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger();
        OpenAiResponsesTransportAdmission admission = admission(ledger);
        OpenAiResponsesTransportPort.Request request = request();

        try (OpenAiResponsesTransportAdmission.Permit permit = admission.admit(request, 100)) {
            assertEquals(202, permit.costEstimate().estimatedMicros());
            permit.markDispatched(request);
            permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS);
        }

        var usage = ledger.snapshot(TENANT_HASH, NOW);
        assertEquals(1, usage.committedRequests());
        assertEquals(202, usage.committedUpperBoundMicros());
    }

    @Test
    void duplicateDispatchFailsWithoutDoubleCounting() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger();
        OpenAiResponsesTransportAdmission admission = admission(ledger);
        OpenAiResponsesTransportPort.Request request = request();

        try (OpenAiResponsesTransportAdmission.Permit permit = admission.admit(request, 100)) {
            permit.markDispatched(request);
            assertThrows(
                OpenAiResponsesTransportException.class,
                () -> permit.markDispatched(request)
            );
            permit.record(OpenAiResponsesTransportControls.Outcome.UNKNOWN);
        }

        assertEquals(1, ledger.snapshot(TENANT_HASH, NOW).committedRequests());
        assertEquals(202, ledger.snapshot(TENANT_HASH, NOW).committedUpperBoundMicros());
    }

    private static OpenAiResponsesTransportAdmission admission(
        OpenAiResponsesRuntimeUsageLedger ledger
    ) {
        var killSwitch = new OpenAiResponsesTransportControls.KillSwitchSnapshot(
            OpenAiResponsesProtocol.PROVIDER_ID,
            OpenAiResponsesProtocol.PROVIDER_VERSION,
            7,
            true,
            "kill-policy-v1"
        );
        var costPolicy = new OpenAiResponsesTransportControls.CostPolicy(
            "pricing-v1",
            OpenAiResponsesProtocol.MODEL_SNAPSHOT,
            1,
            2,
            1_000,
            NOW.minusSeconds(60),
            NOW.plusSeconds(600)
        );
        return new OpenAiResponsesTransportAdmission(
            TENANT_HASH,
            () -> killSwitch,
            killSwitch.generation(),
            killSwitch.evidenceHash(),
            new OpenAiResponsesTransportControls.CircuitBreaker(
                3,
                Duration.ofSeconds(30)
            ),
            new OpenAiResponsesTransportControls.RateLimiter(
                10,
                100,
                100,
                Duration.ofMinutes(1)
            ),
            costPolicy,
            ledger,
            CLOCK
        );
    }

    private static OpenAiResponsesRuntimeUsageLedger ledger() {
        return new OpenAiResponsesRuntimeUsageLedger(
            10,
            100,
            100,
            Duration.ofMinutes(1),
            1_000
        );
    }

    private static OpenAiResponsesTransportPort.Request request() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        return new OpenAiResponsesTransportPort.Request(
            body,
            OpenAiResponsesProtocol.sha256(body),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            () -> false
        );
    }
}
