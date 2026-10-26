package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.security.LocalApprovalAuthorityProvider;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded SLA and design access for the successfully seeded disposable demo administrator. */
public final class PurchasePaymentDemoSlaAuthorities implements LocalApprovalAuthorityProvider {

    private static final String UUID_PATH =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern SLA_ROOT = Pattern.compile(
        "/api/approval/management/sla/(?:calendars|policies)"
    );
    private static final Pattern SLA_ID = Pattern.compile(SLA_ROOT.pattern() + "/" + UUID_PATH);
    private static final Pattern SLA_VERSION = Pattern.compile(SLA_ID.pattern() + "/versions/[1-9][0-9]*");
    private static final Pattern FORM_ROOT = Pattern.compile("/api/approval/form-design-drafts");
    private static final Pattern FORM_ID = Pattern.compile(FORM_ROOT.pattern() + "/" + UUID_PATH);
    private static final Pattern PROCESS_ROOT = Pattern.compile("/api/approval/process-design-drafts");
    private static final Pattern PROCESS_ID = Pattern.compile(PROCESS_ROOT.pattern() + "/" + UUID_PATH);

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
    public Set<String> authorities(String tenantId, String operatorId, String requestPath, String method) {
        if (!scenario.tenantId().equals(tenantId)
            || !scenario.administratorId().equals(operatorId)
            || requestPath == null || method == null
            || seedState.evidence().filter(evidence -> scenario.tenantId().equals(evidence.tenantId())
                && scenario.request().businessKey().equals(evidence.businessKey())).isEmpty()) {
            return Set.of();
        }
        if ("GET".equals(method) && matches(requestPath, SLA_ROOT, SLA_ID, SLA_VERSION)) {
            return Set.of("approval.management.sla.read");
        }
        if (("POST".equals(method) && SLA_ROOT.matcher(requestPath).matches())
            || ("PUT".equals(method) && SLA_VERSION.matcher(requestPath).matches())
            || action(requestPath, method, SLA_VERSION, "validate")) {
            return Set.of("approval.management.sla.design");
        }
        if (action(requestPath, method, SLA_VERSION, "publish")) {
            return Set.of("approval.management.sla.publish");
        }
        if (action(requestPath, method, SLA_VERSION, "activate")) {
            return Set.of("approval.management.sla.activate");
        }
        if ("GET".equals(method) && matches(requestPath, FORM_ROOT, FORM_ID, PROCESS_ROOT, PROCESS_ID)) {
            return Set.of("approval.management.read");
        }
        if ("GET".equals(method) && requestPath.matches(FORM_ID.pattern() + "/contexts")) {
            return Set.of("approval.management.read");
        }
        if (action(requestPath, method, FORM_ID, "preview")) {
            return Set.of("approval.management.read");
        }
        if (("POST".equals(method) && matches(requestPath, FORM_ROOT, PROCESS_ROOT))
            || ("PUT".equals(method) && matches(requestPath, FORM_ID, PROCESS_ID))
            || action(requestPath, method, FORM_ID, "validate")
            || action(requestPath, method, PROCESS_ID, "validate")
            || action(requestPath, method, PROCESS_ID, "simulate")) {
            return Set.of("approval.management.design");
        }
        if (action(requestPath, method, FORM_ID, "publish")
            || action(requestPath, method, PROCESS_ID, "publish")
            || ("POST".equals(method) && "/api/approval/preflight/publication".equals(requestPath))) {
            return Set.of("approval.management.publish");
        }
        return Set.of();
    }

    private static boolean matches(String path, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(path).matches()) return true;
        }
        return false;
    }

    private static boolean action(String path, String method, Pattern base, String action) {
        return "POST".equals(method) && path.matches(base.pattern() + "/" + action);
    }
}
