package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PurchasePaymentDemoSeedConflictDiagnosticTest {

    @Test
    void provisioningConflictStopsStartupWithoutRetryOrSubmission() {
        var seeder = mock(PurchasePaymentDemoSeeder.class);
        var forms = mock(ApprovalFormSubmissionService.class);
        var state = new PurchasePaymentDemoSeedState();
        var conflict = new IdempotencyConflictException("idempotency key conflicts with current command");
        when(seeder.apply()).thenThrow(conflict);
        var runner = new PurchasePaymentDemoConfiguration().purchasePaymentDemoSeedRunner(
            seeder, state, null, forms
        );

        var failure = assertThrows(IllegalStateException.class,
            () -> runner.run(new DefaultApplicationArguments(new String[0])));

        assertDiagnostic(failure, conflict);
        assertTrue(state.evidence().isEmpty());
        verify(seeder, times(1)).apply();
        verifyNoMoreInteractions(seeder);
        verifyNoInteractions(forms);
    }

    @Test
    void submissionConflictAlsoStopsWithoutRecordingReadinessOrRetrying() throws Exception {
        var scenario = PurchasePaymentDemoScenario.load(
            new ObjectMapper().findAndRegisterModules(),
            new ClassPathResource("demo/purchase-payment-golden-path.json"),
            new ClassPathResource("demo/purchase-payment-demo-seed.json")
        );
        var seeder = mock(PurchasePaymentDemoSeeder.class);
        var forms = mock(ApprovalFormSubmissionService.class);
        var state = new PurchasePaymentDemoSeedState();
        when(seeder.apply()).thenReturn(new PurchasePaymentDemoSeedState.SeedEvidence(
            scenario.tenantId(), scenario.request().businessKey(), UUID.randomUUID(), InstanceStatus.RUNNING,
            "purchase-payment", "engine-definition", List.of(UUID.randomUUID()), List.of(), Instant.now()
        ));
        var conflict = new IdempotencyConflictException("legacy receipt is not actor-bound");
        doThrow(conflict).when(forms).submit(any());
        var runner = new PurchasePaymentDemoConfiguration().purchasePaymentDemoSeedRunner(
            seeder, state, scenario, forms
        );

        var failure = assertThrows(IllegalStateException.class,
            () -> runner.run(new DefaultApplicationArguments(new String[0])));

        assertDiagnostic(failure, conflict);
        assertTrue(state.evidence().isEmpty());
        verify(seeder, times(1)).apply();
        verify(forms, times(1)).submit(any());
        verifyNoMoreInteractions(seeder, forms);
    }

    @Test
    void successRunsOnceAndUnrelatedFailuresAreNotMisclassified() {
        AtomicInteger invocations = new AtomicInteger();
        PurchasePaymentDemoConfiguration.runSeedWithConflictDiagnostic(invocations::incrementAndGet);
        assertEquals(1, invocations.get());
        var original = new IllegalArgumentException("unrelated invalid fixture");
        assertSame(original, assertThrows(IllegalArgumentException.class,
            () -> PurchasePaymentDemoConfiguration.runSeedWithConflictDiagnostic(() -> { throw original; })));
    }

    private void assertDiagnostic(IllegalStateException failure, IdempotencyConflictException conflict) {
        assertSame(conflict, failure.getCause());
        assertTrue(failure.getMessage().startsWith("PURCHASE_PAYMENT_DEMO_SEED_REPLAY_CONFLICT:"));
        assertTrue(failure.getMessage().contains("legacy unbound receipt or an actor/request conflict"));
        assertTrue(failure.getMessage().contains("cannot be automatically replayed or reseeded"));
        assertTrue(failure.getMessage().contains("preserve the database and receipts"));
        assertTrue(failure.getMessage().contains("disable approval.demo.purchase-payment.enabled"));
    }
}
