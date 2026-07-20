package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService.BatchCommand;
import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService.NamedScenario;
import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService.ScenarioRunStatus;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands.StableIdentitySnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalBatchSimulationServiceTest {

    private static final String TENANT = "tenant-batch";
    private static final UUID DRAFT_ID = new UUID(0, 301);
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final String FORM_HASH = "1".repeat(64);
    private static final String UI_HASH = "2".repeat(64);
    private static final String PACKAGE_HASH = "3".repeat(64);

    @Test
    void aggregatesNamedScenariosAndStablePathCoverage() {
        TestFixture fixture = fixture(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition(),
            PurchasePaymentTemplate.uiSchemaDefinition()
        );
        StableIdentitySnapshot manager = new StableIdentitySnapshot(
            "subject-manager",
            "USER",
            "4".repeat(64)
        );
        List<NamedScenario> scenarios = List.of(
            scenario(
                "high-value",
                new BigDecimal("20000"),
                Map.of(
                    "managerApproval", ApprovalDefinitionSimulator.Decision.APPROVE,
                    "financeReview", ApprovalDefinitionSimulator.Decision.APPROVE,
                    "financeCountersign", ApprovalDefinitionSimulator.Decision.APPROVE
                ),
                Map.of("managerAssignee", List.of(manager)),
                ApprovalDefinitionSimulator.SimulationStatus.COMPLETED,
                List.of("financeReview", "end"),
                List.of("initiatorRevision"),
                100
            ),
            scenario(
                "low-value",
                new BigDecimal("5000"),
                Map.of(
                    "managerApproval", ApprovalDefinitionSimulator.Decision.APPROVE,
                    "financeCountersign", ApprovalDefinitionSimulator.Decision.APPROVE
                ),
                Map.of(),
                ApprovalDefinitionSimulator.SimulationStatus.COMPLETED,
                List.of("amountCondition", "end"),
                List.of("financeReview", "initiatorRevision"),
                100
            ),
            scenario(
                "manager-reject-loop",
                new BigDecimal("5000"),
                Map.of("managerApproval", ApprovalDefinitionSimulator.Decision.REJECT),
                Map.of(),
                ApprovalDefinitionSimulator.SimulationStatus.TRANSITION_LIMIT_REACHED,
                List.of("initiatorRevision"),
                List.of("end"),
                8
            ),
            scenario(
                "blocked-countersign",
                new BigDecimal("5000"),
                Map.of("managerApproval", ApprovalDefinitionSimulator.Decision.APPROVE),
                Map.of(),
                ApprovalDefinitionSimulator.SimulationStatus.BLOCKED,
                List.of("financeCountersign"),
                List.of("end"),
                100
            )
        );

        var report = fixture.service().simulate(new BatchCommand(
            TENANT,
            DRAFT_ID,
            fixture.draft().revision(),
            scenarios
        ));

        assertEquals(
            List.of(
                "blocked-countersign",
                "high-value",
                "low-value",
                "manager-reject-loop"
            ),
            report.scenarios().stream().map(value -> value.scenarioId()).toList()
        );
        assertTrue(
            report.scenarios().stream().noneMatch(value ->
                value.runStatus() == ScenarioRunStatus.ERROR
            ),
            () -> "scenario errors: " + report.scenarios().stream()
                .filter(value -> value.runStatus() == ScenarioRunStatus.ERROR)
                .map(value -> value.scenarioId() + ": " + value.expectationFailures())
                .toList()
        );
        assertEquals(100, report.coverage().nodes().percentage());
        assertEquals(100, report.coverage().approvalPassPaths().percentage());
        assertEquals(100, report.coverage().conditionRoutes().percentage());
        assertEquals(100, report.coverage().defaultRoutes().percentage());
        assertEquals(100, report.coverage().handleRevisionLoops().percentage());
        assertEquals(100, report.coverage().endNodes().percentage());
        assertEquals(1, report.coverage().approvalRejectPaths().covered());
        assertTrue(report.coverage().blockedScenarioIds().contains("blocked-countersign"));
        assertTrue(
            report.coverage().transitionLimitScenarioIds().contains("manager-reject-loop")
        );
        var high = report.scenarios().stream()
            .filter(value -> value.scenarioId().equals("high-value"))
            .findFirst()
            .orElseThrow();
        assertEquals(ScenarioRunStatus.PASSED, high.runStatus());
        assertTrue(high.expectationFailures().isEmpty());
        assertTrue(high.pathSummary().contains("financeReview[APPROVED]"));
        assertTrue(high.identityResolutions().stream().anyMatch(value ->
            value.nodeId().equals("managerApproval") && value.resolvable()
        ));
    }

    @Test
    void reportsExpectationFailuresWithoutChangingTheSimulatedPath() {
        TestFixture fixture = fixture(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition(),
            PurchasePaymentTemplate.uiSchemaDefinition()
        );
        NamedScenario scenario = scenario(
            "wrong-expectation",
            new BigDecimal("5000"),
            Map.of(
                "managerApproval", ApprovalDefinitionSimulator.Decision.APPROVE,
                "financeCountersign", ApprovalDefinitionSimulator.Decision.APPROVE
            ),
            Map.of(),
            ApprovalDefinitionSimulator.SimulationStatus.COMPLETED,
            List.of("end"),
            List.of("end"),
            100
        );

        var report = fixture.service().simulate(new BatchCommand(
            TENANT,
            DRAFT_ID,
            fixture.draft().revision(),
            List.of(scenario)
        ));
        var result = report.scenarios().getFirst();

        assertEquals(ScenarioRunStatus.EXPECTATION_FAILED, result.runStatus());
        assertEquals(
            ApprovalDefinitionSimulator.SimulationStatus.COMPLETED,
            result.simulationStatus()
        );
        assertTrue(result.visitedNodeIds().contains("end"));
        assertTrue(result.expectationFailures().stream().anyMatch(value ->
            value.contains("expected skipped node was visited")
        ));
    }

    @Test
    void coversExplicitParallelBranchIdsWithoutUsingDisplayNames() {
        ApprovalDefinition definition = parallelDefinition();
        FormDefinition form = simpleForm("parallel-coverage");
        UiSchemaDefinition ui = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            definition.definitionKey(),
            form.version(),
            1,
            "Parallel coverage UI",
            List.of(),
            List.of()
        );
        TestFixture fixture = fixture(definition, form, ui);
        NamedScenario scenario = new NamedScenario(
            "parallel-all",
            "Run all parallel branches",
            Map.of(),
            Map.of(
                "approveA", ApprovalDefinitionSimulator.Decision.APPROVE,
                "approveB", ApprovalDefinitionSimulator.Decision.APPROVE
            ),
            Map.of(),
            ApprovalDefinitionSimulator.SimulationStatus.COMPLETED,
            List.of("approveA", "approveB", "join", "end"),
            List.of(),
            100
        );

        var report = fixture.service().simulate(new BatchCommand(
            TENANT,
            DRAFT_ID,
            fixture.draft().revision(),
            List.of(scenario)
        ));

        assertEquals(2, report.coverage().parallelBranches().total());
        assertEquals(2, report.coverage().parallelBranches().covered());
        assertEquals(100, report.coverage().parallelBranches().percentage());
        assertTrue(report.coverage().uncoveredParallelBranchIds().isEmpty());
        assertTrue(report.scenarios().getFirst().pathSummary().contains("BRANCH_ENTER:branchA"));
        assertTrue(report.scenarios().getFirst().pathSummary().contains("BRANCH_ENTER:branchB"));
    }

    @Test
    void rejectsStaleRevisionBeforeAnyScenarioRuns() {
        TestFixture fixture = fixture(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition(),
            PurchasePaymentTemplate.uiSchemaDefinition()
        );
        NamedScenario scenario = scenario(
            "stale",
            BigDecimal.ONE,
            Map.of(),
            Map.of(),
            null,
            List.of(),
            List.of(),
            10
        );

        var exception = org.junit.jupiter.api.Assertions.assertThrows(
            ApprovalDesignExceptions.DraftRevisionConflict.class,
            () -> fixture.service().simulate(new BatchCommand(
                TENANT,
                DRAFT_ID,
                fixture.draft().revision() - 1,
                List.of(scenario)
            ))
        );

        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    void rejectsInvalidBatchSizesDuplicateIdsAndOversizedInputs() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BatchCommand(TENANT, DRAFT_ID, 1, List.of())
        );

        List<NamedScenario> tooMany = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            tooMany.add(scenario(
                "scenario-" + index,
                BigDecimal.ONE,
                Map.of(),
                Map.of(),
                null,
                List.of(),
                List.of(),
                10
            ));
        }
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BatchCommand(TENANT, DRAFT_ID, 1, tooMany)
        );

        NamedScenario duplicate = scenario(
            "duplicate",
            BigDecimal.ONE,
            Map.of(),
            Map.of(),
            null,
            List.of(),
            List.of(),
            10
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BatchCommand(TENANT, DRAFT_ID, 1, List.of(duplicate, duplicate))
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new NamedScenario(
                "transition-limit",
                "transition-limit",
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                List.of(),
                List.of(),
                1_001
            )
        );

        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < 201; index++) {
            values.put("field" + index, index);
        }
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new NamedScenario(
                "too-many-values",
                "too-many-values",
                values,
                Map.of(),
                Map.of(),
                null,
                List.of(),
                List.of(),
                10
            )
        );

        List<Object> valuesWithNull = new ArrayList<>();
        valuesWithNull.add("present");
        valuesWithNull.add(null);
        NamedScenario nullableArray = new NamedScenario(
            "nullable-array",
            "nullable-array",
            Map.of("items", valuesWithNull),
            Map.of(),
            Map.of(),
            null,
            List.of(),
            List.of(),
            10
        );
        org.junit.jupiter.api.Assertions.assertNull(
            ((List<?>) nullableArray.formValues().get("items")).get(1)
        );

        List<NamedScenario> scenariosWithNull = new ArrayList<>();
        scenariosWithNull.add(null);
        IllegalArgumentException invalidScenario =
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new BatchCommand(TENANT, DRAFT_ID, 1, scenariosWithNull)
            );
        assertEquals("scenario must not be null", invalidScenario.getMessage());

        Map<String, ApprovalDefinitionSimulator.Decision> decisionsWithNull =
            new LinkedHashMap<>();
        decisionsWithNull.put("managerApproval", null);
        IllegalArgumentException invalidDecision =
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new NamedScenario(
                    "null-decision",
                    "null-decision",
                    Map.of(),
                    decisionsWithNull,
                    Map.of(),
                    null,
                    List.of(),
                    List.of(),
                    10
                )
            );
        assertEquals("map value must not be null", invalidDecision.getMessage());
    }

    @Test
    void producesStablePrivateReportsBoundToExactFormPackageIdentity() {
        TestFixture fixture = fixture(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition(),
            PurchasePaymentTemplate.uiSchemaDefinition(),
            ApprovalDesignDraft.Status.VALIDATED,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        NamedScenario scenario = scenario(
            "stable-report",
            new BigDecimal("10000"),
            Map.of(
                "managerApproval", ApprovalDefinitionSimulator.Decision.APPROVE,
                "financeReview", ApprovalDefinitionSimulator.Decision.APPROVE,
                "financeCountersign", ApprovalDefinitionSimulator.Decision.APPROVE
            ),
            Map.of(),
            ApprovalDefinitionSimulator.SimulationStatus.COMPLETED,
            List.of("end"),
            List.of("initiatorRevision"),
            100
        );
        BatchCommand command = new BatchCommand(
            TENANT,
            DRAFT_ID,
            fixture.draft().revision(),
            List.of(scenario)
        );

        var first = fixture.service().simulate(command);
        var second = fixture.service().simulate(command);

        assertEquals(first.reportHash(), second.reportHash());
        assertEquals("1.0", first.schemaVersion());
        assertEquals(NOW, first.generatedAt());
        assertEquals(1, first.formPackageVersion());
        assertEquals(PACKAGE_HASH, first.formPackageHash());
        assertEquals(FORM_HASH, first.formSchemaHash());
        assertEquals(UI_HASH, first.uiSchemaHash());
        assertEquals(1, first.scenarioCount());
        assertEquals(
            ApprovalBatchSimulationService.FormValueDisclosure.MASKED,
            first.formValueDisclosure()
        );
        assertEquals(
            "[REDACTED]",
            first.scenarioResults().getFirst().formValues().get("amount")
        );
        assertTrue(first.scenarioResults().getFirst().formFieldNames().contains("amount"));
        assertEquals(100, first.coverage().startNodes().percentage());
        assertTrue(first.coverage().criticalPathCoverage().percentage() > 0);
    }

    @Test
    void supportsExplicitFullAndFieldNameOnlyReportDisclosure() {
        TestFixture fixture = fixture(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition(),
            PurchasePaymentTemplate.uiSchemaDefinition()
        );
        NamedScenario scenario = scenario(
            "disclosure",
            new BigDecimal("5000"),
            Map.of(
                "managerApproval", ApprovalDefinitionSimulator.Decision.APPROVE,
                "financeCountersign", ApprovalDefinitionSimulator.Decision.APPROVE
            ),
            Map.of(),
            ApprovalDefinitionSimulator.SimulationStatus.COMPLETED,
            List.of("end"),
            List.of(),
            100
        );

        var full = fixture.service().simulate(new BatchCommand(
            TENANT,
            DRAFT_ID,
            fixture.draft().revision(),
            List.of(scenario),
            ApprovalBatchSimulationService.FormValueDisclosure.FULL
        ));
        var names = fixture.service().simulate(new BatchCommand(
            TENANT,
            DRAFT_ID,
            fixture.draft().revision(),
            List.of(scenario),
            ApprovalBatchSimulationService.FormValueDisclosure.FIELD_NAMES_ONLY
        ));

        assertEquals(new BigDecimal("5000"), full.scenarioResults().getFirst()
            .formValues().get("amount"));
        assertTrue(names.scenarioResults().getFirst().formValues().isEmpty());
        assertEquals(List.of("amount"), names.scenarioResults().getFirst().formFieldNames());
    }

    @Test
    void rejectsCrossTenantAndNonEditableDraftsBeforeSimulation() {
        TestFixture editable = fixture(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition(),
            PurchasePaymentTemplate.uiSchemaDefinition()
        );
        NamedScenario scenario = scenario(
            "tenant-boundary",
            BigDecimal.ONE,
            Map.of(),
            Map.of(),
            null,
            List.of(),
            List.of(),
            10
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            ApprovalDesignExceptions.DraftNotFound.class,
            () -> editable.service().simulate(new BatchCommand(
                "another-tenant",
                DRAFT_ID,
                editable.draft().revision(),
                List.of(scenario)
            ))
        );

        TestFixture archived = fixture(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition(),
            PurchasePaymentTemplate.uiSchemaDefinition(),
            ApprovalDesignDraft.Status.ARCHIVED,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            ApprovalDesignExceptions.DraftStateConflict.class,
            () -> archived.service().simulate(new BatchCommand(
                TENANT,
                DRAFT_ID,
                archived.draft().revision(),
                List.of(scenario)
            ))
        );
    }

    private static NamedScenario scenario(
        String id,
        BigDecimal amount,
        Map<String, ApprovalDefinitionSimulator.Decision> decisions,
        Map<String, List<StableIdentitySnapshot>> identities,
        ApprovalDefinitionSimulator.SimulationStatus expectedStatus,
        List<String> expectedVisited,
        List<String> expectedSkipped,
        int maxTransitions
    ) {
        return new NamedScenario(
            id,
            id,
            Map.of("amount", amount),
            decisions,
            identities,
            expectedStatus,
            expectedVisited,
            expectedSkipped,
            maxTransitions
        );
    }

    private static TestFixture fixture(
        ApprovalDefinition definition,
        FormDefinition form,
        UiSchemaDefinition ui
    ) {
        return fixture(
            definition,
            form,
            ui,
            ApprovalDesignDraft.Status.VALIDATED,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static TestFixture fixture(
        ApprovalDefinition definition,
        FormDefinition form,
        UiSchemaDefinition ui,
        ApprovalDesignDraft.Status status,
        Clock clock
    ) {
        FormPackage formPackage = new FormPackage(
            TENANT,
            definition.definitionKey(),
            1,
            form.version(),
            FORM_HASH,
            ui.version(),
            UI_HASH,
            PACKAGE_HASH,
            new UUID(0, 300),
            "publisher",
            NOW
        );
        ApprovalDesignDraft draft = new ApprovalDesignDraft(
            DRAFT_ID,
            TENANT,
            definition.definitionKey(),
            "Batch simulation draft",
            definition,
            new ApprovalDesignDraft.FormPackageReference(
                definition.definitionKey(),
                1,
                PACKAGE_HASH
            ),
            null,
            5,
            status,
            null,
            null,
            "designer",
            "designer",
            NOW,
            NOW
        );
        ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
        ApprovalBatchSimulationService service = new ApprovalBatchSimulationService(
            new SingleDraftStore(draft),
            new SingleFormPackageStore(formPackage),
            new SingleFormStore(form),
            new SingleUiStore(ui),
            validator,
            new ApprovalDefinitionSimulator(validator),
            clock
        );
        return new TestFixture(draft, service);
    }

    private static ApprovalDefinition parallelDefinition() {
        ApprovalDefinition.AssigneeRule single = new ApprovalDefinition.AssigneeRule(
            ApprovalDefinition.AssigneeResolver.VARIABLE_USER,
            "approver",
            ApprovalDefinition.EmptyAssigneePolicy.FAIL
        );
        return new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            "parallel-coverage",
            1,
            "Parallel coverage",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "split"),
                new ApprovalDefinition.ParallelSplitNode(
                    "split",
                    "Parallel split",
                    List.of(
                        new ApprovalDefinition.ParallelBranch(
                            "branchA",
                            "Branch A",
                            "approveA"
                        ),
                        new ApprovalDefinition.ParallelBranch(
                            "branchB",
                            "Branch B",
                            "approveB"
                        )
                    ),
                    "join"
                ),
                new ApprovalDefinition.ApprovalStep(
                    "approveA",
                    "Approval A",
                    single,
                    ApprovalDefinition.ApprovalMode.single(),
                    "join"
                ),
                new ApprovalDefinition.ApprovalStep(
                    "approveB",
                    "Approval B",
                    single,
                    ApprovalDefinition.ApprovalMode.single(),
                    "join"
                ),
                new ApprovalDefinition.ParallelJoinNode(
                    "join",
                    "Parallel join",
                    "end"
                ),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );
    }

    private static FormDefinition simpleForm(String key) {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            key,
            1,
            "Simple form",
            List.of(new FormDefinition.FormField(
                "amount",
                FormDefinition.FieldType.MONEY,
                "Amount",
                false,
                FormDefinition.FieldConstraints.money(2, BigDecimal.ZERO)
            ))
        );
    }

    private record TestFixture(
        ApprovalDesignDraft draft,
        ApprovalBatchSimulationService service
    ) {
    }

    private record SingleDraftStore(ApprovalDesignDraft draft)
        implements ApprovalDesignDraftStore {

        @Override
        public Optional<ApprovalDesignDraft> find(String tenantId, UUID draftId) {
            return draft.tenantId().equals(tenantId) && draft.draftId().equals(draftId)
                ? Optional.of(draft)
                : Optional.empty();
        }

        @Override
        public void save(ApprovalDesignDraft value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DraftPage findDrafts(DraftCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lock(String tenantId, UUID draftId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(ApprovalDesignDraft value, long expectedRevision) {
            throw new UnsupportedOperationException();
        }
    }

    private record SingleFormPackageStore(FormPackage formPackage)
        implements ApprovalFormPackageStore {

        @Override
        public Optional<FormPackage> find(String tenantId, String formKey, int packageVersion) {
            return formPackage.tenantId().equals(tenantId)
                && formPackage.formKey().equals(formKey)
                && formPackage.packageVersion() == packageVersion
                ? Optional.of(formPackage)
                : Optional.empty();
        }

        @Override
        public void lockVersion(String tenantId, String formKey, int packageVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<FormPackage> findByDraft(String tenantId, UUID draftId) {
            return Optional.empty();
        }

        @Override
        public void save(FormPackage value) {
            throw new UnsupportedOperationException();
        }
    }

    private record SingleFormStore(FormDefinition form) implements ApprovalFormStore {

        @Override
        public Optional<PublishedForm> find(String tenantId, String formKey, int version) {
            return form.formKey().equals(formKey) && form.version() == version
                ? Optional.of(new PublishedForm(TENANT, form, FORM_HASH, "publisher", NOW))
                : Optional.empty();
        }

        @Override
        public void lockVersion(String tenantId, String formKey, int version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(PublishedForm value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FormPage findForms(FormCriteria criteria) {
            throw new UnsupportedOperationException();
        }
    }

    private record SingleUiStore(UiSchemaDefinition ui) implements ApprovalUiSchemaStore {

        @Override
        public Optional<PublishedUiSchema> find(
            String tenantId,
            String formKey,
            int formVersion,
            int uiSchemaVersion
        ) {
            return ui.formKey().equals(formKey)
                && ui.formVersion() == formVersion
                && ui.version() == uiSchemaVersion
                ? Optional.of(new PublishedUiSchema(TENANT, ui, UI_HASH, "publisher", NOW))
                : Optional.empty();
        }

        @Override
        public Optional<PublishedUiSchema> findLatest(
            String tenantId,
            String formKey,
            int formVersion
        ) {
            return find(tenantId, formKey, formVersion, ui.version());
        }

        @Override
        public void lockVersion(
            String tenantId,
            String formKey,
            int formVersion,
            int uiSchemaVersion
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(PublishedUiSchema value) {
            throw new UnsupportedOperationException();
        }
    }
}
