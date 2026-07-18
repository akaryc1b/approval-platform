package io.github.akaryc1b.approval.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic hash protocol for deployable release packages and deployment manifests. */
public final class ApprovalReleasePackageHasher {

    public String artifactHash(String artifact) {
        return sha256(artifact);
    }

    public String deploymentMetadataHash(
        String compilerVersion,
        String resourceName,
        String bpmnHash
    ) {
        return hashValues("NOT_DEPLOYED", compilerVersion, resourceName, bpmnHash);
    }

    public String hash(
        String definitionKey,
        int releaseVersion,
        int definitionVersion,
        String definitionHash,
        int formPackageVersion,
        String formPackageHash,
        int formVersion,
        String formHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        String compilerVersion,
        String bpmnResourceName,
        String bpmnArtifact,
        String compiledArtifactHash,
        String bpmnHash,
        String dmnArtifact,
        String dmnHash,
        String deploymentMetadataHash
    ) {
        return hashValues(
            definitionKey,
            Integer.toString(releaseVersion),
            Integer.toString(definitionVersion),
            definitionHash,
            Integer.toString(formPackageVersion),
            formPackageHash,
            Integer.toString(formVersion),
            formHash,
            Integer.toString(uiSchemaVersion),
            uiSchemaHash,
            compilerVersion,
            bpmnResourceName,
            bpmnArtifact,
            compiledArtifactHash,
            bpmnHash,
            dmnArtifact,
            dmnHash,
            deploymentMetadataHash
        );
    }

    public String hashValues(Object... values) {
        StringBuilder canonical = new StringBuilder(8192);
        for (Object value : values) {
            String text = value == null ? null : value.toString();
            if (text == null) {
                canonical.append("-1:");
            } else {
                canonical.append(text.length()).append(':').append(text).append('|');
            }
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
