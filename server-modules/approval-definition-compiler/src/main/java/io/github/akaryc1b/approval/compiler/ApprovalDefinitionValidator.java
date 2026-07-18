package io.github.akaryc1b.approval.compiler;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Performs engine-independent graph, identifier, schema and permission compatibility validation. */
public final class ApprovalDefinitionValidator {

    private static final Pattern NODE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern VARIABLE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    public ValidationReport validate(
        ApprovalDefinition definition,
        FormDefinition formDefinition
    ) {
        return validate(definition, formDefinition, null);
    }

    public ValidationReport validate(
        ApprovalDefinition definition,
        FormDefinition formDefinition,
        UiSchemaDefinition uiSchemaDefinition
    ) {
        List<ValidationIssue> issues = new ArrayList<>();
        validateSchemas(definition, formDefinition, uiSchemaDefinition, issues);

        Map<String, ApprovalDefinition.ProcessNode> nodes = new LinkedHashMap<>();
        int startNodes = 0;
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (!NODE_IDENTIFIER.matcher(node.id()).matches()) {
                issues.add(issue("INVALID_NODE_ID", node.id(), "node ID is not engine-safe"));
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                issues.add(issue("DUPLICATE_NODE_ID", node.id(), "node ID must be unique"));
            }
            if (node instanceof ApprovalDefinition.StartNode) {
                startNodes++;
            }
        }
        if (startNodes != 1) {
            issues.add(issue(
                "INVALID_START_COUNT",
                definition.definitionKey(),
                "exactly one START node is required"
            ));
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
        Map<String, List<String>> splitOwnersByJoin = new LinkedHashMap<>();
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
                validateConditionStep(conditionStep, nodes, fields, issues);
            } else if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
                validateParallelSplit(split, nodes, issues);
                splitOwnersByJoin.computeIfAbsent(split.joinNodeId(), ignored -> new ArrayList<>())
                    .add(split.id());
            } else if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
                requireNode(nodes, join.id(), join.next(), issues);
            } else if (node instanceof ApprovalDefinition.EndNode) {
                endNodes++;
            }
        }
        if (endNodes == 0) {
            issues.add(issue("MISSING_END_NODE", definition.definitionKey(), "an END node is required"));
        }
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (!(node instanceof ApprovalDefinition.ParallelJoinNode join)) {
                continue;
            }
            List<String> owners = splitOwnersByJoin.getOrDefault(join.id(), List.of());
            if (owners.isEmpty()) {
                issues.add(issue(
                    "ORPHAN_PARALLEL_JOIN",
                    join.id(),
                    "parallel JOIN must be referenced by exactly one split"
                ));
            } else if (owners.size() > 1) {
                issues.add(issue(
                    "SHARED_PARALLEL_JOIN",
                    join.id(),
                    "parallel JOIN cannot be shared by multiple splits"
                ));
            }
        }

        if (start instanceof ApprovalDefinition.StartNode) {
            validateReachability(definition.startNodeId(), nodes, issues);
            validateCycles(definition.startNodeId(), nodes, issues);
        }
        validatePermissionContexts(definition, uiSchemaDefinition, issues);
        return new ValidationReport(issues);
    }

    public void validateOrThrow(
        ApprovalDefinition definition,
        FormDefinition formDefinition
    ) {
        validateOrThrow(definition, formDefinition, null);
    }

    public void validateOrThrow(
        ApprovalDefinition definition,
        FormDefinition formDefinition,
        UiSchemaDefinition uiSchemaDefinition
    ) {
        ValidationReport report = validate(definition, formDefinition, uiSchemaDefinition);
        if (!report.valid()) {
            throw new DefinitionValidationException(report);
        }
    }

    private static void validateSchemas(
        ApprovalDefinition definition,
        FormDefinition formDefinition,
        UiSchemaDefinition uiSchemaDefinition,
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
        if (uiSchemaDefinition == null) {
            return;
        }
        if (!UiSchemaDefinition.CURRENT_SCHEMA_VERSION.equals(uiSchemaDefinition.schemaVersion())) {
            issues.add(issue(
                "UNSUPPORTED_UI_SCHEMA",
                uiSchemaDefinition.schemaVersion(),
                "unsupported UI Schema version"
            ));
        }
        if (!definition.definitionKey().equals(uiSchemaDefinition.formKey())
            || formDefinition.version() != uiSchemaDefinition.formVersion()) {
            issues.add(issue(
                "UI_SCHEMA_BINDING_MISMATCH",
                uiSchemaDefinition.formKey(),
                "UI Schema must bind to the exact Form Schema used by the Approval DSL"
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
                "the compiler supports FAIL only"
            ));
        }
    }

    private static void validateConditionStep(
        ApprovalDefinition.ConditionStep condition,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Map<String, FormDefinition.FormField> fields,
        List<ValidationIssue> issues
    ) {
        requireNode(nodes, condition.id(), condition.defaultNext(), issues);
        Set<ApprovalDefinition.ComparisonCondition> conditions = new LinkedHashSet<>();
        for (int index = 0; index < condition.routes().size(); index++) {
            ApprovalDefinition.ConditionRoute route = condition.routes().get(index);
            requireNode(nodes, condition.id(), route.next(), issues);
            validateCondition(condition.id(), route.condition(), fields, issues);
            if (!conditions.add(route.condition())) {
                issues.add(issue(
                    Severity.WARNING,
                    "DUPLICATE_CONDITION_ROUTE",
                    condition.id(),
                    "route " + (index + 1) + " duplicates an earlier condition"
                ));
            }
            warnConstantCondition(condition.id(), route.condition(), fields, issues);
        }
        if (condition.routes().stream().anyMatch(route -> route.next().equals(condition.defaultNext()))) {
            issues.add(issue(
                Severity.INFO,
                "CONDITION_DEFAULT_REUSES_TARGET",
                condition.id(),
                "a conditional and default route converge on the same target"
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
        } else if (field.type() != FormDefinition.FieldType.MONEY
            && field.type() != FormDefinition.FieldType.NUMBER) {
            issues.add(issue(
                "NON_NUMERIC_CONDITION_FIELD",
                nodeId,
                "comparison conditions require a MONEY or NUMBER field"
            ));
        }
    }

    private static void warnConstantCondition(
        String nodeId,
        ApprovalDefinition.ComparisonCondition condition,
        Map<String, FormDefinition.FormField> fields,
        List<ValidationIssue> issues
    ) {
        FormDefinition.FormField field = fields.get(condition.field());
        if (field == null || field.constraints().minimum() == null) {
            return;
        }
        BigDecimal minimum = field.constraints().minimum();
        int compared = condition.value().compareTo(minimum);
        boolean alwaysTrue = switch (condition.operator()) {
            case GREATER_THAN -> compared < 0;
            case GREATER_THAN_OR_EQUAL -> compared <= 0;
            default -> false;
        };
        boolean alwaysFalse = switch (condition.operator()) {
            case LESS_THAN -> compared <= 0;
            case LESS_THAN_OR_EQUAL, EQUAL -> compared < 0;
            default -> false;
        };
        if (alwaysTrue || alwaysFalse) {
            issues.add(issue(
                Severity.WARNING,
                alwaysTrue ? "CONDITION_ALWAYS_TRUE" : "CONDITION_ALWAYS_FALSE",
                nodeId,
                "condition is constant for values allowed by the Form Schema minimum"
            ));
        }
    }

    private static void validateParallelSplit(
        ApprovalDefinition.ParallelSplitNode split,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        List<ValidationIssue> issues
    ) {
        ApprovalDefinition.ProcessNode join = nodes.get(split.joinNodeId());
        if (!(join instanceof ApprovalDefinition.ParallelJoinNode)) {
            issues.add(issue(
                "MISSING_PARALLEL_JOIN",
                split.id(),
                "parallel split joinNodeId must reference a PARALLEL_JOIN node"
            ));
        }
        Set<String> branchIds = new HashSet<>();
        Set<String> branchTargets = new HashSet<>();
        for (ApprovalDefinition.ParallelBranch branch : split.branches()) {
            if (!NODE_IDENTIFIER.matcher(branch.id()).matches()) {
                issues.add(issue(
                    "INVALID_PARALLEL_BRANCH_ID",
                    split.id(),
                    "parallel branch ID is not engine-safe: " + branch.id()
                ));
            }
            if (!branchIds.add(branch.id())) {
                issues.add(issue(
                    "DUPLICATE_PARALLEL_BRANCH_ID",
                    split.id(),
                    "parallel branch IDs must be unique"
                ));
            }
            if (!branchTargets.add(branch.next())) {
                issues.add(issue(
                    Severity.WARNING,
                    "DUPLICATE_PARALLEL_BRANCH_TARGET",
                    split.id(),
                    "multiple parallel branches enter the same node"
                ));
            }
            requireNode(nodes, split.id(), branch.next(), issues);
            if (join instanceof ApprovalDefinition.ParallelJoinNode
                && !allPathsConverge(
                    branch.next(),
                    split.joinNodeId(),
                    nodes,
                    new HashSet<>(),
                    new HashMap<>()
                )) {
                issues.add(issue(
                    "PARALLEL_BRANCH_MISSING_JOIN",
                    branch.id(),
                    "all parallel branch paths must converge on join " + split.joinNodeId()
                ));
            }
        }
    }

    private static boolean allPathsConverge(
        String current,
        String target,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Set<String> visiting,
        Map<String, Boolean> memo
    ) {
        if (current.equals(target)) {
            return true;
        }
        Boolean known = memo.get(current);
        if (known != null) {
            return known;
        }
        if (!visiting.add(current)) {
            return false;
        }
        ApprovalDefinition.ProcessNode node = nodes.get(current);
        if (node == null
            || node instanceof ApprovalDefinition.EndNode
            || node instanceof ApprovalDefinition.ParallelJoinNode) {
            visiting.remove(current);
            memo.put(current, false);
            return false;
        }
        List<String> targets = outgoing(node);
        boolean converges = !targets.isEmpty();
        for (String next : targets) {
            if (!allPathsConverge(next, target, nodes, visiting, memo)) {
                converges = false;
                break;
            }
        }
        visiting.remove(current);
        memo.put(current, converges);
        return converges;
    }

    private static void validatePermissionContexts(
        ApprovalDefinition definition,
        UiSchemaDefinition uiSchema,
        List<ValidationIssue> issues
    ) {
        if (uiSchema == null) {
            return;
        }
        Set<String> contexts = new HashSet<>();
        for (UiSchemaDefinition.NodePermissions permissions : uiSchema.nodePermissions()) {
            if (!contexts.add(permissions.contextKey())) {
                issues.add(issue(
                    "DUPLICATE_PERMISSION_CONTEXT",
                    permissions.contextKey(),
                    "UI Schema permission context must be unique"
                ));
            }
        }
        if (!contexts.contains(UiSchemaDefinition.START_CONTEXT)) {
            issues.add(issue(
                Severity.WARNING,
                "MISSING_START_PERMISSION_CONTEXT",
                UiSchemaDefinition.START_CONTEXT,
                "start context is absent; runtime must apply the secure hidden-field default"
            ));
        }
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if ((node instanceof ApprovalDefinition.ApprovalStep
                || node instanceof ApprovalDefinition.HandleStep)
                && !contexts.contains(node.id())) {
                issues.add(issue(
                    Severity.WARNING,
                    "SAFE_PERMISSION_DEFAULT",
                    node.id(),
                    "node has no explicit UI permission context; all fields default to hidden"
                ));
            }
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
        if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
            return split.branches().stream().map(ApprovalDefinition.ParallelBranch::next).toList();
        }
        if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
            return List.of(join.next());
        }
        return List.of();
    }

    private static ValidationIssue issue(String code, String subject, String message) {
        return issue(Severity.ERROR, code, subject, message);
    }

    private static ValidationIssue issue(
        Severity severity,
        String code,
        String subject,
        String message
    ) {
        return new ValidationIssue(code, subject, message, severity);
    }

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public record ValidationReport(List<ValidationIssue> issues) {
        public ValidationReport {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean valid() {
            return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR);
        }

        public List<ValidationIssue> errors() {
            return issues.stream().filter(issue -> issue.severity() == Severity.ERROR).toList();
        }

        public List<ValidationIssue> warnings() {
            return issues.stream().filter(issue -> issue.severity() == Severity.WARNING).toList();
        }

        public List<ValidationIssue> infos() {
            return issues.stream().filter(issue -> issue.severity() == Severity.INFO).toList();
        }
    }

    public record ValidationIssue(
        String code,
        String subject,
        String message,
        Severity severity
    ) {
        public ValidationIssue(String code, String subject, String message) {
            this(code, subject, message, Severity.ERROR);
        }

        public ValidationIssue {
            severity = severity == null ? Severity.ERROR : severity;
        }
    }

    public static final class DefinitionValidationException extends IllegalArgumentException {
        private final ValidationReport report;

        public DefinitionValidationException(ValidationReport report) {
            super("Approval definition failed validation with " + report.errors().size() + " error(s)");
            this.report = report;
        }

        public ValidationReport report() {
            return report;
        }
    }
}
