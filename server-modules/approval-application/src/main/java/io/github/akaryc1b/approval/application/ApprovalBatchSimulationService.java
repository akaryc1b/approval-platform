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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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

    private static final String REPORT_SCHEMA_VERSION = "1.0";
    private static final String MASKED_VALUE = "[REDACTED]";
    private static final int MAX_SCENARIOS = 100;
    private static final int MAX_FORM_VALUES = 200;
    private static final int MAX_DECISIONS = 500;
    private static final int MAX_IDENTITY_VARIABLES = 200;
    private static final int MAX_IDENTITY_SNAPSHOTS = 1_000;
    private static final int MAX_EXPECTED_NODES = 1_000;
    private static final int MAX_TRANSITIONS = 1_000;
    private static final int MAX_VALUE_DEPTH = 8;
    private static final int MAX_VALUE_ELEMENTS = 2_000;
    private static final int MAX_VALUE_TEXT_LENGTH = 4_096;
    private static final int MAX_SCENARIO_INPUT_LENGTH = 65_536;
    private static final Comparator<ScenarioResult> RESULT_ORDER = Comparator
        .comparing(ScenarioResult::scenarioId);

    private final ApprovalDesignDraftStore drafts;
    private final ApprovalFormPackageResolver resolver;
    private final ApprovalDefinitionValidator validator;
    private final ApprovalDefinitionSimulator simulator;
    private final Clock clock;

    public ApprovalBatchSimulationService(
        ApprovalDesignDraftStore drafts,
        ApprovalFormPackageStore formPackages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ApprovalDefinitionValidator validator,
        ApprovalDefinitionSimulator simulator
    ) {
        this(
            drafts,
            formPackages,
            forms,
            uiSchemas,
            validator,
            simulator,
            Clock.systemUTC()
        );
    }

    public ApprovalBatchSimulationService(
        ApprovalDesignDraftStore drafts,
        ApprovalFormPackageStore formPackages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ApprovalDefinitionValidator validator,
        ApprovalDefinitionSimulator simulator,
        Clock clock
    ) {
        this.drafts = Objects.requireNonNull(drafts);
        this.resolver = new ApprovalFormPackageResolver(formPackages, forms, uiSchemas);
        this.validator = Objects.requireNonNull(validator);
        this.simulator = Objects.requireNonNull(simulator);
        this.clock = Objects.requireNonNull(clock);
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
        if (!draft.editable()) {
            throw new ApprovalDesignExceptions.DraftStateConflict(
                "Only editable Approval DSL drafts can be batch simulated"
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
            .map(scenario -> runScenario(
                draft.definition(),
                exact,
                scenario,
                command.formValueDisclosure()
            ))
            .sorted(RESULT_ORDER)
            .toList();
        CoverageReport coverage = coverage(draft.definition(), results);
        List<UncoveredItem> uncoveredItems = uncoveredItems(coverage);
        String reportHash = reportHash(draft, exact, command, results, coverage);
        return new BatchReport(
            REPORT_SCHEMA_VERSION,
            clock.instant(),
            command.tenantId(),
            command.draftId(),
            draft.revision(),
            draft.definitionKey(),
            draft.definition().version(),
            exact.formPackage().packageVersion(),
            exact.formPackage().packageHash(),
            exact.form().definition().version(),
            exact.form().contentHash(),
            exact.uiSchema().definition().version(),
            exact.uiSchema().contentHash(),
            results.size(),
            command.formValueDisclosure(),
            results,
            coverage,
            uncoveredItems,
            reportHash
        );
    }

    private ScenarioResult runScenario(
        ApprovalDefinition definition,
        ApprovalFormPackageResolver.ExactFormPackage exact,
        NamedScenario named,
        FormValueDisclosure disclosure
    ) {
        FormValueSummary formValueSummary = formValueSummary(named.formValues(), disclosure);
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
                "simulation failed before producing a path",
                formValueSummary.fieldNames(),
                formValueSummary.values()
            );
        }

        List<String> visited = result.steps().stream()
            .map(ApprovalDefinitionSimulator.SimulationStep::nodeId)
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
            pathSummary(result),
            formValueSummary.fieldNames(),
            formValueSummary.values()
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
        Set<String> startNodes = new TreeSet<>();
        Set<String> endNodes = new TreeSet<>();
        Set<String> approvalPasses = new TreeSet<>();
        Set<String> approvalRejects = new TreeSet<>();
        Set<String> conditionRoutes = new TreeSet<>();
        Set<String> defaultRoutes = new TreeSet<>();
        Set<String> parallelSplits = new TreeSet<>();
        Set<String> parallelBranches = new TreeSet<>();
        Set<String> parallelJoins = new TreeSet<>();
        Set<String> handleLoops = new TreeSet<>();
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (node instanceof ApprovalDefinition.StartNode start) {
                startNodes.add(start.id());
            } else if (node instanceof ApprovalDefinition.EndNode end) {
                endNodes.add(end.id());
            } else if (node instanceof ApprovalDefinition.ApprovalStep approval) {
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
                parallelSplits.add(split.id());
                split.branches().forEach(branch -> parallelBranches.add(
                    parallelBranchId(split.id(), branch.id())
                ));
            } else if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
                parallelJoins.add(join.id());
            } else if (node instanceof ApprovalDefinition.HandleStep handle) {
                handleLoops.add(handle.id());
            }
        }

        Set<String> coveredNodes = new TreeSet<>();
        Set<String> coveredStarts = new TreeSet<>();
        Set<String> coveredEnds = new TreeSet<>();
        Set<String> coveredPasses = new TreeSet<>();
        Set<String> coveredRejects = new TreeSet<>();
        Set<String> coveredConditions = new TreeSet<>();
        Set<String> coveredDefaults = new TreeSet<>();
        Set<String> coveredParallelSplits = new TreeSet<>();
        Set<String> coveredParallelBranches = new TreeSet<>();
        Set<String> coveredParallelJoins = new TreeSet<>();
        Set<String> coveredHandleLoops = new TreeSet<>();
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
            for (ApprovalDefinitionSimulator.SimulationStep step : scenario.steps()) {
                coveredNodes.add(step.nodeId());
                ApprovalDefinition.ProcessNode node = nodes.get(step.nodeId());
                if (node instanceof ApprovalDefinition.StartNode) {
                    coveredStarts.add(step.nodeId());
                } else if (node instanceof ApprovalDefinition.EndNode) {
                    coveredEnds.add(step.nodeId());
                } else if (node instanceof ApprovalDefinition.ApprovalStep) {
                    if ("APPROVED".equals(step.outcome())) {
                        coveredPasses.add(step.nodeId());
                    } else if ("REJECTED".equals(step.outcome())) {
                        coveredRejects.add(step.nodeId());
                    }
                } else if (node instanceof ApprovalDefinition.ConditionStep condition) {
                    if (step.outcome().startsWith("ROUTE_")) {
                        int index = routeIndex(step.outcome());
                        if (index >= 0 && index < condition.routes().size()) {
                            coveredConditions.add(conditionRouteId(condition, index));
                        }
                    } else if ("DEFAULT".equals(step.outcome())) {
                        coveredDefaults.add(defaultRouteId(condition));
                    }
                } else if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
                    coveredParallelSplits.add(step.nodeId());
                    if (step.outcome().startsWith("BRANCH_ENTER:")) {
                        String branchId = step.outcome().substring("BRANCH_ENTER:".length());
                        coveredParallelBranches.add(parallelBranchId(split.id(), branchId));
                    }
                } else if (node instanceof ApprovalDefinition.ParallelJoinNode) {
                    if ("PARALLEL_JOINED".equals(step.outcome())) {
                        coveredParallelJoins.add(step.nodeId());
                    }
                } else if (node instanceof ApprovalDefinition.HandleStep
                    && "HANDLED".equals(step.outcome())) {
                    coveredHandleLoops.add(step.nodeId());
                }
            }
        }

        CoverageMetric nodesMetric = metric(coveredNodes, allNodes);
        CoverageMetric startsMetric = metric(coveredStarts, startNodes);
        CoverageMetric endsMetric = metric(coveredEnds, endNodes);
        CoverageMetric passesMetric = metric(coveredPasses, approvalPasses);
        CoverageMetric rejectsMetric = metric(coveredRejects, approvalRejects);
        CoverageMetric conditionsMetric = metric(coveredConditions, conditionRoutes);
        CoverageMetric defaultsMetric = metric(coveredDefaults, defaultRoutes);
        CoverageMetric splitsMetric = metric(coveredParallelSplits, parallelSplits);
        CoverageMetric branchesMetric = metric(coveredParallelBranches, parallelBranches);
        CoverageMetric joinsMetric = metric(coveredParallelJoins, parallelJoins);
        CoverageMetric handlesMetric = metric(coveredHandleLoops, handleLoops);
        CoverageMetric decisionCoverage = combineMetrics(
            passesMetric,
            rejectsMetric,
            conditionsMetric,
            defaultsMetric
        );
        CoverageMetric structuralCoverage = combineMetrics(
            nodesMetric,
            startsMetric,
            endsMetric,
            splitsMetric,
            branchesMetric,
            joinsMetric
        );
        CoverageMetric criticalPathCoverage = combineMetrics(
            passesMetric,
            rejectsMetric,
            conditionsMetric,
            defaultsMetric,
            handlesMetric,
            endsMetric
        );

        return new CoverageReport(
            nodesMetric,
            startsMetric,
            endsMetric,
            passesMetric,
            rejectsMetric,
            conditionsMetric,
            defaultsMetric,
            splitsMetric,
            branchesMetric,
            joinsMetric,
            handlesMetric,
            criticalPathCoverage,
            decisionCoverage,
            structuralCoverage,
            difference(allNodes, coveredNodes),
            difference(startNodes, coveredStarts),
            difference(endNodes, coveredEnds),
            difference(approvalPasses, coveredPasses),
            difference(approvalRejects, coveredRejects),
            difference(conditionRoutes, coveredConditions),
            difference(defaultRoutes, coveredDefaults),
            difference(parallelSplits, coveredParallelSplits),
            difference(parallelBranches, coveredParallelBranches),
            difference(parallelJoins, coveredParallelJoins),
            difference(handleLoops, coveredHandleLoops),
            blockedScenarios.stream().sorted().toList(),
            transitionLimitScenarios.stream().sorted().toList(),
            !blockedScenarios.isEmpty(),
            !transitionLimitScenarios.isEmpty()
        );
    }

    private static int routeIndex(String outcome) {
        try {
            return Integer.parseInt(outcome.substring("ROUTE_".length())) - 1;
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

    private static CoverageMetric combineMetrics(CoverageMetric... metrics) {
        int covered = 0;
        int total = 0;
        for (CoverageMetric metric : metrics) {
            covered += metric.covered();
            total += metric.total();
        }
        return new CoverageMetric(covered, total, total == 0 ? 100 : covered * 100 / total);
    }

    private static List<UncoveredItem> uncoveredItems(CoverageReport coverage) {
        List<UncoveredItem> items = new ArrayList<>();
        addUncovered(items, "NODE", coverage.uncoveredNodeIds());
        addUncovered(items, "START", coverage.uncoveredStartNodeIds());
        addUncovered(items, "END", coverage.uncoveredEndNodeIds());
        addUncovered(items, "APPROVAL_APPROVE", coverage.uncoveredApprovalPassNodeIds());
        addUncovered(items, "APPROVAL_REJECT", coverage.uncoveredApprovalRejectNodeIds());
        addUncovered(items, "CONDITION_ROUTE", coverage.uncoveredConditionRouteIds());
        addUncovered(items, "CONDITION_DEFAULT", coverage.uncoveredDefaultRouteIds());
        addUncovered(items, "PARALLEL_SPLIT", coverage.uncoveredParallelSplitNodeIds());
        addUncovered(items, "PARALLEL_BRANCH", coverage.uncoveredParallelBranchIds());
        addUncovered(items, "PARALLEL_JOIN", coverage.uncoveredParallelJoinNodeIds());
        addUncovered(items, "HANDLE_LOOP", coverage.uncoveredHandleNodeIds());
        return items.stream()
            .sorted(Comparator.comparing(UncoveredItem::category)
                .thenComparing(UncoveredItem::stableId))
            .toList();
    }

    private static void addUncovered(
        List<UncoveredItem> items,
        String category,
        List<String> values
    ) {
        values.forEach(value -> items.add(new UncoveredItem(category, value)));
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
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String boundedText(String value, String name, int maxLength) {
        String normalized = text(value, name);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                name + " must not exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }

    private static String hash(String value, String name) {
        String normalized = text(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static Map<String, Object> normalizeFormValues(Map<String, Object> values) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (values == null) {
            return Map.of();
        }
        if (values.size() > MAX_FORM_VALUES) {
            throw new IllegalArgumentException(
                "formValues must not contain more than " + MAX_FORM_VALUES + " fields"
            );
        }
        InputBudget budget = new InputBudget();
        values.forEach((key, value) -> sorted.put(
            boundedText(key, "form value field", 200),
            normalizeValue(value, 1, budget)
        ));
        Map<String, Object> normalized = Collections.unmodifiableMap(
            new LinkedHashMap<>(sorted)
        );
        if (canonicalValue(normalized).length() > MAX_SCENARIO_INPUT_LENGTH) {
            throw new IllegalArgumentException(
                "formValues canonical input exceeds " + MAX_SCENARIO_INPUT_LENGTH + " characters"
            );
        }
        return normalized;
    }

    private static Object normalizeValue(Object value, int depth, InputBudget budget) {
        if (depth > MAX_VALUE_DEPTH) {
            throw new IllegalArgumentException(
                "formValues nesting depth must not exceed " + MAX_VALUE_DEPTH
            );
        }
        budget.claim();
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String string) {
            if (string.length() > MAX_VALUE_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                    "form value text must not exceed " + MAX_VALUE_TEXT_LENGTH + " characters"
                );
            }
            return string;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("form value number must be finite", exception);
            }
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> nested = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("form value object keys must be strings");
                }
                nested.put(
                    boundedText(key, "form value object key", 200),
                    normalizeValue(entry.getValue(), depth + 1, budget)
                );
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(nested));
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizeValue(item, depth + 1, budget));
            }
            return Collections.unmodifiableList(normalized);
        }
        throw new IllegalArgumentException(
            "form values support only JSON strings, numbers, booleans, arrays and objects"
        );
    }

    private static <T> Map<String, T> sortedMap(Map<String, T> values) {
        TreeMap<String, T> sorted = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> sorted.put(
                boundedText(key, "map key", 200),
                requireMapValue(value)
            ));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static <T> T requireMapValue(T value) {
        if (value == null) {
            throw new IllegalArgumentException("map value must not be null");
        }
        return value;
    }

    private static Map<String, List<StableIdentitySnapshot>> sortedIdentities(
        Map<String, List<StableIdentitySnapshot>> values
    ) {
        TreeMap<String, List<StableIdentitySnapshot>> sorted = new TreeMap<>();
        if (values != null) {
            if (values.size() > MAX_IDENTITY_VARIABLES) {
                throw new IllegalArgumentException(
                    "identitySnapshots must not contain more than "
                        + MAX_IDENTITY_VARIABLES
                        + " variables"
                );
            }
            int total = 0;
            for (Map.Entry<String, List<StableIdentitySnapshot>> entry : values.entrySet()) {
                List<StableIdentitySnapshot> snapshots = entry.getValue() == null
                    ? List.of()
                    : entry.getValue().stream()
                        .map(value -> Objects.requireNonNull(
                            value,
                            "identity snapshot must not be null"
                        ))
                        .sorted(Comparator.comparing(StableIdentitySnapshot::subjectId)
                            .thenComparing(StableIdentitySnapshot::subjectType)
                            .thenComparing(StableIdentitySnapshot::snapshotHash))
                        .toList();
                total += snapshots.size();
                sorted.put(
                    boundedText(entry.getKey(), "identity variable", 200),
                    snapshots
                );
            }
            if (total > MAX_IDENTITY_SNAPSHOTS) {
                throw new IllegalArgumentException(
                    "identitySnapshots must not contain more than "
                        + MAX_IDENTITY_SNAPSHOTS
                        + " snapshots"
                );
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static List<String> normalizedNodeIds(List<String> values, String name) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_EXPECTED_NODES) {
            throw new IllegalArgumentException(
                name + " must not contain more than " + MAX_EXPECTED_NODES + " nodes"
            );
        }
        TreeSet<String> normalized = new TreeSet<>();
        values.forEach(value -> normalized.add(boundedText(value, name, 200)));
        return List.copyOf(normalized);
    }

    private static FormValueSummary formValueSummary(
        Map<String, Object> values,
        FormValueDisclosure disclosure
    ) {
        List<String> fieldNames = List.copyOf(values.keySet());
        Map<String, Object> disclosed = switch (disclosure) {
            case FULL -> values;
            case FIELD_NAMES_ONLY -> Map.of();
            case MASKED -> {
                LinkedHashMap<String, Object> masked = new LinkedHashMap<>();
                fieldNames.forEach(field -> masked.put(field, MASKED_VALUE));
                yield Collections.unmodifiableMap(masked);
            }
        };
        return new FormValueSummary(fieldNames, disclosed);
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return "s" + string.length() + ':' + string;
        }
        if (value instanceof Boolean bool) {
            return "b:" + bool;
        }
        if (value instanceof Number number) {
            return "n:" + new BigDecimal(number.toString())
                .stripTrailingZeros()
                .toPlainString();
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            StringBuilder canonical = new StringBuilder("{");
            sorted.forEach((key, nested) -> canonical
                .append(canonicalValue(key))
                .append(canonicalValue(nested)));
            return canonical.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder canonical = new StringBuilder("[");
            list.forEach(item -> canonical.append(canonicalValue(item)));
            return canonical.append(']').toString();
        }
        throw new IllegalArgumentException("unsupported canonical value type");
    }

    private static String reportHash(
        ApprovalDesignDraft draft,
        ApprovalFormPackageResolver.ExactFormPackage exact,
        BatchCommand command,
        List<ScenarioResult> results,
        CoverageReport coverage
    ) {
        List<String> values = new ArrayList<>();
        Collections.addAll(
            values,
            REPORT_SCHEMA_VERSION,
            draft.tenantId(),
            draft.draftId().toString(),
            Long.toString(draft.revision()),
            draft.definitionKey(),
            Integer.toString(draft.definition().version()),
            Integer.toString(exact.formPackage().packageVersion()),
            exact.formPackage().packageHash(),
            Integer.toString(exact.form().definition().version()),
            exact.form().contentHash(),
            Integer.toString(exact.uiSchema().definition().version()),
            exact.uiSchema().contentHash(),
            command.formValueDisclosure().name()
        );
        draft.definition().nodes().stream()
            .sorted(Comparator.comparing(ApprovalDefinition.ProcessNode::id))
            .forEach(node -> values.add(
                "node:" + node.id() + ':' + node.getClass().getSimpleName()
            ));
        command.scenarios().stream()
            .sorted(Comparator.comparing(NamedScenario::scenarioId))
            .forEach(scenario -> {
                Collections.addAll(
                    values,
                    "scenario:" + scenario.scenarioId(),
                    scenario.name(),
                    canonicalValue(scenario.formValues()),
                    String.valueOf(scenario.expectedTerminalStatus()),
                    Integer.toString(scenario.maxTransitions())
                );
                scenario.approvalDecisions().forEach((key, value) -> values.add(
                    "decision:" + key + ':' + value.name()
                ));
                scenario.identitySnapshots().forEach((key, snapshots) -> snapshots.forEach(
                    snapshot -> values.add(
                        "identity:"
                            + key
                            + ':'
                            + snapshot.subjectId()
                            + ':'
                            + snapshot.subjectType()
                            + ':'
                            + snapshot.snapshotHash()
                    )
                ));
                scenario.expectedVisitedNodeIds().forEach(value -> values.add(
                    "expectedVisited:" + value
                ));
                scenario.expectedSkippedNodeIds().forEach(value -> values.add(
                    "expectedSkipped:" + value
                ));
            });
        results.forEach(result -> {
            Collections.addAll(
                values,
                "result:" + result.scenarioId(),
                result.runStatus().name(),
                String.valueOf(result.simulationStatus()),
                String.valueOf(result.terminalNodeId()),
                result.pathSummary()
            );
            result.steps().forEach(step -> values.add(
                "step:"
                    + step.sequence()
                    + ':'
                    + step.nodeId()
                    + ':'
                    + step.outcome()
                    + ':'
                    + String.valueOf(step.nextNodeId())
            ));
            result.issueCodes().forEach(value -> values.add("issue:" + value));
            result.expectationFailures().forEach(value -> values.add("expectation:" + value));
        });
        Collections.addAll(
            values,
            "critical:" + coverage.criticalPathCoverage().percentage(),
            "decision:" + coverage.decisionCoverage().percentage(),
            "structural:" + coverage.structuralCoverage().percentage()
        );
        uncoveredItems(coverage).forEach(item -> values.add(
            "uncovered:" + item.category() + ':' + item.stableId()
        ));
        return sha256(values.toArray(String[]::new));
    }

    private record FormValueSummary(List<String> fieldNames, Map<String, Object> values) {
    }

    private static final class InputBudget {
        private int elements;

        private void claim() {
            elements++;
            if (elements > MAX_VALUE_ELEMENTS) {
                throw new IllegalArgumentException(
                    "formValues must not contain more than "
                        + MAX_VALUE_ELEMENTS
                        + " nested values"
                );
            }
        }
    }

    public record BatchCommand(
        String tenantId,
        UUID draftId,
        long expectedRevision,
        List<NamedScenario> scenarios,
        FormValueDisclosure formValueDisclosure
    ) {
        public BatchCommand(
            String tenantId,
            UUID draftId,
            long expectedRevision,
            List<NamedScenario> scenarios
        ) {
            this(
                tenantId,
                draftId,
                expectedRevision,
                scenarios,
                FormValueDisclosure.MASKED
            );
        }

        public BatchCommand {
            tenantId = boundedText(tenantId, "tenantId", 200);
            if (draftId == null) {
                throw new IllegalArgumentException("draftId must not be null");
            }
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
            scenarios = scenarios == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(scenarios));
            if (scenarios.isEmpty() || scenarios.size() > MAX_SCENARIOS) {
                throw new IllegalArgumentException(
                    "scenarios must contain between 1 and " + MAX_SCENARIOS + " items"
                );
            }
            Set<String> ids = new LinkedHashSet<>();
            for (NamedScenario scenario : scenarios) {
                if (scenario == null) {
                    throw new IllegalArgumentException("scenario must not be null");
                }
                if (!ids.add(scenario.scenarioId())) {
                    throw new IllegalArgumentException(
                        "scenarioId must be unique: " + scenario.scenarioId()
                    );
                }
            }
            formValueDisclosure = formValueDisclosure == null
                ? FormValueDisclosure.MASKED
                : formValueDisclosure;
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
            scenarioId = boundedText(scenarioId, "scenarioId", 100);
            name = boundedText(name, "name", 200);
            formValues = normalizeFormValues(formValues);
            if (approvalDecisions != null
                && approvalDecisions.size() > MAX_DECISIONS) {
                throw new IllegalArgumentException(
                    "approvalDecisions must not contain more than "
                        + MAX_DECISIONS
                        + " entries"
                );
            }
            approvalDecisions = sortedMap(approvalDecisions);
            identitySnapshots = sortedIdentities(identitySnapshots);
            expectedVisitedNodeIds = normalizedNodeIds(
                expectedVisitedNodeIds,
                "expectedVisitedNodeIds"
            );
            expectedSkippedNodeIds = normalizedNodeIds(
                expectedSkippedNodeIds,
                "expectedSkippedNodeIds"
            );
            if (maxTransitions < 1 || maxTransitions > MAX_TRANSITIONS) {
                throw new IllegalArgumentException(
                    "maxTransitions must be between 1 and " + MAX_TRANSITIONS
                );
            }
        }
    }

    public enum FormValueDisclosure {
        FULL,
        FIELD_NAMES_ONLY,
        MASKED
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
            nodeId = boundedText(nodeId, "nodeId", 200);
            inputVariable = boundedText(inputVariable, "inputVariable", 200);
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
        List<ApprovalDefinitionSimulator.SimulationStep> steps,
        List<String> issueCodes,
        List<String> expectationFailures,
        List<IdentityResolution> identityResolutions,
        String pathSummary,
        List<String> formFieldNames,
        Map<String, Object> formValues
    ) {
        public ScenarioResult {
            scenarioId = boundedText(scenarioId, "scenarioId", 100);
            name = boundedText(name, "name", 200);
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
            pathSummary = boundedText(pathSummary, "pathSummary", MAX_SCENARIO_INPUT_LENGTH);
            formFieldNames = formFieldNames == null ? List.of() : List.copyOf(formFieldNames);
            formValues = formValues == null ? Map.of() : formValues;
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
        CoverageMetric startNodes,
        CoverageMetric endNodes,
        CoverageMetric approvalPassPaths,
        CoverageMetric approvalRejectPaths,
        CoverageMetric conditionRoutes,
        CoverageMetric defaultRoutes,
        CoverageMetric parallelSplits,
        CoverageMetric parallelBranches,
        CoverageMetric parallelJoins,
        CoverageMetric handleRevisionLoops,
        CoverageMetric criticalPathCoverage,
        CoverageMetric decisionCoverage,
        CoverageMetric structuralCoverage,
        List<String> uncoveredNodeIds,
        List<String> uncoveredStartNodeIds,
        List<String> uncoveredEndNodeIds,
        List<String> uncoveredApprovalPassNodeIds,
        List<String> uncoveredApprovalRejectNodeIds,
        List<String> uncoveredConditionRouteIds,
        List<String> uncoveredDefaultRouteIds,
        List<String> uncoveredParallelSplitNodeIds,
        List<String> uncoveredParallelBranchIds,
        List<String> uncoveredParallelJoinNodeIds,
        List<String> uncoveredHandleNodeIds,
        List<String> blockedScenarioIds,
        List<String> transitionLimitScenarioIds,
        boolean blockedObserved,
        boolean transitionLimitObserved
    ) {
        public CoverageReport {
            nodes = Objects.requireNonNull(nodes);
            startNodes = Objects.requireNonNull(startNodes);
            endNodes = Objects.requireNonNull(endNodes);
            approvalPassPaths = Objects.requireNonNull(approvalPassPaths);
            approvalRejectPaths = Objects.requireNonNull(approvalRejectPaths);
            conditionRoutes = Objects.requireNonNull(conditionRoutes);
            defaultRoutes = Objects.requireNonNull(defaultRoutes);
            parallelSplits = Objects.requireNonNull(parallelSplits);
            parallelBranches = Objects.requireNonNull(parallelBranches);
            parallelJoins = Objects.requireNonNull(parallelJoins);
            handleRevisionLoops = Objects.requireNonNull(handleRevisionLoops);
            criticalPathCoverage = Objects.requireNonNull(criticalPathCoverage);
            decisionCoverage = Objects.requireNonNull(decisionCoverage);
            structuralCoverage = Objects.requireNonNull(structuralCoverage);
            uncoveredNodeIds = copy(uncoveredNodeIds);
            uncoveredStartNodeIds = copy(uncoveredStartNodeIds);
            uncoveredEndNodeIds = copy(uncoveredEndNodeIds);
            uncoveredApprovalPassNodeIds = copy(uncoveredApprovalPassNodeIds);
            uncoveredApprovalRejectNodeIds = copy(uncoveredApprovalRejectNodeIds);
            uncoveredConditionRouteIds = copy(uncoveredConditionRouteIds);
            uncoveredDefaultRouteIds = copy(uncoveredDefaultRouteIds);
            uncoveredParallelSplitNodeIds = copy(uncoveredParallelSplitNodeIds);
            uncoveredParallelBranchIds = copy(uncoveredParallelBranchIds);
            uncoveredParallelJoinNodeIds = copy(uncoveredParallelJoinNodeIds);
            uncoveredHandleNodeIds = copy(uncoveredHandleNodeIds);
            blockedScenarioIds = copy(blockedScenarioIds);
            transitionLimitScenarioIds = copy(transitionLimitScenarioIds);
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    public record UncoveredItem(String category, String stableId) {
        public UncoveredItem {
            category = boundedText(category, "category", 100);
            stableId = boundedText(stableId, "stableId", 500);
        }
    }

    public record BatchReport(
        String schemaVersion,
        Instant generatedAt,
        String tenantId,
        UUID draftId,
        long draftRevision,
        String definitionKey,
        int definitionVersion,
        int formPackageVersion,
        String formPackageHash,
        int formSchemaVersion,
        String formSchemaHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        int scenarioCount,
        FormValueDisclosure formValueDisclosure,
        List<ScenarioResult> scenarioResults,
        CoverageReport coverage,
        List<UncoveredItem> uncoveredItems,
        String reportHash
    ) {
        public BatchReport {
            schemaVersion = boundedText(schemaVersion, "schemaVersion", 20);
            generatedAt = Objects.requireNonNull(generatedAt, "generatedAt must not be null");
            tenantId = boundedText(tenantId, "tenantId", 200);
            if (draftId == null) {
                throw new IllegalArgumentException("draftId must not be null");
            }
            if (draftRevision < 1
                || definitionVersion < 1
                || formPackageVersion < 1
                || formSchemaVersion < 1
                || uiSchemaVersion < 1) {
                throw new IllegalArgumentException("report versions must be positive");
            }
            definitionKey = boundedText(definitionKey, "definitionKey", 200);
            formPackageHash = hash(formPackageHash, "formPackageHash");
            formSchemaHash = hash(formSchemaHash, "formSchemaHash");
            uiSchemaHash = hash(uiSchemaHash, "uiSchemaHash");
            formValueDisclosure = Objects.requireNonNull(formValueDisclosure);
            scenarioResults = scenarioResults == null ? List.of() : List.copyOf(scenarioResults);
            if (scenarioCount != scenarioResults.size()) {
                throw new IllegalArgumentException("scenarioCount must match scenarioResults size");
            }
            coverage = Objects.requireNonNull(coverage, "coverage must not be null");
            uncoveredItems = uncoveredItems == null ? List.of() : List.copyOf(uncoveredItems);
            reportHash = hash(reportHash, "reportHash");
        }

        public long revision() {
            return draftRevision;
        }

        public List<ScenarioResult> scenarios() {
            return scenarioResults;
        }
    }
}
