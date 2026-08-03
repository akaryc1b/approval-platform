package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialFailure;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialLease;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSourceException;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Server-owned P6-B source for one exact OpenAI API-key binding.
 *
 * <p>The source opens no network connection and creates no Provider invocation. Material is read
 * only when an exact lease is requested and is transferred immediately to the accepted bounded
 * lease implementation.</p>
 */
public final class OpenAiEnvironmentCredentialMaterialSource
    implements CredentialMaterialSource {

    public static final String PROVIDER_KEY = "openai-responses";
    public static final String CREDENTIAL_REFERENCE_ID = "openai-api-key";
    public static final String PROTOCOL_PROFILE = "OPENAI_RESPONSES_V1";
    public static final String CAPABILITY = "APPROVAL_ASSISTANCE";
    public static final String SECRET_VARIABLE = "OPENAI_API_KEY";
    public static final String VERSION_VARIABLE = "OPENAI_API_KEY_VERSION";

    private static final int MAXIMUM_API_KEY_CHARACTERS = 4_096;

    private final String exactRequestEvidenceHash;
    private final String tenantHash;
    private final String credentialReferenceHash;
    private final String routePlanHash;
    private final String credentialBindingHash;
    private final String versionReference;
    private final String versionEvidenceHash;
    private final String policyRevision;
    private final String bindingEvidenceHash;
    private final EnvironmentVariableReader environment;
    private final Clock clock;
    private final LongSupplier ordinalSource;
    private final AtomicBoolean leaseActive = new AtomicBoolean();

    public OpenAiEnvironmentCredentialMaterialSource(
        CredentialMaterialRequest admittedRequest,
        EnvironmentVariableReader environment,
        Clock clock,
        LongSupplier ordinalSource
    ) {
        CredentialMaterialRequest request = requireP6BProfile(admittedRequest);
        this.exactRequestEvidenceHash = request.evidenceHash();
        this.tenantHash = request.tenantHash();
        this.credentialReferenceHash = request.credentialReferenceHash();
        this.routePlanHash = request.routePlanHash();
        this.credentialBindingHash = request.credentialBindingHash();
        this.versionReference = request.expectedVersion().versionReference();
        this.versionEvidenceHash = request.expectedVersion().versionEvidenceHash();
        this.policyRevision = request.policyRevision();
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ordinalSource = Objects.requireNonNull(
            ordinalSource,
            "ordinalSource must not be null"
        );
        this.bindingEvidenceHash = CanonicalPayloadHash.sha256Utf8(
            String.join(
                "\n",
                "openai-environment-binding-v1",
                tenantHash,
                credentialReferenceHash,
                routePlanHash,
                credentialBindingHash,
                versionReference,
                versionEvidenceHash,
                policyRevision,
                exactRequestEvidenceHash
            )
        );
    }

    public static OpenAiEnvironmentCredentialMaterialSource systemEnvironment(
        CredentialMaterialRequest admittedRequest,
        Clock clock
    ) {
        AtomicLong ordinals = new AtomicLong();
        return new OpenAiEnvironmentCredentialMaterialSource(
            admittedRequest,
            EnvironmentVariableReader.system(),
            clock,
            ordinals::incrementAndGet
        );
    }

    @Override
    public MaterialScope openMaterial(
        io.github.akaryc1b.approval.connector.contract.CredentialReference reference,
        String expectedKeyId,
        String expectedVersionId
    ) {
        throw new CredentialMaterialSource.SourceUnavailableException();
    }

    @Override
    public CredentialMaterialLease openLease(CredentialMaterialRequest request) {
        CredentialMaterialRequest exact = requireExactRequest(request);
        requireEffectiveVersion(exact, clock.instant());
        if (!leaseActive.compareAndSet(false, true)) {
            throw new CredentialMaterialSourceException(
                CredentialMaterialFailure.CONCURRENT_ACCESS_REJECTED
            );
        }

        char[] secretCharacters = null;
        byte[] material = null;
        boolean transferred = false;
        try {
            String sourceVersion = readVersion();
            if (!sourceVersion.equals(versionReference)) {
                throw new CredentialMaterialSourceException(
                    CredentialMaterialFailure.VERSION_DRIFT
                );
            }

            secretCharacters = readSecretCharacters();
            requireWellFormedApiKey(secretCharacters);
            material = encodeAndZeroIntermediate(secretCharacters);
            long acquisitionOrdinal = nextOrdinal();
            String sourceEvidenceHash = CanonicalPayloadHash.sha256Utf8(
                String.join(
                    "\n",
                    "openai-environment-source-v1",
                    SECRET_VARIABLE,
                    VERSION_VARIABLE,
                    bindingEvidenceHash,
                    exact.evidenceHash(),
                    sourceVersion,
                    Integer.toString(material.length),
                    Long.toString(acquisitionOrdinal)
                )
            );
            CredentialMaterialDescriptor descriptor = CredentialMaterialDescriptor.loaded(
                exact,
                sourceEvidenceHash,
                acquisitionOrdinal
            );
            CredentialMaterialLease lease = CredentialMaterialLease.takeOwnership(
                exact,
                descriptor,
                material,
                ordinalSource,
                () -> leaseActive.set(false)
            );
            transferred = true;
            return lease;
        } catch (CredentialMaterialSourceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CredentialMaterialSourceException(
                CredentialMaterialFailure.SOURCE_UNAVAILABLE
            );
        } finally {
            if (secretCharacters != null) {
                Arrays.fill(secretCharacters, '\0');
            }
            if (!transferred) {
                if (material != null) {
                    Arrays.fill(material, (byte) 0);
                }
                leaseActive.set(false);
            }
        }
    }

    public String bindingEvidenceHash() {
        return bindingEvidenceHash;
    }

    public boolean leaseActive() {
        return leaseActive.get();
    }

    @Override
    public String toString() {
        return "OpenAiEnvironmentCredentialMaterialSource[bindingEvidenceHash="
            + bindingEvidenceHash + ", leaseActive=" + leaseActive.get() + "]";
    }

    private CredentialMaterialRequest requireExactRequest(CredentialMaterialRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!PROVIDER_KEY.equals(request.providerKey())) {
            throw failure(CredentialMaterialFailure.PROVIDER_DRIFT);
        }
        if (!tenantHash.equals(request.tenantHash())) {
            throw failure(CredentialMaterialFailure.TENANT_DRIFT);
        }
        if (!credentialReferenceHash.equals(request.credentialReferenceHash())) {
            throw failure(CredentialMaterialFailure.REFERENCE_DRIFT);
        }
        if (!routePlanHash.equals(request.routePlanHash())) {
            throw failure(CredentialMaterialFailure.ROUTE_DRIFT);
        }
        if (!credentialBindingHash.equals(request.credentialBindingHash())) {
            throw failure(CredentialMaterialFailure.BINDING_DRIFT);
        }
        if (!versionReference.equals(request.expectedVersion().versionReference())
            || !versionEvidenceHash.equals(request.expectedVersion().versionEvidenceHash())) {
            throw failure(CredentialMaterialFailure.VERSION_DRIFT);
        }
        if (request.materialType() != CredentialMaterialType.API_KEY) {
            throw failure(CredentialMaterialFailure.MATERIAL_TYPE_DRIFT);
        }
        if (request.operation() != ConnectorOperation.AI_ADVISORY_GENERATE) {
            throw failure(CredentialMaterialFailure.OPERATION_NOT_ALLOWED);
        }
        if (!PROTOCOL_PROFILE.equals(request.protocolProfile())) {
            throw failure(CredentialMaterialFailure.PROTOCOL_DRIFT);
        }
        if (!CAPABILITY.equals(request.capability())) {
            throw failure(CredentialMaterialFailure.CAPABILITY_DRIFT);
        }
        if (request.environment() != CredentialMaterialEnvironment.PRODUCTION) {
            throw failure(CredentialMaterialFailure.ENVIRONMENT_DRIFT);
        }
        if (!policyRevision.equals(request.policyRevision())) {
            throw failure(CredentialMaterialFailure.POLICY_DRIFT);
        }
        if (!exactRequestEvidenceHash.equals(request.evidenceHash())) {
            throw failure(CredentialMaterialFailure.MATERIAL_MALFORMED);
        }
        return request;
    }

    private String readVersion() {
        String value;
        try {
            value = environment.readNonSecret(VERSION_VARIABLE);
        } catch (RuntimeException failure) {
            throw failure(CredentialMaterialFailure.SOURCE_UNAVAILABLE);
        }
        if (value == null) {
            throw failure(CredentialMaterialFailure.SOURCE_UNAVAILABLE);
        }
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > 128) {
            throw failure(CredentialMaterialFailure.MATERIAL_MALFORMED);
        }
        return value;
    }

    private char[] readSecretCharacters() {
        char[] value;
        try {
            value = environment.readSecret(SECRET_VARIABLE);
        } catch (RuntimeException failure) {
            throw failure(CredentialMaterialFailure.SOURCE_UNAVAILABLE);
        }
        if (value == null) {
            throw failure(CredentialMaterialFailure.SOURCE_UNAVAILABLE);
        }
        return value;
    }

    private long nextOrdinal() {
        long value = ordinalSource.getAsLong();
        if (value < 0) {
            throw failure(CredentialMaterialFailure.UNKNOWN);
        }
        return value;
    }

    private static void requireWellFormedApiKey(char[] value) {
        if (value.length == 0 || value.length > MAXIMUM_API_KEY_CHARACTERS) {
            throw failure(CredentialMaterialFailure.MATERIAL_MALFORMED);
        }
        for (char character : value) {
            if (character < 0x21 || character > 0x7e) {
                throw failure(CredentialMaterialFailure.MATERIAL_MALFORMED);
            }
        }
    }

    private static byte[] encodeAndZeroIntermediate(char[] value) {
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] copy = new byte[encoded.remaining()];
            encoded.get(copy);
            return copy;
        } catch (CharacterCodingException failure) {
            throw failure(CredentialMaterialFailure.MATERIAL_MALFORMED);
        } finally {
            if (encoded != null) {
                ByteBuffer writable = encoded.duplicate();
                writable.clear();
                while (writable.hasRemaining()) {
                    writable.put((byte) 0);
                }
            }
        }
    }

    private static void requireEffectiveVersion(
        CredentialMaterialRequest request,
        Instant now
    ) {
        if (now.isBefore(request.expectedVersion().effectiveFrom())) {
            throw failure(CredentialMaterialFailure.CREDENTIAL_NOT_YET_VALID);
        }
        if (!now.isBefore(request.expectedVersion().expiresAt())) {
            throw failure(CredentialMaterialFailure.CREDENTIAL_EXPIRED);
        }
    }

    private static CredentialMaterialRequest requireP6BProfile(
        CredentialMaterialRequest request
    ) {
        Objects.requireNonNull(request, "admittedRequest must not be null");
        boolean invalid = !PROVIDER_KEY.equals(request.providerKey())
            || !CREDENTIAL_REFERENCE_ID.equals(
                request.credentialReference().referenceId()
            )
            || request.materialType() != CredentialMaterialType.API_KEY
            || request.operation() != ConnectorOperation.AI_ADVISORY_GENERATE
            || !PROTOCOL_PROFILE.equals(request.protocolProfile())
            || !CAPABILITY.equals(request.capability())
            || request.environment() != CredentialMaterialEnvironment.PRODUCTION;
        if (invalid) {
            throw new IllegalArgumentException(
                "admittedRequest must match the exact P6-B OpenAI Secret profile"
            );
        }
        return request;
    }

    private static CredentialMaterialSourceException failure(
        CredentialMaterialFailure failure
    ) {
        return new CredentialMaterialSourceException(failure);
    }

    public interface EnvironmentVariableReader {

        char[] readSecret(String variableName);

        String readNonSecret(String variableName);

        static EnvironmentVariableReader system() {
            return new EnvironmentVariableReader() {
                @Override
                public char[] readSecret(String variableName) {
                    String value = System.getenv(variableName);
                    return value == null ? null : value.toCharArray();
                }

                @Override
                public String readNonSecret(String variableName) {
                    return System.getenv(variableName);
                }
            };
        }
    }
}
