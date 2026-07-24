package io.github.akaryc1b.approval.connector.testing;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorExecutionPort;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorResult;
import io.github.akaryc1b.approval.connector.contract.IdempotencyEvidence;
import io.github.akaryc1b.approval.connector.contract.IdempotencyEvidence.IdempotencyResult;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Network-free typed connector used to validate registry selection and explicit invocation.
 */
public final class DeterministicTypedConnectorPort<P, R>
    implements ConnectorExecutionPort<P, R> {

    private final ProviderDescriptor descriptor;
    private final Clock clock;
    private final Function<P, R> responseFactory;
    private final AtomicInteger invocationCount = new AtomicInteger();

    public DeterministicTypedConnectorPort(
        ProviderDescriptor descriptor,
        Clock clock,
        Function<P, R> responseFactory
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.responseFactory = Objects.requireNonNull(
            responseFactory,
            "responseFactory must not be null"
        );
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult<R> execute(
        TrustedConnectorExecutionContext context,
        ConnectorRequest<P> request
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!descriptor.providerKey().equals(context.providerKey())) {
            throw new IllegalArgumentException("trusted context targets another provider");
        }
        descriptor.requireEnabledCapability(request.operation().requiredCapability());
        invocationCount.incrementAndGet();
        R value = Objects.requireNonNull(
            responseFactory.apply(request.payload()),
            "responseFactory must not return null"
        );
        String providerRequestId = "typed-" + CanonicalPayloadHash.sha256Utf8(
            descriptor.providerKey()
                + "\n"
                + request.operation().name()
                + "\n"
                + request.idempotencyKey()
                + "\n"
                + request.canonicalPayloadHash()
        ).substring(0, 20);
        return ConnectorResult.success(
            value,
            new ConnectorProviderResult(
                providerRequestId,
                200,
                clock.instant(),
                Map.of("adapter", "deterministic-typed")
            ),
            new IdempotencyEvidence(
                request.idempotencyKey(),
                request.canonicalPayloadHash(),
                IdempotencyResult.FIRST_SEEN
            ),
            request.securityEvidence()
        );
    }

    public int invocationCount() {
        return invocationCount.get();
    }
}
