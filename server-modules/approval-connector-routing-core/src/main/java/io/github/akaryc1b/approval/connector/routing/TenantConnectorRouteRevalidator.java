package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ResolutionStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RevalidationStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRevalidation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Revalidates one immutable plan without selecting a replacement or dispatching transport.
 */
public final class TenantConnectorRouteRevalidator {

    private final TenantConnectorRouteConfigurationSource configurationSource;
    private final CredentialBindingCatalog credentialCatalog;

    public TenantConnectorRouteRevalidator(
        TenantConnectorRouteConfigurationSource configurationSource,
        CredentialBindingCatalog credentialCatalog
    ) {
        this.configurationSource = Objects.requireNonNull(
            configurationSource,
            "configurationSource must not be null"
        );
        this.credentialCatalog = Objects.requireNonNull(
            credentialCatalog,
            "credentialCatalog must not be null"
        );
    }

    public RouteRevalidation revalidate(
        String trustedTenantId,
        RoutePlan plan,
        Instant evaluatedAt
    ) {
        String tenantHash = TenantConnectorRouteContracts.tenantEvidenceHash(trustedTenantId);
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");

        if (!plan.hashMatches()) {
            return result(RevalidationStatus.INVALID_PLAN, tenantHash, plan, null);
        }
        if (!tenantHash.equals(plan.tenantEvidenceHash())) {
            return result(RevalidationStatus.TENANT_MISMATCH, tenantHash, plan, null);
        }

        TenantConnectorRouteSnapshot snapshot;
        try {
            snapshot = configurationSource.load();
        } catch (RuntimeException exception) {
            return result(RevalidationStatus.SOURCE_UNAVAILABLE, tenantHash, plan, null);
        }
        if (snapshot == null) {
            return result(RevalidationStatus.SOURCE_UNAVAILABLE, tenantHash, plan, null);
        }
        if (!snapshot.integrityValid()) {
            return result(
                RevalidationStatus.INVALID_CONFIGURATION,
                tenantHash,
                plan,
                snapshot.snapshotHash()
            );
        }
        if (!snapshot.snapshotHash().equals(plan.configurationSnapshotHash())) {
            return result(RevalidationStatus.STALE, tenantHash, plan, snapshot.snapshotHash());
        }

        List<RouteDefinition> candidates = snapshot.exactCandidates(
            trustedTenantId,
            plan.capability(),
            plan.intent()
        );
        if (candidates.size() != 1) {
            return result(
                RevalidationStatus.INVALID_CONFIGURATION,
                tenantHash,
                plan,
                snapshot.snapshotHash()
            );
        }

        RouteDefinition route = candidates.getFirst();
        ResolutionStatus routeStatus = TenantConnectorRouteResolver.routeStatus(route, evaluatedAt);
        RevalidationStatus mapped = map(routeStatus);
        if (mapped != RevalidationStatus.VALID) {
            return result(mapped, tenantHash, plan, snapshot.snapshotHash());
        }
        if (!planMatchesRoute(plan, route)) {
            return result(
                RevalidationStatus.INCOMPATIBLE,
                tenantHash,
                plan,
                snapshot.snapshotHash()
            );
        }

        CredentialBindingDescriptor descriptor;
        try {
            descriptor = credentialCatalog.find(route.credentialReference()).orElse(null);
        } catch (RuntimeException exception) {
            descriptor = null;
        }
        if (!TenantConnectorRouteResolver.credentialCompatible(route, descriptor, evaluatedAt)
            || descriptor == null
            || !descriptor.referenceHash().equals(plan.credentialReferenceHash())) {
            return result(
                RevalidationStatus.INCOMPATIBLE,
                tenantHash,
                plan,
                snapshot.snapshotHash()
            );
        }
        return result(RevalidationStatus.VALID, tenantHash, plan, snapshot.snapshotHash());
    }

    private static boolean planMatchesRoute(RoutePlan plan, RouteDefinition route) {
        return plan.providerKey().equals(route.providerKey())
            && plan.capability() == route.capability()
            && plan.intent() == route.intent()
            && plan.connectorOperation() == route.intent().connectorOperation()
            && plan.apiFamily() == route.apiFamily()
            && plan.transportProfile() == route.transportProfile()
            && plan.credentialMaterialType() == route.credentialMaterialType()
            && plan.routeVersion().equals(route.routeVersion())
            && plan.routePolicyVersion().equals(route.routePolicyVersion())
            && plan.credentialPolicyVersion().equals(route.credentialPolicyVersion())
            && plan.routeDefinitionHash().equals(route.definitionHash())
            && plan.credentialDescriptorFingerprint().equals(
                route.credentialDescriptorFingerprint()
            );
    }

    private static RevalidationStatus map(ResolutionStatus status) {
        return switch (status) {
            case RESOLVED -> RevalidationStatus.VALID;
            case DISABLED -> RevalidationStatus.DISABLED;
            case EXPIRED -> RevalidationStatus.EXPIRED;
            case NOT_YET_VALID -> RevalidationStatus.NOT_YET_VALID;
            case UNSUPPORTED -> RevalidationStatus.UNSUPPORTED;
            case INVALID_CONFIGURATION, AMBIGUOUS, MISSING ->
                RevalidationStatus.INVALID_CONFIGURATION;
            case INCOMPATIBLE -> RevalidationStatus.INCOMPATIBLE;
            case SOURCE_UNAVAILABLE -> RevalidationStatus.SOURCE_UNAVAILABLE;
        };
    }

    private static RouteRevalidation result(
        RevalidationStatus status,
        String tenantHash,
        RoutePlan plan,
        String snapshotHash
    ) {
        return RouteRevalidation.create(status, tenantHash, plan.planHash(), snapshotHash);
    }
}
