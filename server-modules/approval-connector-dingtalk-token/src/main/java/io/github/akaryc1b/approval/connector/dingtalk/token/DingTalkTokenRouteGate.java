package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;

import java.time.Instant;

@FunctionalInterface
public interface DingTalkTokenRouteGate {

    Result revalidate(String trustedTenantId, RoutePlan routePlan, Instant evaluatedAt);

    record Result(boolean valid, String statusCode, String evidenceHash) {

        public Result {
            statusCode = DingTalkTokenSupport.stableCode(statusCode, "statusCode");
            evidenceHash = DingTalkTokenSupport.sha256(evidenceHash, "evidenceHash");
        }
    }
}
