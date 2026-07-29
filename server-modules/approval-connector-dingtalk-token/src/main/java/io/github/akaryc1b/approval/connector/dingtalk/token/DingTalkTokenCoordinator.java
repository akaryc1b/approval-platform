package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class DingTalkTokenCoordinator implements AutoCloseable {

    private final CredentialMaterialSource materialSource;
    private final DingTalkTokenEndpointPort endpointPort;
    private final DingTalkTokenPolicy policy;
    private final Clock clock;
    private final DingTalkTokenCache cache;
    private final DingTalkTokenSecurityValidator securityValidator;
    private final AtomicLong ordinal = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, CompletableFuture<DingTalkTokenEntry>> flights =
        new ConcurrentHashMap<>();

    public DingTalkTokenCoordinator(
        CredentialBindingCatalog credentialCatalog,
        CredentialMaterialSource materialSource,
        DingTalkTokenRouteGate routeGate,
        DingTalkTokenKillSwitch killSwitch,
        DingTalkTokenEndpointPort endpointPort,
        DingTalkTokenPolicy policy,
        Clock clock
    ) {
        this.materialSource = Objects.requireNonNull(
            materialSource,
            "materialSource must not be null"
        );
        this.endpointPort = Objects.requireNonNull(endpointPort, "endpointPort must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        cache = new DingTalkTokenCache(policy.maximumEntries());
        securityValidator = new DingTalkTokenSecurityValidator(
            credentialCatalog,
            routeGate,
            killSwitch,
            this::requireOpen,
            cache::invalidateFamily
        );
    }

    public DingTalkAccessTokenLease acquire(DingTalkTokenRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireOpen();
        if (!policy.policyVersion().equals(request.tokenPolicyVersion())) {
            throw failure(DingTalkTokenFailure.CREDENTIAL_POLICY_DRIFT);
        }
        securityValidator.validate(request, clock.instant());
        cache.rotateFamily(request.familyHash(), request.cacheKeyHash());

        DingTalkTokenEntry cached = cache.get(request.cacheKeyHash());
        Instant now = clock.instant();
        if (cached != null && cached.usableWithoutRefreshAt(now)) {
            return cached.issueLease(DingTalkTokenOutcome.CACHE_HIT, request, false, now);
        }

        boolean refresh = cached != null;
        cache.removeAndClose(request.cacheKeyHash(), cached);
        cache.ensureCapacity(request.cacheKeyHash());

        CompletableFuture<DingTalkTokenEntry> created = new CompletableFuture<>();
        CompletableFuture<DingTalkTokenEntry> existing = flights.putIfAbsent(
            request.cacheKeyHash(),
            created
        );
        if (existing != null) {
            DingTalkTokenEntry joined = await(existing);
            securityValidator.validate(request, clock.instant());
            if (!joined.usableAt(clock.instant())) {
                throw failure(DingTalkTokenFailure.TOKEN_EXPIRED);
            }
            return joined.issueLease(
                DingTalkTokenOutcome.SINGLE_FLIGHT_JOIN,
                request,
                false,
                clock.instant()
            );
        }

        try {
            DingTalkTokenEntry loaded = loadAndInstall(request);
            created.complete(loaded);
            return loaded.issueLease(
                refresh ? DingTalkTokenOutcome.REFRESHED : DingTalkTokenOutcome.ACQUIRED,
                request,
                true,
                clock.instant()
            );
        } catch (RuntimeException | Error problem) {
            created.completeExceptionally(problem);
            throw problem;
        } finally {
            flights.remove(request.cacheKeyHash(), created);
        }
    }

    public int cachedEntryCount() {
        return cache.size();
    }

    public int inFlightCount() {
        return flights.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        DingTalkTokenLifecycleException problem = failure(
            DingTalkTokenFailure.COORDINATOR_CLOSED
        );
        flights.values().forEach(future -> future.completeExceptionally(problem));
        flights.clear();
        cache.close();
    }

    @Override
    public String toString() {
        return "DingTalkTokenCoordinator[entries=" + cache.size()
            + ", flights=" + flights.size() + ", material=<redacted>]";
    }

    private DingTalkTokenEntry loadAndInstall(DingTalkTokenRequest request) {
        securityValidator.validate(request, clock.instant());
        AtomicReference<DingTalkTokenEntry> produced = new AtomicReference<>();
        AtomicInteger responseCount = new AtomicInteger();
        DingTalkTokenEndpointRequest endpointRequest = DingTalkTokenEndpointRequest.create(
            request,
            nextOrdinal()
        );

        try (var lease = materialSource.openLease(request.applicationCredentialRequest())) {
            lease.useMaterial(material -> DingTalkApplicationCredentialCodec.decode(
                material,
                (applicationKey, applicationSecret) -> invokeEndpoint(
                    endpointRequest,
                    request,
                    applicationKey,
                    applicationSecret,
                    responseCount,
                    produced
                )
            ));
        } catch (DingTalkTokenLifecycleException problem) {
            closeProduced(produced);
            throw problem;
        } catch (RuntimeException problem) {
            closeProduced(produced);
            throw failure(DingTalkTokenFailure.CREDENTIAL_MATERIAL_FAILURE);
        }

        DingTalkTokenEntry entry = produced.get();
        if (responseCount.get() != 1 || entry == null) {
            closeProduced(produced);
            throw failure(DingTalkTokenFailure.ENDPOINT_MALFORMED);
        }
        try {
            securityValidator.validate(request, clock.instant());
            requireOpen();
            cache.install(entry);
            return entry;
        } catch (RuntimeException | Error problem) {
            entry.close();
            throw problem;
        }
    }

    private void invokeEndpoint(
        DingTalkTokenEndpointRequest endpointRequest,
        DingTalkTokenRequest request,
        byte[] applicationKey,
        byte[] applicationSecret,
        AtomicInteger responseCount,
        AtomicReference<DingTalkTokenEntry> produced
    ) {
        try {
            endpointPort.acquire(
                endpointRequest,
                applicationKey,
                applicationSecret,
                (tokenMaterial, lifetimeSeconds) -> {
                    if (responseCount.getAndIncrement() != 0) {
                        Arrays.fill(tokenMaterial, (byte) 0);
                        throw failure(DingTalkTokenFailure.ENDPOINT_MALFORMED);
                    }
                    DingTalkTokenEntry entry = tokenEntry(
                        request,
                        tokenMaterial,
                        lifetimeSeconds
                    );
                    if (!produced.compareAndSet(null, entry)) {
                        entry.close();
                        throw failure(DingTalkTokenFailure.ENDPOINT_MALFORMED);
                    }
                }
            );
        } catch (DingTalkTokenLifecycleException problem) {
            throw problem;
        } catch (RuntimeException problem) {
            throw failure(DingTalkTokenFailure.ENDPOINT_UNAVAILABLE);
        }
    }

    private DingTalkTokenEntry tokenEntry(
        DingTalkTokenRequest request,
        byte[] tokenMaterial,
        long lifetimeSeconds
    ) {
        Objects.requireNonNull(tokenMaterial, "tokenMaterial must not be null");
        Instant issuedAt = clock.instant();
        Duration lifetime;
        try {
            lifetime = Duration.ofSeconds(lifetimeSeconds);
        } catch (ArithmeticException problem) {
            Arrays.fill(tokenMaterial, (byte) 0);
            throw failure(DingTalkTokenFailure.TOKEN_LIFETIME_INVALID);
        }
        if (lifetime.isNegative() || lifetime.isZero()
            || lifetime.compareTo(policy.minimumValidity()) < 0
            || lifetime.compareTo(policy.maximumLifetime()) > 0) {
            Arrays.fill(tokenMaterial, (byte) 0);
            throw failure(DingTalkTokenFailure.TOKEN_LIFETIME_INVALID);
        }
        Instant expiresAt = issuedAt.plus(lifetime);
        Instant refreshAt = expiresAt.minus(policy.refreshBeforeExpiry());
        if (refreshAt.isBefore(issuedAt)) {
            refreshAt = issuedAt;
        }
        long generation = nextOrdinal();
        String versionReference = DingTalkTokenSupport.hash(
            request.cacheKeyHash() + "\n" + generation + "\n" + issuedAt + "\n" + expiresAt
        );
        return DingTalkTokenEntry.takeOwnership(
            request.familyHash(),
            request.cacheKeyHash(),
            versionReference,
            issuedAt,
            refreshAt,
            expiresAt,
            generation,
            tokenMaterial
        );
    }

    private DingTalkTokenEntry await(CompletableFuture<DingTalkTokenEntry> future) {
        try {
            return future.get(policy.singleFlightWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException problem) {
            throw failure(DingTalkTokenFailure.SINGLE_FLIGHT_TIMEOUT);
        } catch (InterruptedException problem) {
            Thread.currentThread().interrupt();
            throw failure(DingTalkTokenFailure.ENDPOINT_CANCELLED);
        } catch (ExecutionException problem) {
            Throwable cause = problem.getCause();
            if (cause instanceof DingTalkTokenLifecycleException lifecycle) {
                throw lifecycle;
            }
            throw failure(DingTalkTokenFailure.SINGLE_FLIGHT_FAILED);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw failure(DingTalkTokenFailure.COORDINATOR_CLOSED);
        }
    }

    private long nextOrdinal() {
        return ordinal.getAndIncrement();
    }

    private static void closeProduced(AtomicReference<DingTalkTokenEntry> produced) {
        DingTalkTokenEntry entry = produced.getAndSet(null);
        if (entry != null) {
            entry.close();
        }
    }

    private static DingTalkTokenLifecycleException failure(DingTalkTokenFailure failure) {
        return new DingTalkTokenLifecycleException(failure);
    }
}
