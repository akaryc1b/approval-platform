package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalDesignCommands.StableIdentitySnapshot;
import io.github.akaryc1b.approval.application.ApprovalDesignResults.AssigneeResolution;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ApprovalDraftSimulation {

    private final ApprovalDesignDraftStore drafts;
    private final ApprovalFormPackageResolver resolver;
    private final ApprovalDefinitionValidator validator;
    private final ApprovalDefinitionSimulator simulator;

    ApprovalDraftSimulation(
        ApprovalDesignDraftStore drafts,
        ApprovalFormPackageResolver resolver,
        ApprovalDefinitionValidator validator,
        ApprovalDefinitionSimulator simulator
    ) {
        this.drafts = Objects.requireNonNull(drafts);
        this.resolver = Objects.requireNonNull(resolver);
        this.validator = Objects.requireNonNull(validator);
        this.simulator = Objects.requireNonNull(simulator);
    }

    ApprovalDesignResults.Simulation simulate(ApprovalDesignCommands.Simulation command) {
        ApprovalDesignDraft draft = ApprovalDesignChecks.requireDraft(
            drafts,
            command.tenantId(),
            command.draftId()
        );
        ApprovalDesignChecks.requireRevision(draft, command.expectedRevision());
        var exact = resolver.resolve(draft);
        var validation = validator.validate(
            draft.definition(),
            exact.form().definition(),
            exact.uiSchema().definition()
        );
        List<AssigneeResolution> assignees = assigneeSummaries(
            draft.definition(),
            command.identityInputs()
        );
        if (!validation.valid()) {
            var issues = validation.errors().stream()
                .map(issue -> new ApprovalDefinitionSimulator.SimulationIssue(
                    "STATIC_" + issue.code(),
                    issue.subject(),
                    issue.message()
                ))
                .toList();
            var blocked = new ApprovalDefinitionSimulator.SimulationResult(
                ApprovalDefinitionSimulator.SimulationStatus.BLOCKED,
                draft.definition().startNodeId(),
                List.of(),
                issues
            );
            return new ApprovalDesignResults.Simulation(
                draft.draftId(),
                draft.revision(),
                validation.issues(),
                blocked,
                assignees,
                "Blocked by static validation"
            );
        }
        var result = simulator.simulate(
            draft.definition(),
            exact.form().definition(),
            command.scenario()
        );
        String path = result.steps().stream()
            .map(step -> step.nodeId() + '[' + step.outcome() + ']')
            .reduce((left, right) -> left + " -> " + right)
            .orElse("No nodes visited");
        return new ApprovalDesignResults.Simulation(
            draft.draftId(),
            draft.revision(),
            validation.issues(),
            result,
            assignees,
            path
        );
    }

    private static List<AssigneeResolution> assigneeSummaries(
        ApprovalDefinition definition,
        Map<String, List<StableIdentitySnapshot>> identityInputs
    ) {
        List<AssigneeResolution> summaries = new ArrayList<>();
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            ApprovalDefinition.AssigneeRule rule;
            String mode;
            if (node instanceof ApprovalDefinition.ApprovalStep approval) {
                rule = approval.assignee();
                mode = approval.mode().type().name();
            } else if (node instanceof ApprovalDefinition.HandleStep handle) {
                rule = handle.assignee();
                mode = "SINGLE";
            } else {
                continue;
            }
            List<StableIdentitySnapshot> snapshots = identityInputs.getOrDefault(
                rule.variable(),
                List.of()
            );
            boolean collection = rule.resolver()
                == ApprovalDefinition.AssigneeResolver.VARIABLE_USER_LIST;
            boolean resolvable = collection ? !snapshots.isEmpty() : snapshots.size() == 1;
            summaries.add(new AssigneeResolution(
                node.id(),
                rule.resolver(),
                rule.variable(),
                mode,
                resolvable,
                snapshots
            ));
        }
        return List.copyOf(summaries);
    }
}
