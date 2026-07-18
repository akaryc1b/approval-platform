package io.github.akaryc1b.approval.compiler;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalNestedParallelDefinitionTest {

    private final ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
    private final ApprovalDslCompiler compiler = new ApprovalDslCompiler(validator);
    private final ApprovalDefinitionSimulator simulator = new ApprovalDefinitionSimulator(validator);
    private final FormDefinition form = PurchasePaymentTemplate.formDefinition();

    @Test
    void acceptsCompilesAndSimulatesNestedParallelSplits() {
        ApprovalDefinition definition = nestedDefinition();

        var report = validator.validate(definition, form);
        var first = compiler.compile(definition, form);
        var second = compiler.compile(definition, form);
        var result = simulator.simulate(
            definition,
            form,
            new ApprovalDefinitionSimulator.Scenario(
                Map.of(),
                Map.of(
                    "outerApproval",
                    ApprovalDefinitionSimulator.Decision.APPROVE,
                    "innerApprovalA",
                    ApprovalDefinitionSimulator.Decision.APPROVE,
                    "innerApprovalB",
                    ApprovalDefinitionSimulator.Decision.APPROVE
                ),
                100
            )
        );

        assertTrue(report.valid(), () -> report.issues().toString());
        assertEquals(first, second);
        assertTrue(first.bpmnXml().contains("<parallelGateway id=\"outerSplit\""));
        assertTrue(first.bpmnXml().contains("<parallelGateway id=\"innerSplit\""));
        assertEquals(ApprovalDefinitionSimulator.SimulationStatus.COMPLETED, result.status());
        assertEquals(
            2,
            result.steps().stream()
                .filter(step -> "PARALLEL_JOINED".equals(step.outcome()))
                .count()
        );
    }

    private static ApprovalDefinition nestedDefinition() {
        return new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            "Nested parallel purchase payment",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "outerSplit"),
                new ApprovalDefinition.ParallelSplitNode(
                    "outerSplit",
                    "Outer split",
                    List.of(
                        new ApprovalDefinition.ParallelBranch(
                            "outerDirect",
                            "Direct review",
                            "outerApproval"
                        ),
                        new ApprovalDefinition.ParallelBranch(
                            "outerNested",
                            "Nested review",
                            "innerSplit"
                        )
                    ),
                    "outerJoin"
                ),
                approval("outerApproval", "Outer approval", "outerJoin"),
                new ApprovalDefinition.ParallelSplitNode(
                    "innerSplit",
                    "Inner split",
                    List.of(
                        new ApprovalDefinition.ParallelBranch(
                            "innerA",
                            "Inner A",
                            "innerApprovalA"
                        ),
                        new ApprovalDefinition.ParallelBranch(
                            "innerB",
                            "Inner B",
                            "innerApprovalB"
                        )
                    ),
                    "innerJoin"
                ),
                approval("innerApprovalA", "Inner approval A", "innerJoin"),
                approval("innerApprovalB", "Inner approval B", "innerJoin"),
                new ApprovalDefinition.ParallelJoinNode(
                    "innerJoin",
                    "Inner join",
                    "outerJoin"
                ),
                new ApprovalDefinition.ParallelJoinNode(
                    "outerJoin",
                    "Outer join",
                    "end"
                ),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );
    }

    private static ApprovalDefinition.ApprovalStep approval(
        String id,
        String name,
        String next
    ) {
        return new ApprovalDefinition.ApprovalStep(
            id,
            name,
            new ApprovalDefinition.AssigneeRule(
                ApprovalDefinition.AssigneeResolver.VARIABLE_USER,
                id + "User",
                ApprovalDefinition.EmptyAssigneePolicy.FAIL
            ),
            ApprovalDefinition.ApprovalMode.single(),
            next
        );
    }
}
