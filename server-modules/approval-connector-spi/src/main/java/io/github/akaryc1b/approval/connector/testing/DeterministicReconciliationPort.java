package io.github.akaryc1b.approval.connector.testing;

import io.github.akaryc1b.approval.connector.contract.ConnectorError;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorReconciliationEvidence;
import io.github.akaryc1b.approval.connector.contract.ConnectorReconciliationPort;
import io.github.akaryc1b.approval.connector.contract.ConnectorReconciliationRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorReconciliationResult;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.ProviderFailureClass;
import io.github.akaryc1b.approval.connector.contract.ReconciliationStatus;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Test-only reconciliation port backed by immutable deterministic fixtures.
 */
public final class DeterministicReconciliationPort<T>
    implements ConnectorReconciliationPort<T> {

    private final ProviderDescriptor descriptor;
    private final Clock clock;
    private final Map<String, Fixture<T>> fixtures;

    public DeterministicReconciliationPort(
        ProviderDescriptor descriptor,
        Clock clock,
        Collection<Fixture<T>> fixtures
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(fixtures, "fixtures must not be null");
        Map<String, Fixture<T>> sorted = new TreeMap<>();
        for (Fixture<T> fixture : fixtures) {
            Objects.requireNonNull(fixture, "fixtures must not contain null");
            if (sorted.putIfAbsent(fixture.idempotencyKey(), fixture) != null) {
                throw new IllegalArgumentException(
                    "duplicate reconciliation fixture: " + fixture.idempotencyKey()
                );
            }
        }
        this.fixtures = Map.copyOf(new LinkedHashMap<>(sorted));
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorReconciliationResult<T> reconcile(
        TrustedConnectorExecutionContext context,
        ConnectorReconciliationRequest request
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!descriptor.providerKey().equals(context.providerKey())) {
            throw new IllegalArgumentException("trusted context targets another provider");
        }
        descriptor.requireEnabledCapability(request.originalOperation().requiredCapability());
        Fixture<T> fixture = fixtures.get(request.originalIdempotencyKey());
        if (fixture == null) {
            return failure(
                request,
                ReconciliationStatus.NOT_FOUND,
                "RECONCILIATION_NOT_FOUND",
                ProviderFailureClass.UNKNOWN,
                "deterministic reconciliation evidence was not found",
                request.providerRequestId(),
                Map.of("source", "deterministic")
            );
        }
        if (!fixture.canonicalPayloadHash().equals(request.originalPayloadHash())) {
            return conflict(request, fixture, "payload hash does not match stored evidence");
        }
        if (request.providerRequestId() != null
            && fixture.providerRequestId() != null
            && !request.providerRequestId().equals(fixture.providerRequestId())) {
            return conflict(request, fixture, "provider request ID does not match stored evidence");
        }
        return fromFixture(request, fixture);
    }

    private ConnectorReconciliationResult<T> fromFixture(
        ConnectorReconciliationRequest request,
        Fixture<T> fixture
    ) {
        ConnectorReconciliationEvidence evidence = evidence(
            request,
            fixture.status(),
            fixture.providerRequestId(),
            fixture.details()
        );
        ConnectorProviderResult providerResult = providerResult(
            fixture.providerRequestId(),
            statusCode(fixture.status()),
            fixture.details()
        );
        if (fixture.status() == ReconciliationStatus.CONFIRMED_SUCCESS) {
            return new ConnectorReconciliationResult<>(
                fixture.status(),
                fixture.value(),
                providerResult,
                evidence,
                null
            );
        }
        return new ConnectorReconciliationResult<>(
            fixture.status(),
            null,
            providerResult,
            evidence,
            new ConnectorError(
                fixture.errorCode(),
                fixture.failureClass(),
                fixture.message(),
                fixture.details()
            )
        );
    }

    private ConnectorReconciliationResult<T> conflict(
        ConnectorReconciliationRequest request,
        Fixture<T> fixture,
        String message
    ) {
        return failure(
            request,
            ReconciliationStatus.CONFLICT,
            "RECONCILIATION_CONFLICT",
            ProviderFailureClass.VALIDATION,
            message,
            fixture.providerRequestId(),
            Map.of("source", "deterministic")
        );
    }

    private ConnectorReconciliationResult<T> failure(
        ConnectorReconciliationRequest request,
        ReconciliationStatus status,
        String code,
        ProviderFailureClass failureClass,
        String message,
        String providerRequestId,
        Map<String, String> details
    ) {
        ConnectorReconciliationEvidence evidence = evidence(
            request,
            status,
            providerRequestId,
            details
        );
        return new ConnectorReconciliationResult<>(
            status,
            null,
            providerResult(providerRequestId, 0, details),
            evidence,
            new ConnectorError(code, failureClass, message, details)
        );
    }

    private ConnectorReconciliationEvidence evidence(
        ConnectorReconciliationRequest request,
        ReconciliationStatus status,
        String providerRequestId,
        Map<String, String> details
    ) {
        return new ConnectorReconciliationEvidence(
            request.originalRequestId(),
            request.originalIdempotencyKey(),
            request.originalPayloadHash(),
            request.originalOperation(),
            request.originalOutcome(),
            providerRequestId,
            status,
            clock.instant(),
            details
        );
    }

    private ConnectorProviderResult providerResult(
        String providerRequestId,
        int statusCode,
        Map<String, String> details
    ) {
        return new ConnectorProviderResult(
            providerRequestId,
            statusCode,
            clock.instant(),
            details
        );
    }

    private static int statusCode(ReconciliationStatus status) {
        return switch (status) {
            case CONFIRMED_SUCCESS -> 200;
            case CONFIRMED_REJECTION -> 422;
            case CONFIRMED_PERMANENT_FAILURE -> 400;
            case STILL_UNKNOWN, NOT_FOUND, CONFLICT -> 0;
        };
    }

    public record Fixture<T>(
        String idempotencyKey,
        String canonicalPayloadHash,
        String providerRequestId,
        ReconciliationStatus status,
        T value,
        String errorCode,
        ProviderFailureClass failureClass,
        String message,
        Map<String, String> details
    ) {

        private static final Pattern SAFE_IDENTIFIER = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"
        );
        private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

        public Fixture {
            idempotencyKey = requireSafeIdentifier(idempotencyKey, "idempotencyKey");
            canonicalPayloadHash = requireHash(canonicalPayloadHash);
            providerRequestId = optionalText(providerRequestId, "providerRequestId");
            status = Objects.requireNonNull(status, "status must not be null");
            if (status == ReconciliationStatus.NOT_FOUND
                || status == ReconciliationStatus.CONFLICT) {
                throw new IllegalArgumentException(
                    "NOT_FOUND and CONFLICT are derived reconciliation statuses"
                );
            }
            details = details == null ? Map.of() : Map.copyOf(details);
            if (status == ReconciliationStatus.CONFIRMED_SUCCESS) {
                value = Objects.requireNonNull(value, "confirmed success value must not be null");
                if (errorCode != null || failureClass != null || message != null) {
                    throw new IllegalArgumentException(
                        "confirmed success fixture must not contain error fields"
                    );
                }
            } else {
                if (value != null) {
                    throw new IllegalArgumentException(
                        "non-success fixture must not contain a value"
                    );
                }
                errorCode = requireText(errorCode, "errorCode");
                failureClass = Objects.requireNonNull(
                    failureClass,
                    "failureClass must not be null"
                );
                message = requireText(message, "message");
            }
        }

        public static <T> Fixture<T> confirmedSuccess(
            String idempotencyKey,
            String canonicalPayloadHash,
            String providerRequestId,
            T value
        ) {
            return new Fixture<>(
                idempotencyKey,
                canonicalPayloadHash,
                providerRequestId,
                ReconciliationStatus.CONFIRMED_SUCCESS,
                value,
                null,
                null,
                null,
                Map.of("source", "deterministic")
            );
        }

        public static <T> Fixture<T> confirmedRejection(
            String idempotencyKey,
            String canonicalPayloadHash,
            String providerRequestId
        ) {
            return new Fixture<>(
                idempotencyKey,
                canonicalPayloadHash,
                providerRequestId,
                ReconciliationStatus.CONFIRMED_REJECTION,
                null,
                "RECONCILIATION_CONFIRMED_REJECTION",
                ProviderFailureClass.VALIDATION,
                "provider confirmed rejection",
                Map.of("source", "deterministic")
            );
        }

        public static <T> Fixture<T> confirmedPermanentFailure(
            String idempotencyKey,
            String canonicalPayloadHash,
            String providerRequestId
        ) {
            return new Fixture<>(
                idempotencyKey,
                canonicalPayloadHash,
                providerRequestId,
                ReconciliationStatus.CONFIRMED_PERMANENT_FAILURE,
                null,
                "RECONCILIATION_CONFIRMED_PERMANENT_FAILURE",
                ProviderFailureClass.PERMANENT,
                "provider confirmed permanent failure",
                Map.of("source", "deterministic")
            );
        }

        public static <T> Fixture<T> stillUnknown(
            String idempotencyKey,
            String canonicalPayloadHash,
            String providerRequestId
        ) {
            return new Fixture<>(
                idempotencyKey,
                canonicalPayloadHash,
                providerRequestId,
                ReconciliationStatus.STILL_UNKNOWN,
                null,
                "RECONCILIATION_STILL_UNKNOWN",
                ProviderFailureClass.UNKNOWN,
                "provider result remains unknown",
                Map.of("source", "deterministic")
            );
        }

        private static String requireSafeIdentifier(String value, String name) {
            String identifier = requireText(value, name);
            if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
                throw new IllegalArgumentException(name + " contains unsupported characters");
            }
            return identifier;
        }

        private static String requireHash(String value) {
            String hash = requireText(value, "canonicalPayloadHash")
                .toLowerCase(Locale.ROOT);
            if (!SHA_256.matcher(hash).matches()) {
                throw new IllegalArgumentException(
                    "canonicalPayloadHash must be a lower-case SHA-256 value"
                );
            }
            return hash;
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank() || value.length() > 512) {
                throw new IllegalArgumentException(
                    name + " must contain between 1 and 512 characters"
                );
            }
            return value;
        }

        private static String optionalText(String value, String name) {
            return value == null || value.isBlank() ? null : requireText(value, name);
        }
    }
}
