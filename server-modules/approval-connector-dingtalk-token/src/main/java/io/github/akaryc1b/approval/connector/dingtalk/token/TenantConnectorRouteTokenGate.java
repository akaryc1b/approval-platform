package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteRevalidator;

import java.time.Instant;
import java.util.Objects;

public final class TenantConnectorRouteTokenGate implements DingTalkTokenRouteGate {

    private final TenantConnectorRouteRevalidator revalidator;

    public TenantConnectorRouteTokenGate(TenantConnectorRouteRevalidator revalidator) {
        this.revalidator = Objects.requireNonNull(revalidator, "revalidator must not be null");
    }

    @Override
    public Result revalidate(String trustedTenantId, RoutePlan routePlan, Instant evaluatedAt) {
        var result = revalidator.revalidate(trustedTenantId, routePlan, evaluatedAt);
        return new Result(
            result.validForDispatch(),
            result.status().name().toLowerCase(java.util.Locale.ROOT),
            result.evidenceHash()
        );
    }
}
