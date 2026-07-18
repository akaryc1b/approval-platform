package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver.AssigneeRules;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps the purchase-payment Form Schema to the existing M1 workflow command. */
public final class PurchasePaymentFormSubmissionStarter implements FormSubmissionWorkflowStarter {

    private final PurchasePaymentApplicationService service;

    public PurchasePaymentFormSubmissionStarter(PurchasePaymentApplicationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @Override
    public WorkflowStartResult start(
        RequestContext context,
        String formKey,
        int formVersion,
        String businessKey,
        Map<String, Object> values,
        Map<String, Object> startParameters
    ) {
        if (!PurchasePaymentTemplate.DEFINITION_KEY.equals(formKey)) {
            throw new UnsupportedFormSubmissionException(
                "no workflow starter is registered for form " + formKey
            );
        }
        if (formVersion != PurchasePaymentTemplate.FORM_VERSION) {
            throw new UnsupportedFormSubmissionException(
                "purchase-payment workflow is bound to form version "
                    + PurchasePaymentTemplate.FORM_VERSION
            );
        }
        AssigneeRules rules = rules(startParameters, context.operatorId());
        PurchasePaymentApplicationService.StartResult result = service.start(
            new PurchasePaymentApplicationService.StartCommand(
                context,
                businessKey,
                requireMoney(values, "amount"),
                requireText(values, "supplier"),
                requireText(values, "purchaseOrderReference"),
                requireStrings(values, "attachments"),
                rules
            )
        );
        return new WorkflowStartResult(
            result.instanceId(),
            result.status().name(),
            result.startedAt()
        );
    }

    private static AssigneeRules rules(Map<String, Object> parameters, String operatorId) {
        Map<String, Object> source = parameters == null ? Map.of() : parameters;
        String connectorKey = requireText(source, "connectorKey");
        Map<String, Object> initiator = requireMap(source, "initiatorUserId");
        ExternalId externalId = new ExternalId(
            optionalText(initiator, "source", connectorKey),
            optionalText(initiator, "objectType", "USER"),
            optionalText(initiator, "value", operatorId)
        );
        int maximum = integer(source.get("maximumFinanceApprovers"), 20);
        if (maximum < 1 || maximum > 100) {
            throw new IllegalArgumentException("maximumFinanceApprovers must be between 1 and 100");
        }
        return new AssigneeRules(
            connectorKey,
            externalId,
            requireText(source, "financeReviewerRoleCode"),
            requireText(source, "financeApproverPositionCode"),
            maximum
        );
    }

    private static BigDecimal requireMoney(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof BigDecimal decimal) return decimal;
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return new BigDecimal(value.toString());
    }

    private static String requireText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return text.trim();
    }

    private static String optionalText(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }

    private static List<String> requireStrings(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
        return items.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("maximumFinanceApprovers must be an integer");
        }
    }

    public static final class UnsupportedFormSubmissionException extends RuntimeException {
        public UnsupportedFormSubmissionException(String message) { super(message); }
    }
}
