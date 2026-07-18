package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalDesignCommands.StableIdentitySnapshot;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator.ValidationIssue;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Response protocol for validation, simulation and immutable release publication. */
public final class ApprovalDesignResults {

    private ApprovalDesignResults() {
    }

    public record Validation(
        UUID draftId,
        long revision,
        ApprovalDesignDraft.Status status,
        String definitionHash,
        List<ValidationIssue> issues
    ) {
        public Validation {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean valid() {
            return issues.stream().noneMatch(issue ->
                issue.severity() == ApprovalDefinitionValidator.Severity.ERROR
            );
        }
    }

    public record AssigneeResolution(
        String nodeId,
        ApprovalDefinition.AssigneeResolver resolver,
        String inputVariable,
        String approvalMode,
        boolean resolvable,
        List<StableIdentitySnapshot> identities
    ) {
        public AssigneeResolution {
            identities = identities == null ? List.of() : List.copyOf(identities);
        }
    }

    public record Simulation(
        UUID draftId,
        long revision,
        List<ValidationIssue> staticIssues,
        ApprovalDefinitionSimulator.SimulationResult simulation,
        List<AssigneeResolution> assigneeResolutions,
        String pathSummary
    ) {
        public Simulation {
            staticIssues = staticIssues == null ? List.of() : List.copyOf(staticIssues);
            simulation = Objects.requireNonNull(simulation);
            assigneeResolutions = assigneeResolutions == null
                ? List.of()
                : List.copyOf(assigneeResolutions);
            pathSummary = requireText(pathSummary, "pathSummary");
        }
    }

    public record Publish(
        ApprovalReleasePackage releasePackage,
        long draftRevision,
        boolean replayedExistingRelease
    ) {
        public Publish {
            releasePackage = Objects.requireNonNull(releasePackage);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
