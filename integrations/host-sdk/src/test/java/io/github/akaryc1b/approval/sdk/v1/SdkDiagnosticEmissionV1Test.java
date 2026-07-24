package io.github.akaryc1b.approval.sdk.v1;

import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.diagnosticFingerprint;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.diagnosticSampleBucket;
import static io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.emitDiagnostic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.BoundedDiagnosticDeduplicationTracker;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.DiagnosticEmissionPolicy;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.DiagnosticEmissionRequest;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.DiagnosticEmissionResult;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.DiagnosticEmissionStatus;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.ScriptedInMemoryDiagnosticSink;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.DiagnosticSeverity;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.SafeDiagnostic;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkDiagnosticEmissionV1Test {
    @Test
    void fixtureProducesDeterministicFingerprintBucketAndEmission() throws IOException {
        Map<String, Object> fixture = EmissionPolicyFixtureSupport.fixture();
        Map<String, Object> expected = EmissionPolicyFixtureSupport.object(fixture, "expectations");
        DiagnosticEmissionPolicy policy = EmissionPolicyFixtureSupport.diagnosticPolicy(fixture);
        SafeDiagnostic diagnostic = EmissionPolicyFixtureSupport.diagnostic(fixture);
        BoundedDiagnosticDeduplicationTracker tracker =
            new BoundedDiagnosticDeduplicationTracker(policy.deduplicationCapacity());
        DiagnosticEmissionResult result = emitDiagnostic(
            policy,
            EmissionPolicyFixtureSupport.emissionRequest(fixture, diagnostic),
            tracker,
            new ScriptedInMemoryDiagnosticSink(4)
        );
        assertEquals(expected.get("diagnosticFingerprint"), diagnosticFingerprint(diagnostic));
        assertEquals(((Number) expected.get("sampleBucket")).intValue(), result.sampleBucket());
        assertEquals(DiagnosticEmissionStatus.EMITTED, result.status());
    }

    @Test
    void thresholdAndSamplingSuppressWithoutMutatingState() throws IOException {
        Map<String, Object> fixture = EmissionPolicyFixtureSupport.fixture();
        DiagnosticEmissionPolicy policy = EmissionPolicyFixtureSupport.diagnosticPolicy(fixture);
        SafeDiagnostic warning = EmissionPolicyFixtureSupport.diagnostic(fixture);
        SafeDiagnostic info = new SafeDiagnostic(
            "1", warning.code(), DiagnosticSeverity.INFO, warning.message(), warning.context(),
            warning.redactionCount(), warning.provenanceDigest()
        );
        BoundedDiagnosticDeduplicationTracker tracker =
            new BoundedDiagnosticDeduplicationTracker(policy.deduplicationCapacity());
        ScriptedInMemoryDiagnosticSink sink = new ScriptedInMemoryDiagnosticSink(4);
        DiagnosticEmissionRequest base = EmissionPolicyFixtureSupport.emissionRequest(fixture, info);
        assertEquals(
            DiagnosticEmissionStatus.BELOW_THRESHOLD,
            emitDiagnostic(policy, base, tracker, sink).status()
        );
        Map<String, Object> expected = EmissionPolicyFixtureSupport.object(fixture, "expectations");
        String key = (String) expected.get("sampledOutKey");
        assertEquals(
            ((Number) expected.get("sampledOutBucket")).intValue(),
            diagnosticSampleBucket(policy, key, diagnosticFingerprint(warning))
        );
        assertEquals(
            DiagnosticEmissionStatus.SAMPLED_OUT,
            emitDiagnostic(
                policy,
                new DiagnosticEmissionRequest("1", "sampled", key, base.ordinal() + 1, warning),
                tracker,
                sink
            ).status()
        );
        assertEquals(0, sink.size());
        assertEquals(0, tracker.size());
    }

    @Test
    void deduplicationUsesMonotonicOrdinalsAndBoundsFailClosed() throws IOException {
        Map<String, Object> fixture = EmissionPolicyFixtureSupport.fixture();
        DiagnosticEmissionPolicy policy = EmissionPolicyFixtureSupport.diagnosticPolicy(fixture);
        SafeDiagnostic diagnostic = EmissionPolicyFixtureSupport.diagnostic(fixture);
        DiagnosticEmissionRequest base = EmissionPolicyFixtureSupport.emissionRequest(fixture, diagnostic);
        BoundedDiagnosticDeduplicationTracker tracker =
            new BoundedDiagnosticDeduplicationTracker(policy.deduplicationCapacity());
        ScriptedInMemoryDiagnosticSink sink = new ScriptedInMemoryDiagnosticSink(8);
        assertEquals(
            DiagnosticEmissionStatus.EMITTED,
            emitDiagnostic(policy, base, tracker, sink).status()
        );
        assertEquals(
            DiagnosticEmissionStatus.DUPLICATE,
            emitDiagnostic(
                policy,
                new DiagnosticEmissionRequest(
                    "1",
                    "duplicate",
                    base.sampleKey(),
                    base.ordinal() + 2,
                    diagnostic
                ),
                tracker,
                sink
            ).status()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> tracker.isDuplicate("other", base.ordinal() + 1, 5)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new BoundedDiagnosticDeduplicationTracker(0)
        );
        assertThrows(
            SdkEmissionPolicyV1.UnsupportedEmissionPolicyVersionException.class,
            () -> new DiagnosticEmissionPolicy(
                "2",
                policy.minimumSeverity(),
                policy.sampleNumerator(),
                policy.sampleDenominator(),
                policy.sampleSalt(),
                policy.deduplicationWindowOrdinals(),
                policy.deduplicationCapacity()
            )
        );
    }

    @Test
    void sinkCapacityAndScriptedFailureAreStableDegradedOutcomes() throws IOException {
        Map<String, Object> fixture = EmissionPolicyFixtureSupport.fixture();
        DiagnosticEmissionPolicy original = EmissionPolicyFixtureSupport.diagnosticPolicy(fixture);
        DiagnosticEmissionPolicy all = new DiagnosticEmissionPolicy(
            "1",
            original.minimumSeverity(),
            original.sampleDenominator(),
            original.sampleDenominator(),
            original.sampleSalt(),
            original.deduplicationWindowOrdinals(),
            original.deduplicationCapacity()
        );
        SafeDiagnostic diagnostic = EmissionPolicyFixtureSupport.diagnostic(fixture);
        DiagnosticEmissionRequest base = EmissionPolicyFixtureSupport.emissionRequest(fixture, diagnostic);
        BoundedDiagnosticDeduplicationTracker tracker =
            new BoundedDiagnosticDeduplicationTracker(all.deduplicationCapacity());
        ScriptedInMemoryDiagnosticSink capacitySink = new ScriptedInMemoryDiagnosticSink(1);
        assertEquals(
            DiagnosticEmissionStatus.EMITTED,
            emitDiagnostic(all, base, tracker, capacitySink).status()
        );
        SafeDiagnostic different = new SafeDiagnostic(
            "1",
            "adapter.other",
            diagnostic.severity(),
            diagnostic.message(),
            diagnostic.context(),
            diagnostic.redactionCount(),
            diagnostic.provenanceDigest()
        );
        assertEquals(
            DiagnosticEmissionStatus.SINK_CAPACITY,
            emitDiagnostic(
                all,
                new DiagnosticEmissionRequest(
                    "1",
                    "capacity",
                    base.sampleKey(),
                    base.ordinal() + 1,
                    different
                ),
                tracker,
                capacitySink
            ).status()
        );
        BoundedDiagnosticDeduplicationTracker failedTracker =
            new BoundedDiagnosticDeduplicationTracker(all.deduplicationCapacity());
        DiagnosticEmissionResult failed = emitDiagnostic(
            all,
            base,
            failedTracker,
            new ScriptedInMemoryDiagnosticSink(4, List.of(1))
        );
        assertEquals(DiagnosticEmissionStatus.SINK_FAILED, failed.status());
        assertFalse(failed.toString().contains("scripted diagnostic sink failure"));
    }
}
