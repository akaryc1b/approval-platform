package io.github.akaryc1b.approval.domain.definition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Framework-independent source model compiled into an engine-specific process artifact.
 */
public record ApprovalDefinition(
    String schemaVersion,
    String definitionKey,
    int version,
    String name,
    String startNodeId,
    List<ProcessNode> nodes
) {

    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    public ApprovalDefinition {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        definitionKey = requireText(definitionKey, "definitionKey");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        name = requireText(name, "name");
        startNodeId = requireText(startNodeId, "startNodeId");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
    }

    public sealed interface ProcessNode permits StartNode, ApprovalStep, ConditionStep, EndNode {

        String id();

        String name();
    }

    public record StartNode(String id, String name, String next) implements ProcessNode {

        public StartNode {
            id = requireText(id, "start.id");
            name = requireText(name, "start.name");
            next = requireText(next, "start.next");
        }
    }

    public record ApprovalStep(
        String id,
        String name,
        AssigneeRule assignee,
        ApprovalMode mode,
        String next
    ) implements ProcessNode {

        public ApprovalStep {
            id = requireText(id, "approval.id");
            name = requireText(name, "approval.name");
            assignee = Objects.requireNonNull(assignee, "approval.assignee must not be null");
            mode = Objects.requireNonNull(mode, "approval.mode must not be null");
            next = requireText(next, "approval.next");
        }
    }

    public record ConditionStep(
        String id,
        String name,
        List<ConditionRoute> routes,
        String defaultNext
    ) implements ProcessNode {

        public ConditionStep {
            id = requireText(id, "condition.id");
            name = requireText(name, "condition.name");
            routes = routes == null ? List.of() : List.copyOf(routes);
            if (routes.isEmpty()) {
                throw new IllegalArgumentException("condition.routes must not be empty");
            }
            defaultNext = requireText(defaultNext, "condition.defaultNext");
        }
    }

    public record EndNode(String id, String name) implements ProcessNode {

        public EndNode {
            id = requireText(id, "end.id");
            name = requireText(name, "end.name");
        }
    }

    public record AssigneeRule(
        AssigneeResolver resolver,
        String variable,
        EmptyAssigneePolicy emptyPolicy
    ) {

        public AssigneeRule {
            resolver = Objects.requireNonNull(resolver, "resolver must not be null");
            variable = requireText(variable, "variable");
            emptyPolicy = Objects.requireNonNull(emptyPolicy, "emptyPolicy must not be null");
        }
    }

    public enum AssigneeResolver {
        INITIATOR_MANAGER,
        VARIABLE_USER,
        VARIABLE_USER_LIST
    }

    public enum EmptyAssigneePolicy {
        FAIL,
        SKIP
    }

    public record ApprovalMode(ApprovalModeType type) {

        public ApprovalMode {
            type = Objects.requireNonNull(type, "approval mode type must not be null");
        }

        public static ApprovalMode single() {
            return new ApprovalMode(ApprovalModeType.SINGLE);
        }

        public static ApprovalMode all() {
            return new ApprovalMode(ApprovalModeType.ALL);
        }

        public static ApprovalMode any() {
            return new ApprovalMode(ApprovalModeType.ANY);
        }
    }

    public enum ApprovalModeType {
        SINGLE,
        ALL,
        ANY
    }

    public record ConditionRoute(ComparisonCondition condition, String next) {

        public ConditionRoute {
            condition = Objects.requireNonNull(condition, "condition must not be null");
            next = requireText(next, "condition route next");
        }
    }

    public record ComparisonCondition(
        String field,
        ComparisonOperator operator,
        BigDecimal value
    ) {

        public ComparisonCondition {
            field = requireText(field, "condition field");
            operator = Objects.requireNonNull(operator, "condition operator must not be null");
            value = Objects.requireNonNull(value, "condition value must not be null");
        }
    }

    public enum ComparisonOperator {
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        EQUAL,
        NOT_EQUAL
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
