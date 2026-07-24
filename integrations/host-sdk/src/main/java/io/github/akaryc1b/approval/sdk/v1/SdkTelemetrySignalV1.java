package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Reference-only telemetry signal validation and deterministic fake export. */
public final class SdkTelemetrySignalV1 {
    public static final String CONTRACT_VERSION = SdkTelemetryHandoffV1.CONTRACT_VERSION;
    private static final int MAX_ALLOWED_KEYS = 32;
    private static final int MAX_ALLOWED_NAMES = 64;
    private static final int MAX_SIGNAL_BATCH = 1_000;
    private static final int MAX_EXPORTER_CAPACITY = 10_000;
    private static final Pattern FORBIDDEN_ATTRIBUTE = Pattern.compile(
        "(?:authorization|token|password|secret|private[._-]?key|certificate|credential"
            + "|tenant|operator|permission|authority|auditReference|audit_reference)",
        Pattern.CASE_INSENSITIVE
    );

    private SdkTelemetrySignalV1() {
    }

    public enum TelemetrySignalKind {
        COUNTER,
        EVENT
    }

    public enum TelemetryExportStatus {
        EXPORTED,
        DEGRADED
    }

    public enum TelemetryAppendStatus {
        ACCEPTED,
        CAPACITY_REJECTED,
        DUPLICATE_REJECTED
    }

    public record TelemetryAttributePolicy(
        String contractVersion,
        List<String> allowedSignalNames,
        List<String> allowedAttributeKeys,
        Map<String, List<String>> allowedAttributeValues,
        int maxAttributeCount,
        int maxAttributeValueLength,
        int maxBatchSize
    ) {
        public TelemetryAttributePolicy {
            requireVersion(contractVersion);
            allowedSignalNames = uniqueStrings(
                allowedSignalNames,
                "telemetryPolicy.allowedSignalNames",
                MAX_ALLOWED_NAMES
            );
            allowedAttributeKeys = uniqueStrings(
                allowedAttributeKeys,
                "telemetryPolicy.allowedAttributeKeys",
                MAX_ALLOWED_KEYS
            );
            for (String key : allowedAttributeKeys) {
                if (FORBIDDEN_ATTRIBUTE.matcher(key).find()) {
                    throw new IllegalArgumentException(
                        "Forbidden telemetry attribute key: " + key
                    );
                }
            }
            allowedAttributeValues = normalizeAllowedAttributeValues(
                allowedAttributeKeys,
                allowedAttributeValues
            );
            requireRange(
                maxAttributeCount,
                0,
                allowedAttributeKeys.size(),
                "telemetryPolicy.maxAttributeCount"
            );
            requireRange(
                maxAttributeValueLength,
                1,
                256,
                "telemetryPolicy.maxAttributeValueLength"
            );
            requireRange(maxBatchSize, 1, MAX_SIGNAL_BATCH, "telemetryPolicy.maxBatchSize");
        }
    }

    public record TelemetrySignalInput(
        String contractVersion,
        String signalId,
        String signalName,
        TelemetrySignalKind signalKind,
        String endpointId,
        String operation,
        String requestId,
        String traceId,
        String bindingId,
        String authenticationContextId,
        String outcome,
        long quantity,
        long ordinal,
        String provenanceDigest,
        Map<String, String> attributes
    ) {
        public TelemetrySignalInput {
            requireVersion(contractVersion);
            signalId = required(signalId, "telemetry.signalId");
            signalName = required(signalName, "telemetry.signalName");
            signalKind = Objects.requireNonNull(signalKind, "telemetry.signalKind");
            endpointId = required(endpointId, "telemetry.endpointId");
            operation = required(operation, "telemetry.operation");
            requestId = required(requestId, "telemetry.requestId");
            traceId = required(traceId, "telemetry.traceId");
            bindingId = required(bindingId, "telemetry.bindingId");
            authenticationContextId = required(
                authenticationContextId,
                "telemetry.authenticationContextId"
            );
            outcome = required(outcome, "telemetry.outcome");
            provenanceDigest = required(provenanceDigest, "telemetry.provenanceDigest");
            requireNonNegative(quantity, "telemetry.quantity");
            requireNonNegative(ordinal, "telemetry.ordinal");
            if (signalKind == TelemetrySignalKind.EVENT && quantity != 1) {
                throw new IllegalArgumentException("Event telemetry quantity must equal 1");
            }
            if (signalKind == TelemetrySignalKind.COUNTER && quantity < 1) {
                throw new IllegalArgumentException("Counter telemetry quantity must be positive");
            }
            attributes = immutableStringMap(attributes, "telemetry.attributes");
        }
    }

    public record ReferenceTelemetrySignal(
        String contractVersion,
        String signalId,
        String signalName,
        TelemetrySignalKind signalKind,
        String endpointId,
        String operation,
        String requestId,
        String traceId,
        String bindingId,
        String authenticationContextId,
        String outcome,
        long quantity,
        long ordinal,
        String provenanceDigest,
        Map<String, String> attributes,
        String aggregationIdentityDigest,
        String signalDigest
    ) {
        public ReferenceTelemetrySignal {
            requireVersion(contractVersion);
            signalId = required(signalId, "telemetry.signalId");
            signalName = required(signalName, "telemetry.signalName");
            signalKind = Objects.requireNonNull(signalKind, "telemetry.signalKind");
            endpointId = required(endpointId, "telemetry.endpointId");
            operation = required(operation, "telemetry.operation");
            requestId = required(requestId, "telemetry.requestId");
            traceId = required(traceId, "telemetry.traceId");
            bindingId = required(bindingId, "telemetry.bindingId");
            authenticationContextId = required(
                authenticationContextId,
                "telemetry.authenticationContextId"
            );
            outcome = required(outcome, "telemetry.outcome");
            provenanceDigest = required(provenanceDigest, "telemetry.provenanceDigest");
            attributes = immutableStringMap(attributes, "telemetry.attributes");
            aggregationIdentityDigest = required(
                aggregationIdentityDigest,
                "telemetry.aggregationIdentityDigest"
            );
            signalDigest = required(signalDigest, "telemetry.signalDigest");
            requireNonNegative(quantity, "telemetry.quantity");
            requireNonNegative(ordinal, "telemetry.ordinal");
        }
    }

    public record TelemetryExportProof(
        String contractVersion,
        int signalCount,
        String firstSignalId,
        String lastSignalId,
        String batchDigest
    ) {
        public TelemetryExportProof {
            requireVersion(contractVersion);
            requireRange(signalCount, 1, MAX_SIGNAL_BATCH, "telemetryProof.signalCount");
            firstSignalId = required(firstSignalId, "telemetryProof.firstSignalId");
            lastSignalId = required(lastSignalId, "telemetryProof.lastSignalId");
            batchDigest = required(batchDigest, "telemetryProof.batchDigest");
        }
    }

    public record TelemetryExportResult(
        String contractVersion,
        TelemetryExportStatus status,
        String reasonCode,
        TelemetryExportProof proof,
        int exportedSignalCount,
        int exporterSize
    ) {
        public TelemetryExportResult {
            requireVersion(contractVersion);
            status = Objects.requireNonNull(status, "telemetryExport.status");
            reasonCode = required(reasonCode, "telemetryExport.reasonCode");
            if (exportedSignalCount < 0 || exporterSize < 0) {
                throw new IllegalArgumentException("Telemetry export counters cannot be negative");
            }
        }
    }

    public static final class UnsupportedTelemetryHandoffVersionException
        extends IllegalArgumentException {
        private final String contractVersion;

        public UnsupportedTelemetryHandoffVersionException(String contractVersion) {
            super("Unsupported telemetry/handoff contract version: " + contractVersion);
            this.contractVersion = contractVersion;
        }

        public String contractVersion() {
            return contractVersion;
        }
    }

    public static final class ScriptedTelemetryExporter {
        private final int capacity;
        private final Set<Integer> failureBatchNumbers;
        private final Set<String> batchDigests = new HashSet<>();
        private final List<ReferenceTelemetrySignal> signals = new ArrayList<>();
        private int appendAttempts;

        public ScriptedTelemetryExporter(int capacity) {
            this(capacity, List.of());
        }

        public ScriptedTelemetryExporter(int capacity, List<Integer> failureBatchNumbers) {
            requireRange(capacity, 1, MAX_EXPORTER_CAPACITY, "telemetryExporter.capacity");
            this.capacity = capacity;
            this.failureBatchNumbers = positiveUniqueIntegers(
                failureBatchNumbers,
                "telemetryExporter.failureBatchNumbers"
            );
        }

        public synchronized TelemetryAppendStatus appendBatch(
            List<ReferenceTelemetrySignal> batch,
            TelemetryExportProof proof
        ) {
            Objects.requireNonNull(batch, "batch");
            Objects.requireNonNull(proof, "proof");
            appendAttempts++;
            if (failureBatchNumbers.contains(appendAttempts)) {
                throw new IllegalStateException("scripted telemetry exporter failure");
            }
            if (batchDigests.contains(proof.batchDigest())) {
                return TelemetryAppendStatus.DUPLICATE_REJECTED;
            }
            if (signals.size() + batch.size() > capacity) {
                return TelemetryAppendStatus.CAPACITY_REJECTED;
            }
            signals.addAll(batch);
            batchDigests.add(proof.batchDigest());
            return TelemetryAppendStatus.ACCEPTED;
        }

        public synchronized List<ReferenceTelemetrySignal> signals() {
            return List.copyOf(signals);
        }

        public synchronized int size() {
            return signals.size();
        }
    }

    public static ReferenceTelemetrySignal createReferenceTelemetrySignal(
        TelemetryAttributePolicy policy,
        TelemetrySignalInput input
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(input, "input");
        if (!policy.allowedSignalNames().contains(input.signalName())) {
            throw new IllegalArgumentException(
                "Telemetry signal name is not allowed: " + input.signalName()
            );
        }
        Map<String, String> attributes = validateAttributes(policy, input.attributes());
        Map<String, Object> aggregationInput = new LinkedHashMap<>();
        aggregationInput.put("attributes", attributes);
        aggregationInput.put("endpointId", input.endpointId());
        aggregationInput.put("operation", input.operation());
        aggregationInput.put("outcome", input.outcome());
        aggregationInput.put("provenanceDigest", input.provenanceDigest());
        aggregationInput.put("signalKind", lower(input.signalKind()));
        aggregationInput.put("signalName", input.signalName());
        String aggregationIdentityDigest = digest(aggregationInput);

        Map<String, Object> signalInput = new LinkedHashMap<>();
        signalInput.put("aggregationIdentityDigest", aggregationIdentityDigest);
        signalInput.put("attributes", attributes);
        signalInput.put("authenticationContextId", input.authenticationContextId());
        signalInput.put("bindingId", input.bindingId());
        signalInput.put("contractVersion", CONTRACT_VERSION);
        signalInput.put("endpointId", input.endpointId());
        signalInput.put("operation", input.operation());
        signalInput.put("ordinal", input.ordinal());
        signalInput.put("outcome", input.outcome());
        signalInput.put("provenanceDigest", input.provenanceDigest());
        signalInput.put("quantity", input.quantity());
        signalInput.put("requestId", input.requestId());
        signalInput.put("signalId", input.signalId());
        signalInput.put("signalKind", lower(input.signalKind()));
        signalInput.put("signalName", input.signalName());
        signalInput.put("traceId", input.traceId());
        String signalDigest = digest(signalInput);

        return new ReferenceTelemetrySignal(
            CONTRACT_VERSION,
            input.signalId(),
            input.signalName(),
            input.signalKind(),
            input.endpointId(),
            input.operation(),
            input.requestId(),
            input.traceId(),
            input.bindingId(),
            input.authenticationContextId(),
            input.outcome(),
            input.quantity(),
            input.ordinal(),
            input.provenanceDigest(),
            attributes,
            aggregationIdentityDigest,
            signalDigest
        );
    }

    public static TelemetryExportResult exportTelemetryBatch(
        TelemetryAttributePolicy policy,
        List<ReferenceTelemetrySignal> signals,
        ScriptedTelemetryExporter exporter
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(signals, "signals");
        Objects.requireNonNull(exporter, "exporter");
        if (signals.isEmpty() || signals.size() > policy.maxBatchSize()) {
            throw new IllegalArgumentException("Telemetry batch size is outside policy bounds");
        }
        Set<String> signalIds = new HashSet<>();
        Set<String> signalDigests = new HashSet<>();
        long previousOrdinal = -1L;
        for (ReferenceTelemetrySignal signal : signals) {
            Objects.requireNonNull(signal, "signal");
            if (!signalIds.add(signal.signalId())) {
                throw new IllegalArgumentException("Telemetry batch contains duplicate signalId");
            }
            if (!signalDigests.add(signal.signalDigest())) {
                throw new IllegalArgumentException("Telemetry batch contains duplicate signalDigest");
            }
            if (signal.ordinal() < previousOrdinal) {
                throw new IllegalArgumentException(
                    "Telemetry batch ordinals must be non-decreasing"
                );
            }
            previousOrdinal = signal.ordinal();
        }
        List<Object> batchValue = signals.stream()
            .map(SdkTelemetrySignalV1::telemetrySignalMap)
            .map(value -> (Object) value)
            .toList();
        String batchDigest = digest(batchValue);
        TelemetryExportProof proof = new TelemetryExportProof(
            CONTRACT_VERSION,
            signals.size(),
            signals.get(0).signalId(),
            signals.get(signals.size() - 1).signalId(),
            batchDigest
        );
        TelemetryAppendStatus appendStatus;
        try {
            appendStatus = exporter.appendBatch(signals, proof);
        } catch (RuntimeException exception) {
            return exportResult(
                TelemetryExportStatus.DEGRADED,
                "telemetry_exporter_failed",
                proof,
                0,
                exporter
            );
        }
        if (appendStatus == TelemetryAppendStatus.CAPACITY_REJECTED) {
            return exportResult(
                TelemetryExportStatus.DEGRADED,
                "telemetry_exporter_capacity",
                proof,
                0,
                exporter
            );
        }
        if (appendStatus == TelemetryAppendStatus.DUPLICATE_REJECTED) {
            return exportResult(
                TelemetryExportStatus.DEGRADED,
                "telemetry_duplicate_batch",
                proof,
                0,
                exporter
            );
        }
        return exportResult(
            TelemetryExportStatus.EXPORTED,
            "telemetry_batch_exported",
            proof,
            signals.size(),
            exporter
        );
    }

    public static Map<String, Object> telemetrySignalMap(ReferenceTelemetrySignal signal) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("aggregationIdentityDigest", signal.aggregationIdentityDigest());
        output.put("attributes", signal.attributes());
        output.put("authenticationContextId", signal.authenticationContextId());
        output.put("bindingId", signal.bindingId());
        output.put("contractVersion", signal.contractVersion());
        output.put("endpointId", signal.endpointId());
        output.put("operation", signal.operation());
        output.put("ordinal", signal.ordinal());
        output.put("outcome", signal.outcome());
        output.put("provenanceDigest", signal.provenanceDigest());
        output.put("quantity", signal.quantity());
        output.put("requestId", signal.requestId());
        output.put("signalDigest", signal.signalDigest());
        output.put("signalId", signal.signalId());
        output.put("signalKind", lower(signal.signalKind()));
        output.put("signalName", signal.signalName());
        output.put("traceId", signal.traceId());
        return output;
    }

    public static Map<String, Object> telemetryExportResultMap(TelemetryExportResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractVersion", result.contractVersion());
        output.put("exportedSignalCount", result.exportedSignalCount());
        output.put("exporterSize", result.exporterSize());
        output.put("proof", result.proof() == null ? null : proofMap(result.proof()));
        output.put("reasonCode", result.reasonCode());
        output.put("status", lower(result.status()));
        return output;
    }

    private static Map<String, Object> proofMap(TelemetryExportProof proof) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("batchDigest", proof.batchDigest());
        output.put("contractVersion", proof.contractVersion());
        output.put("firstSignalId", proof.firstSignalId());
        output.put("lastSignalId", proof.lastSignalId());
        output.put("signalCount", proof.signalCount());
        return output;
    }

    private static TelemetryExportResult exportResult(
        TelemetryExportStatus status,
        String reasonCode,
        TelemetryExportProof proof,
        int exportedSignalCount,
        ScriptedTelemetryExporter exporter
    ) {
        return new TelemetryExportResult(
            CONTRACT_VERSION,
            status,
            reasonCode,
            proof,
            exportedSignalCount,
            exporter.size()
        );
    }

    private static Map<String, String> validateAttributes(
        TelemetryAttributePolicy policy,
        Map<String, String> attributes
    ) {
        Objects.requireNonNull(attributes, "telemetry.attributes");
        List<String> keys = new ArrayList<>(attributes.keySet());
        Collections.sort(keys);
        if (keys.size() > policy.maxAttributeCount()) {
            throw new IllegalArgumentException(
                "telemetry.attributes exceeds maxAttributeCount"
            );
        }
        Map<String, String> output = new LinkedHashMap<>();
        for (String key : keys) {
            required(key, "telemetry.attribute key");
            if (!policy.allowedAttributeKeys().contains(key)) {
                throw new IllegalArgumentException(
                    "Telemetry attribute key is not allowed: " + key
                );
            }
            if (FORBIDDEN_ATTRIBUTE.matcher(key).find()) {
                throw new IllegalArgumentException(
                    "Forbidden telemetry attribute key: " + key
                );
            }
            String value = required(attributes.get(key), "telemetry.attributes." + key);
            if (value.length() > policy.maxAttributeValueLength()) {
                throw new IllegalArgumentException(
                    "Telemetry attribute value is too long: " + key
                );
            }
            if (!policy.allowedAttributeValues().get(key).contains(value)) {
                throw new IllegalArgumentException(
                    "Telemetry attribute value is not allowed: " + key
                );
            }
            output.put(key, value);
        }
        return Collections.unmodifiableMap(output);
    }

    private static Map<String, List<String>> normalizeAllowedAttributeValues(
        List<String> keys,
        Map<String, List<String>> values
    ) {
        Objects.requireNonNull(values, "telemetryPolicy.allowedAttributeValues");
        if (!new HashSet<>(keys).equals(values.keySet())) {
            throw new IllegalArgumentException(
                "telemetryPolicy.allowedAttributeValues must cover exactly the allowed keys"
            );
        }
        Map<String, List<String>> output = new LinkedHashMap<>();
        for (String key : keys) {
            output.put(
                key,
                uniqueStrings(
                    values.get(key),
                    "telemetryPolicy.allowedAttributeValues." + key,
                    64
                )
            );
        }
        return Collections.unmodifiableMap(output);
    }

    private static List<String> uniqueStrings(
        List<String> values,
        String field,
        int maxSize
    ) {
        Objects.requireNonNull(values, field);
        if (values.isEmpty() || values.size() > maxSize) {
            throw new IllegalArgumentException(field + " must be non-empty and bounded");
        }
        Set<String> seen = new HashSet<>();
        List<String> output = new ArrayList<>();
        for (String value : values) {
            String requiredValue = required(value, field);
            if (!seen.add(requiredValue)) {
                throw new IllegalArgumentException(field + " contains duplicates");
            }
            output.add(requiredValue);
        }
        Collections.sort(output);
        return List.copyOf(output);
    }

    private static Set<Integer> positiveUniqueIntegers(
        List<Integer> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        Set<Integer> output = new HashSet<>();
        for (Integer value : values) {
            Objects.requireNonNull(value, field);
            requireRange(value, 1, Integer.MAX_VALUE, field);
            if (!output.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicates");
            }
        }
        return Set.copyOf(output);
    }

    private static Map<String, String> immutableStringMap(
        Map<String, String> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        Map<String, String> output = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(values.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            output.put(
                required(key, field + " key"),
                required(values.get(key), field + "." + key)
            );
        }
        return Collections.unmodifiableMap(output);
    }

    private static String digest(Object value) {
        return CanonicalJson.sha256Hex(
            CanonicalJson.canonicalizeValue(value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void requireVersion(String value) {
        if (!CONTRACT_VERSION.equals(value)) {
            throw new UnsupportedTelemetryHandoffVersionException(value);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value;
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }

    private static void requireRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                field + " must be in [" + min + ", " + max + "]"
            );
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase();
    }
}
