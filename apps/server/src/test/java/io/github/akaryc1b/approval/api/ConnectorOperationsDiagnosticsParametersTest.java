package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .StableFailureCode;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .InvocationOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectorOperationsDiagnosticsParametersTest {

    @Test
    void emptyParametersUseBoundedStableDefaults() {
        var criteria = ConnectorOperationsDiagnosticsParameters.parse(new LinkedMultiValueMap<>());
        assertEquals(50, criteria.pageSize());
        assertEquals(
            io.github.akaryc1b.approval.connector.operations
                .ConnectorOperationsDiagnosticsContracts.Sort.CREATED_AT_DESC,
            criteria.sort()
        );
    }

    @Test
    void allowlistedExactFiltersAreParsed() {
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("pageSize", "20");
        parameters.add("provider", "dingtalk");
        parameters.add("invocationOutcome", "UNKNOWN_AFTER_DISPATCH");
        parameters.add("dispatchAttempted", "true");
        parameters.add("stableFailureCode", "TRANSPORT_TIMEOUT");
        var criteria = ConnectorOperationsDiagnosticsParameters.parse(parameters);
        assertEquals(20, criteria.pageSize());
        assertEquals(InvocationOutcome.UNKNOWN_AFTER_DISPATCH, criteria.invocationOutcome());
        assertEquals(Boolean.TRUE, criteria.dispatchAttempted());
        assertEquals(StableFailureCode.TRANSPORT_TIMEOUT, criteria.stableFailureCode());
    }

    @Test
    void unknownFilterIsRejected() {
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("tenantId", "tenant-a");
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectorOperationsDiagnosticsParameters.parse(parameters)
        );
    }

    @Test
    void duplicateFilterIsRejected() {
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("pageSize", "10");
        parameters.add("pageSize", "20");
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectorOperationsDiagnosticsParameters.parse(parameters)
        );
    }

    @Test
    void wildcardSearchIsRejected() {
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("provider", "ding*");
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectorOperationsDiagnosticsParameters.parse(parameters)
        );
    }

    @Test
    void invalidBooleanAndEnumAreRejected() {
        LinkedMultiValueMap<String, String> invalidBoolean = new LinkedMultiValueMap<>();
        invalidBoolean.add("dispatchAttempted", "yes");
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectorOperationsDiagnosticsParameters.parse(invalidBoolean)
        );
        LinkedMultiValueMap<String, String> invalidEnum = new LinkedMultiValueMap<>();
        invalidEnum.add("invocationOutcome", "ANY");
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectorOperationsDiagnosticsParameters.parse(invalidEnum)
        );
    }
}
