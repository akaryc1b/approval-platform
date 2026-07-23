package io.github.akaryc1b.approval.connector;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorError;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.ConnectorOutcome;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorSecretRedactor;
import io.github.akaryc1b.approval.connector.contract.ConnectorSecurityEvidence;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.IdempotencyEvidence;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.ProviderFailureClass;
import io.github.akaryc1b.approval.connector.contract.RetryDisposition;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.testing.DeterministicMockConnector;
import io.github.akaryc1b.approval.connector.testing.DeterministicMockConnector.MockCommand;
import io.github.akaryc1b.approval.connector.testing.DeterministicMockConnector.Scenario;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorFoundationContractsTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void providerDescriptorSerializesDeterministicallyWithoutSensitiveMetadata() {
        var descriptor = new ProviderDescriptor(
            "provider-one",
            ProviderDescriptor.ProviderType.OFFICE_PLATFORM,
            "2026-07.v1",
            Set.of(
                ConnectorProvider.Capability.NOTIFICATION,
                ConnectorProvider.Capability.ORGANIZATION
            ),
            ProviderDescriptor.ProviderState.ENABLED,
            Map.of("minimumPlatformVersion", "0.1", "maximumPayloadBytes", "4096")
        );

        assertEquals(descriptor.canonicalJson(), descriptor.canonicalJson());
        assertTrue(descriptor.canonicalJson().indexOf("NOTIFICATION")
            < descriptor.canonicalJson().indexOf("ORGANIZATION"));
        assertTrue(descriptor.canonicalJson().indexOf("maximumPayloadBytes")
            < descriptor.canonicalJson().indexOf("minimumPlatformVersion"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProviderDescriptor(
                "provider-one",
                ProviderDescriptor.ProviderType.OFFICE_PLATFORM,
                "v1",
                Set.of(),
                ProviderDescriptor.ProviderState.ENABLED,
                Map.of("accessToken", "must-not-be-stored")
            )
        );
    }

    @Test
    void capabilitySetIsClosedAndUnknownCapabilitiesAreRejected() {
        assertEquals(
            ConnectorProvider.Capability.NOTIFICATION,
            ConnectorProvider.Capability.parse("notification")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectorProvider.Capability.parse("UNBOUNDED_PROVIDER_EXTENSION")
        );
    }

    @Test
    void clientRequestCannotCarryTrustedIdentity() {
        Set<String> componentNames = Arrays.stream(ConnectorRequest.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());

        for (String forbidden : Set.of(
            "tenantId",
            "operatorId",
            "authority",
            "auditIdentity",
            "credentialReference"
        )) {
            assertFalse(componentNames.contains(forbidden));
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> new TrustedConnectorExecutionContext(
                "tenant-a",
                "provider-a",
                new CredentialReference("provider-b", "vault:credential-one"),
                NOW
            )
        );
    }

    @Test
    void requestRejectsMismatchedSigningPayloadHash() {
        String requestHash = CanonicalPayloadHash.sha256Utf8("request");
        String signedHash = CanonicalPayloadHash.sha256Utf8("different");

        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectorRequest<>(
                "request-1",
                "trace-1",
                "idempotency-1",
                ConnectorOperation.NOTIFICATION_SEND,
                requestHash,
                securityEvidence(signedHash, NOW),
                "payload"
            )
        );
    }

    @Test
    void signingTimestampNonceAndValidityWindowAreBounded() {
        String hash = CanonicalPayloadHash.sha256Utf8("payload");
        var evidence = securityEvidence(hash, NOW.minusSeconds(299));

        assertTrue(evidence.timestampIsValidAt(NOW));
        assertFalse(securityEvidence(hash, NOW.minusSeconds(301)).timestampIsValidAt(NOW));
        assertEquals("hmac-sha256-v1", evidence.signatureAlgorithm().identifier());
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectorSecurityEvidence(
                NOW,
                "short",
                ConnectorSecurityEvidence.SignatureAlgorithm.HMAC_SHA256_V1,
                Duration.ofMinutes(5),
                ConnectorSecurityEvidence.ReplayDetectionResult.ACCEPTED,
                hash
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectorSecurityEvidence(
                NOW,
                "nonce-0123456789",
                ConnectorSecurityEvidence.SignatureAlgorithm.HMAC_SHA256_V1,
                Duration.ofMinutes(11),
                ConnectorSecurityEvidence.ReplayDetectionResult.ACCEPTED,
                hash
            )
        );
    }

    @Test
    void canonicalPayloadHashIsDeterministic() {
        assertEquals(
            "9b1cfec7910b8e973ab3f9aff4a5f076769c3afe61899ba002aae6bfa6684542",
            CanonicalPayloadHash.sha256Utf8("approval-platform")
        );
        assertNotEquals(
            CanonicalPayloadHash.sha256Utf8("approval-platform"),
            CanonicalPayloadHash.sha256Utf8("approval-platform\n")
        );
    }

    @Test
    void credentialAndErrorEvidenceRedactSecrets() {
        var reference = new CredentialReference(
            DeterministicMockConnector.PROVIDER_KEY,
            "vault:connector:credential-one"
        );
        var error = new ConnectorError(
            "PROVIDER_FAILURE",
            ProviderFailureClass.TRANSIENT,
            "Authorization=Bearer provider-token-value token=plain-value sk-abcdefghijk",
            Map.of("accessToken", "fixture-token", "diagnostic", "password=plain-value")
        );

        assertFalse(reference.toString().contains("credential-one"));
        assertFalse(error.message().contains("provider-token-value"));
        assertFalse(error.message().contains("plain-value"));
        assertFalse(error.message().contains("abcdefghijk"));
        assertEquals(ConnectorSecretRedactor.REDACTED, error.details().get("accessToken"));
        assertFalse(error.details().get("diagnostic").contains("plain-value"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectorProviderResult(
                "provider-request",
                200,
                NOW,
                Map.of("authorization", "not-allowed")
            )
        );
    }

    @Test
    void retryClassificationDoesNotBlindlyRetryTimeoutOrUnknown() {
        assertEquals(RetryDisposition.DO_NOT_RETRY, result(ConnectorOutcome.REJECTED).retryDisposition());
        assertEquals(
            RetryDisposition.RETRY_WITH_BACKOFF,
            result(ConnectorOutcome.RATE_LIMITED).retryDisposition()
        );
        assertEquals(
            RetryDisposition.RETRY_WITH_BACKOFF,
            result(ConnectorOutcome.RETRYABLE_PROVIDER_FAILURE).retryDisposition()
        );
        assertEquals(
            RetryDisposition.DO_NOT_RETRY,
            result(ConnectorOutcome.PERMANENT_PROVIDER_FAILURE).retryDisposition()
        );
        assertEquals(
            RetryDisposition.RECONCILE_BEFORE_RETRY,
            result(ConnectorOutcome.TIMEOUT).retryDisposition()
        );
        assertEquals(
            RetryDisposition.RECONCILE_BEFORE_RETRY,
            result(ConnectorOutcome.UNKNOWN).retryDisposition()
        );
    }

    @Test
    void deterministicMockReturnsEveryRequiredOutcome() {
        var connector = connector();
        Map<Scenario, ConnectorOutcome> expected = Map.of(
            Scenario.SUCCESS, ConnectorOutcome.SUCCESS,
            Scenario.REJECTED, ConnectorOutcome.REJECTED,
            Scenario.RATE_LIMITED, ConnectorOutcome.RATE_LIMITED,
            Scenario.RETRYABLE_FAILURE, ConnectorOutcome.RETRYABLE_PROVIDER_FAILURE,
            Scenario.PERMANENT_FAILURE, ConnectorOutcome.PERMANENT_PROVIDER_FAILURE,
            Scenario.TIMEOUT, ConnectorOutcome.TIMEOUT,
            Scenario.UNKNOWN, ConnectorOutcome.UNKNOWN
        );

        expected.forEach((scenario, outcome) -> assertEquals(
            outcome,
            connector.execute(context("tenant-a"), request(scenario, scenario.name())).outcome()
        ));
        assertEquals(expected.size(), connector.uniqueExecutionCount());
    }

    @Test
    void duplicateIdempotencyReturnsSameResultAndRejectsHashConflict() {
        var connector = connector();
        var firstRequest = request(Scenario.SUCCESS, "same-key");
        var first = connector.execute(context("tenant-a"), firstRequest);
        var replay = connector.execute(context("tenant-a"), firstRequest);
        var conflicting = request(Scenario.REJECTED, "same-key");
        var conflict = connector.execute(context("tenant-a"), conflicting);

        assertEquals(ConnectorOutcome.SUCCESS, first.outcome());
        assertEquals(
            IdempotencyEvidence.IdempotencyResult.REPLAYED_SAME_RESULT,
            replay.idempotencyEvidence().result()
        );
        assertEquals(first.providerResult().providerRequestId(), replay.providerResult().providerRequestId());
        assertEquals(ConnectorOutcome.REJECTED, conflict.outcome());
        assertEquals(
            IdempotencyEvidence.IdempotencyResult.CONFLICT,
            conflict.idempotencyEvidence().result()
        );
        assertEquals(1, connector.uniqueExecutionCount());
    }

    @Test
    void deterministicMockEnforcesTrustedTenantBoundary() {
        var result = connector().execute(
            context("tenant-b"),
            request(Scenario.SUCCESS, "tenant-boundary")
        );

        assertEquals(ConnectorOutcome.REJECTED, result.outcome());
        assertEquals(ProviderFailureClass.AUTHORIZATION, result.error().failureClass());
        assertEquals("TENANT_BOUNDARY_VIOLATION", result.error().code());
    }

    private static DeterministicMockConnector connector() {
        return new DeterministicMockConnector("tenant-a", CLOCK);
    }

    private static TrustedConnectorExecutionContext context(String tenantId) {
        return new TrustedConnectorExecutionContext(
            tenantId,
            DeterministicMockConnector.PROVIDER_KEY,
            new CredentialReference(
                DeterministicMockConnector.PROVIDER_KEY,
                "vault:connector:credential-one"
            ),
            NOW
        );
    }

    private static ConnectorRequest<MockCommand> request(Scenario scenario, String key) {
        var command = new MockCommand("command-" + scenario.name(), scenario, "fixture");
        String hash = CanonicalPayloadHash.sha256Utf8(command.canonicalPayload());
        return new ConnectorRequest<>(
            "request-" + key,
            "trace-" + key,
            key,
            ConnectorOperation.NOTIFICATION_SEND,
            hash,
            securityEvidence(hash, NOW),
            command
        );
    }

    private static ConnectorSecurityEvidence securityEvidence(String hash, Instant timestamp) {
        return new ConnectorSecurityEvidence(
            timestamp,
            "nonce-0123456789",
            ConnectorSecurityEvidence.SignatureAlgorithm.HMAC_SHA256_V1,
            Duration.ofMinutes(5),
            ConnectorSecurityEvidence.ReplayDetectionResult.ACCEPTED,
            hash
        );
    }

    private static ConnectorResult<String> result(ConnectorOutcome outcome) {
        String hash = CanonicalPayloadHash.sha256Utf8(outcome.name());
        var providerResult = new ConnectorProviderResult("provider-request", 503, NOW, Map.of());
        var idempotency = new IdempotencyEvidence(
            "idempotency-" + outcome.name(),
            hash,
            IdempotencyEvidence.IdempotencyResult.FIRST_SEEN
        );
        return ConnectorResult.failure(
            outcome,
            providerResult,
            idempotency,
            null,
            new ConnectorError(
                "PROVIDER_FAILURE",
                ProviderFailureClass.TRANSIENT,
                "provider failure",
                Map.of()
            )
        );
    }
}
