package io.github.akaryc1b.approval.compiler;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalParallelDefinitionTest {

    private final ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
    private final ApprovalDslCompiler compiler = new ApprovalDslCompiler(validator);
    private final ApprovalDefinitionSimulator simulator = new ApprovalDefinitionSimulator(validator);
    private final FormDefinition form = PurchasePaymentTemplate.formDefinition();

    @Test
    void validatesCompilesAndSimulatesParallelConditionCombinationDeterministically() {
        ApprovalDefinition definition = definition("join");

        var report = validator.validate(definition, form);
        var first = compiler.compile(definition, form);
        var second = compiler.compile(definition, form);
        var result = simulator.simulate(
            definition,
            form,
            new ApprovalDefinitionSimulator.Scenario(
                Map.of("amount", new BigDecimal("12000")),
                Map.of(
                    "managerApproval",
                    ApprovalDefinitionSimulator.Decision.APPROVE,
                    "financeApproval",
                    ApprovalDefinitionSimulator.Decision.APPROVE
                ),
                100
            )
        );

        assertTrue(report.valid(), () -> report.issues().toString());
        assertEquals(first, second);
        assertTrue(first.bpmnXml().contains("<parallelGateway id=\"split\""));
        assertTrue(first.bpmnXml().contains("<parallelGateway id=\"join\""));
        assertTrue(first.bpmnXml().contains("flow_split_branch_managerBranch"));
        assertTrue(first.bpmnXml().contains("flow_split_branch_financeBranch"));
        assertEquals(ApprovalDefinitionSimulator.SimulationStatus.COMPLETED, result.status());
        assertTrue(result.steps().stream()
            .anyMatch(step -> "PARALLEL_JOINED".equals(step.outcome())));
    }

    @Test
    void rejectsParallelBranchThatDoesNotConvergeOnDeclaredJoin() {
        ApprovalDefinition definition = definition("otherJoin");

        var report = validator.validate(definition, form);

        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> "PARALLEL_BRANCH_MISSING_JOIN".equals(issue.code())));
    }

    private static ApprovalDefinition definition(String financeTarget) {
        List<ApprovalDefinition.ProcessNode> nodes = new ArrayList<>(List.of(
            new ApprovalDefinition.StartNode("start", "Start", "split"),
            new ApprovalDefinition.ParallelSplitNode(
                "split",
                "Parallel review",
                List.of(
                    new ApprovalDefinition.ParallelBranch(
                        "managerBranch",
                        "Manager",
                        "managerApproval"
                    ),
                    new ApprovalDefinition.ParallelBranch(
                        "financeBranch",
                        "Finance",
                        "amountRoute"
                    )
                ),
                "join"
            ),
            approval("managerApproval", "Manager approval", "join"),
            new ApprovalDefinition.ConditionStep(
                "amountRoute",
                "Amount route",
                List.of(new ApprovalDefinition.ConditionRoute(
                    new ApprovalDefinition.ComparisonCondition(
                        "amount",
                        ApprovalDefinition.ComparisonOperator.GREATER_THAN_OR_EQUAL,
                        new BigDecimal("10000")
                    ),
                    "financeApproval"
                )),
                financeTarget
            ),
            approval("financeApproval", "Finance approval", financeTarget),
            new ApprovalDefinition.ParallelJoinNode("join", "Join", "end")
        ));
        if (!"join".equals(financeTarget)) {
            nodes.add(new ApprovalDefinition.ParallelJoinNode(
                "otherJoin",
                "Other join",
                "end"
            ));
        }
        nodes.add(new ApprovalDefinition.EndNode("end", "End"));
        return new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            "Parallel purchase payment",
            "start",
            nodes
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
