package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.credential.CapturedCredentialBindingPlan;

/**
 * Supplies one exact, secret-free credential binding plan for a trusted DingTalk invocation.
 */
@FunctionalInterface
public interface DingTalkCredentialPlanSource {

    CapturedCredentialBindingPlan planFor(
        TrustedConnectorExecutionContext context,
        ConnectorOperation operation
    );
}
