package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRequest;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteResolution;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteRevalidation;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteResolver;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteRevalidator;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.time.Instant;
import java.util.Objects;

/**
 * Platform-application owner for trusted tenant route resolution and revalidation.
 */
public final class TenantConnectorRouteResolutionService {

    private final TenantConnectorRouteResolver resolver;
    private final TenantConnectorRouteRevalidator revalidator;

    public TenantConnectorRouteResolutionService(
        TenantConnectorRouteResolver resolver,
        TenantConnectorRouteRevalidator revalidator
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.revalidator = Objects.requireNonNull(revalidator, "revalidator must not be null");
    }

    public RouteResolution resolve(
        RequestContext trustedContext,
        RouteRequest request,
        Instant logicalTime
    ) {
        Objects.requireNonNull(trustedContext, "trustedContext must not be null");
        return resolver.resolve(trustedContext.tenantId(), request, logicalTime);
    }

    public RouteRevalidation revalidate(
        RequestContext trustedContext,
        RoutePlan plan,
        Instant logicalTime
    ) {
        Objects.requireNonNull(trustedContext, "trustedContext must not be null");
        return revalidator.revalidate(trustedContext.tenantId(), plan, logicalTime);
    }
}
