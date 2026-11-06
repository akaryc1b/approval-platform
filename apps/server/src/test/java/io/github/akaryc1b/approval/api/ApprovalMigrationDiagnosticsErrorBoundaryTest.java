package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalMigrationDiagnosticsErrorBoundaryTest {
    @Test
    void missingPlanIsNotFoundOnBothReadOnlyAliases() throws Exception {
        var query = mock(ApprovalMigrationDiagnosticsQuery.class);
        when(query.findPlanDiagnostics(eq("tenant-a"), any())).thenReturn(Optional.empty());
        var mvc = MockMvcBuilders.standaloneSetup(new ApprovalMigrationDiagnosticsController(query))
            .setControllerAdvice(new ApprovalMigrationOperationsApiExceptionHandler()).build();
        for (String root : new String[] { "management", "mobile" }) {
            mvc.perform(get("/api/approval/" + root + "/process-instance-operations/plans/{id}/diagnostics", UUID.randomUUID())
                    .header("X-Tenant-Id", "tenant-a").header("X-Request-Id", "diagnostics-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "diagnostics-not-found"))
                .andExpect(jsonPath("$.code").value("APPROVAL_MIGRATION_OPERATIONS_NOT_FOUND"));
        }
    }

    @Test
    void unexpectedStorageFailureRemainsSanitizedServerError() throws Exception {
        var query = mock(ApprovalMigrationDiagnosticsQuery.class);
        when(query.findPlanDiagnostics(eq("tenant-a"), any())).thenThrow(new RuntimeException("private storage diagnostic"));
        var mvc = MockMvcBuilders.standaloneSetup(new ApprovalMigrationDiagnosticsController(query))
            .setControllerAdvice(new ApprovalMigrationOperationsApiExceptionHandler()).build();
        mvc.perform(get("/api/approval/management/process-instance-operations/plans/{id}/diagnostics", UUID.randomUUID())
                .header("X-Tenant-Id", "tenant-a").header("X-Request-Id", "diagnostics-failure"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("APPROVAL_MIGRATION_OPERATIONS_READ_FAILED"))
            .andExpect(jsonPath("$.message").value("Migration operations evidence could not be read"));
    }
}
