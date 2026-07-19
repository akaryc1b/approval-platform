package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalDesignCommands.StableIdentitySnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/** Runs named Approval DSL scenarios without deploying or starting engine instances. */
public final class ApprovalBatchSimulationService {

    private static final int MAX_SCENARIOS = 100;
    private static final Comparator<ScenarioResult> RESULT_ORDER = Comparator
        .comparing(ScenarioResult::scenarioId);

    private final ApprovalDesignDraftStore drafts;
    private final ApprovalFormPackageResolver resolver;
    private final ApprovalDefinitionValidator validator;
    private final ApprovalDefinitionSimulator simulator;

    public ApprovalBatchSimulationService(
        ApprovalDesignDraftStore drafts,
        ApprovalFormPackageStore formPackages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ApprovalDefinitionValidator validator,
        ApprovalDefinitionSimulator simulator
    ) {
        this.drafts = Objects.requireNonNull(drafts);
        this.resolver = new ApprovalFormPackageResolver(formPackages, forms, uiSchemas);
        this.validator = Objects.requireNonNull(validator);
        this.simulator = Objects.requireNonNull(simulator);
    }

    public BatchReport simulate(BatchCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ApprovalDesignDraft draft = drafts.find(command.tenantId(), command.draftId())
            .orElseThrow(() -> new ApprovalDesignExceptions.DraftNotFound(
                "Approval DSL draft was not found for the tenant"
            ));
        if (draft.revision() != command.expectedRevision()) {
            throw new ApprovalDesignExceptions.DraftRevisionConflict(
                "Approval DSL draft revision changed before batch simulation"
            );
        }
        ApprovalFormPackageResolver.ExactFormPackage exact = resolver.resolve(draft);
        ApprovalDefinitionValidator.ValidationReport validation = validator.validate(
            draft.definition(),
            exact.form().definition(),
            exact.uiSchema().definition()
        );
        if (!validation.valid()) {
            throw new ApprovalDefinitionValidator.DefinitionValidationException(validation);
        }

        List<ScenarioResult> results = command.scenarios().stream()
            .map(scenario -> runScenario(draft.definition(), exact, scenario))
            .sorted(RESULT_ORDER)
            .toList();
        CoverageReport coverage = coverage(draft.definition(), results);
        return new BatchReport(
            command.tenantId(),
            command.draftId(),
            draft.revision(),
            draft.definitionKey(),
            draft.definition().version(),
            results,
            coverage
        );
    }

    private ScenarioResult runScenario(
        ApprovalDefinition definition,
        ApprovalFormPackageResolver.ExactFormPackage exact,
        NamedScenario named
    ) {
        ApprovalDefinitionSimulator.SimulationResult result;
        try {
            result = simulator.simulate(
                definition,
                exact.form().definition(),
                new ApprovalDefinitionSimulator.Scenario(
                    named.formValues(),
                    named.approvalDecisions(),
                    named.maxTransitions()
                )
            );
        } catch (RuntimeException exception) {
            return new ScenarioResult(
                named.scenarioId(),
                named.name(),
                ScenarioRunStatus.ERROR,
                null,
                null,
                List.of(),
                allNodeIds(definition),
                List.of(),
                List.of("SIMULATION_EXCEPTION"),
                List.of(safeMessage(exception)),
                identityResolutions(definition, named.identitySnapshots()),
                "simulation failed before producing a path"
            );
        }

        List<String> visited = result.steps().stream()
            .map(ApprovalDefinitionSimulator.Step::nodeId)
            .distinct()
            .sorted()
            .toList();
        Set<String> visitedSet = Set.copyOf(visited);
        List<String> skipped = allNodeIds(definition).stream()
            .filter(nodeId -> !visitedSet.contains(nodeId))
            .toList();
        List<String> expectationFailures = expectationFailures(
            definition,
            named,
            result,
            visitedSet
        );
        ScenarioRunStatus runStatus = runStatus(result.status(), expectationFailures);
        List<String> issueCodes = result.issues().stream()
            .map(ApprovalDefinitionSimulator.SimulationIssue::code)
            .distinct()
            .sorted()
            .toList();
        return new ScenarioResult(
            named.scenarioId(),
            named.name(),
            runStatus,
            result.status(),
            result.terminalNodeId(),
            visited,
            skipped,
            result.steps(),
            issueCodes,
            expectationFailures,
            identityResolutions(definition, named.identitySnapshots()),
            pathSummary(result)
        );
    }

    private static List<String> expectationFailures(
        ApprovalDefinition definition,
        NamedScenario named,
        ApprovalDefinitionSimulator.SimulationResult result,
        Set<String> visited
    ) {
        Set<String> allNodes = Set.copyOf(allNodeIds(definition));
        List<String> failures = new ArrayList<>();
        if (named.expectedTerminalStatus() != null
            && named.expectedTerminalStatus() != result.status()) {
            failures.add(
                "expected terminal status "
                    + named.expectedTerminalStatus()
                    + " but was "
                    + result.status()
            );
        }
        for (String nodeId : named.expectedVisitedNodeIds()) {
            if (!allNodes.contains(nodeId)) {
                failures.add("expected visited node does not exist: " + nodeId);
            } else if (!visited.contains(nodeId)) {
                failures.add("expected node was not visited: " + nodeId);
            }
        }
        for (String nodeId : named.expectedSkippedNodeIds()) {
            if (!allNodes.contains(nodeId)) {
                failures.add("expected skipped node does not exist: " + nodeId);
            } else if (visited.contains(nodeId)) {
                failures.add("expected skipped node was visited: " + nodeId);
            }
        }
        return failures.stream().sorted().toList();
    }

    private static ScenarioRunStatus runStatus(
        ApprovalDefinitionSimulator.SimulationStatus status,
        List<String> expectationFailures
    ) {
        if (status == ApprovalDefinitionSimulator.SimulationStatus.BLOCKED) {
            return ScenarioRunStatus.BLOCKED;
        }
        if (status
            == ApprovalDefinitionSimulator.SimulationStatus.TRANSITION_LIMIT_REACHED) {
            return ScenarioRunStatus.TRANSITION_LIMIT_REACHED;
        }
        return expectationFailures.isEmpty()
            ? ScenarioRunStatus.PASSED
            : ScenarioRunStatus.EXPECTATION_FAILED;
    }

    private static List<IdentityResolution> identityResolutions(
        ApprovalDefinition definition,
        Map<String, List<StableIdentitySnapshot>> identitySnapshots
    ) {
        List<IdentityResolution> resolutions = new ArrayList<>();
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            ApprovalDefinition.AssigneeRule rule = assigneeRule(node);
            if (rule == null) {
                continue;
            }
            List<StableIdentitySnapshot> values = identitySnapshots.getOrDefault(
                rule.variable(),
                List.of()
            );
            resolutions.add(new IdentityResolution(
                node.id(),
                rule.variable(),
                rule.resolver(),
                !values.isEmpty(),
                values.stream()
                    .map(StableIdentitySnapshot::subjectId)
                    .distinct()
                    .sorted()
                    .toList(),
                values.stream()
                    .map(StableIdentitySnapshot::snapshotHash)
                    .distinct()
                    .sorted()
                    .toList()
            ));
        }
        return resolutions.stream()
            .sorted(Comparator.comparing(IdentityResolution::nodeId))
            .toList();
    }

    private static ApprovalDefinition.AssigneeRule assigneeRule(
        ApprovalDefinition.ProcessNode node
    ) {
        if (node instanceof ApprovalDefinition.ApprovalStep approval) {
            return approval.assignee();
        }
        if (node instanceof ApprovalDefinition.HandleStep handle) {
            return handle.assignee();
        }
        return null;
    }

    private static CoverageReport coverage(
        ApprovalDefinition definition,
        List<ScenarioResult> results
    ) {
        Map<String, ApprovalDefinition.ProcessNode> nodes = definition.nodes().stream()
            .collect(Collectors.toMap(
                ApprovalDefinition.ProcessNode::id,
                value -> value,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        Set<String> allNodes = new TreeSet<>(nodes.keySet());
        Set<String> approvalPasses = new TreeSet<>();
        Set<String> approvalRejects = new TreeSet<>();
        Set<String> conditionRoutes = new TreeSet<>();
        Set<String> defaultRoutes = new TreeSet<>();
        Set<String> parallelBranches = new TreeSet<>();
        Set<String> handleLoops = new TreeSet<>();
        Set<String> endNodes = new TreeSet<>();
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (node instanceof ApprovalDefinition.ApprovalStep approval) {
                approvalPasses.add(approval.id());
                if (approval.rejectNext() != null) {
                    approvalRejects.add(approval.id());
                }
            } else if (node instanceof ApprovalDefinition.ConditionStep condition) {
                for (int index = 0; index < condition.routes().size(); index++) {
                    conditionRoutes.add(conditionRouteId(condition, index));
                }
                defaultRoutes.add(defaultRouteId(condition));
            } else if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
                split.branches().forEach(branch -> parallelBranches.add(
                    parallelBranchId(split.id(), branch.id())
                ));
            } else if (node instanceof ApprovalDefinition.HandleStep handle) {
                handleLoops.add(handle.id());
            } else if (node instanceof ApprovalDefinition.EndNode end) {
                endNodes.add(end.id());
            }
        }

        Set<String> coveredNodes = new TreeSet<>();
        Set<String> coveredPasses = new TreeSet<>();
        Set<String> coveredRejects = new TreeSet<>();
        Set<String> coveredConditions = new TreeSet<>();
        Set<String> coveredDefaults = new TreeSet<>();
        Set<String> coveredParallelBranches = new TreeSet<>();
        Set<String> coveredHandleLoops = new TreeSet<>();
        Set<String> coveredEnds = new TreeSet<>();
        List<String> blockedScenarios = new ArrayList<>();
        List<String> transitionLimitScenarios = new ArrayList<>();

        for (ScenarioResult scenario : results) {
            if (scenario.simulationStatus()
                == ApprovalDefinitionSimulator.SimulationStatus.BLOCKED) {
                blockedScenarios.add(scenario.scenarioId());
            }
            if (scenario.simulationStatus()
                == ApprovalDefinitionSimulator.SimulationStatus.TRANSITION_LIMIT_REACHED) {
                transitionLimitScenarios.add(scenario.scenarioId());
            }
            for (ApprovalDefinitionSimulator.Step step : scenario.steps()) {
                coveredNodes.add(step.nodeId());
                ApprovalDefinition.ProcessNode node = nodes.get(step.nodeId());
                if (node instanceof ApprovalDefinition.ApprovalStep) {
                    if ("APPROVED".equals(step.outcome())) {
                        coveredPasses.add(step.nodeId());
                    } else if ("REJECTED".equals(step.outcome())) {
                        coveredRejects.add(step.nodeId());
                    }
                } else if (node instanceof ApprovalDefinition.ConditionStep condition) {
                    if (step.outcome().startsWith("ROUTE:")) {
                        int index = routeIndex(step.outcome());
                        if (index >= 0 && index < condition.routes().size()) {
                            coveredConditions.add(conditionRouteId(condition, index));
                        }
                    } else if ("DEFAULT".equals(step.outcome())) {
                        coveredDefaults.add(defaultRouteId(condition));
                    }
                } else if (node instanceof ApprovalDefinition.ParallelSplitNode split
                    && step.outcome().startsWith("BRANCH_ENTER:")) {
                    String branchId = step.outcome().substring("BRANCH_ENTER:".length());
                    coveredParallelBranches.add(parallelBranchId(split.id(), branchId));
                } else if (node instanceof ApprovalDefinition.HandleStep
                    && "RESUBMITTED".equals(step.outcome())) {
                    coveredHandleLoops.add(step.nodeId());
                } else if (node instanceof ApprovalDefinition.EndNode) {
                    coveredEnds.add(step.nodeId());
                }
            }
        }

        return new CoverageReport(
            metric(coveredNodes, allNodes),
            metric(coveredPasses, approvalPasses),
            metric(coveredRejects, approvalRejects),
            metric(coveredConditions, conditionRoutes),
            metric(coveredDefaults, defaultRoutes),
            metric(coveredParallelBranches, parallelBranches),
            metric(coveredHandleLoops, handleLoops),
            metric(coveredEnds, endNodes),
            difference(allNodes, coveredNodes),
            difference(approvalPasses, coveredPasses),
            difference(approvalRejects, coveredRejects),
            difference(conditionRoutes, coveredConditions),
            difference(defaultRoutes, coveredDefaults),
            difference(parallelBranches, coveredParallelBranches),
            difference(handleLoops, coveredHandleLoops),
            difference(endNodes, coveredEnds),
            blockedScenarios.stream().sorted().toList(),
            transitionLimitScenarios.stream().sorted().toList()
        );
    }

    private static int routeIndex(String outcome) {
        try {
            return Integer.parseInt(outcome.substring("ROUTE:".length())) - 1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static CoverageMetric metric(Set<String> covered, Set<String> total) {
        int coveredCount = (int) covered.stream().filter(total::contains).count();
        int percentage = total.isEmpty() ? 100 : coveredCount * 100 / total.size();
        return new CoverageMetric(coveredCount, total.size(), percentage);
    }

    private static List<String> difference(Set<String> total, Set<String> covered) {
        return total.stream().filter(value -> !covered.contains(value)).toList();
    }

    private static String conditionRouteId(
        ApprovalDefinition.ConditionStep condition,
        int index
    ) {
        ApprovalDefinition.ConditionRoute route = condition.routes().get(index);
        ApprovalDefinition.ComparisonCondition comparison = route.condition();
        return condition.id()
            + ":route:"
            + sha256(
                comparison.field(),
                comparison.operator().name(),
                comparison.value().stripTrailingZeros().toPlainString(),
                route.next()
            );
    }

    private static String defaultRouteId(ApprovalDefinition.ConditionStep condition) {
        return condition.id() + ":default";
    }

    private static String parallelBranchId(String splitId, String branchId) {
        return splitId + ":branch:" + branchId;
    }

    private static String sha256(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<String> allNodeIds(ApprovalDefinition definition) {
        return definition.nodes().stream()
            .map(ApprovalDefinition.ProcessNode::id)
            .sorted()
            .toList();
    }

    private static String pathSummary(
        ApprovalDefinitionSimulator.SimulationResult result
    ) {
        if (result.steps().isEmpty()) {
            return "no transitions";
        }
        return result.steps().stream()
            .map(step -> step.nodeId() + '[' + step.outcome() + ']')
            .collect(Collectors.joining(" -> "));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "simulation failed" : message;
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static <T> Map<String, T> sortedMap(Map<String, T> values) {
        TreeMap<String, T> sorted = new TreeMap<>();
        if (values != null) {
            sorted.putAll(values);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Map<String, List<StableIdentitySnapshot>> sortedIdentities(
        Map<String, List<StableIdentitySnapshot>> values
    ) {
        TreeMap<String, List<StableIdentitySnapshot>> sorted = new TreeMap<>();
        if (values != null) {
            values.forEach((key, snapshots) -> sorted.put(
                text(key, "identity variable"),
                snapshots == null ? List.of() : List.copyOf(snapshots)
            ));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public record BatchCommand(
        String tenantId,
        UUID draftId,
        long expectedRevision,
        List<NamedScenario> scenarios
    ) {
        public BatchCommand {
            tenantId = text(tenantId, "tenantId");
            draftId = Objects.requireNonNull(draftId, "draftId must not be null");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
            if (scenarios.isEmpty() || scenarios.size() > MAX_SCENARIOS) {
                throw new IllegalArgumentException(
                    "scenarios must contain between 1 and " + MAX_SCENARIOS + " items"
                );
            }
            Set<String> ids = new LinkedHashSet<>();
            for (NamedScenario scenario : scenarios) {
                if (!ids.add(scenario.scenarioId())) {
                    throw new IllegalArgumentException(
                        "scenarioId must be unique: " + scenario.scenarioId()
                    );
                }
            }
        }
    }

    public record NamedScenario(
        String scenarioId,
        String name,
        Map<String, Object> formValues,
        Map<String, ApprovalDefinitionSimulator.Decision> approvalDecisions,
        Map<String, List<StableIdentitySnapshot>> identitySnapshots,
        ApprovalDefinitionSimulator.SimulationStatus expectedTerminalStatus,
        List<String> expectedVisitedNodeIds,
        List<String> expectedSkippedNodeIds,
        int maxTransitions
    ) {
        public NamedScenario {
            scenarioId = text(scenarioId, "scenarioId");
            name = text(name, "name");
            formValues = sortedMap(formValues);
            approvalDecisions = sortedMap(approvalDecisions);
            identitySnapshots = sortedIdentities(identitySnapshots);
            expectedVisitedNodeIds = expectedVisitedNodeIds == null
                ? List.of()
                : List.copyOf(new TreeSet<>(expectedVisitedNodeIds));
            expectedSkippedNodeIds = expectedSkippedNodeIds == null
                ? List.of()
                : List.copyOf(new TreeSet<>(expectedSkippedNodeIds));
            expectedVisitedNodeIds.forEach(value -> text(value, "expectedVisitedNodeId"));
            expectedSkippedNodeIds.forEach(value -> text(value, "expectedSkippedNodeId"));
            if (maxTransitions < 1 || maxTransitions > 10_000) {
                throw new IllegalArgumentException(
                    "maxTransitions must be between 1 and 10000"
                );
            }
        }
    }

    public enum ScenarioRunStatus {
        PASSED,
        EXPECTATION_FAILED,
        BLOCKED,
        TRANSITION_LIMIT_REACHED,
        ERROR
    }

    public record IdentityResolution(
        String nodeId,
        String inputVariable,
        ApprovalDefinition.AssigneeResolver resolver,
        boolean resolvable,
        List<String> subjectIds,
        List<String> snapshotHashes
    ) {
        public IdentityResolution {
            nodeId = text(nodeId, "nodeId");
            inputVariable = text(inputVariable, "inputVariable");
            resolver = Objects.requireNonNull(resolver, "resolver must not be null");
            subjectIds = subjectIds == null ? List.of() : List.copyOf(subjectIds);
            snapshotHashes = snapshotHashes == null ? List.of() : List.copyOf(snapshotHashes);
        }
    }

    public record ScenarioResult(
        String scenarioId,
        String name,
        ScenarioRunStatus runStatus,
        ApprovalDefinitionSimulator.SimulationStatus simulationStatus,
        String terminalNodeId,
        List<String> visitedNodeIds,
        List<String> skippedNodeIds,
        List<ApprovalDefinitionSimulator.Step> steps,
        List<String> issueCodes,
        List<String> expectationFailures,
        List<IdentityResolution> identityResolutions,
        String pathSummary
    ) {
        public ScenarioResult {
            scenarioId = text(scenarioId, "scenarioId");
            name = text(name, "name");
            runStatus = Objects.requireNonNull(runStatus, "runStatus must not be null");
            visitedNodeIds = visitedNodeIds == null ? List.of() : List.copyOf(visitedNodeIds);
            skippedNodeIds = skippedNodeIds == null ? List.of() : List.copyOf(skippedNodeIds);
            steps = steps == null ? List.of() : List.copyOf(steps);
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
            expectationFailures = expectationFailures == null
                ? List.of()
                : List.copyOf(expectationFailures);
            identityResolutions = identityResolutions == null
                ? List.of()
                : List.copyOf(identityResolutions);
            pathSummary = text(pathSummary, "pathSummary");
        }
    }

    public record CoverageMetric(int covered, int total, int percentage) {
        public CoverageMetric {
            if (covered < 0 || total < 0 || covered > total) {
                throw new IllegalArgumentException("invalid coverage counts");
            }
            if (percentage < 0 || percentage > 100) {
                throw new IllegalArgumentException("coverage percentage must be 0..100");
            }
        }
    }

    public record CoverageReport(
        CoverageMetric nodes,
        CoverageMetric approvalPassPaths,
        CoverageMetric approvalRejectPaths,
        CoverageMetric conditionRoutes,
        CoverageMetric defaultRoutes,
        CoverageMetric parallelBranches,
        CoverageMetric handleRevisionLoops,
        CoverageMetric endNodes,
        List<String> uncoveredNodeIds,
        List<String> uncoveredApprovalPassNodeIds,
        List<String> uncoveredApprovalRejectNodeIds,
        List<String> uncoveredConditionRouteIds,
        List<String> uncoveredDefaultRouteIds,
        List<String> uncoveredParallelBranchIds,
        List<String> uncoveredHandleNodeIds,
        List<String> uncoveredEndNodeIds,
        List<String> blockedScenarioIds,
        List<String> transitionLimitScenarioIds
    ) {
        public CoverageReport {
            nodes = Objects.requireNonNull(nodes);
            approvalPassPaths = Objects.requireNonNull(approvalPassPaths);
            approvalRejectPaths = Objects.requireNonNull(approvalRejectPaths);
            conditionRoutes = Objects.requireNonNull(conditionRoutes);
            defaultRoutes = Objects.requireNonNull(defaultRoutes);
            parallelBranches = Objects.requireNonNull(parallelBranches);
            handleRevisionLoops = Objects.requireNonNull(handleRevisionLoops);
            endNodes = Objects.requireNonNull(endNodes);
            uncoveredNodeIds = copy(uncoveredNodeIds);
            uncoveredApprovalPassNodeIds = copy(uncoveredApprovalPassNodeIds);
            uncoveredApprovalRejectNodeIds = copy(uncoveredApprovalRejectNodeIds);
            uncoveredConditionRouteIds = copy(uncoveredConditionRouteIds);
            uncoveredDefaultRouteIds = copy(uncoveredDefaultRouteIds);
            uncoveredParallelBranchIds = copy(uncoveredParallelBranchIds);
            uncoveredHandleNodeIds = copy(uncoveredHandleNodeIds);
            uncoveredEndNodeIds = copy(uncoveredEndNodeIds);
            blockedScenarioIds = copy(blockedScenarioIds);
            transitionLimitScenarioIds = copy(transitionLimitScenarioIds);
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    public record BatchReport(
        String tenantId,
        UUID draftId,
        long revision,
        String definitionKey,
        int definitionVersion,
        List<ScenarioResult> scenarios,
        CoverageReport coverage
    ) {
        public BatchReport {
            tenantId = text(tenantId, "tenantId");
            draftId = Objects.requireNonNull(draftId, "draftId must not be null");
            if (revision < 1 || definitionVersion < 1) {
                throw new IllegalArgumentException("report versions must be positive");
            }
            definitionKey = text(definitionKey, "definitionKey");
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
            coverage = Objects.requireNonNull(coverage, "coverage must not be null");
        }
    }
}
