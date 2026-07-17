package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.util.Objects;

/**
 * Resolves organization rules before an engine instance is created.
 */
public interface PurchasePaymentAssigneeResolver {

    AssigneeSnapshot resolve(RequestContext context, AssigneeRules rules);

    record AssigneeRules(
        String connectorKey,
        ExternalId initiatorUserId,
        String financeReviewerRoleCode,
        String financeApproverPositionCode,
        int maximumFinanceApprovers
    ) {
        public AssigneeRules {
            connectorKey = requireText(connectorKey, "connectorKey");
            initiatorUserId = Objects.requireNonNull(
                initiatorUserId,
                "initiatorUserId must not be null"
            );
            financeReviewerRoleCode = requireText(
                financeReviewerRoleCode,
                "financeReviewerRoleCode"
            );
            financeApproverPositionCode = requireText(
                financeApproverPositionCode,
                "financeApproverPositionCode"
            );
            if (maximumFinanceApprovers < 1 || maximumFinanceApprovers > 100) {
                throw new IllegalArgumentException(
                    "maximumFinanceApprovers must be between 1 and 100"
                );
            }
        }
    }

    final class AssigneeResolutionException extends RuntimeException {

        private final String code;

        public AssigneeResolutionException(String code, String message) {
            super(requireText(message, "message"));
            this.code = requireText(code, "code");
        }

        public String code() {
            return code;
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
