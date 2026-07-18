package io.github.akaryc1b.approval.application;

/** Stable exception taxonomy mapped to Approval DSL API error codes. */
public final class ApprovalDesignExceptions {

    private ApprovalDesignExceptions() {
    }

    public static final class DraftNotFound extends RuntimeException {
        public DraftNotFound(String message) {
            super(message);
        }
    }

    public static final class FormPackageNotFound extends RuntimeException {
        public FormPackageNotFound(String message) {
            super(message);
        }
    }

    public static final class PublishedDefinitionNotFound extends RuntimeException {
        public PublishedDefinitionNotFound(String message) {
            super(message);
        }
    }

    public static final class DraftRevisionConflict extends RuntimeException {
        public DraftRevisionConflict(String message) {
            super(message);
        }
    }

    public static final class DraftStateConflict extends RuntimeException {
        public DraftStateConflict(String message) {
            super(message);
        }
    }

    public static final class DefinitionVersionConflict extends RuntimeException {
        public DefinitionVersionConflict(String message) {
            super(message);
        }
    }

    public static final class ReleaseVersionConflict extends RuntimeException {
        public ReleaseVersionConflict(String message) {
            super(message);
        }
    }

    public static final class CompiledArtifactConflict extends RuntimeException {
        public CompiledArtifactConflict(String message) {
            super(message);
        }
    }

    public static final class FormPackageIntegrity extends RuntimeException {
        public FormPackageIntegrity(String message) {
            super(message);
        }
    }
}
