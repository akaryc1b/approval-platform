package io.github.akaryc1b.approval.application;

/** Stable application failures for secure Approval artifact transfer. */
public final class ApprovalArtifactTransferExceptions {

    private ApprovalArtifactTransferExceptions() {
    }

    public abstract static class TransferException extends RuntimeException {

        protected TransferException(String message) {
            super(message);
        }

        protected TransferException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class InvalidFormat extends TransferException {

        public InvalidFormat(String message) {
            super(message);
        }

        public InvalidFormat(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class UnsupportedVersion extends TransferException {

        public UnsupportedVersion(String message) {
            super(message);
        }
    }

    public static final class TooLarge extends TransferException {

        public TooLarge(String message) {
            super(message);
        }
    }

    public static final class HashMismatch extends TransferException {

        public HashMismatch(String message) {
            super(message);
        }
    }

    public static final class ArtifactIntegrityFailed extends TransferException {

        public ArtifactIntegrityFailed(String message) {
            super(message);
        }
    }

    public static final class SourceNotFound extends TransferException {

        public SourceNotFound(String message) {
            super(message);
        }
    }

    public static final class FormPackageIncompatible extends TransferException {

        public FormPackageIncompatible(String message) {
            super(message);
        }

        public FormPackageIncompatible(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ValidationFailed extends TransferException {

        public ValidationFailed(String message) {
            super(message);
        }
    }

    public static final class ImportConflict extends TransferException {

        public ImportConflict(String message) {
            super(message);
        }

        public ImportConflict(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
