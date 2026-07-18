package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Validated command protocol for Approval DSL design operations. */
public final class ApprovalDesignCommands {

    private ApprovalDesignCommands() {
    }

    public enum SaveMode {
        EXPLICIT,
        AUTO_SAVE
    }

    public record Create(
        RequestContext context,
        String definitionKey,
        String name,
        int definitionVersion,
        int formPackageVersion
    ) {
        public Create {
            context = Objects.requireNonNull(context);
            definitionKey = text(definitionKey, "definitionKey");
            name = text(name, "name");
            positive(definitionVersion, "definitionVersion");
            positive(formPackageVersion, "formPackageVersion");
        }
    }

    public record CopyPublished(
        RequestContext context,
        String definitionKey,
        int sourceDefinitionVersion,
        int targetDefinitionVersion,
        int formPackageVersion,
        String name
    ) {
        public CopyPublished {
            context = Objects.requireNonNull(context);
            definitionKey = text(definitionKey, "definitionKey");
            positive(sourceDefinitionVersion, "sourceDefinitionVersion");
            positive(targetDefinitionVersion, "targetDefinitionVersion");
            positive(formPackageVersion, "formPackageVersion");
            name = text(name, "name");
        }
    }

    public record Update(
        RequestContext context,
        UUID draftId,
        long expectedRevision,
        String name,
        ApprovalDefinition definition,
        int formPackageVersion,
        SaveMode saveMode
    ) {
        public Update {
            context = Objects.requireNonNull(context);
            draftId = Objects.requireNonNull(draftId);
            positive(expectedRevision, "expectedRevision");
            name = text(name, "name");
            definition = Objects.requireNonNull(definition);
            positive(formPackageVersion, "formPackageVersion");
            saveMode = saveMode == null ? SaveMode.EXPLICIT : saveMode;
        }
    }

    public record Revision(
        RequestContext context,
        UUID draftId,
        long expectedRevision
    ) {
        public Revision {
            context = Objects.requireNonNull(context);
            draftId = Objects.requireNonNull(draftId);
            positive(expectedRevision, "expectedRevision");
        }
    }

    public record Simulation(
        String tenantId,
        UUID draftId,
        long expectedRevision,
        ApprovalDefinitionSimulator.Scenario scenario,
        Map<String, List<StableIdentitySnapshot>> identityInputs
    ) {
        public Simulation {
            tenantId = text(tenantId, "tenantId");
            draftId = Objects.requireNonNull(draftId);
            positive(expectedRevision, "expectedRevision");
            scenario = scenario == null
                ? ApprovalDefinitionSimulator.Scenario.empty()
                : scenario;
            Map<String, List<StableIdentitySnapshot>> copied = new LinkedHashMap<>();
            if (identityInputs != null) {
                identityInputs.forEach((key, value) -> copied.put(
                    text(key, "identity input variable"),
                    value == null ? List.of() : List.copyOf(value)
                ));
            }
            identityInputs = Map.copyOf(copied);
        }
    }

    public record Publish(
        RequestContext context,
        UUID draftId,
        long expectedRevision,
        int definitionVersion,
        int releaseVersion
    ) {
        public Publish {
            context = Objects.requireNonNull(context);
            draftId = Objects.requireNonNull(draftId);
            positive(expectedRevision, "expectedRevision");
            positive(definitionVersion, "definitionVersion");
            positive(releaseVersion, "releaseVersion");
        }
    }

    public record StableIdentitySnapshot(
        String subjectId,
        String subjectType,
        String snapshotHash
    ) {
        public StableIdentitySnapshot {
            subjectId = text(subjectId, "subjectId");
            subjectType = text(subjectType, "subjectType");
            snapshotHash = hash(snapshotHash, "snapshotHash");
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
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

    private static void positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
