package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.security.LocalApprovalAuthorityProvider;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded SLA configuration access for the successfully seeded disposable demo administrator. */
public final class PurchasePaymentDemoSlaAuthorities implements LocalApprovalAuthorityProvider {

    private static final String UUID_PATH =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern SLA_PATH = Pattern.compile(
        "/api/approval/management/sla/(?:calendars|policies)(?:/" + UUID_PATH
            + "(?:/versions/[1-9][0-9]*(?:/(?:validate|publish|activate))?)?)?"
    );
    private static final Set<String> AUTHORITIES = Set.of(
        "approval.management.sla.read",
        "approval.management.sla.design",
        "approval.management.sla.publish",
        "approval.management.sla.activate"
    );

    private final PurchasePaymentDemoScenario scenario;
    private final PurchasePaymentDemoSeedState seedState;

    public PurchasePaymentDemoSlaAuthorities(
        PurchasePaymentDemoScenario scenario,
        PurchasePaymentDemoSeedState seedState
    ) {
        this.scenario = Objects.requireNonNull(scenario);
        this.seedState = Objects.requireNonNull(seedState);
    }

    @Override
    public Set<String> authorities(String tenantId, String operatorId, String requestPath) {
        if (!scenario.tenantId().equals(tenantId)
            || !scenario.administratorId().equals(operatorId)
            || requestPath == null || !SLA_PATH.matcher(requestPath).matches()) {
            return Set.of();
        }
        return seedState.evidence()
            .filter(evidence -> scenario.tenantId().equals(evidence.tenantId())
                && scenario.request().businessKey().equals(evidence.businessKey()))
            .map(evidence -> AUTHORITIES)
            .orElseGet(Set::of);
    }

}
