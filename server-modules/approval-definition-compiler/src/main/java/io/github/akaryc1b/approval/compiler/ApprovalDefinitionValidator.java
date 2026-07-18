package io.github.akaryc1b.approval.compiler;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Performs engine-independent graph, identifier and schema compatibility validation.
 */
public final class ApprovalDefinitionValidator {

    private static final Pattern NODE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern VARIABLE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    public ValidationReport validate(
        ApprovalDefinition definition,
        FormDefinition formDefinition
    ) {
        List<ValidationIssue> issues = new ArrayList<>();
        validateSchemas(definition, formDefinition, issues);

        Map<String, ApprovalDefinition.ProcessNode> nodes = new LinkedHashMap<>();
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (!NODE_IDENTIFIER.matcher(node.id()).matches()) {
                issues.add(issue("INVALID_NODE_ID", node.id(), "node ID is not engine-safe"));
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                issues.add(issue("DUPLICATE_NODE_ID", node.id(), "node ID must be unique"));
            }
        }

        ApprovalDefinition.ProcessNode start = nodes.get(definition.startNodeId());
        if (!(start instanceof ApprovalDefinition.StartNode)) {
            issues.add(issue(
                "INVALID_START_NODE",
                definition.startNodeId(),
                "startNodeId must reference a START node"
            ));
        }

        Map<String, FormDefinition.FormField> fields = new HashMap<>();
        for (FormDefinition.FormField field : formDefinition.fields()) {
            if (!VARIABLE_IDENTIFIER.matcher(field.key()).matches()) {
                issues.add(issue(
                    "INVALID_FIELD_KEY",
                    field.key(),
                    "field key must be a safe variable identifier"
                ));
            }
            if (fields.putIfAbsent(field.key(), field) != null) {
                issues.add(issue("DUPLICATE_FIELD_KEY", field.key(), "field key must be unique"));
            }
        }

        int endNodes = 0;
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (node instanceof ApprovalDefinition.StartNode startNode) {
                requireNode(nodes, startNode.id(), startNode.next(), issues);
            } else if (node instanceof ApprovalDefinition.ApprovalStep approvalStep) {
                requireNode(nodes, approvalStep.id(), approvalStep.next(), issues);
                if (approvalStep.rejectNext() != null) {
                    requireNode(nodes, approvalStep.id(), approvalStep.rejectNext(), issues);
                }
                validateApproval(approvalStep, issues);
            } else if (node instanceof ApprovalDefinition.HandleStep handleStep) {
                requireNode(nodes, handleStep.id(), handleStep.next(), issues);
                validateHandle(handleStep, issues);
            } else if (node instanceof ApprovalDefinition.ConditionStep conditionStep) {
                requireNode(nodes, conditionStep.id(), conditionStep.defaultNext(), issues);
                for (ApprovalDefinition.ConditionRoute route : conditionStep.routes()) {
                    requireNode(nodes, conditionStep.id(), route.next(), issues);
                    validateCondition(conditionStep.id(), route.condition(), fields, issues);
                }
            } else if (node instanceof ApprovalDefinition.EndNode) {
                endNodes++;
            }
        }
        if (endNodes == 0) {
            issues.add(issue("MISSING_END_NODE", definition.definitionKey(), "an END node is required"));
        }

        if (start instanceof ApprovalDefinition.StartNode) {
            validateReachability(definition.startNodeId(), nodes, issues);
            validateCycles(definition.startNodeId(), nodes, issues);
        }
        return new ValidationReport(issues);
    }

    public void validateOrThrow(
        ApprovalDefinition definition,
        FormDefinition formDefinition
    ) {
        ValidationReport report = validate(definition, formDefinition);
        if (!report.valid()) {
            throw new DefinitionValidationException(report);
        }
    }

    private static void validateSchemas(
        ApprovalDefinition definition,
        FormDefinition formDefinition,
        List<ValidationIssue> issues
    ) {
        if (!ApprovalDefinition.CURRENT_SCHEMA_VERSION.equals(definition.schemaVersion())) {
            issues.add(issue(
                "UNSUPPORTED_PROCESS_SCHEMA",
                definition.schemaVersion(),
                "unsupported Approval DSL schema version"
            ));
        }
        if (!FormDefinition.CURRENT_SCHEMA_VERSION.equals(formDefinition.schemaVersion())) {
            issues.add(issue(
                "UNSUPPORTED_FORM_SCHEMA",
                formDefinition.schemaVersion(),
                "unsupported Form Schema version"
            ));
        }
        if (!definition.definitionKey().equals(formDefinition.formKey())) {
            issues.add(issue(
                "FORM_PROCESS_KEY_MISMATCH",
                formDefinition.formKey(),
                "formKey must equal definitionKey"
            ));
        }
    }

    private static void validateApproval(
        ApprovalDefinition.ApprovalStep approval,
        List<ValidationIssue> issues
    ) {
        validateAssigneeVariable(approval.id(), approval.assignee(), issues);
        boolean collection = approval.assignee().resolver()
            == ApprovalDefinition.AssigneeResolver.VARIABLE_USER_LIST;
        if (collection && approval.mode().type() == ApprovalDefinition.ApprovalModeType.SINGLE) {
            issues.add(issue(
                "INVALID_APPROVAL_MODE",
                approval.id(),
                "a user-list resolver requires ALL or ANY mode"
            ));
        }
        if (!collection && approval.mode().type() != ApprovalDefinition.ApprovalModeType.SINGLE) {
            issues.add(issue(
                "INVALID_APPROVAL_MODE",
                approval.id(),
                "a single-user resolver requires SINGLE mode"
            ));
        }
    }

    private static void validateHandle(
        ApprovalDefinition.HandleStep handle,
        List<ValidationIssue> issues
    ) {
        validateAssigneeVariable(handle.id(), handle.assignee(), issues);
        if (handle.assignee().resolver()
            == ApprovalDefinition.AssigneeResolver.VARIABLE_USER_LIST) {
            issues.add(issue(
                "INVALID_HANDLE_ASSIGNEE",
                handle.id(),
                "a handle step requires a single-user assignee"
            ));
        }
    }

    private static void validateAssigneeVariable(
        String nodeId,
        ApprovalDefinition.AssigneeRule assignee,
        List<ValidationIssue> issues
    ) {
        if (!VARIABLE_IDENTIFIER.matcher(assignee.variable()).matches()) {
            issues.add(issue(
                "INVALID_ASSIGNEE_VARIABLE",
                nodeId,
                "assignee variable must be a safe identifier"
            ));
        }
        if (assignee.emptyPolicy() != ApprovalDefinition.EmptyAssigneePolicy.FAIL) {
            issues.add(issue(
                "UNSUPPORTED_EMPTY_POLICY",
                nodeId,
                "the first compiler version supports FAIL only"
            ));
        }
    }

    private static void validateCondition(
        String nodeId,
        ApprovalDefinition.ComparisonCondition condition,
        Map<String, FormDefinition.FormField> fields,
        List<ValidationIssue> issues
    ) {
        if (!VARIABLE_IDENTIFIER.matcher(condition.field()).matches()) {
            issues.add(issue(
                "INVALID_CONDITION_FIELD",
                nodeId,
                "condition field must be a safe variable identifier"
            ));
            return;
        }
        FormDefinition.FormField field = fields.get(condition.field());
        if (field == null) {
            issues.add(issue(
                "UNKNOWN_CONDITION_FIELD",
                nodeId,
                "condition field is not present in the Form Schema"
            ));
        } else if (field.type() != FormDefinition.FieldType.MONEY) {
            issues.add(issue(
                "NON_NUMERIC_CONDITION_FIELD",
                nodeId,
                "comparison conditions currently require a MONEY field"
            ));
        }
    }

    private static void requireNode(
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        String source,
        String target,
        List<ValidationIssue> issues
    ) {
        if (!nodes.containsKey(target)) {
            issues.add(issue(
                "UNKNOWN_NODE_REFERENCE",
                source,
                "outgoing reference points to unknown node " + target
            ));
        }
    }

    private static void validateReachability(
        String startNodeId,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        List<ValidationIssue> issues
    ) {
        Set<String> visited = new HashSet<>();
        collectReachable(startNodeId, nodes, visited);
        for (String nodeId : nodes.keySet()) {
            if (!visited.contains(nodeId)) {
                issues.add(issue("UNREACHABLE_NODE", nodeId, "node is not reachable from start"));
            }
        }
    }

    private static void collectReachable(
        String nodeId,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Set<String> visited
    ) {
        if (!visited.add(nodeId) || !nodes.containsKey(nodeId)) {
            return;
        }
        for (String target : outgoing(nodes.get(nodeId))) {
            collectReachable(target, nodes, visited);
        }
    }

    private static void validateCycles(
        String startNodeId,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        List<ValidationIssue> issues
    ) {
        validateCycles(
            startNodeId,
            nodes,
            new HashSet<>(),
            new HashSet<>(),
            new ArrayList<>(),
            issues
        );
    }

    private static void validateCycles(
        String nodeId,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Set<String> visiting,
        Set<String> visited,
        List<String> path,
        List<ValidationIssue> issues
    ) {
        if (visited.contains(nodeId) || !nodes.containsKey(nodeId)) {
            return;
        }
        if (visiting.contains(nodeId)) {
            int cycleStart = path.indexOf(nodeId);
            List<String> cycle = cycleStart < 0
                ? List.of(nodeId)
                : path.subList(cycleStart, path.size());
            boolean controlledRevision = cycle.stream()
                .map(nodes::get)
                .anyMatch(ApprovalDefinition.HandleStep.class::isInstance);
            if (!controlledRevision) {
                issues.add(issue(
                    "PROCESS_CYCLE",
                    nodeId,
                    "cycles require an explicit HANDLE revision step"
                ));
            }
            return;
        }

        visiting.add(nodeId);
        path.add(nodeId);
        for (String target : outgoing(nodes.get(nodeId))) {
            validateCycles(target, nodes, visiting, visited, path, issues);
        }
        path.removeLast();
        visiting.remove(nodeId);
        visited.add(nodeId);
    }

    private static List<String> outgoing(ApprovalDefinition.ProcessNode node) {
        if (node instanceof ApprovalDefinition.StartNode startNode) {
            return List.of(startNode.next());
        }
        if (node instanceof ApprovalDefinition.ApprovalStep approvalStep) {
            if (approvalStep.rejectNext() == null) {
                return List.of(approvalStep.next());
            }
            return List.of(approvalStep.next(), approvalStep.rejectNext());
        }
        if (node instanceof ApprovalDefinition.HandleStep handleStep) {
            return List.of(handleStep.next());
        }
        if (node instanceof ApprovalDefinition.ConditionStep conditionStep) {
            List<String> values = new ArrayList<>();
            conditionStep.routes().forEach(route -> values.add(route.next()));
            values.add(conditionStep.defaultNext());
            return List.copyOf(values);
        }
        return List.of();
    }

    private static ValidationIssue issue(String code, String subject, String message) {
        return new ValidationIssue(code, subject, message);
    }

    public record ValidationReport(List<ValidationIssue> issues) {

        public ValidationReport {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean valid() {
            return issues.isEmpty();
        }
    }

    public record ValidationIssue(String code, String subject, String message) {
    }

    public static final class DefinitionValidationException extends IllegalArgumentException {

        private final ValidationReport report;

        public DefinitionValidationException(ValidationReport report) {
            super("Approval definition failed validation with " + report.issues().size() + " issue(s)");
            this.report = report;
        }

        public ValidationReport report() {
            return report;
        }
    }
}
