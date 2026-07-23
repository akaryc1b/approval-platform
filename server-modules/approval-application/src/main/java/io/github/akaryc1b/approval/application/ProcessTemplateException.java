package io.github.akaryc1b.approval.application;

/** Safe template validation and governed draft-creation error. */
public class ProcessTemplateException extends RuntimeException {
    public ProcessTemplateException(String message) {
        super(message);
    }

    public static final class PackageTooLarge extends ProcessTemplateException {
        public PackageTooLarge(String message) {
            super(message);
        }
    }

    public static final class HashMismatch extends ProcessTemplateException {
        public HashMismatch(String message) {
            super(message);
        }
    }

    public static final class CrossTenantBinding extends ProcessTemplateException {
        public CrossTenantBinding(String message) {
            super(message);
        }
    }

    public static final class StalePlan extends ProcessTemplateException {
        public StalePlan(String message) {
            super(message);
        }
    }

    public static final class DraftCreationRejected extends ProcessTemplateException {
        public DraftCreationRejected(String message) {
            super(message);
        }
    }
}
