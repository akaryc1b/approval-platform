package io.github.akaryc1b.approval.connector.credential;

import java.io.Serial;

import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver.SecretBytesUse;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;

public interface CredentialMaterialSource {

    MaterialScope openMaterial(
        CredentialReference reference,
        String expectedKeyId,
        String expectedVersionId
    );

    interface MaterialScope extends AutoCloseable {

        String keyId();

        String versionId();

        String sourceEvidenceHash();

        void useSecretBytes(SecretBytesUse use);

        boolean active();

        @Override
        void close();
    }

    final class SourceUnavailableException extends IllegalStateException {

        @Serial
        private static final long serialVersionUID = 1L;

        public SourceUnavailableException() {
            super("credential material source is unavailable");
        }
    }

    final class MaterialInvalidException extends IllegalStateException {

        @Serial
        private static final long serialVersionUID = 1L;

        public MaterialInvalidException() {
            super("credential material is invalid");
        }
    }
}
