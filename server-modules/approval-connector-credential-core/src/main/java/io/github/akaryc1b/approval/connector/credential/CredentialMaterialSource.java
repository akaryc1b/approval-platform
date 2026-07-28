package io.github.akaryc1b.approval.connector.credential;

import java.io.Serial;
import java.util.Objects;

import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver.SecretBytesUse;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;

public interface CredentialMaterialSource {

    MaterialScope openMaterial(
        CredentialReference reference,
        String expectedKeyId,
        String expectedVersionId
    );

    /**
     * Opens the P5 exact governed lease. Existing P2 sources remain source compatible, while an
     * unselected production backend fails closed instead of falling back to another source.
     */
    default CredentialMaterialLease openLease(CredentialMaterialRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        throw new CredentialMaterialSourceException(
            CredentialMaterialFailure.BACKEND_NOT_SELECTED
        );
    }

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
