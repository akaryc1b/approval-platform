package io.github.akaryc1b.approval.connector.operations;

import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsCriteria;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsPage;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsSummary;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .PageCursor;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .QueryWindow;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Tenant-scoped bounded query facade for process-local diagnostics. */
public final class ConnectorOperationsDiagnosticsQueryService {

    private final ConnectorOperationsDiagnosticsSource source;
    private final ConnectorDiagnosticsPageTokenCodec tokenCodec;
    private final Clock clock;
    private final int maximumResponseBytes;

    public ConnectorOperationsDiagnosticsQueryService(
        ConnectorOperationsDiagnosticsSource source,
        ConnectorDiagnosticsPageTokenCodec tokenCodec,
        Clock clock,
        int maximumResponseBytes
    ) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.tokenCodec = Objects.requireNonNull(tokenCodec, "tokenCodec must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maximumResponseBytes < 1_024 || maximumResponseBytes > 262_144) {
            throw new IllegalArgumentException("maximumResponseBytes is outside the closed bound");
        }
        this.maximumResponseBytes = maximumResponseBytes;
    }

    public DiagnosticsPage query(String trustedTenantId, DiagnosticsCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        String tenantHash = GovernedConnectorInvocationContracts.tenantHash(trustedTenantId);
        PageCursor cursor = criteria.pageToken() == null
            ? null
            : tokenCodec.decode(criteria.pageToken(), tenantHash, criteria.filterHash());
        QueryWindow window = source.query(tenantHash, criteria, cursor);
        String next = window.moreAvailable()
            ? tokenCodec.encode(new PageCursor(
                tenantHash,
                criteria.filterHash(),
                window.highWatermark(),
                window.nextBeforeSequence()
            ))
            : null;
        Instant evaluatedAt = clock.instant();
        DiagnosticsPage page = new DiagnosticsPage(
            window.items(),
            next,
            criteria.pageSize(),
            evaluatedAt,
            true,
            false,
            false,
            false,
            false,
            false
        );
        if (page.canonicalJson().getBytes(StandardCharsets.UTF_8).length > maximumResponseBytes) {
            throw new ConnectorOperationsDiagnosticsExceptions.ResponseTooLarge();
        }
        return page;
    }

    public DiagnosticsSummary summarize(String trustedTenantId) {
        String tenantHash = GovernedConnectorInvocationContracts.tenantHash(trustedTenantId);
        return source.summarize(tenantHash, clock.instant());
    }
}
