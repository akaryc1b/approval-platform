package io.github.akaryc1b.approval.application;

/** Safe validation error raised before any import write can occur. */
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
}
