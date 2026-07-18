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
        Context context = new Context(nodes, scenario);
        WalkResult result = walk(definition.startNodeId(), null, context);
        if (result.status() == InternalStatus.JOIN_REACHED) {
            context.issues().add(new SimulationIssue(
                "UNEXPECTED_PARALLEL_JOIN",
                result.terminalNodeId(),
                "simulation reached a parallel join without its owning split"
            ));
            return context.result(SimulationStatus.BLOCKED, result.terminalNodeId());
        }
        return context.result(result.status().publicStatus(), result.terminalNodeId());
    }

    private static WalkResult walk(
        String startNodeId,
        String stopBeforeJoin,
        Context context
    ) {
        String current = startNodeId;
        while (true) {
            if (current.equals(stopBeforeJoin)) {
                return WalkResult.joinReached(current);
            }
            if (!context.claim(current)) {
                return WalkResult.stop(InternalStatus.TRANSITION_LIMIT_REACHED, current);
            }
            ApprovalDefinition.ProcessNode node = context.nodes().get(current);
            if (node == null) {
                context.issues().add(new SimulationIssue(
                    "UNKNOWN_NODE",
                    current,
                    "simulation reached an unknown node"
                ));
                return WalkResult.stop(InternalStatus.BLOCKED, current);
            }
            if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
                WalkResult parallel = simulateParallel(split, context);
                if (parallel.status() != InternalStatus.CONTINUE) {
                    return parallel;
                }
                current = parallel.terminalNodeId();
                continue;
            }
            if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
                context.issues().add(new SimulationIssue(
                    "UNEXPECTED_PARALLEL_JOIN",
                    join.id(),
                    "parallel join was reached outside its owning split"
                ));
                context.addStep(join, "BLOCKED_UNEXPECTED_JOIN", null);
                return WalkResult.stop(InternalStatus.BLOCKED, join.id());
            }
            Transition transition = transition(node, context.scenario(), context.issues());
            context.addStep(node, transition.outcome(), transition.nextNodeId());
            if (transition.status() != null) {
                if (stopBeforeJoin != null && transition.status() == SimulationStatus.COMPLETED) {
                    context.issues().add(new SimulationIssue(
                        "PARALLEL_BRANCH_ESCAPED_JOIN",
                        node.id(),
                        "parallel branch completed before reaching join " + stopBeforeJoin
                    ));
                    return WalkResult.stop(InternalStatus.BLOCKED, node.id());
                }
                return WalkResult.stop(
                    InternalStatus.fromPublic(transition.status()),
                    node.id()
                );
            }
            current = transition.nextNodeId();
        }
    }

    private static WalkResult simulateParallel(
        ApprovalDefinition.ParallelSplitNode split,
        Context context
    ) {
        context.addStep(split, "PARALLEL_ENTER", split.joinNodeId());
        for (ApprovalDefinition.ParallelBranch branch : split.branches()) {
            context.addSyntheticStep(
                split.id(),
                split.name(),
                NodeKind.PARALLEL_SPLIT,
                "BRANCH_ENTER:" + branch.id(),
                branch.next()
            );
            WalkResult branchResult = walk(branch.next(), split.joinNodeId(), context);
            if (branchResult.status() != InternalStatus.JOIN_REACHED) {
                context.issues().add(new SimulationIssue(
                    "PARALLEL_BRANCH_BLOCKED",
                    branch.id(),
                    "parallel branch did not reach join " + split.joinNodeId()
                ));
                return branchResult;
            }
            context.addSyntheticStep(
                split.id(),
                split.name(),
                NodeKind.PARALLEL_SPLIT,
                "BRANCH_WAITING:" + branch.id(),
                split.joinNodeId()
            );
        }

        if (!context.claim(split.joinNodeId())) {
            return WalkResult.stop(
                InternalStatus.TRANSITION_LIMIT_REACHED,
                split.joinNodeId()
            );
        }
        ApprovalDefinition.ProcessNode target = context.nodes().get(split.joinNodeId());
        if (!(target instanceof ApprovalDefinition.ParallelJoinNode join)) {
            context.issues().add(new SimulationIssue(
                "MISSING_PARALLEL_JOIN",
                split.id(),
                "parallel split join target is not a parallel join"
            ));
            return WalkResult.stop(InternalStatus.BLOCKED, split.id());
        }
        context.addStep(join, "PARALLEL_JOINED", join.next());
        return WalkResult.continueAt(join.next());
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
            Decision decision = scenario.decisions().get(approval.id());
            if (decision == null) {
                issues.add(new SimulationIssue(
                    "MISSING_APPROVAL_DECISION",
                    approval.id(),
                    "scenario did not provide a decision for the approval node"
                ));
                return Transition.stop("BLOCKED", SimulationStatus.BLOCKED);
            }
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
        if (node instanceof ApprovalDefinition.ParallelSplitNode) {
            return NodeKind.PARALLEL_SPLIT;
        }
        if (node instanceof ApprovalDefinition.ParallelJoinNode) {
            return NodeKind.PARALLEL_JOIN;
        }
        return NodeKind.END;
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
        PARALLEL_SPLIT,
        PARALLEL_JOIN,
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

    private static final class Context {
        private final Map<String, ApprovalDefinition.ProcessNode> nodes;
        private final Scenario scenario;
        private final List<SimulationStep> steps = new ArrayList<>();
        private final List<SimulationIssue> issues = new ArrayList<>();
        private int transitions;

        private Context(
            Map<String, ApprovalDefinition.ProcessNode> nodes,
            Scenario scenario
        ) {
            this.nodes = nodes;
            this.scenario = scenario;
        }

        private boolean claim(String nodeId) {
            if (transitions >= scenario.maxTransitions()) {
                issues.add(new SimulationIssue(
                    "TRANSITION_LIMIT_REACHED",
                    nodeId,
                    "simulation exceeded the configured transition limit"
                ));
                return false;
            }
            transitions++;
            return true;
        }

        private void addStep(
            ApprovalDefinition.ProcessNode node,
            String outcome,
            String nextNodeId
        ) {
            addSyntheticStep(node.id(), node.name(), kind(node), outcome, nextNodeId);
        }

        private void addSyntheticStep(
            String nodeId,
            String nodeName,
            NodeKind nodeKind,
            String outcome,
            String nextNodeId
        ) {
            steps.add(new SimulationStep(
                steps.size() + 1,
                nodeId,
                nodeName,
                nodeKind,
                outcome,
                nextNodeId
            ));
        }

        private SimulationResult result(SimulationStatus status, String terminalNodeId) {
            return new SimulationResult(
                status,
                terminalNodeId,
                List.copyOf(steps),
                List.copyOf(issues)
            );
        }

        private Map<String, ApprovalDefinition.ProcessNode> nodes() {
            return nodes;
        }

        private Scenario scenario() {
            return scenario;
        }

        private List<SimulationIssue> issues() {
            return issues;
        }
    }

    private enum InternalStatus {
        CONTINUE(null),
        JOIN_REACHED(null),
        COMPLETED(SimulationStatus.COMPLETED),
        REJECTED(SimulationStatus.REJECTED),
        BLOCKED(SimulationStatus.BLOCKED),
        TRANSITION_LIMIT_REACHED(SimulationStatus.TRANSITION_LIMIT_REACHED);

        private final SimulationStatus publicStatus;

        InternalStatus(SimulationStatus publicStatus) {
            this.publicStatus = publicStatus;
        }

        private SimulationStatus publicStatus() {
            if (publicStatus == null) {
                throw new IllegalStateException("internal status has no public equivalent");
            }
            return publicStatus;
        }

        private static InternalStatus fromPublic(SimulationStatus status) {
            return switch (status) {
                case COMPLETED -> COMPLETED;
                case REJECTED -> REJECTED;
                case BLOCKED -> BLOCKED;
                case TRANSITION_LIMIT_REACHED -> TRANSITION_LIMIT_REACHED;
            };
        }
    }

    private record WalkResult(InternalStatus status, String terminalNodeId) {
        private static WalkResult continueAt(String nodeId) {
            return new WalkResult(InternalStatus.CONTINUE, nodeId);
        }

        private static WalkResult joinReached(String nodeId) {
            return new WalkResult(InternalStatus.JOIN_REACHED, nodeId);
        }

        private static WalkResult stop(InternalStatus status, String nodeId) {
            return new WalkResult(status, nodeId);
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
