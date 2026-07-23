package io.github.akaryc1b.approval.sdk.v1;

import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.REDACTED;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.assertNoSensitiveLiteral;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.auditMap;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.createAuditEvent;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.diagnosticMap;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.emit;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.render;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.renderException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.AdapterAuditEvent;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.AdapterAuditInput;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.AuditOutcome;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.ConfigurationClassification;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.ConfigurationEntry;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.DiagnosticSeverity;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.FakeConfigurationSnapshot;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.FakeConfigurationSource;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.InMemoryAdapterAuditSink;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.RawDiagnostic;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.ResolvedConfiguration;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.SafeDiagnostic;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.UnsupportedDiagnosticsAuditVersionException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkDiagnosticsAuditV1Test {
    @Test
    void sharedFixtureProducesDeterministicProvenanceAndReferenceOnlySensitiveValues() throws IOException {
        Map<String, Object> fixture = DiagnosticsAuditFixtureSupport.fixture();
        ResolvedConfiguration configuration = configuration(fixture);
        Map<String, Object> expectations = DiagnosticsAuditFixtureSupport.object(fixture, "expectations");
        assertEquals(expectations.get("contentDigest"), configuration.provenance().contentDigest());
        assertEquals(expectations.get("publicValues"), configuration.publicValues());
        assertEquals(expectations.get("sensitiveReferences"), configuration.sensitiveReferences());
        assertFalse(configuration.publicValues().toString().contains("DO-NOT-LEAK"));
        assertFalse(configuration.sensitiveReferences().toString().contains("DO-NOT-LEAK"));
    }

    @Test
    void diagnosticRenderingRedactsSensitiveKeysAndLiteralOccurrences() throws IOException {
        Map<String, Object> fixture = DiagnosticsAuditFixtureSupport.fixture();
        ResolvedConfiguration configuration = configuration(fixture);
        SafeDiagnostic diagnostic = render(configuration, diagnostic(fixture));
        Map<String, Object> expected = DiagnosticsAuditFixtureSupport.object(
            DiagnosticsAuditFixtureSupport.object(fixture, "expectations"),
            "safeDiagnostic"
        );
        assertEquals(
            CanonicalJson.canonicalizeValue(expected),
            CanonicalJson.canonicalizeValue(diagnosticMap(diagnostic))
        );
        assertFalse(CanonicalJson.canonicalizeValue(diagnosticMap(diagnostic)).contains("DO-NOT-LEAK"));
    }

    @Test
    void exceptionDiagnosticsDoNotExposeMessageOrStack() throws IOException {
        ResolvedConfiguration configuration = configuration(DiagnosticsAuditFixtureSupport.fixture());
        SafeDiagnostic diagnostic = renderException(
            configuration,
            "adapter.exception",
            DiagnosticSeverity.ERROR,
            "req-fixture-001",
            new IllegalStateException("fixture-secret-DO-NOT-LEAK at stack")
        );
        String serialized = CanonicalJson.canonicalizeValue(diagnosticMap(diagnostic));
        assertEquals("Adapter operation failed", diagnostic.message());
        assertEquals("IllegalStateException", diagnostic.context().get("exceptionType"));
        assertFalse(serialized.contains("DO-NOT-LEAK"));
        assertFalse(serialized.contains("at stack"));
    }

    @Test
    void auditEventContainsReferencesAndProvenanceOnly() throws IOException {
        Map<String, Object> fixture = DiagnosticsAuditFixtureSupport.fixture();
        ResolvedConfiguration configuration = configuration(fixture);
        AdapterAuditEvent event = createAuditEvent(configuration, audit(fixture));
        assertEquals(configuration.provenance().contentDigest(), event.provenanceDigest());
        String serialized = CanonicalJson.canonicalizeValue(auditMap(event));
        assertFalse(serialized.matches("(?is).*(tenant|operator|permission|auditReference|DO-NOT-LEAK).*"));
        assertEquals(13, auditMap(event).size());
    }

    @Test
    void inMemoryAuditSinkRecordsValidatedEvent() throws IOException {
        Map<String, Object> fixture = DiagnosticsAuditFixtureSupport.fixture();
        ResolvedConfiguration configuration = configuration(fixture);
        InMemoryAdapterAuditSink sink = new InMemoryAdapterAuditSink();
        emit(sink, createAuditEvent(configuration, audit(fixture)));
        assertEquals(1, sink.events().size());
        assertEquals("audit-fixture-001", sink.events().get(0).eventId());
    }

    @Test
    void unknownVersionAndDuplicateKeysFailClosed() throws IOException {
        Map<String, Object> fixture = DiagnosticsAuditFixtureSupport.fixture();
        FakeConfigurationSnapshot source = snapshot(fixture);
        assertThrows(
            UnsupportedDiagnosticsAuditVersionException.class,
            () -> new FakeConfigurationSnapshot(
                "2",
                source.sourceKind(),
                source.sourceId(),
                source.revision(),
                source.loadedAtEpochSeconds(),
                source.entries()
            )
        );
        List<ConfigurationEntry> duplicate = new ArrayList<>(source.entries());
        duplicate.add(source.entries().get(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> new FakeConfigurationSnapshot(
                "1",
                source.sourceKind(),
                source.sourceId(),
                source.revision(),
                source.loadedAtEpochSeconds(),
                duplicate
            )
        );
    }

    @Test
    void leakedSensitiveLiteralIsRejectedAndSensitiveOutputKeysAreRedacted() throws IOException {
        ResolvedConfiguration configuration = configuration(DiagnosticsAuditFixtureSupport.fixture());
        assertThrows(
            IllegalStateException.class,
            () -> assertNoSensitiveLiteral(configuration, "fixture-secret-DO-NOT-LEAK")
        );
        SafeDiagnostic safe = render(
            configuration,
            new RawDiagnostic(
                "1",
                "safe",
                DiagnosticSeverity.INFO,
                "ok",
                Map.of("password", "not-a-fixture-secret")
            )
        );
        assertEquals(REDACTED, safe.context().get("password"));
        assertTrue(safe.redactionCount() > 0);
    }

    private static ResolvedConfiguration configuration(Map<String, Object> fixture) {
        return new FakeConfigurationSource(snapshot(fixture)).load();
    }

    @SuppressWarnings("unchecked")
    private static FakeConfigurationSnapshot snapshot(Map<String, Object> fixture) {
        Map<String, Object> raw = DiagnosticsAuditFixtureSupport.object(fixture, "configuration");
        List<ConfigurationEntry> entries = new ArrayList<>();
        for (Object value : (List<Object>) raw.get("entries")) {
            Map<String, Object> entry = (Map<String, Object>) value;
            entries.add(new ConfigurationEntry(
                (String) entry.get("key"),
                (String) entry.get("value"),
                "public".equals(entry.get("classification"))
                    ? ConfigurationClassification.PUBLIC
                    : ConfigurationClassification.SENSITIVE
            ));
        }
        return new FakeConfigurationSnapshot(
            (String) raw.get("contractVersion"),
            (String) raw.get("sourceKind"),
            (String) raw.get("sourceId"),
            (String) raw.get("revision"),
            ((Number) raw.get("loadedAtEpochSeconds")).longValue(),
            entries
        );
    }

    @SuppressWarnings("unchecked")
    private static RawDiagnostic diagnostic(Map<String, Object> fixture) {
        Map<String, Object> raw = DiagnosticsAuditFixtureSupport.object(fixture, "diagnostic");
        Map<String, String> context = new LinkedHashMap<>();
        ((Map<String, Object>) raw.get("context")).forEach((key, value) -> context.put(key, (String) value));
        return new RawDiagnostic(
            (String) raw.get("contractVersion"),
            (String) raw.get("code"),
            DiagnosticSeverity.valueOf(((String) raw.get("severity")).toUpperCase()),
            (String) raw.get("message"),
            context
        );
    }

    private static AdapterAuditInput audit(Map<String, Object> fixture) {
        Map<String, Object> raw = DiagnosticsAuditFixtureSupport.object(fixture, "audit");
        return new AdapterAuditInput(
            (String) raw.get("contractVersion"),
            (String) raw.get("eventId"),
            (String) raw.get("eventType"),
            (String) raw.get("endpointId"),
            (String) raw.get("operation"),
            (String) raw.get("requestId"),
            (String) raw.get("traceId"),
            (String) raw.get("bindingId"),
            (String) raw.get("authenticationContextId"),
            AuditOutcome.valueOf(((String) raw.get("outcome")).toUpperCase()),
            (String) raw.get("reasonCode"),
            ((Number) raw.get("occurredAtEpochSeconds")).longValue()
        );
    }
}
