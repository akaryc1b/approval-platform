package io.github.akaryc1b.approval.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Produces a deterministic release identity for an exact Form/UI version pair. */
public final class FormPackageHasher {

    public String hash(
        String formKey,
        int packageVersion,
        int formVersion,
        String formHash,
        int uiSchemaVersion,
        String uiSchemaHash
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "form-package-v1");
            update(digest, formKey);
            update(digest, Integer.toString(packageVersion));
            update(digest, Integer.toString(formVersion));
            update(digest, formHash);
            update(digest, Integer.toString(uiSchemaVersion));
            update(digest, uiSchemaHash);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }
}
