package io.github.akaryc1b.approval.compiler;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalDefinitionSimulatorTest {

    private final ApprovalDefinitionSimulator simulator = new ApprovalDefinitionSimulator();

    @Test
    void followsConditionRouteAndApprovalDecision() {
        ApprovalDefinitionSimulator.SimulationResult result = simulator.simulate(
            conditionalDefinition(),
            formDefinition(),
            new ApprovalDefinitionSimulator.Scenario(
                Map.of("amount", new BigDecimal("1500")),
                Map.of("manager", ApprovalDefinitionSimulator.Decision.APPROVE),
                20
            )
        );

        assertTrue(result.completed());
        assertEquals(ApprovalDefinitionSimulator.SimulationStatus.COMPLETED, result.status());
        assertEquals("end", result.terminalNodeId());
        assertEquals(
            List.of("start", "amountCheck", "manager", "end"),
            result.steps().stream()
                .map(ApprovalDefinitionSimulator.SimulationStep::nodeId)
                .toList()
        );
        assertEquals("ROUTE_1", result.steps().get(1).outcome());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void blocksWhenConditionScenarioValueIsMissing() {
        ApprovalDefinitionSimulator.SimulationResult result = simulator.simulate(
            conditionalDefinition(),
            formDefinition(),
            ApprovalDefinitionSimulator.Scenario.empty()
        );

        assertFalse(result.completed());
        assertEquals(ApprovalDefinitionSimulator.SimulationStatus.BLOCKED, result.status());
        assertEquals("amountCheck", result.terminalNodeId());
        assertEquals("MISSING_CONDITION_VALUE", result.issues().getFirst().code());
    }

    @Test
    void boundsControlledRevisionLoopsByTransitionLimit() {
        ApprovalDefinition definition = new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            "expense",
            1,
            "Expense approval",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "manager"),
                new ApprovalDefinition.ApprovalStep(
                    "manager",
                    "Manager approval",
                    managerRule(),
                    ApprovalDefinition.ApprovalMode.single(),
                    "end",
                    "revise"
                ),
                new ApprovalDefinition.HandleStep(
                    "revise",
                    "Initiator revision",
                    new ApprovalDefinition.AssigneeRule(
                        ApprovalDefinition.AssigneeResolver.VARIABLE_USER,
                        "initiatorId",
                        ApprovalDefinition.EmptyAssigneePolicy.FAIL
                    ),
                    "manager"
                ),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );

        ApprovalDefinitionSimulator.SimulationResult result = simulator.simulate(
            definition,
            formDefinition(),
            new ApprovalDefinitionSimulator.Scenario(
                Map.of(),
                Map.of("manager", ApprovalDefinitionSimulator.Decision.REJECT),
                5
            )
        );

        assertEquals(
            ApprovalDefinitionSimulator.SimulationStatus.TRANSITION_LIMIT_REACHED,
            result.status()
        );
        assertEquals(5, result.steps().size());
        assertEquals("TRANSITION_LIMIT_REACHED", result.issues().getFirst().code());
    }

    private static ApprovalDefinition conditionalDefinition() {
        return new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            "expense",
            1,
            "Expense approval",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "amountCheck"),
                new ApprovalDefinition.ConditionStep(
                    "amountCheck",
                    "Amount check",
                    List.of(new ApprovalDefinition.ConditionRoute(
                        new ApprovalDefinition.ComparisonCondition(
                            "amount",
                            ApprovalDefinition.ComparisonOperator.GREATER_THAN_OR_EQUAL,
                            new BigDecimal("1000")
                        ),
                        "manager"
                    )),
                    "end"
                ),
                new ApprovalDefinition.ApprovalStep(
                    "manager",
                    "Manager approval",
                    managerRule(),
                    ApprovalDefinition.ApprovalMode.single(),
                    "end"
                ),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );
    }

    private static ApprovalDefinition.AssigneeRule managerRule() {
        return new ApprovalDefinition.AssigneeRule(
            ApprovalDefinition.AssigneeResolver.VARIABLE_USER,
            "managerId",
            ApprovalDefinition.EmptyAssigneePolicy.FAIL
        );
    }

    private static FormDefinition formDefinition() {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "expense",
            1,
            "Expense form",
            List.of(new FormDefinition.FormField(
                "amount",
                FormDefinition.FieldType.MONEY,
                "Amount",
                true,
                FormDefinition.FieldConstraints.money(2, BigDecimal.ZERO),
                FormDefinition.DefaultValue.none()
            ))
        );
    }
}
