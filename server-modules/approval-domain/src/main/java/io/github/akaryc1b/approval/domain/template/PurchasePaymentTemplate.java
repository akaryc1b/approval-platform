package io.github.akaryc1b.approval.domain.template;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;

import java.math.BigDecimal;
import java.util.List;

/**
 * First production-shaped vertical-slice definition used by server and client fixtures.
 */
public final class PurchasePaymentTemplate {

    public static final String DEFINITION_KEY = "purchase-payment";
    public static final int PROCESS_VERSION = 2;
    public static final int FORM_VERSION = 1;
    public static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("10000.00");

    public static final String INITIATOR_ASSIGNEE_VARIABLE = "initiatorAssignee";
    public static final String MANAGER_ASSIGNEE_VARIABLE = "managerAssignee";
    public static final String FINANCE_REVIEWER_VARIABLE = "financeReviewer";
    public static final String FINANCE_APPROVERS_VARIABLE = "financeApprovers";
    public static final String REVISION_TASK_KEY = "initiatorRevision";

    private PurchasePaymentTemplate() {
    }

    public static ApprovalDefinition processDefinition() {
        return new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            DEFINITION_KEY,
            PROCESS_VERSION,
            "Purchase payment approval",
            "start",
            List.of(
                new ApprovalDefinition.StartNode(
                    "start",
                    "Start",
                    "managerApproval"
                ),
                new ApprovalDefinition.ApprovalStep(
                    "managerApproval",
                    "Initiator manager approval",
                    new ApprovalDefinition.AssigneeRule(
                        ApprovalDefinition.AssigneeResolver.INITIATOR_MANAGER,
                        MANAGER_ASSIGNEE_VARIABLE,
                        ApprovalDefinition.EmptyAssigneePolicy.FAIL
                    ),
                    ApprovalDefinition.ApprovalMode.single(),
                    "amountCondition",
                    REVISION_TASK_KEY
                ),
                new ApprovalDefinition.ConditionStep(
                    "amountCondition",
                    "High-value finance review decision",
                    List.of(new ApprovalDefinition.ConditionRoute(
                        new ApprovalDefinition.ComparisonCondition(
                            "amount",
                            ApprovalDefinition.ComparisonOperator.GREATER_THAN_OR_EQUAL,
                            HIGH_VALUE_THRESHOLD
                        ),
                        "financeReview"
                    )),
                    "financeCountersign"
                ),
                new ApprovalDefinition.ApprovalStep(
                    "financeReview",
                    "High-value finance approval",
                    new ApprovalDefinition.AssigneeRule(
                        ApprovalDefinition.AssigneeResolver.VARIABLE_USER,
                        FINANCE_REVIEWER_VARIABLE,
                        ApprovalDefinition.EmptyAssigneePolicy.FAIL
                    ),
                    ApprovalDefinition.ApprovalMode.single(),
                    "financeCountersign",
                    REVISION_TASK_KEY
                ),
                new ApprovalDefinition.ApprovalStep(
                    "financeCountersign",
                    "Parallel finance countersign",
                    new ApprovalDefinition.AssigneeRule(
                        ApprovalDefinition.AssigneeResolver.VARIABLE_USER_LIST,
                        FINANCE_APPROVERS_VARIABLE,
                        ApprovalDefinition.EmptyAssigneePolicy.FAIL
                    ),
                    ApprovalDefinition.ApprovalMode.all(),
                    "end",
                    REVISION_TASK_KEY
                ),
                new ApprovalDefinition.HandleStep(
                    REVISION_TASK_KEY,
                    "Initiator revises rejected request",
                    new ApprovalDefinition.AssigneeRule(
                        ApprovalDefinition.AssigneeResolver.VARIABLE_USER,
                        INITIATOR_ASSIGNEE_VARIABLE,
                        ApprovalDefinition.EmptyAssigneePolicy.FAIL
                    ),
                    "managerApproval"
                ),
                new ApprovalDefinition.EndNode("end", "Completed")
            )
        );
    }

    public static FormDefinition formDefinition() {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            DEFINITION_KEY,
            FORM_VERSION,
            "Purchase payment request",
            List.of(
                new FormDefinition.FormField(
                    "amount",
                    FormDefinition.FieldType.MONEY,
                    "Payment amount",
                    true,
                    FormDefinition.FieldConstraints.money(2, new BigDecimal("0.01"))
                ),
                new FormDefinition.FormField(
                    "supplier",
                    FormDefinition.FieldType.TEXT,
                    "Supplier",
                    true,
                    FormDefinition.FieldConstraints.text(200)
                ),
                new FormDefinition.FormField(
                    "purchaseOrderReference",
                    FormDefinition.FieldType.TEXT,
                    "Purchase order reference",
                    true,
                    FormDefinition.FieldConstraints.text(100)
                ),
                new FormDefinition.FormField(
                    "attachments",
                    FormDefinition.FieldType.ATTACHMENT,
                    "Payment documents",
                    true,
                    FormDefinition.FieldConstraints.attachments(1, true)
                )
            )
        );
    }
}
