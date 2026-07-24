package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Deterministic length-prefixed evidence hashing for immutable Form Package resolution. */
public final class ProcessTemplateFormPackageEvidenceHasher {

    public String hash(FormPackageEvidence evidence) {
        java.util.Objects.requireNonNull(evidence, "evidence");
        return hash(
            evidence.tenantId(),
            evidence.definitionKey(),
            evidence.packageVersion(),
            evidence.formVersion(),
            evidence.formHash(),
            evidence.uiSchemaVersion(),
            evidence.uiSchemaHash(),
            evidence.packageHash()
        );
    }

    public String hash(
        String tenantId,
        String definitionKey,
        int packageVersion,
        int formVersion,
        String formHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        String packageHash
    ) {
        return sha256(lengthPrefix(List.of(
            "process-template-form-package-evidence-v1",
            tenantId,
            definitionKey,
            Integer.toString(packageVersion),
            Integer.toString(formVersion),
            formHash,
            Integer.toString(uiSchemaVersion),
            uiSchemaHash,
            packageHash
        )));
    }

    public String governedPreviewHash(
        ProcessTemplateContracts.ImportPlan plan,
        FormPackageEvidence evidence
    ) {
        java.util.Objects.requireNonNull(plan, "plan");
        java.util.Objects.requireNonNull(evidence, "evidence");
        return sha256(lengthPrefix(List.of(
            "process-template-governed-preview-v1",
            plan.packageContentHash(),
            plan.registryEvidence().tenantId(),
            plan.registryEvidence().platformProtocolVersion(),
            plan.registryEvidence().contentHash(),
            plan.planHash(),
            plan.draftTarget().tenantId(),
            plan.draftTarget().definitionKey(),
            Integer.toString(plan.draftTarget().definitionVersion()),
            plan.draftTarget().draftName(),
            evidence.tenantId(),
            evidence.definitionKey(),
            Integer.toString(evidence.packageVersion()),
            Integer.toString(evidence.formVersion()),
            evidence.formHash(),
            Integer.toString(evidence.uiSchemaVersion()),
            evidence.uiSchemaHash(),
            evidence.packageHash(),
            evidence.contentHash()
        )));
    }

    private static String lengthPrefix(List<String> values) {
        StringBuilder canonical = new StringBuilder(2048);
        for (String value : values) {
            canonical.append(value.length()).append(':').append(value).append('|');
        }
        return canonical.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
