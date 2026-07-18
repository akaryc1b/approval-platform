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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Performs authoritative, engine-independent Approval DSL validation. */
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
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(formDefinition, "formDefinition must not be null");
        List<ValidationIssue> issues = new ArrayList<>();
        validateSchemas(definition, formDefinition, uiSchemaDefinition, issues);

        Map<String, ApprovalDefinition.ProcessNode> nodes = indexNodes(definition, issues);
        Map<String, FormDefinition.FormField> fields = indexFields(formDefinition, issues);
        Map<String, List<String>> splitOwnersByJoin = splitOwners(definition);

        validateStartAndEnd(definition, nodes, issues);
        validateNodes(definition, nodes, fields, splitOwnersByJoin, issues);
        validateJoinOwnership(definition, splitOwnersByJoin, issues);
        validateReachability(definition.startNodeId(), nodes, issues);
        validateCycles(definition.startNodeId(), nodes, issues);
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
        FormDefinition form,
        UiSchemaDefinition uiSchema,
        List<ValidationIssue> issues
    ) {
        if (!ApprovalDefinition.CURRENT_SCHEMA_VERSION.equals(definition.schemaVersion())) {
            issues.add(error(
                "UNSUPPORTED_PROCESS_SCHEMA",
                definition.schemaVersion(),
                "unsupported Approval DSL schema version"
            ));
        }
        if (!FormDefinition.CURRENT_SCHEMA_VERSION.equals(form.schemaVersion())) {
            issues.add(error(
                "UNSUPPORTED_FORM_SCHEMA",
                form.schemaVersion(),
                "unsupported Form Schema version"
            ));
        }
        if (!definition.definitionKey().equals(form.formKey())) {
            issues.add(error(
                "FORM_PROCESS_KEY_MISMATCH",
                form.formKey(),
                "formKey must equal definitionKey"
            ));
        }
        if (uiSchema == null) {
            return;
        }
        if (!UiSchemaDefinition.CURRENT_SCHEMA_VERSION.equals(uiSchema.schemaVersion())) {
            issues.add(error(
                "UNSUPPORTED_UI_SCHEMA",
                uiSchema.schemaVersion(),
                "unsupported UI Schema version"
            ));
        }
        if (!definition.definitionKey().equals(uiSchema.formKey())
            || form.version() != uiSchema.formVersion()) {
            issues.add(error(
                "UI_SCHEMA_BINDING_MISMATCH",
                uiSchema.formKey(),
                "UI Schema must bind to the exact Form Schema used by the Approval DSL"
            ));
        }
    }

    private static Map<String, ApprovalDefinition.ProcessNode> indexNodes(
        ApprovalDefinition definition,
        List<ValidationIssue> issues
    ) {
        Map<String, ApprovalDefinition.ProcessNode> nodes = new LinkedHashMap<>();
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (!NODE_IDENTIFIER.matcher(node.id()).matches()) {
                issues.add(error("INVALID_NODE_ID", node.id(), "node ID is not engine-safe"));
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                issues.add(error("DUPLICATE_NODE_ID", node.id(), "node ID must be unique"));
            }
        }
        return nodes;
    }

    private static Map<String, FormDefinition.FormField> indexFields(
        FormDefinition form,
        List<ValidationIssue> issues
    ) {
        Map<String, FormDefinition.FormField> fields = new HashMap<>();
        for (FormDefinition.FormField field : form.fields()) {
            if (!VARIABLE_IDENTIFIER.matcher(field.key()).matches()) {
                issues.add(error(
                    "INVALID_FIELD_KEY",
                    field.key(),
                    "field key must be a safe variable identifier"
                ));
            }
            if (fields.putIfAbsent(field.key(), field) != null) {
                issues.add(error(
                    "DUPLICATE_FIELD_KEY",
                    field.key(),
                    "field key must be unique"
                ));
            }
        }
        return fields;
    }

    private static Map<String, List<String>> splitOwners(ApprovalDefinition definition) {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
                owners.computeIfAbsent(split.joinNodeId(), ignored -> new ArrayList<>())
                    .add(split.id());
            }
        }
        return owners;
    }

    private static void validateStartAndEnd(
        ApprovalDefinition definition,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        List<ValidationIssue> issues
    ) {
        long starts = definition.nodes().stream()
            .filter(ApprovalDefinition.StartNode.class::isInstance)
            .count();
        long ends = definition.nodes().stream()
            .filter(ApprovalDefinition.EndNode.class::isInstance)
            .count();
        if (starts != 1) {
            issues.add(error(
                "INVALID_START_COUNT",
                definition.definitionKey(),
                "exactly one START node is required"
            ));
        }
        if (!(nodes.get(definition.startNodeId()) instanceof ApprovalDefinition.StartNode)) {
            issues.add(error(
                "INVALID_START_NODE",
                definition.startNodeId(),
                "startNodeId must reference a START node"
            ));
        }
        if (ends == 0) {
            issues.add(error(
                "MISSING_END_NODE",
                definition.definitionKey(),
                "an END node is required"
            ));
        }
    }

    private static void validateNodes(
        ApprovalDefinition definition,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Map<String, FormDefinition.FormField> fields,
        Map<String, List<String>> splitOwnersByJoin,
        List<ValidationIssue> issues
    ) {
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (node instanceof ApprovalDefinition.StartNode start) {
                requireNode(nodes, start.id(), start.next(), issues);
            } else if (node instanceof ApprovalDefinition.ApprovalStep approval) {
                requireNode(nodes, approval.id(), approval.next(), issues);
                if (approval.rejectNext() != null) {
                    requireNode(nodes, approval.id(), approval.rejectNext(), issues);
                }
                validateApproval(approval, issues);
            } else if (node instanceof ApprovalDefinition.HandleStep handle) {
                requireNode(nodes, handle.id(), handle.next(), issues);
                validateHandle(handle, issues);
            } else if (node instanceof ApprovalDefinition.ConditionStep condition) {
                validateCondition(condition, nodes, fields, issues);
            } else if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
                validateParallelSplit(split, nodes, splitOwnersByJoin, issues);
            } else if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
                requireNode(nodes, join.id(), join.next(), issues);
            }
        }
    }

    private static void validateApproval(
        ApprovalDefinition.ApprovalStep approval,
        List<ValidationIssue> issues
    ) {
        validateAssignee(approval.id(), approval.assignee(), issues);
        boolean listResolver = approval.assignee().resolver()
            == ApprovalDefinition.AssigneeResolver.VARIABLE_USER_LIST;
        boolean singleMode = approval.mode().type()
            == ApprovalDefinition.ApprovalModeType.SINGLE;
        if (listResolver == singleMode) {
            issues.add(error(
                "INVALID_APPROVAL_MODE",
                approval.id(),
                listResolver
                    ? "a user-list resolver requires ALL or ANY mode"
                    : "a single-user resolver requires SINGLE mode"
            ));
        }
    }

    private static void validateHandle(
        ApprovalDefinition.HandleStep handle,
        List<ValidationIssue> issues
    ) {
        validateAssignee(handle.id(), handle.assignee(), issues);
        if (handle.assignee().resolver()
            == ApprovalDefinition.AssigneeResolver.VARIABLE_USER_LIST) {
            issues.add(error(
                "INVALID_HANDLE_ASSIGNEE",
                handle.id(),
                "a handle step requires a single-user assignee"
            ));
        }
    }

    private static void validateAssignee(
        String nodeId,
        ApprovalDefinition.AssigneeRule assignee,
        List<ValidationIssue> issues
    ) {
        if (!VARIABLE_IDENTIFIER.matcher(assignee.variable()).matches()) {
            issues.add(error(
                "INVALID_ASSIGNEE_VARIABLE",
                nodeId,
                "assignee variable must be a safe identifier"
            ));
        }
        if (assignee.emptyPolicy() != ApprovalDefinition.EmptyAssigneePolicy.FAIL) {
            issues.add(error(
                "UNSUPPORTED_EMPTY_POLICY",
                nodeId,
                "the compiler supports FAIL only"
            ));
        }
    }

    private static void validateCondition(
        ApprovalDefinition.ConditionStep condition,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Map<String, FormDefinition.FormField> fields,
        List<ValidationIssue> issues
    ) {
        requireNode(nodes, condition.id(), condition.defaultNext(), issues);
        Set<ApprovalDefinition.ComparisonCondition> unique = new LinkedHashSet<>();
        for (int index = 0; index < condition.routes().size(); index++) {
            ApprovalDefinition.ConditionRoute route = condition.routes().get(index);
            requireNode(nodes, condition.id(), route.next(), issues);
            validateComparison(condition.id(), route.condition(), fields, issues);
            if (!unique.add(route.condition())) {
                issues.add(warning(
                    "DUPLICATE_CONDITION_ROUTE",
                    condition.id(),
                    "route " + (index + 1) + " duplicates an earlier condition"
                ));
            }
            warnConstantComparison(condition.id(), route.condition(), fields, issues);
        }
        if (condition.routes().stream()
            .anyMatch(route -> route.next().equals(condition.defaultNext()))) {
            issues.add(info(
                "CONDITION_DEFAULT_REUSES_TARGET",
                condition.id(),
                "a conditional and default route converge on the same target"
            ));
        }
    }

    private static void validateComparison(
        String nodeId,
        ApprovalDefinition.ComparisonCondition condition,
        Map<String, FormDefinition.FormField> fields,
        List<ValidationIssue> issues
    ) {
        if (!VARIABLE_IDENTIFIER.matcher(condition.field()).matches()) {
            issues.add(error(
                "INVALID_CONDITION_FIELD",
                nodeId,
                "condition field must be a safe variable identifier"
            ));
            return;
        }
        FormDefinition.FormField field = fields.get(condition.field());
        if (field == null) {
            issues.add(error(
                "UNKNOWN_CONDITION_FIELD",
                nodeId,
                "condition field is not present in the Form Schema"
            ));
        } else if (field.type() != FormDefinition.FieldType.MONEY
            && field.type() != FormDefinition.FieldType.NUMBER) {
            issues.add(error(
                "NON_NUMERIC_CONDITION_FIELD",
                nodeId,
                "comparison conditions require a MONEY or NUMBER field"
            ));
        }
    }

    private static void warnConstantComparison(
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
            issues.add(warning(
                alwaysTrue ? "CONDITION_ALWAYS_TRUE" : "CONDITION_ALWAYS_FALSE",
                nodeId,
                "condition is constant for values allowed by the Form Schema minimum"
            ));
        }
    }

    private static void validateParallelSplit(
        ApprovalDefinition.ParallelSplitNode split,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Map<String, List<String>> splitOwnersByJoin,
        List<ValidationIssue> issues
    ) {
        ApprovalDefinition.ProcessNode join = nodes.get(split.joinNodeId());
        if (!(join instanceof ApprovalDefinition.ParallelJoinNode)) {
            issues.add(error(
                "MISSING_PARALLEL_JOIN",
                split.id(),
                "parallel split joinNodeId must reference a PARALLEL_JOIN node"
            ));
        }
        Set<String> branchIds = new HashSet<>();
        Set<String> branchTargets = new HashSet<>();
        for (ApprovalDefinition.ParallelBranch branch : split.branches()) {
            if (!NODE_IDENTIFIER.matcher(branch.id()).matches()) {
                issues.add(error(
                    "INVALID_PARALLEL_BRANCH_ID",
                    split.id(),
                    "parallel branch ID is not engine-safe: " + branch.id()
                ));
            }
            if (!branchIds.add(branch.id())) {
                issues.add(error(
                    "DUPLICATE_PARALLEL_BRANCH_ID",
                    split.id(),
                    "parallel branch IDs must be unique"
                ));
            }
            if (!branchTargets.add(branch.next())) {
                issues.add(warning(
                    "DUPLICATE_PARALLEL_BRANCH_TARGET",
                    split.id(),
                    "multiple parallel branches enter the same node"
                ));
            }
            requireNode(nodes, split.id(), branch.next(), issues);
            if (join instanceof ApprovalDefinition.ParallelJoinNode
                && convergence(
                    branch.next(),
                    split.joinNodeId(),
                    nodes,
                    splitOwnersByJoin,
                    List.of()
                ) != Convergence.CONVERGES) {
                issues.add(error(
                    "PARALLEL_BRANCH_MISSING_JOIN",
                    branch.id(),
                    "all parallel branch paths must converge on join " + split.joinNodeId()
                ));
            }
        }
    }

    private static Convergence convergence(
        String current,
        String target,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Map<String, List<String>> splitOwnersByJoin,
        List<String> path
    ) {
        if (current.equals(target)) {
            return Convergence.CONVERGES;
        }
        int cycleStart = path.indexOf(current);
        if (cycleStart >= 0) {
            boolean controlled = path.subList(cycleStart, path.size()).stream()
                .map(nodes::get)
                .anyMatch(ApprovalDefinition.HandleStep.class::isInstance);
            return controlled ? Convergence.CONTROLLED_LOOP : Convergence.FAILS;
        }
        ApprovalDefinition.ProcessNode node = nodes.get(current);
        if (node == null || node instanceof ApprovalDefinition.EndNode) {
            return Convergence.FAILS;
        }

        List<String> nextPath = new ArrayList<>(path);
        nextPath.add(current);
        if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
            List<String> owners = splitOwnersByJoin.getOrDefault(join.id(), List.of());
            if (owners.size() != 1 || !nextPath.contains(owners.getFirst())) {
                return Convergence.FAILS;
            }
            return convergence(
                join.next(),
                target,
                nodes,
                splitOwnersByJoin,
                nextPath
            );
        }
        if (node instanceof ApprovalDefinition.ParallelSplitNode nestedSplit) {
            ApprovalDefinition.ProcessNode nestedJoin = nodes.get(nestedSplit.joinNodeId());
            List<String> owners = splitOwnersByJoin.getOrDefault(
                nestedSplit.joinNodeId(),
                List.of()
            );
            if (!(nestedJoin instanceof ApprovalDefinition.ParallelJoinNode join)
                || owners.size() != 1
                || !owners.getFirst().equals(nestedSplit.id())) {
                return Convergence.FAILS;
            }
            for (ApprovalDefinition.ParallelBranch branch : nestedSplit.branches()) {
                if (convergence(
                    branch.next(),
                    nestedSplit.joinNodeId(),
                    nodes,
                    splitOwnersByJoin,
                    nextPath
                ) != Convergence.CONVERGES) {
                    return Convergence.FAILS;
                }
            }
            return convergence(
                join.next(),
                target,
                nodes,
                splitOwnersByJoin,
                nextPath
            );
        }

        List<String> targets = outgoing(node);
        if (targets.isEmpty()) {
            return Convergence.FAILS;
        }
        boolean hasExit = false;
        for (String next : targets) {
            Convergence result = convergence(
                next,
                target,
                nodes,
                splitOwnersByJoin,
                nextPath
            );
            if (result == Convergence.FAILS) {
                return Convergence.FAILS;
            }
            if (result == Convergence.CONVERGES) {
                hasExit = true;
            }
        }
        return hasExit ? Convergence.CONVERGES : Convergence.FAILS;
    }

    private static void validateJoinOwnership(
        ApprovalDefinition definition,
        Map<String, List<String>> splitOwnersByJoin,
        List<ValidationIssue> issues
    ) {
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (!(node instanceof ApprovalDefinition.ParallelJoinNode join)) {
                continue;
            }
            List<String> owners = splitOwnersByJoin.getOrDefault(join.id(), List.of());
            if (owners.isEmpty()) {
                issues.add(error(
                    "ORPHAN_PARALLEL_JOIN",
                    join.id(),
                    "parallel JOIN must be referenced by exactly one split"
                ));
            } else if (owners.size() > 1) {
                issues.add(error(
                    "SHARED_PARALLEL_JOIN",
                    join.id(),
                    "parallel JOIN cannot be shared by multiple splits"
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
            issues.add(error(
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
        if (!(nodes.get(startNodeId) instanceof ApprovalDefinition.StartNode)) {
            return;
        }
        Set<String> visited = new HashSet<>();
        collectReachable(startNodeId, nodes, visited);
        for (String nodeId : nodes.keySet()) {
            if (!visited.contains(nodeId)) {
                issues.add(error(
                    "UNREACHABLE_NODE",
                    nodeId,
                    "node is not reachable from start"
                ));
            }
        }
    }

    private static void collectReachable(
        String nodeId,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Set<String> visited
    ) {
        if (!visited.add(nodeId)) {
            return;
        }
        ApprovalDefinition.ProcessNode node = nodes.get(nodeId);
        if (node == null) {
            return;
        }
        for (String next : outgoing(node)) {
            collectReachable(next, nodes, visited);
        }
    }

    private static void validateCycles(
        String startNodeId,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        List<ValidationIssue> issues
    ) {
        if (!(nodes.get(startNodeId) instanceof ApprovalDefinition.StartNode)) {
            return;
        }
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
        String current,
        Map<String, ApprovalDefinition.ProcessNode> nodes,
        Set<String> visiting,
        Set<String> visited,
        List<String> path,
        List<ValidationIssue> issues
    ) {
        if (visited.contains(current) || !nodes.containsKey(current)) {
            return;
        }
        if (visiting.contains(current)) {
            int cycleStart = path.indexOf(current);
            List<String> cycle = cycleStart < 0
                ? List.of(current)
                : path.subList(cycleStart, path.size());
            boolean controlled = cycle.stream()
                .map(nodes::get)
                .anyMatch(ApprovalDefinition.HandleStep.class::isInstance);
            if (!controlled) {
                issues.add(error(
                    "PROCESS_CYCLE",
                    current,
                    "cycles require an explicit HANDLE revision step"
                ));
            }
            return;
        }
        visiting.add(current);
        path.add(current);
        for (String next : outgoing(nodes.get(current))) {
            validateCycles(next, nodes, visiting, visited, path, issues);
        }
        path.removeLast();
        visiting.remove(current);
        visited.add(current);
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
                issues.add(error(
                    "DUPLICATE_PERMISSION_CONTEXT",
                    permissions.contextKey(),
                    "UI Schema permission context must be unique"
                ));
            }
        }
        if (!contexts.contains(UiSchemaDefinition.START_CONTEXT)) {
            issues.add(warning(
                "MISSING_START_PERMISSION_CONTEXT",
                UiSchemaDefinition.START_CONTEXT,
                "start context is absent; runtime must apply the secure hidden-field default"
            ));
        }
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if ((node instanceof ApprovalDefinition.ApprovalStep
                || node instanceof ApprovalDefinition.HandleStep)
                && !contexts.contains(node.id())) {
                issues.add(warning(
                    "SAFE_PERMISSION_DEFAULT",
                    node.id(),
                    "node has no explicit UI permission context; all fields default to hidden"
                ));
            }
        }
    }

    private static List<String> outgoing(ApprovalDefinition.ProcessNode node) {
        if (node instanceof ApprovalDefinition.StartNode start) {
            return List.of(start.next());
        }
        if (node instanceof ApprovalDefinition.ApprovalStep approval) {
            return approval.rejectNext() == null
                ? List.of(approval.next())
                : List.of(approval.next(), approval.rejectNext());
        }
        if (node instanceof ApprovalDefinition.HandleStep handle) {
            return List.of(handle.next());
        }
        if (node instanceof ApprovalDefinition.ConditionStep condition) {
            List<String> targets = new ArrayList<>();
            condition.routes().forEach(route -> targets.add(route.next()));
            targets.add(condition.defaultNext());
            return List.copyOf(targets);
        }
        if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
            return split.branches().stream()
                .map(ApprovalDefinition.ParallelBranch::next)
                .toList();
        }
        if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
            return List.of(join.next());
        }
        return List.of();
    }

    private static ValidationIssue error(String code, String subject, String message) {
        return new ValidationIssue(code, subject, message, Severity.ERROR);
    }

    private static ValidationIssue warning(String code, String subject, String message) {
        return new ValidationIssue(code, subject, message, Severity.WARNING);
    }

    private static ValidationIssue info(String code, String subject, String message) {
        return new ValidationIssue(code, subject, message, Severity.INFO);
    }

    private enum Convergence {
        CONVERGES,
        CONTROLLED_LOOP,
        FAILS
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
            return errors().isEmpty();
        }

        public List<ValidationIssue> errors() {
            return issues.stream()
                .filter(issue -> issue.severity() == Severity.ERROR)
                .toList();
        }

        public List<ValidationIssue> warnings() {
            return issues.stream()
                .filter(issue -> issue.severity() == Severity.WARNING)
                .toList();
        }

        public List<ValidationIssue> infos() {
            return issues.stream()
                .filter(issue -> issue.severity() == Severity.INFO)
                .toList();
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
            code = Objects.requireNonNull(code, "code must not be null");
            subject = Objects.requireNonNull(subject, "subject must not be null");
            message = Objects.requireNonNull(message, "message must not be null");
            severity = severity == null ? Severity.ERROR : severity;
        }
    }

    public static final class DefinitionValidationException extends IllegalArgumentException {
        private final ValidationReport report;

        public DefinitionValidationException(ValidationReport report) {
            super("Approval definition failed validation with "
                + report.errors().size()
                + " error(s)");
            this.report = Objects.requireNonNull(report, "report must not be null");
        }

        public ValidationReport report() {
            return report;
        }
    }
}
