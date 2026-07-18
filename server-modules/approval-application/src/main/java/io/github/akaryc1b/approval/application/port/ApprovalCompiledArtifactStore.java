package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.definition.ApprovalCompiledArtifact;

import java.util.Optional;

/** Immutable compiled process artifact repository. */
public interface ApprovalCompiledArtifactStore {

    void lockArtifact(
        String tenantId,
        String definitionKey,
        int definitionVersion,
        String compilerVersion
    );

    Optional<ApprovalCompiledArtifact> find(
        String tenantId,
        String definitionKey,
        int definitionVersion,
        String compilerVersion
    );

    void save(ApprovalCompiledArtifact artifact);
}
