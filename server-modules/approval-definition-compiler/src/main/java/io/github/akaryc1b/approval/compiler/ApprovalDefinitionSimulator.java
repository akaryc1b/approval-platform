package io.github.akaryc1b.approval.compiler;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, engine-independent Approval DSL scenario simulator.
 * It never reads engine runtime tables and only follows the immutable source model.
 */
public final class ApprovalDefinitionSimulator {

    private static final int DEFAULT_MAX_TRANSITIONS = 200;

    private final ApprovalDefinitionValidator validator;

    public ApprovalDefinitionSimulator() {
        this(new ApprovalDefinitionValidator());
    }

    public ApprovalDefinitionSimulator(ApprovalDefinitionValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public SimulationResult simulate(
        ApprovalDefinition definition,
        FormDefinition formDefinition,
        Scenario scenario
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(formDefinition, "formDefinition must not be null");
        scenario = scenario == null ? Scenario.empty() : scenario;
        validator.validateOrThrow(definition, formDefinition);

        Map<String, ApprovalDefinition.ProcessNode> nodes = new LinkedHashMap<>();
        definition.nodes().forEach(node -> nodes.put(node.id(), node));
        List<SimulationStep> steps = new ArrayList<>();
        List<SimulationIssue> issues = new ArrayList<>();
        String current = definition.startNodeId();

        for (int sequence = 1; sequence <= scenario.maxTransitions(); sequence++) {
            ApprovalDefinition.ProcessNode node = nodes.get(current);
            if (node == null) {
                issues.add(new SimulationIssue(
                    "UNKNOWN_NODE",
                    current,
                    "simulation reached an unknown node"
                ));
                return result(SimulationStatus.BLOCKED, current, steps, issues);
            }
            Transition transition = transition(node, scenario, issues);
            steps.add(new SimulationStep(
                sequence,
                node.id(),
                node.name(),
                kind(node),
                transition.outcome(),
                transition.nextNodeId()
            ));
            if (transition.status() != null) {
                return result(transition.status(), node.id(), steps, issues);
            }
            current = transition.nextNodeId();
        }

        issues.add(new SimulationIssue(
            "TRANSITION_LIMIT_REACHED",
            current,
            "simulation exceeded the configured transition limit"
        ));
        return result(
            SimulationStatus.TRANSITION_LIMIT_REACHED,
            current,
            steps,
            issues
        );
    }

    private static Transition transition(
        ApprovalDefinition.ProcessNode node,
        Scenario scenario,
        List<SimulationIssue> issues
    ) {
        if (node instanceof ApprovalDefinition.StartNode start) {
            return Transition.next("STARTED", start.next());
        }
        if (node instanceof ApprovalDefinition.ApprovalStep approval) {
            Decision decision = scenario.decisions().getOrDefault(approval.id(), Decision.APPROVE);
            if (decision == Decision.REJECT) {
                if (approval.rejectNext() == null) {
                    return Transition.stop("REJECTED", SimulationStatus.REJECTED);
                }
                return Transition.next("REJECTED", approval.rejectNext());
            }
            return Transition.next("APPROVED", approval.next());
        }
        if (node instanceof ApprovalDefinition.HandleStep handle) {
            return Transition.next("HANDLED", handle.next());
        }
        if (node instanceof ApprovalDefinition.ConditionStep condition) {
            return conditionTransition(condition, scenario.formValues(), issues);
        }
        if (node instanceof ApprovalDefinition.EndNode) {
            return Transition.stop("COMPLETED", SimulationStatus.COMPLETED);
        }
        issues.add(new SimulationIssue(
            "UNSUPPORTED_NODE",
            node.id(),
            "simulation does not support this node type"
        ));
        return Transition.stop("BLOCKED", SimulationStatus.BLOCKED);
    }

    private static Transition conditionTransition(
        ApprovalDefinition.ConditionStep condition,
        Map<String, Object> values,
        List<SimulationIssue> issues
    ) {
        for (int index = 0; index < condition.routes().size(); index++) {
            ApprovalDefinition.ConditionRoute route = condition.routes().get(index);
            Evaluation evaluation = evaluate(route.condition(), values);
            if (evaluation.issue() != null) {
                issues.add(new SimulationIssue(
                    evaluation.issue().code(),
                    condition.id(),
                    evaluation.issue().message()
                ));
                return Transition.stop("BLOCKED", SimulationStatus.BLOCKED);
            }
            if (evaluation.matches()) {
                return Transition.next("ROUTE_" + (index + 1), route.next());
            }
        }
        return Transition.next("DEFAULT", condition.defaultNext());
    }

    private static Evaluation evaluate(
        ApprovalDefinition.ComparisonCondition condition,
        Map<String, Object> values
    ) {
        Object raw = values.get(condition.field());
        if (raw == null) {
            return Evaluation.issue(
                "MISSING_CONDITION_VALUE",
                "condition field " + condition.field() + " has no scenario value"
            );
        }
        BigDecimal actual;
        try {
            actual = raw instanceof BigDecimal decimal
                ? decimal
                : new BigDecimal(raw.toString());
        } catch (RuntimeException exception) {
            return Evaluation.issue(
                "NON_NUMERIC_CONDITION_VALUE",
                "condition field " + condition.field() + " is not numeric"
            );
        }
        int comparison = actual.compareTo(condition.value());
        boolean matches = switch (condition.operator()) {
            case GREATER_THAN -> comparison > 0;
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            case LESS_THAN -> comparison < 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
            case EQUAL -> comparison == 0;
            case NOT_EQUAL -> comparison != 0;
        };
        return Evaluation.match(matches);
    }

    private static NodeKind kind(ApprovalDefinition.ProcessNode node) {
        if (node instanceof ApprovalDefinition.StartNode) {
            return NodeKind.START;
        }
        if (node instanceof ApprovalDefinition.ApprovalStep) {
            return NodeKind.APPROVAL;
        }
        if (node instanceof ApprovalDefinition.HandleStep) {
            return NodeKind.HANDLE;
        }
        if (node instanceof ApprovalDefinition.ConditionStep) {
            return NodeKind.CONDITION;
        }
        return NodeKind.END;
    }

    private static SimulationResult result(
        SimulationStatus status,
        String terminalNodeId,
        List<SimulationStep> steps,
        List<SimulationIssue> issues
    ) {
        return new SimulationResult(
            status,
            terminalNodeId,
            List.copyOf(steps),
            List.copyOf(issues)
        );
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    public enum NodeKind {
        START,
        APPROVAL,
        HANDLE,
        CONDITION,
        END
    }

    public enum SimulationStatus {
        COMPLETED,
        REJECTED,
        BLOCKED,
        TRANSITION_LIMIT_REACHED
    }

    public record Scenario(
        Map<String, Object> formValues,
        Map<String, Decision> decisions,
        int maxTransitions
    ) {

        public Scenario {
            formValues = formValues == null ? Map.of() : Map.copyOf(formValues);
            decisions = decisions == null ? Map.of() : Map.copyOf(decisions);
            if (maxTransitions < 1 || maxTransitions > 1000) {
                throw new IllegalArgumentException(
                    "maxTransitions must be between 1 and 1000"
                );
            }
        }

        public static Scenario empty() {
            return new Scenario(Map.of(), Map.of(), DEFAULT_MAX_TRANSITIONS);
        }
    }

    public record SimulationResult(
        SimulationStatus status,
        String terminalNodeId,
        List<SimulationStep> steps,
        List<SimulationIssue> issues
    ) {

        public SimulationResult {
            status = Objects.requireNonNull(status, "status must not be null");
            terminalNodeId = Objects.requireNonNull(
                terminalNodeId,
                "terminalNodeId must not be null"
            );
            steps = steps == null ? List.of() : List.copyOf(steps);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean completed() {
            return status == SimulationStatus.COMPLETED;
        }
    }

    public record SimulationStep(
        int sequence,
        String nodeId,
        String nodeName,
        NodeKind kind,
        String outcome,
        String nextNodeId
    ) {

        public SimulationStep {
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
            nodeName = Objects.requireNonNull(nodeName, "nodeName must not be null");
            kind = Objects.requireNonNull(kind, "kind must not be null");
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    public record SimulationIssue(String code, String nodeId, String message) {

        public SimulationIssue {
            code = Objects.requireNonNull(code, "code must not be null");
            nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
            message = Objects.requireNonNull(message, "message must not be null");
        }
    }

    private record Transition(
        String outcome,
        String nextNodeId,
        SimulationStatus status
    ) {

        private static Transition next(String outcome, String nextNodeId) {
            return new Transition(outcome, nextNodeId, null);
        }

        private static Transition stop(String outcome, SimulationStatus status) {
            return new Transition(outcome, null, status);
        }
    }

    private record Evaluation(boolean matches, EvaluationIssue issue) {

        private static Evaluation match(boolean matches) {
            return new Evaluation(matches, null);
        }

        private static Evaluation issue(String code, String message) {
            return new Evaluation(false, new EvaluationIssue(code, message));
        }
    }

    private record EvaluationIssue(String code, String message) {
    }
}
