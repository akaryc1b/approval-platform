package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.invocation.ConnectorInvocationObservationSink;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationRequest;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedReadOnlyConnectorInvocationCoordinator;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservedReadOnlyConnectorInvocationServiceTest {

    @Test
    void recordsExactlyOneSecretFreeEvidenceAfterInvocation() {
        GovernedReadOnlyConnectorInvocationCoordinator coordinator = mock(
            GovernedReadOnlyConnectorInvocationCoordinator.class
        );
        InvocationRequest request = request();
        InvocationResult result = mock(InvocationResult.class);
        when(coordinator.invoke("tenant-a", request)).thenReturn(result);
        when(result.evidence()).thenReturn(mock(
            io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
                .InvocationEvidence.class
        ));
        AtomicInteger observations = new AtomicInteger();
        ConnectorInvocationObservationSink sink = (evidence, evaluatedAt) ->
            observations.incrementAndGet();
        ObservedReadOnlyConnectorInvocationService service = new ObservedReadOnlyConnectorInvocationService(
            coordinator,
            sink,
            Clock.fixed(Instant.parse("2026-07-29T07:00:00Z"), ZoneOffset.UTC)
        );

        assertSame(result, service.invoke("tenant-a", request));
        org.junit.jupiter.api.Assertions.assertEquals(1, observations.get());
    }

    @Test
    void diagnosticsFailureCannotChangeInvocationResult() {
        GovernedReadOnlyConnectorInvocationCoordinator coordinator = mock(
            GovernedReadOnlyConnectorInvocationCoordinator.class
        );
        InvocationRequest request = request();
        InvocationResult result = mock(InvocationResult.class);
        when(coordinator.invoke("tenant-a", request)).thenReturn(result);
        when(result.evidence()).thenReturn(mock(
            io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
                .InvocationEvidence.class
        ));
        ObservedReadOnlyConnectorInvocationService service = new ObservedReadOnlyConnectorInvocationService(
            coordinator,
            (evidence, evaluatedAt) -> {
                throw new IllegalStateException("synthetic diagnostics failure");
            },
            Clock.systemUTC()
        );

        assertSame(result, service.invoke("tenant-a", request));
    }

    @Test
    void coordinatorFailureIsNotMaskedOrRetried() {
        GovernedReadOnlyConnectorInvocationCoordinator coordinator = mock(
            GovernedReadOnlyConnectorInvocationCoordinator.class
        );
        InvocationRequest request = request();
        when(coordinator.invoke("tenant-a", request)).thenThrow(
            new IllegalStateException("synthetic invocation failure")
        );
        ObservedReadOnlyConnectorInvocationService service = new ObservedReadOnlyConnectorInvocationService(
            coordinator,
            ConnectorInvocationObservationSink.noop(),
            Clock.systemUTC()
        );

        assertThrows(IllegalStateException.class, () -> service.invoke("tenant-a", request));
    }

    private static InvocationRequest request() {
        return new InvocationRequest(
            RouteIntent.ORGANIZATION_READ_USER_BY_ID,
            "user-1",
            "correlation-1"
        );
    }
}
