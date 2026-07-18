package io.github.akaryc1b.approval.domain.definition;

import java.time.Instant;
import java.util.Objects;

/** Immutable compiler output bound to one exact Approval DSL and Form Schema. */
public record ApprovalCompiledArtifact(
    String tenantId,
    String definitionKey,
    int definitionVersion,
    String definitionHash,
    int formVersion,
    String formHash,
    String compilerVersion,
    String resourceName,
    String bpmnXml,
    String compiledArtifactHash,
    String bpmnHash,
    Instant createdAt
) {
    public ApprovalCompiledArtifact {
        tenantId = requireText(tenantId, "tenantId");
        definitionKey = requireText(definitionKey, "definitionKey");
        if (definitionVersion < 1 || formVersion < 1) {
            throw new IllegalArgumentException("definition and form versions must be positive");
        }
        definitionHash = requireHash(definitionHash, "definitionHash");
        formHash = requireHash(formHash, "formHash");
        compilerVersion = requireText(compilerVersion, "compilerVersion");
        resourceName = requireText(resourceName, "resourceName");
        bpmnXml = requireText(bpmnXml, "bpmnXml");
        compiledArtifactHash = requireHash(compiledArtifactHash, "compiledArtifactHash");
        bpmnHash = requireHash(bpmnHash, "bpmnHash");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }
}
