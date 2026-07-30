package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.connector.operations.BoundedConnectorOperationsDiagnosticsStore;
import io.github.akaryc1b.approval.connector.operations.ConnectorDiagnosticsPageTokenCodec;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Clock;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectorOperationsDiagnosticsControllerTest {

    @Test
    void diagnosticsResponsesAreNoStore() {
        Fixture fixture = fixture();
        var response = fixture.controller().findDiagnostics(
            "tenant-a",
            new LinkedMultiValueMap<>()
        );
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void summaryResponsesAreNoStore() {
        Fixture fixture = fixture();
        var response = fixture.controller().summarize("tenant-a");
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    private static Fixture fixture() {
        BoundedConnectorOperationsDiagnosticsStore store =
            new BoundedConnectorOperationsDiagnosticsStore(8, 8);
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 3);
        ConnectorDiagnosticsPageTokenCodec codec = new ConnectorDiagnosticsPageTokenCodec(key);
        ConnectorOperationsDiagnosticsQueryService service =
            new ConnectorOperationsDiagnosticsQueryService(
                store,
                codec,
                Clock.systemUTC(),
                262_144
            );
        return new Fixture(new ConnectorOperationsDiagnosticsController(service), codec);
    }

    private record Fixture(
        ConnectorOperationsDiagnosticsController controller,
        ConnectorDiagnosticsPageTokenCodec codec
    ) {
    }
}
