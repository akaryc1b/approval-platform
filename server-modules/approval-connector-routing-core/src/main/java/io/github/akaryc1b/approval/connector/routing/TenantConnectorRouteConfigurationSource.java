package io.github.akaryc1b.approval.connector.routing;

/**
 * Server-owned, read-only source for one immutable startup route snapshot.
 */
@FunctionalInterface
public interface TenantConnectorRouteConfigurationSource {

    TenantConnectorRouteSnapshot load();
}
