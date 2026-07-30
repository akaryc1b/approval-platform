package io.github.akaryc1b.approval.connector.invocation;

import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkAccessTokenLease;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenCoordinator;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenEvidence;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenKillSwitch;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenLifecycleException;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenRequest;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .CompletionClassification;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DingTalkReadOnlyDispatchPort;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DingTalkTokenRequestSource;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DispatchRequest;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DispatchResponse;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DispatchState;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DurationBucket;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .EvidenceInput;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .GateResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationEvidence;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationPolicy;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationRequest;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .StableFailureCode;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .ResolutionStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .RevalidationStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRequest;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteResolution;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .RouteRevalidation;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteResolver;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteRevalidator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default-disabled Spring-owned coordinator for one synchronous governed read-only invocation.
 */
public final class GovernedReadOnlyConnectorInvocationCoordinator implements AutoCloseable {

    private final TenantConnectorRouteResolver routeResolver;
    private final TenantConnectorRouteRevalidator routeRevalidator;
    private final DingTalkTokenCoordinator tokenCoordinator;
    private final DingTalkTokenKillSwitch killSwitch;
    private final DingTalkTokenRequestSource tokenRequestSource;
    private final DingTalkReadOnlyDispatchPort dispatchPort;
    private final InvocationPolicy policy;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();

    public GovernedReadOnlyConnectorInvocationCoordinator(
        TenantConnectorRouteResolver routeResolver,
        TenantConnectorRouteRevalidator routeRevalidator,
        DingTalkTokenCoordinator tokenCoordinator,
        DingTalkTokenKillSwitch killSwitch,
        DingTalkTokenRequestSource tokenRequestSource,
        DingTalkReadOnlyDispatchPort dispatchPort,
        InvocationPolicy policy,
        Clock clock
    ) {
        this.routeResolver = Objects.requireNonNull(
            routeResolver,
            "routeResolver must not be null"
        );
        this.routeRevalidator = Objects.requireNonNull(
            routeRevalidator,
            "routeRevalidator must not be null"
        );
        this.tokenCoordinator = Objects.requireNonNull(
            tokenCoordinator,
            "tokenCoordinator must not be null"
        );
        this.killSwitch = Objects.requireNonNull(killSwitch, "killSwitch must not be null");
        this.tokenRequestSource = Objects.requireNonNull(
            tokenRequestSource,
            "tokenRequestSource must not be null"
        );
        this.dispatchPort = Objects.requireNonNull(dispatchPort, "dispatchPort must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public InvocationResult invoke(
        String trustedTenantId,
        InvocationRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Instant startedAt = clock.instant();
        InvocationState state = new InvocationState(
            GovernedConnectorInvocationContracts.tenantHash(trustedTenantId),
            request
        );
        if (closed.get()) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                StableFailureCode.COORDINATOR_CLOSED
            );
        }
        if (request.canonicalByteCount() > policy.maximumRequestBytes()) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                StableFailureCode.INVALID_REQUEST
            );
        }

        RouteResolution resolution;
        try {
            resolution = routeResolver.resolve(
                trustedTenantId,
                new RouteRequest(
                    request.intent().capability(),
                    request.intent(),
                    request.subjectId(),
                    request.correlationReference()
                ),
                clock.instant()
            );
        } catch (RuntimeException problem) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                StableFailureCode.ROUTE_REJECTED
            );
        }
        if (!resolution.executablePlanPresent()) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                mapResolution(resolution.status())
            );
        }

        RoutePlan plan = resolution.plan().orElseThrow();
        state.plan = plan;
        if (!GovernedConnectorInvocationContracts.closedMatrixAllows(plan)) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                StableFailureCode.UNSUPPORTED_OPERATION
            );
        }

        StableFailureCode gateFailure = evaluateKillSwitch(trustedTenantId, state);
        if (gateFailure != StableFailureCode.NONE) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                gateFailure
            );
        }

        RouteRevalidation beforeToken = routeRevalidator.revalidate(
            trustedTenantId,
            plan,
            clock.instant()
        );
        if (!beforeToken.validForDispatch()) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                mapRevalidation(beforeToken.status(), false)
            );
        }

        DingTalkTokenRequest tokenRequest;
        try {
            tokenRequest = Objects.requireNonNull(
                tokenRequestSource.create(trustedTenantId, plan),
                "tokenRequestSource returned null"
            );
            validateTokenRequest(trustedTenantId, plan, tokenRequest);
        } catch (DingTalkTokenLifecycleException problem) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                tokenFailure(problem)
            );
        } catch (RuntimeException problem) {
            return failure(
                state,
                startedAt,
                CompletionClassification.REJECTED_BEFORE_DISPATCH,
                StableFailureCode.TOKEN_REQUEST_INVALID
            );
        }
        state.tokenRequest = tokenRequest;

        try (DingTalkAccessTokenLease lease = tokenCoordinator.acquire(tokenRequest)) {
            state.tokenEvidence = lease.evidence();
            StableFailureCode afterTokenGate = evaluateKillSwitch(trustedTenantId, state);
            if (afterTokenGate != StableFailureCode.NONE) {
                return failure(
                    state,
                    startedAt,
                    CompletionClassification.REJECTED_BEFORE_DISPATCH,
                    afterTokenGate
                );
            }

            RouteRevalidation afterToken = routeRevalidator.revalidate(
                trustedTenantId,
                plan,
                clock.instant()
            );
            if (!afterToken.validForDispatch()) {
                return failure(
                    state,
                    startedAt,
                    CompletionClassification.REJECTED_BEFORE_DISPATCH,
                    mapRevalidation(afterToken.status(), true)
                );
            }
            if (!tokenEvidenceMatches(state)) {
                return failure(
                    state,
                    startedAt,
                    CompletionClassification.REJECTED_BEFORE_DISPATCH,
                    StableFailureCode.TOKEN_ROUTE_DRIFT
                );
            }

            DispatchRequest dispatchRequest;
            try {
                dispatchRequest = new DispatchRequest(
                    trustedTenantId,
                    plan,
                    request,
                    policy.timeout(),
                    policy.maximumResponseBytes()
                );
            } catch (RuntimeException problem) {
                return failure(
                    state,
                    startedAt,
                    CompletionClassification.REJECTED_BEFORE_DISPATCH,
                    StableFailureCode.INVALID_REQUEST
                );
            }

            AtomicReference<DispatchResponse> response = new AtomicReference<>();
            AtomicReference<RuntimeException> transportFailure = new AtomicReference<>();
            try {
                lease.use(accessToken -> {
                    state.dispatchCount.incrementAndGet();
                    try {
                        response.set(dispatchPort.dispatch(dispatchRequest, accessToken));
                    } catch (RuntimeException problem) {
                        transportFailure.set(problem);
                        throw problem;
                    }
                });
            } catch (RuntimeException problem) {
                return failure(
                    state,
                    startedAt,
                    CompletionClassification.UNKNOWN_AFTER_DISPATCH,
                    StableFailureCode.TRANSPORT_EXCEPTION
                );
            }
            if (transportFailure.get() != null) {
                return failure(
                    state,
                    startedAt,
                    CompletionClassification.UNKNOWN_AFTER_DISPATCH,
                    StableFailureCode.TRANSPORT_EXCEPTION
                );
            }
            return finishDispatch(state, startedAt, response.get());
        } catch (DingTalkTokenLifecycleException problem) {
            CompletionClassification completion = state.dispatchCount.get() == 0
                ? CompletionClassification.REJECTED_BEFORE_DISPATCH
                : CompletionClassification.UNKNOWN_AFTER_DISPATCH;
            StableFailureCode failure = state.dispatchCount.get() == 0
                ? tokenFailure(problem)
                : StableFailureCode.TRANSPORT_UNKNOWN;
            return failure(state, startedAt, completion, failure);
        } catch (RuntimeException problem) {
            CompletionClassification completion = state.dispatchCount.get() == 0
                ? CompletionClassification.REJECTED_BEFORE_DISPATCH
                : CompletionClassification.UNKNOWN_AFTER_DISPATCH;
            StableFailureCode failure = state.dispatchCount.get() == 0
                ? StableFailureCode.TOKEN_ACQUISITION_FAILED
                : StableFailureCode.TRANSPORT_UNKNOWN;
            return failure(state, startedAt, completion, failure);
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public String toString() {
        return "GovernedReadOnlyConnectorInvocationCoordinator[closed=" + closed.get()
            + ", policyVersion=" + policy.policyVersion() + ", secret=<redacted>]";
    }

    private InvocationResult finishDispatch(
        InvocationState state,
        Instant startedAt,
        DispatchResponse response
    ) {
        if (response == null) {
            return failure(
                state,
                startedAt,
                CompletionClassification.UNKNOWN_AFTER_DISPATCH,
                StableFailureCode.RESPONSE_INVALID
            );
        }
        if (response.responseBytes() > policy.maximumResponseBytes()) {
            return failure(
                state,
                startedAt,
                CompletionClassification.UNKNOWN_AFTER_DISPATCH,
                StableFailureCode.RESPONSE_TOO_LARGE
            );
        }
        if (response.state() == DispatchState.SUCCEEDED) {
            InvocationEvidence evidence = evidence(
                state,
                startedAt,
                CompletionClassification.SUCCEEDED,
                StableFailureCode.NONE
            );
            return InvocationResult.success(response.providerResult().orElseThrow(), evidence);
        }
        if (response.state() == DispatchState.PROVIDER_REJECTED) {
            return failure(
                state,
                startedAt,
                CompletionClassification.PROVIDER_REJECTED,
                StableFailureCode.PROVIDER_REJECTED
            );
        }
        StableFailureCode failure = response.state() == DispatchState.TIMEOUT
            ? StableFailureCode.TRANSPORT_TIMEOUT
            : StableFailureCode.TRANSPORT_UNKNOWN;
        return failure(
            state,
            startedAt,
            CompletionClassification.UNKNOWN_AFTER_DISPATCH,
            failure
        );
    }

    private StableFailureCode evaluateKillSwitch(
        String trustedTenantId,
        InvocationState state
    ) {
        try {
            DingTalkTokenKillSwitch.Decision decision = Objects.requireNonNull(
                killSwitch.evaluate(
                    trustedTenantId,
                    state.plan.planHash(),
                    policy.killSwitchRevision()
                ),
                "killSwitch returned null"
            );
            if (!policy.killSwitchRevision().equals(decision.revision())) {
                state.gateResult = GateResult.REVISION_DRIFT;
                return StableFailureCode.KILL_SWITCH_REVISION_DRIFT;
            }
            if (!decision.acquisitionAllowed()) {
                state.gateResult = GateResult.BLOCKED;
                return StableFailureCode.KILL_SWITCH_BLOCKED;
            }
            state.gateResult = GateResult.ALLOWED;
            return StableFailureCode.NONE;
        } catch (RuntimeException problem) {
            state.gateResult = GateResult.EVALUATION_FAILED;
            return StableFailureCode.KILL_SWITCH_UNAVAILABLE;
        }
    }

    private void validateTokenRequest(
        String trustedTenantId,
        RoutePlan plan,
        DingTalkTokenRequest request
    ) {
        if (!trustedTenantId.equals(request.trustedTenantId())
            || !plan.planHash().equals(request.routePlan().planHash())
            || !plan.routeDefinitionHash().equals(request.routePlan().routeDefinitionHash())
            || !plan.planHash().equals(request.applicationCredentialRequest().routePlanHash())) {
            throw new IllegalArgumentException("Token request does not match the resolved route");
        }
        if (!policy.killSwitchRevision().equals(request.killSwitchRevision())) {
            throw new IllegalArgumentException("Token request Kill Switch revision drifted");
        }
        if (!policy.tokenPolicyVersion().equals(request.tokenPolicyVersion())) {
            throw new DingTalkTokenLifecycleException(
                io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenFailure
                    .CREDENTIAL_POLICY_DRIFT
            );
        }
    }

    private static boolean tokenEvidenceMatches(InvocationState state) {
        DingTalkTokenEvidence evidence = state.tokenEvidence;
        DingTalkTokenRequest request = state.tokenRequest;
        return evidence != null
            && request != null
            && evidence.routePlanHash().equals(state.plan.planHash())
            && evidence.requestEvidenceHash().equals(request.evidenceHash())
            && evidence.credentialRequestHash().equals(
                request.applicationCredentialRequest().evidenceHash()
            );
    }

    private InvocationResult failure(
        InvocationState state,
        Instant startedAt,
        CompletionClassification completion,
        StableFailureCode failure
    ) {
        return InvocationResult.failure(evidence(state, startedAt, completion, failure));
    }

    private InvocationEvidence evidence(
        InvocationState state,
        Instant startedAt,
        CompletionClassification completion,
        StableFailureCode failure
    ) {
        RoutePlan plan = state.plan;
        DingTalkTokenRequest tokenRequest = state.tokenRequest;
        DingTalkTokenEvidence tokenEvidence = state.tokenEvidence;
        int count = state.dispatchCount.get();
        return InvocationEvidence.create(new EvidenceInput(
            state.tenantHash,
            state.request.requestHash(),
            plan == null ? null : plan.planHash(),
            plan == null ? null : plan.routeDefinitionHash(),
            plan == null ? null : plan.credentialReferenceHash(),
            tokenRequest == null
                ? null
                : tokenRequest.applicationCredentialRequest().credentialBindingHash(),
            tokenRequest == null
                ? null
                : tokenRequest.applicationCredentialRequest().expectedVersion().versionReference(),
            tokenRequest == null
                ? null
                : tokenRequest.applicationCredentialRequest().expectedVersion()
                    .versionEvidenceHash(),
            tokenEvidence == null ? null : tokenEvidence.evidenceHash(),
            tokenEvidence == null ? null : tokenEvidence.outcome(),
            plan == null ? null : plan.transportProfile(),
            plan == null ? null : plan.apiFamily(),
            state.request.intent().connectorOperation(),
            state.request.intent().providerOperation(),
            state.gateResult,
            count == 1,
            count,
            completion,
            DurationBucket.from(elapsed(startedAt)),
            failure
        ));
    }

    private Duration elapsed(Instant startedAt) {
        Instant completedAt = clock.instant();
        return completedAt.isBefore(startedAt)
            ? Duration.ZERO
            : Duration.between(startedAt, completedAt);
    }

    private static StableFailureCode mapResolution(ResolutionStatus status) {
        return switch (status) {
            case MISSING -> StableFailureCode.ROUTE_MISSING;
            case DISABLED -> StableFailureCode.ROUTE_DISABLED;
            case UNSUPPORTED -> StableFailureCode.UNSUPPORTED_OPERATION;
            case INCOMPATIBLE -> StableFailureCode.CREDENTIAL_REVALIDATION_FAILED;
            case RESOLVED -> StableFailureCode.NONE;
            default -> StableFailureCode.ROUTE_REJECTED;
        };
    }

    private static StableFailureCode mapRevalidation(
        RevalidationStatus status,
        boolean afterToken
    ) {
        if (status == RevalidationStatus.INCOMPATIBLE) {
            return StableFailureCode.CREDENTIAL_REVALIDATION_FAILED;
        }
        if (status == RevalidationStatus.DISABLED) {
            return StableFailureCode.ROUTE_DISABLED;
        }
        if (status == RevalidationStatus.UNSUPPORTED) {
            return StableFailureCode.UNSUPPORTED_OPERATION;
        }
        if (status == RevalidationStatus.STALE || status == RevalidationStatus.TENANT_MISMATCH) {
            return afterToken
                ? StableFailureCode.POST_TOKEN_ROUTE_DRIFT
                : StableFailureCode.ROUTE_STALE;
        }
        return afterToken
            ? StableFailureCode.POST_TOKEN_ROUTE_DRIFT
            : StableFailureCode.ROUTE_REJECTED;
    }

    private static StableFailureCode tokenFailure(DingTalkTokenLifecycleException problem) {
        return switch (problem.failure()) {
            case CREDENTIAL_POLICY_DRIFT -> StableFailureCode.TOKEN_POLICY_DRIFT;
            case ROUTE_PLAN_INVALID, ROUTE_REVALIDATION_FAILED, TENANT_MISMATCH, PROVIDER_MISMATCH ->
                StableFailureCode.TOKEN_ROUTE_DRIFT;
            default -> StableFailureCode.TOKEN_ACQUISITION_FAILED;
        };
    }

    private static final class InvocationState {
        private final String tenantHash;
        private final InvocationRequest request;
        private final AtomicInteger dispatchCount = new AtomicInteger();
        private GateResult gateResult = GateResult.NOT_EVALUATED;
        private RoutePlan plan;
        private DingTalkTokenRequest tokenRequest;
        private DingTalkTokenEvidence tokenEvidence;

        private InvocationState(String tenantHash, InvocationRequest request) {
            this.tenantHash = tenantHash;
            this.request = request;
        }
    }
}
