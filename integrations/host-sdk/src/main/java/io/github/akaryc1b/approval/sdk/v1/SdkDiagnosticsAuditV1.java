package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/** Deterministic configuration provenance, diagnostic redaction and adapter audit contracts. */
public final class SdkDiagnosticsAuditV1 {
    public static final String CONTRACT_VERSION = "1";
    public static final String REDACTED = "[REDACTED]";
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
        "(?:authorization|token|password|secret|private[._-]?key|certificate|credential[._-]?(?:value|material))",
        Pattern.CASE_INSENSITIVE
    );

    private SdkDiagnosticsAuditV1() {
    }

    public enum ConfigurationClassification {
        PUBLIC,
        SENSITIVE
    }

    public enum DiagnosticSeverity {
        INFO,
        WARNING,
        ERROR
    }

    public enum AuditOutcome {
        SUCCEEDED,
        FAILED,
        REJECTED
    }

    public record ConfigurationEntry(
        String key,
        String value,
        ConfigurationClassification classification
    ) {
        public ConfigurationEntry {
            key = required(key, "configuration.entry.key");
            value = required(value, "configuration.entry.value");
            classification = Objects.requireNonNull(classification, "configuration.entry.classification");
        }
    }

    public record FakeConfigurationSnapshot(
        String contractVersion,
        String sourceKind,
        String sourceId,
        String revision,
        long loadedAtEpochSeconds,
        List<ConfigurationEntry> entries
    ) {
        public FakeConfigurationSnapshot {
            requireVersion(contractVersion);
            if (!"fixture".equals(sourceKind)) {
                throw new IllegalArgumentException(
                    "Only fixture configuration sources are supported in this safe slice"
                );
            }
            sourceId = required(sourceId, "configuration.sourceId");
            revision = required(revision, "configuration.revision");
            if (loadedAtEpochSeconds < 0) {
                throw new IllegalArgumentException("configuration.loadedAtEpochSeconds cannot be negative");
            }
            entries = immutableEntries(entries);
        }
    }

    public record ConfigurationProvenance(
        String contractVersion,
        String sourceKind,
        String sourceId,
        String revision,
        long loadedAtEpochSeconds,
        String contentDigest,
        List<String> publicKeys,
        List<String> sensitiveKeys
    ) {
        public ConfigurationProvenance {
            requireVersion(contractVersion);
            if (!"fixture".equals(sourceKind)) {
                throw new IllegalArgumentException("Unsupported provenance source kind");
            }
            sourceId = required(sourceId, "provenance.sourceId");
            revision = required(revision, "provenance.revision");
            contentDigest = required(contentDigest, "provenance.contentDigest");
            publicKeys = List.copyOf(publicKeys);
            sensitiveKeys = List.copyOf(sensitiveKeys);
        }
    }

    public static final class ResolvedConfiguration {
        private final Map<String, String> publicValues;
        private final Map<String, String> sensitiveReferences;
        private final ConfigurationProvenance provenance;
        private final List<String> sensitiveLiterals;

        private ResolvedConfiguration(
            Map<String, String> publicValues,
            Map<String, String> sensitiveReferences,
            ConfigurationProvenance provenance,
            List<String> sensitiveLiterals
        ) {
            this.publicValues = Collections.unmodifiableMap(new LinkedHashMap<>(publicValues));
            this.sensitiveReferences = Collections.unmodifiableMap(new LinkedHashMap<>(sensitiveReferences));
            this.provenance = Objects.requireNonNull(provenance, "provenance");
            this.sensitiveLiterals = List.copyOf(sensitiveLiterals);
        }

        public Map<String, String> publicValues() {
            return publicValues;
        }

        public Map<String, String> sensitiveReferences() {
            return sensitiveReferences;
        }

        public ConfigurationProvenance provenance() {
            return provenance;
        }
    }

    public static final class FakeConfigurationSource {
        private final FakeConfigurationSnapshot snapshot;

        public FakeConfigurationSource(FakeConfigurationSnapshot snapshot) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }

        public ResolvedConfiguration load() {
            return resolve(snapshot);
        }
    }

    public record RawDiagnostic(
        String contractVersion,
        String code,
        DiagnosticSeverity severity,
        String message,
        Map<String, String> context
    ) {
        public RawDiagnostic {
            requireVersion(contractVersion);
            code = required(code, "diagnostic.code");
            severity = Objects.requireNonNull(severity, "diagnostic.severity");
            message = required(message, "diagnostic.message");
            context = immutableStringMap(context, "diagnostic.context");
        }
    }

    public record SafeDiagnostic(
        String contractVersion,
        String code,
        DiagnosticSeverity severity,
        String message,
        Map<String, String> context,
        int redactionCount,
        String provenanceDigest
    ) {
        public SafeDiagnostic {
            requireVersion(contractVersion);
            code = required(code, "diagnostic.code");
            severity = Objects.requireNonNull(severity, "diagnostic.severity");
            message = required(message, "diagnostic.message");
            context = immutableStringMap(context, "diagnostic.context");
            if (redactionCount < 0) {
                throw new IllegalArgumentException("redactionCount cannot be negative");
            }
            provenanceDigest = required(provenanceDigest, "diagnostic.provenanceDigest");
        }
    }

    public record AdapterAuditInput(
        String contractVersion,
        String eventId,
        String eventType,
        String endpointId,
        String operation,
        String requestId,
        String traceId,
        String bindingId,
        String authenticationContextId,
        AuditOutcome outcome,
        String reasonCode,
        long occurredAtEpochSeconds
    ) {
        public AdapterAuditInput {
            requireVersion(contractVersion);
            eventId = required(eventId, "audit.eventId");
            eventType = required(eventType, "audit.eventType");
            endpointId = required(endpointId, "audit.endpointId");
            operation = required(operation, "audit.operation");
            requestId = required(requestId, "audit.requestId");
            traceId = required(traceId, "audit.traceId");
            bindingId = required(bindingId, "audit.bindingId");
            authenticationContextId = required(
                authenticationContextId,
                "audit.authenticationContextId"
            );
            outcome = Objects.requireNonNull(outcome, "audit.outcome");
            reasonCode = required(reasonCode, "audit.reasonCode");
            if (occurredAtEpochSeconds < 0) {
                throw new IllegalArgumentException("audit.occurredAtEpochSeconds cannot be negative");
            }
        }
    }

    public record AdapterAuditEvent(
        String contractVersion,
        String eventId,
        String eventType,
        String endpointId,
        String operation,
        String requestId,
        String traceId,
        String bindingId,
        String authenticationContextId,
        AuditOutcome outcome,
        String reasonCode,
        long occurredAtEpochSeconds,
        String provenanceDigest
    ) {
        public AdapterAuditEvent {
            requireVersion(contractVersion);
            eventId = required(eventId, "audit.eventId");
            eventType = required(eventType, "audit.eventType");
            endpointId = required(endpointId, "audit.endpointId");
            operation = required(operation, "audit.operation");
            requestId = required(requestId, "audit.requestId");
            traceId = required(traceId, "audit.traceId");
            bindingId = required(bindingId, "audit.bindingId");
            authenticationContextId = required(
                authenticationContextId,
                "audit.authenticationContextId"
            );
            outcome = Objects.requireNonNull(outcome, "audit.outcome");
            reasonCode = required(reasonCode, "audit.reasonCode");
            provenanceDigest = required(provenanceDigest, "audit.provenanceDigest");
            if (occurredAtEpochSeconds < 0) {
                throw new IllegalArgumentException("audit.occurredAtEpochSeconds cannot be negative");
            }
        }
    }

    public static final class InMemoryAdapterAuditSink {
        private final List<AdapterAuditEvent> events = new ArrayList<>();

        public synchronized void append(AdapterAuditEvent event) {
            events.add(Objects.requireNonNull(event, "event"));
        }

        public synchronized List<AdapterAuditEvent> events() {
            return List.copyOf(events);
        }
    }

    public static final class UnsupportedDiagnosticsAuditVersionException
        extends IllegalArgumentException {
        private final String contractVersion;

        public UnsupportedDiagnosticsAuditVersionException(String contractVersion) {
            super("Unsupported diagnostics/audit contract version: " + contractVersion);
            this.contractVersion = contractVersion;
        }

        public String contractVersion() {
            return contractVersion;
        }
    }

    public static ResolvedConfiguration resolve(FakeConfigurationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<ConfigurationEntry> entries = new ArrayList<>(snapshot.entries());
        entries.sort((left, right) -> left.key().compareTo(right.key()));

        List<Object> digestEntries = new ArrayList<>();
        Map<String, String> publicValues = new LinkedHashMap<>();
        Map<String, String> sensitiveReferences = new LinkedHashMap<>();
        List<String> publicKeys = new ArrayList<>();
        List<String> sensitiveKeys = new ArrayList<>();
        List<String> sensitiveLiterals = new ArrayList<>();

        for (ConfigurationEntry entry : entries) {
            String valueDigest = CanonicalJson.sha256Hex(
                entry.value().getBytes(StandardCharsets.UTF_8)
            );
            digestEntries.add(Map.of(
                "classification",
                entry.classification() == ConfigurationClassification.PUBLIC ? "public" : "sensitive",
                "key",
                entry.key(),
                "valueDigest",
                valueDigest
            ));
            if (entry.classification() == ConfigurationClassification.PUBLIC) {
                publicKeys.add(entry.key());
                publicValues.put(entry.key(), entry.value());
            } else {
                sensitiveKeys.add(entry.key());
                sensitiveLiterals.add(entry.value());
                sensitiveReferences.put(
                    entry.key(),
                    "sensitive:" + snapshot.sourceId() + ":" + entry.key() + ":" + valueDigest.substring(0, 16)
                );
            }
        }

        Map<String, Object> provenanceInput = new LinkedHashMap<>();
        provenanceInput.put("contractVersion", CONTRACT_VERSION);
        provenanceInput.put("entries", digestEntries);
        provenanceInput.put("loadedAtEpochSeconds", snapshot.loadedAtEpochSeconds());
        provenanceInput.put("revision", snapshot.revision());
        provenanceInput.put("sourceId", snapshot.sourceId());
        provenanceInput.put("sourceKind", snapshot.sourceKind());
        String contentDigest = CanonicalJson.sha256Hex(
            CanonicalJson.canonicalizeValue(provenanceInput).getBytes(StandardCharsets.UTF_8)
        );
        ConfigurationProvenance provenance = new ConfigurationProvenance(
            CONTRACT_VERSION,
            "fixture",
            snapshot.sourceId(),
            snapshot.revision(),
            snapshot.loadedAtEpochSeconds(),
            contentDigest,
            publicKeys,
            sensitiveKeys
        );
        return new ResolvedConfiguration(
            publicValues,
            sensitiveReferences,
            provenance,
            sensitiveLiterals
        );
    }

    public static SafeDiagnostic render(
        ResolvedConfiguration configuration,
        RawDiagnostic diagnostic
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(diagnostic, "diagnostic");
        Counter counter = new Counter();
        String message = redactText(diagnostic.message(), configuration.sensitiveLiterals, counter);
        Map<String, String> context = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(diagnostic.context().keySet());
        Collections.sort(keys);
        for (String key : keys) {
            String value = diagnostic.context().get(key);
            if (SENSITIVE_KEY.matcher(key).find()
                || configuration.provenance().sensitiveKeys().contains(key)) {
                context.put(key, REDACTED);
                counter.value++;
            } else {
                context.put(key, redactText(value, configuration.sensitiveLiterals, counter));
            }
        }
        SafeDiagnostic safe = new SafeDiagnostic(
            CONTRACT_VERSION,
            diagnostic.code(),
            diagnostic.severity(),
            message,
            context,
            counter.value,
            configuration.provenance().contentDigest()
        );
        assertNoSensitiveLiteral(configuration, diagnosticJson(safe));
        return safe;
    }

    public static SafeDiagnostic renderException(
        ResolvedConfiguration configuration,
        String code,
        DiagnosticSeverity severity,
        String requestId,
        Throwable error
    ) {
        String exceptionType = error == null ? "UnknownError" : error.getClass().getSimpleName();
        return render(
            configuration,
            new RawDiagnostic(
                CONTRACT_VERSION,
                code,
                severity,
                "Adapter operation failed",
                Map.of(
                    "exceptionType",
                    exceptionType.isEmpty() ? "UnknownError" : exceptionType,
                    "requestId",
                    required(requestId, "exceptionDiagnostic.requestId")
                )
            )
        );
    }

    public static AdapterAuditEvent createAuditEvent(
        ResolvedConfiguration configuration,
        AdapterAuditInput input
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(input, "input");
        AdapterAuditEvent event = new AdapterAuditEvent(
            CONTRACT_VERSION,
            input.eventId(),
            input.eventType(),
            input.endpointId(),
            input.operation(),
            input.requestId(),
            input.traceId(),
            input.bindingId(),
            input.authenticationContextId(),
            input.outcome(),
            input.reasonCode(),
            input.occurredAtEpochSeconds(),
            configuration.provenance().contentDigest()
        );
        assertNoSensitiveLiteral(configuration, auditJson(event));
        return event;
    }

    public static void emit(InMemoryAdapterAuditSink sink, AdapterAuditEvent event) {
        Objects.requireNonNull(sink, "sink").append(event);
    }

    public static void assertNoSensitiveLiteral(
        ResolvedConfiguration configuration,
        String output
    ) {
        Objects.requireNonNull(configuration, "configuration");
        String requiredOutput = required(output, "output");
        for (String literal : configuration.sensitiveLiterals) {
            if (!literal.isEmpty() && requiredOutput.contains(literal)) {
                throw new IllegalStateException("Sensitive configuration literal escaped redaction");
            }
        }
    }

    public static Map<String, Object> diagnosticMap(SafeDiagnostic diagnostic) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractVersion", diagnostic.contractVersion());
        map.put("code", diagnostic.code());
        map.put("severity", diagnostic.severity().name().toLowerCase());
        map.put("message", diagnostic.message());
        map.put("context", diagnostic.context());
        map.put("redactionCount", diagnostic.redactionCount());
        map.put("provenanceDigest", diagnostic.provenanceDigest());
        return map;
    }

    public static Map<String, Object> auditMap(AdapterAuditEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractVersion", event.contractVersion());
        map.put("eventId", event.eventId());
        map.put("eventType", event.eventType());
        map.put("endpointId", event.endpointId());
        map.put("operation", event.operation());
        map.put("requestId", event.requestId());
        map.put("traceId", event.traceId());
        map.put("bindingId", event.bindingId());
        map.put("authenticationContextId", event.authenticationContextId());
        map.put("outcome", event.outcome().name().toLowerCase());
        map.put("reasonCode", event.reasonCode());
        map.put("occurredAtEpochSeconds", event.occurredAtEpochSeconds());
        map.put("provenanceDigest", event.provenanceDigest());
        return map;
    }

    private static String diagnosticJson(SafeDiagnostic diagnostic) {
        return CanonicalJson.canonicalizeValue(diagnosticMap(diagnostic));
    }

    private static String auditJson(AdapterAuditEvent event) {
        return CanonicalJson.canonicalizeValue(auditMap(event));
    }

    private static String redactText(
        String value,
        List<String> sensitiveLiterals,
        Counter counter
    ) {
        String output = value;
        for (String literal : sensitiveLiterals) {
            if (literal.isEmpty()) {
                continue;
            }
            int count = countOccurrences(output, literal);
            if (count > 0) {
                counter.value += count;
                output = output.replace(literal, REDACTED);
            }
        }
        return output;
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static List<ConfigurationEntry> immutableEntries(List<ConfigurationEntry> values) {
        Objects.requireNonNull(values, "configuration.entries");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("configuration.entries must be non-empty");
        }
        Set<String> keys = new HashSet<>();
        List<ConfigurationEntry> output = new ArrayList<>();
        for (ConfigurationEntry value : values) {
            ConfigurationEntry entry = Objects.requireNonNull(value, "configuration.entry");
            if (!keys.add(entry.key())) {
                throw new IllegalArgumentException("Duplicate configuration key: " + entry.key());
            }
            output.add(entry);
        }
        return List.copyOf(output);
    }

    private static Map<String, String> immutableStringMap(
        Map<String, String> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        Map<String, String> output = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            output.put(
                required(entry.getKey(), field + " key"),
                required(entry.getValue(), field + "." + entry.getKey())
            );
        }
        return Collections.unmodifiableMap(output);
    }

    private static void requireVersion(String value) {
        if (!CONTRACT_VERSION.equals(value)) {
            throw new UnsupportedDiagnosticsAuditVersionException(value);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value;
    }

    private static final class Counter {
        private int value;
    }
}
