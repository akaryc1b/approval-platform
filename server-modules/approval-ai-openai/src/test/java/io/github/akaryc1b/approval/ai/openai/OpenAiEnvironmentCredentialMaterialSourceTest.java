package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.openai.OpenAiEnvironmentCredentialMaterialSource.EnvironmentVariableReader;
import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialFailure;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialLease;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialLeaseSupport;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSourceException;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiEnvironmentCredentialMaterialSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");
    private static final String API_KEY = "sk-test-only-openai-key-1234567890";

    @Test
    void exactRequestUsesCallbackScopedCopyAndZeroizesEveryPlatformOwnedArray() {
        CapturingEnvironment environment = environment(API_KEY, "key-v1");
        OpenAiEnvironmentCredentialMaterialSource source = source(request(), environment);

        CredentialMaterialLease lease = source.openLease(request());
        assertTrue(source.leaseActive());
        assertTrue(allZero(environment.lastReturnedSecret()));
        assertFalse(lease.toString().contains(API_KEY));
        assertFalse(lease.descriptor().toString().contains(API_KEY));
        assertFalse(source.toString().contains(API_KEY));

        AtomicReference<byte[]> callbackCopy = new AtomicReference<>();
        lease.useMaterial(material -> {
            callbackCopy.set(material);
            assertArrayEquals(API_KEY.getBytes(StandardCharsets.UTF_8), material);
        });
        assertTrue(allZero(callbackCopy.get()));
        assertFalse(lease.auditEvidence().toString().contains(API_KEY));

        lease.close();
        assertFalse(source.leaseActive());
        assertTrue(lease.closed());
    }

    @Test
    void sourceAllowsOnlyOneActiveLeaseAndAllowsAFreshLeaseAfterClose() {
        CapturingEnvironment environment = environment(API_KEY, "key-v1");
        OpenAiEnvironmentCredentialMaterialSource source = source(request(), environment);

        CredentialMaterialLease first = source.openLease(request());
        CredentialMaterialSourceException concurrent = assertThrows(
            CredentialMaterialSourceException.class,
            () -> source.openLease(request())
        );
        assertEquals(
            CredentialMaterialFailure.CONCURRENT_ACCESS_REJECTED,
            concurrent.failure()
        );

        first.close();
        try (CredentialMaterialLease second = source.openLease(request())) {
            second.useMaterial(material -> assertArrayEquals(
                API_KEY.getBytes(StandardCharsets.UTF_8),
                material
            ));
        }
        assertFalse(source.leaseActive());
        assertEquals(2, environment.secretReads());
        assertEquals(2, environment.versionReads());
    }

    @Test
    void callbackFailureStillClosesAndReleasesTheLease() {
        CapturingEnvironment environment = environment(API_KEY, "key-v1");
        OpenAiEnvironmentCredentialMaterialSource source = source(request(), environment);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> CredentialMaterialLeaseSupport.withLease(
                source,
                request(),
                lease -> lease.useMaterial(material -> {
                    throw new IllegalStateException("test callback failure");
                })
            )
        );
        assertEquals("test callback failure", failure.getMessage());
        assertFalse(source.leaseActive());

        try (CredentialMaterialLease next = source.openLease(request())) {
            assertTrue(next.active());
        }
        assertFalse(source.leaseActive());
    }

    @Test
    void missingBlankMalformedAndVersionDriftFailClosedWithoutSecretDisclosure() {
        assertFailure(
            source(request(), new CapturingEnvironment(null, "key-v1")),
            request(),
            CredentialMaterialFailure.SOURCE_UNAVAILABLE
        );
        assertFailure(
            source(request(), new CapturingEnvironment(API_KEY, null)),
            request(),
            CredentialMaterialFailure.SOURCE_UNAVAILABLE
        );
        assertFailure(
            source(request(), environment("", "key-v1")),
            request(),
            CredentialMaterialFailure.MATERIAL_MALFORMED
        );
        assertFailure(
            source(request(), environment(" leading-space", "key-v1")),
            request(),
            CredentialMaterialFailure.MATERIAL_MALFORMED
        );
        assertFailure(
            source(request(), environment("line\nbreak", "key-v1")),
            request(),
            CredentialMaterialFailure.MATERIAL_MALFORMED
        );
        assertFailure(
            source(request(), environment(API_KEY, " key-v1")),
            request(),
            CredentialMaterialFailure.MATERIAL_MALFORMED
        );
        assertFailure(
            source(request(), environment(API_KEY, "key-v2")),
            request(),
            CredentialMaterialFailure.VERSION_DRIFT
        );
    }

    @Test
    void exactRequestDriftIsRejectedBeforeEnvironmentAccess() {
        CapturingEnvironment environment = environment(API_KEY, "key-v1");
        CredentialMaterialRequest admitted = request();
        OpenAiEnvironmentCredentialMaterialSource source = source(admitted, environment);

        assertDrift(
            source,
            copy(admitted, hash("other-route"), admitted.credentialBindingHash(),
                admitted.expectedVersion(), admitted.environment(), admitted.policyRevision()),
            CredentialMaterialFailure.ROUTE_DRIFT
        );
        assertDrift(
            source,
            copy(admitted, admitted.routePlanHash(), hash("other-binding"),
                admitted.expectedVersion(), admitted.environment(), admitted.policyRevision()),
            CredentialMaterialFailure.BINDING_DRIFT
        );
        assertDrift(
            source,
            copy(admitted, admitted.routePlanHash(), admitted.credentialBindingHash(),
                version("key-v2", NOW.minusSeconds(60), NOW.plusSeconds(600)),
                admitted.environment(), admitted.policyRevision()),
            CredentialMaterialFailure.VERSION_DRIFT
        );
        assertDrift(
            source,
            copy(admitted, admitted.routePlanHash(), admitted.credentialBindingHash(),
                admitted.expectedVersion(), CredentialMaterialEnvironment.NON_PRODUCTION,
                admitted.policyRevision()),
            CredentialMaterialFailure.ENVIRONMENT_DRIFT
        );
        assertDrift(
            source,
            copy(admitted, admitted.routePlanHash(), admitted.credentialBindingHash(),
                admitted.expectedVersion(), admitted.environment(), "other-policy"),
            CredentialMaterialFailure.POLICY_DRIFT
        );

        assertEquals(0, environment.secretReads());
        assertEquals(0, environment.versionReads());
    }

    @Test
    void notYetValidAndExpiredVersionFailBeforeEnvironmentAccess() {
        CapturingEnvironment notYetEnvironment = environment(API_KEY, "key-v1");
        CredentialMaterialRequest notYet = request(
            version("key-v1", NOW.plusSeconds(1), NOW.plusSeconds(600))
        );
        assertFailure(
            source(notYet, notYetEnvironment),
            notYet,
            CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID
        );
        assertEquals(0, notYetEnvironment.secretReads());
        assertEquals(0, notYetEnvironment.versionReads());

        CapturingEnvironment expiredEnvironment = environment(API_KEY, "key-v1");
        CredentialMaterialRequest expired = request(
            version("key-v1", NOW.minusSeconds(600), NOW)
        );
        assertFailure(
            source(expired, expiredEnvironment),
            expired,
            CredentialMaterialFailure.CREDENTIAL_EXPIRED
        );
        assertEquals(0, expiredEnvironment.secretReads());
        assertEquals(0, expiredEnvironment.versionReads());
    }

    @Test
    void evidenceAndExceptionsRemainRedactedAndLegacyScopeIsUnavailable() {
        CapturingEnvironment environment = environment(API_KEY, "key-v1");
        OpenAiEnvironmentCredentialMaterialSource source = source(request(), environment);

        assertEquals(64, source.bindingEvidenceHash().length());
        assertNotEquals(hash(API_KEY), source.bindingEvidenceHash());
        assertFalse(source.toString().contains(API_KEY));

        assertThrows(
            CredentialMaterialSource.SourceUnavailableException.class,
            () -> source.openMaterial(
                request().credentialReference(),
                "key-v1",
                "key-v1"
            )
        );

        CredentialMaterialSourceException missing = assertThrows(
            CredentialMaterialSourceException.class,
            () -> source(
                request(),
                new CapturingEnvironment(null, "key-v1")
            ).openLease(request())
        );
        assertFalse(missing.getMessage().contains(API_KEY));
        assertFalse(missing.getMessage().contains("OPENAI_API_KEY"));
    }

    private static OpenAiEnvironmentCredentialMaterialSource source(
        CredentialMaterialRequest request,
        EnvironmentVariableReader environment
    ) {
        AtomicLong ordinal = new AtomicLong();
        return new OpenAiEnvironmentCredentialMaterialSource(
            request,
            environment,
            Clock.fixed(NOW, ZoneOffset.UTC),
            ordinal::incrementAndGet
        );
    }

    private static CredentialMaterialRequest request() {
        return request(version("key-v1", NOW.minusSeconds(60), NOW.plusSeconds(600)));
    }

    private static CredentialMaterialRequest request(CredentialMaterialVersion version) {
        return new CredentialMaterialRequest(
            new CredentialReference(
                OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY,
                OpenAiEnvironmentCredentialMaterialSource.CREDENTIAL_REFERENCE_ID
            ),
            "tenant-a",
            OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY,
            hash("route-plan"),
            hash("credential-binding"),
            version,
            CredentialMaterialType.API_KEY,
            ConnectorOperation.AI_ADVISORY_GENERATE,
            OpenAiEnvironmentCredentialMaterialSource.PROTOCOL_PROFILE,
            OpenAiEnvironmentCredentialMaterialSource.CAPABILITY,
            CredentialMaterialEnvironment.PRODUCTION,
            "m6-e-p6-openai-secret-v1"
        );
    }

    private static CredentialMaterialRequest copy(
        CredentialMaterialRequest source,
        String routePlanHash,
        String bindingHash,
        CredentialMaterialVersion version,
        CredentialMaterialEnvironment environment,
        String policyRevision
    ) {
        return new CredentialMaterialRequest(
            source.credentialReference(),
            source.tenantId(),
            source.providerKey(),
            routePlanHash,
            bindingHash,
            version,
            source.materialType(),
            source.operation(),
            source.protocolProfile(),
            source.capability(),
            environment,
            policyRevision
        );
    }

    private static CredentialMaterialVersion version(
        String reference,
        Instant effectiveFrom,
        Instant expiresAt
    ) {
        return new CredentialMaterialVersion(
            reference,
            effectiveFrom,
            expiresAt,
            hash(reference)
        );
    }

    private static CapturingEnvironment environment(String secret, String version) {
        return new CapturingEnvironment(secret, version);
    }

    private static void assertDrift(
        OpenAiEnvironmentCredentialMaterialSource source,
        CredentialMaterialRequest request,
        CredentialMaterialFailure expected
    ) {
        assertFailure(source, request, expected);
    }

    private static void assertFailure(
        OpenAiEnvironmentCredentialMaterialSource source,
        CredentialMaterialRequest request,
        CredentialMaterialFailure expected
    ) {
        CredentialMaterialSourceException failure = assertThrows(
            CredentialMaterialSourceException.class,
            () -> source.openLease(request)
        );
        assertEquals(expected, failure.failure());
        assertFalse(source.leaseActive());
    }

    private static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    private static boolean allZero(char[] value) {
        return value != null && value.length > 0
            && new String(value).chars().allMatch(character -> character == 0);
    }

    private static boolean allZero(byte[] value) {
        return value != null && value.length > 0
            && Arrays.stream(toIntegers(value)).allMatch(number -> number == 0);
    }

    private static int[] toIntegers(byte[] value) {
        int[] output = new int[value.length];
        for (int index = 0; index < value.length; index++) {
            output[index] = value[index];
        }
        return output;
    }

    private static final class CapturingEnvironment implements EnvironmentVariableReader {

        private final String secret;
        private final String version;
        private final AtomicInteger secretReads = new AtomicInteger();
        private final AtomicInteger versionReads = new AtomicInteger();
        private volatile char[] lastReturnedSecret;

        private CapturingEnvironment(String secret, String version) {
            this.secret = secret;
            this.version = version;
        }

        @Override
        public char[] readSecret(String variableName) {
            assertEquals(OpenAiEnvironmentCredentialMaterialSource.SECRET_VARIABLE, variableName);
            secretReads.incrementAndGet();
            lastReturnedSecret = secret == null ? null : secret.toCharArray();
            return lastReturnedSecret;
        }

        @Override
        public String readNonSecret(String variableName) {
            assertEquals(OpenAiEnvironmentCredentialMaterialSource.VERSION_VARIABLE, variableName);
            versionReads.incrementAndGet();
            return version;
        }

        private char[] lastReturnedSecret() {
            return lastReturnedSecret;
        }

        private int secretReads() {
            return secretReads.get();
        }

        private int versionReads() {
            return versionReads.get();
        }
    }
}
