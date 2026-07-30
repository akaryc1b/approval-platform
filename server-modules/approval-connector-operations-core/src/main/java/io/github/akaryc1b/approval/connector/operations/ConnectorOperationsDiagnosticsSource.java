package io.github.akaryc1b.approval.connector.operations;

import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts.DiagnosticsCriteria;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts.DiagnosticsSummary;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts.PageCursor;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts.QueryWindow;
import java.time.Instant;

public interface ConnectorOperationsDiagnosticsSource {
    QueryWindow query(String tenantHash, DiagnosticsCriteria criteria, PageCursor cursor);
    DiagnosticsSummary summarize(String tenantHash, Instant evaluatedAt);
}
