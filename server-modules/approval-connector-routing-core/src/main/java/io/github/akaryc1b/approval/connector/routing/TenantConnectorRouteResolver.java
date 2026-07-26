package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ResolutionStatus;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteEvidence;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRequest;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteResolution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact tenant-local route resolution. It never opens credential material or invokes transport.
 */
public final class TenantConnectorRouteResolver {

    private final TenantConnectorRouteConfigurationSource configurationSource;
    private final CredentialBindingCatalog credentialCatalog;
    private final RouteEvidenceFactory evidenceFactory;

    public TenantConnectorRouteResolver(
        TenantConnectorRouteConfigurationSource configurationSource,
        CredentialBindingCatalog credentialCatalog
    ) {
        this(configurationSource, credentialCatalog, RouteEvidence::create);
    }

    TenantConnectorRouteResolver(
        TenantConnectorRouteConfigurationSource configurationSource,
        CredentialBindingCatalog credentialCatalog,
        RouteEvidenceFactory evidenceFactory
    ) {
        this.configurationSource = Objects.requireNonNull(
            configurationSource,
            "configurationSource must not be null"
        );
        this.credentialCatalog = Objects.requireNonNull(
            credentialCatalog,
            "credentialCatalog must not be null"
        );
        this.evidenceFactory = Objects.requireNonNull(
            evidenceFactory,
            "evidenceFactory must not be null"
        );
    }

    public RouteResolution resolve(
        String trustedTenantId,
        RouteRequest request,
        Instant evaluatedAt
    ) {
        String tenantHash = TenantConnectorRouteContracts.tenantEvidenceHash(trustedTenantId);
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        String requestHash = request.evidenceHash();

        TenantConnectorRouteSnapshot snapshot;
        try {
            snapshot = configurationSource.load();
        } catch (RuntimeException exception) {
            return finish(
                ResolutionStatus.SOURCE_UNAVAILABLE,
                tenantHash,
                requestHash,
                null,
                null,
                null
            );
        }
        if (snapshot == null) {
            return finish(
                ResolutionStatus.SOURCE_UNAVAILABLE,
                tenantHash,
                requestHash,
                null,
                null,
                null
            );
        }
        if (!snapshot.integrityValid()) {
            return finish(
                ResolutionStatus.INVALID_CONFIGURATION,
                tenantHash,
                requestHash,
                snapshot.snapshotHash(),
                null,
                null
            );
        }

        List<RouteDefinition> candidates = snapshot.exactCandidates(
            trustedTenantId,
            request.capability(),
            request.intent()
        );
        if (candidates.isEmpty()) {
            return finish(
                ResolutionStatus.MISSING,
                tenantHash,
                requestHash,
                snapshot.snapshotHash(),
                null,
                null
            );
        }
        if (candidates.size() != 1) {
            return finish(
                ResolutionStatus.AMBIGUOUS,
                tenantHash,
                requestHash,
                snapshot.snapshotHash(),
                null,
                null
            );
        }

        RouteDefinition route = candidates.getFirst();
        ResolutionStatus routeStatus = routeStatus(route, evaluatedAt);
        if (routeStatus != ResolutionStatus.RESOLVED) {
            return finish(
                routeStatus,
                tenantHash,
                requestHash,
                snapshot.snapshotHash(),
                route.definitionHash(),
                null
            );
        }

        CredentialBindingDescriptor descriptor;
        try {
            Optional<CredentialBindingDescriptor> found = credentialCatalog.find(
                route.credentialReference()
            );
            descriptor = found.orElse(null);
        } catch (RuntimeException exception) {
            descriptor = null;
        }
        if (!credentialCompatible(route, descriptor, evaluatedAt)) {
            return finish(
                ResolutionStatus.INCOMPATIBLE,
                tenantHash,
                requestHash,
                snapshot.snapshotHash(),
                route.definitionHash(),
                null
            );
        }

        RoutePlan plan = RoutePlan.create(
            trustedTenantId,
            route,
            snapshot.snapshotHash(),
            request,
            descriptor.referenceHash(),
            evaluatedAt
        );
        return finish(
            ResolutionStatus.RESOLVED,
            tenantHash,
            requestHash,
            snapshot.snapshotHash(),
            route.definitionHash(),
            plan
        );
    }

    static ResolutionStatus routeStatus(RouteDefinition route, Instant evaluatedAt) {
        if (!route.hashMatches()) {
            return ResolutionStatus.INVALID_CONFIGURATION;
        }
        if (!route.enabled()) {
            return ResolutionStatus.DISABLED;
        }
        if (route.notYetValidAt(evaluatedAt)) {
            return ResolutionStatus.NOT_YET_VALID;
        }
        if (route.expiredAt(evaluatedAt)) {
            return ResolutionStatus.EXPIRED;
        }
        if (!route.supportedByP4()) {
            return ResolutionStatus.UNSUPPORTED;
        }
        return ResolutionStatus.RESOLVED;
    }

    static boolean credentialCompatible(
        RouteDefinition route,
        CredentialBindingDescriptor descriptor,
        Instant evaluatedAt
    ) {
        if (descriptor == null
            || descriptor.state() != CredentialBindingState.ACTIVE
            || !route.tenantId().equals(descriptor.tenantId())
            || !route.providerKey().equals(descriptor.providerKey())
            || !route.credentialReference().equals(descriptor.reference())
            || route.credentialMaterialType() != descriptor.credentialType()
            || !descriptor.allowedOperations().contains(route.intent().connectorOperation())
            || !route.credentialPolicyVersion().equals(descriptor.policyVersion())
            || !route.credentialDescriptorFingerprint().equals(descriptor.fingerprint())) {
            return false;
        }
        return (descriptor.notBefore() == null || !evaluatedAt.isBefore(descriptor.notBefore()))
            && (descriptor.expiresAt() == null || evaluatedAt.isBefore(descriptor.expiresAt()));
    }

    private RouteResolution finish(
        ResolutionStatus status,
        String tenantHash,
        String requestHash,
        String snapshotHash,
        String definitionHash,
        RoutePlan plan
    ) {
        try {
            RouteEvidence evidence = evidenceFactory.create(
                status,
                tenantHash,
                requestHash,
                snapshotHash,
                definitionHash,
                plan == null ? null : plan.planHash()
            );
            return new RouteResolution(status, Optional.ofNullable(plan), evidence);
        } catch (RuntimeException exception) {
            RouteEvidence fallback = RouteEvidence.create(
                ResolutionStatus.INVALID_CONFIGURATION,
                tenantHash,
                requestHash,
                snapshotHash,
                null,
                null
            );
            return new RouteResolution(
                ResolutionStatus.INVALID_CONFIGURATION,
                Optional.empty(),
                fallback
            );
        }
    }

    @FunctionalInterface
    interface RouteEvidenceFactory {
        RouteEvidence create(
            ResolutionStatus status,
            String tenantEvidenceHash,
            String requestEvidenceHash,
            String configurationSnapshotHash,
            String routeDefinitionHash,
            String planHash
        );
    }
}
