package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.AttemptStatusFilter;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.FailureClass;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceSort;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.ReconciliationState;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationDiagnosticsParametersTest {

    private static final String TENANT = "tenant-a";
    private static final UUID PLAN_ID = UUID.fromString(
        "78000000-0000-0000-0000-000000000001"
    );

    @Test
    void defaultsRemainBoundedAndStable() {
        var criteria = ApprovalMigrationDiagnosticsParameters.parse(
            TENANT,
            PLAN_ID,
            new LinkedMultiValueMap<>()
        );

        assertEquals(1, criteria.page());
        assertEquals(50, criteria.pageSize());
        assertEquals(0, criteria.offset());
        assertEquals(InstanceSort.SEQUENCE_ASC, criteria.sort());
        assertNull(criteria.attemptStatus());
        assertNull(criteria.failureClass());
        assertNull(criteria.reconciliationState());
    }

    @Test
    void parsesClosedFiltersAndExplicitOffsetTimestamps() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("page", "2");
        parameters.add("pageSize", "25");
        parameters.add("sort", "LATEST_EVIDENCE_DESC");
        parameters.add("status", "UNKNOWN");
        parameters.add("failureClass", "AMBIGUOUS_UNKNOWN");
        parameters.add("reconciliationState", "OPEN");
        parameters.add("instanceId", "78000000-0000-0000-0000-000000000099");
        parameters.add("from", "2026-07-01T00:00:00+08:00");
        parameters.add("to", "2026-07-02T00:00:00+08:00");

        var criteria = ApprovalMigrationDiagnosticsParameters.parse(TENANT, PLAN_ID, parameters);

        assertEquals(2, criteria.page());
        assertEquals(25, criteria.pageSize());
        assertEquals(25, criteria.offset());
        assertEquals(InstanceSort.LATEST_EVIDENCE_DESC, criteria.sort());
        assertEquals(AttemptStatusFilter.UNKNOWN, criteria.attemptStatus());
        assertEquals(FailureClass.AMBIGUOUS_UNKNOWN, criteria.failureClass());
        assertEquals(ReconciliationState.OPEN, criteria.reconciliationState());
        assertEquals("2026-06-30T16:00:00Z", criteria.from().toString());
    }

    @Test
    void rejectsDuplicateUnknownOversizedAndPollutedParameters() {
        MultiValueMap<String, String> duplicate = new LinkedMultiValueMap<>();
        duplicate.add("page", "1");
        duplicate.add("page", "2");
        assertInvalid(duplicate);

        MultiValueMap<String, String> unknown = new LinkedMultiValueMap<>();
        unknown.add("orderBy", "tenant_id desc");
        assertInvalid(unknown);

        MultiValueMap<String, String> oversized = new LinkedMultiValueMap<>();
        oversized.add("status", "x".repeat(129));
        assertInvalid(oversized);

        MultiValueMap<String, String> polluted = new LinkedMultiValueMap<>();
        polluted.add("sort", "SEQUENCE_ASC,latest_evidence_at desc");
        assertInvalid(polluted);
    }

    @Test
    void rejectsInvalidEnumsPagingIdentifiersAndTimeRanges() {
        for (MultiValueMap<String, String> parameters : java.util.List.of(
            params("status", "RUNNING"),
            params("failureClass", "SQL_ERROR"),
            params("reconciliationState", "RETRY"),
            params("page", "0"),
            params("pageSize", "101"),
            params("instanceId", "not-a-uuid"),
            params("from", "2026-07-01T00:00:00"),
            range("2026-07-03T00:00:00Z", "2026-07-02T00:00:00Z"),
            range("2026-06-01T00:00:00Z", "2026-07-03T00:00:00Z")
        )) {
            assertInvalid(parameters);
        }
    }

    private static MultiValueMap<String, String> params(String name, String value) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add(name, value);
        return parameters;
    }

    private static MultiValueMap<String, String> range(String from, String to) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("from", from);
        parameters.add("to", to);
        return parameters;
    }

    private static void assertInvalid(MultiValueMap<String, String> parameters) {
        assertThrows(
            IllegalArgumentException.class,
            () -> ApprovalMigrationDiagnosticsParameters.parse(TENANT, PLAN_ID, parameters)
        );
    }
}
