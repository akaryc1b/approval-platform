package io.github.akaryc1b.approval.connector.operations;

/** Stable exceptions mapped by the read-only management API. */
public final class ConnectorOperationsDiagnosticsExceptions {
    private ConnectorOperationsDiagnosticsExceptions() { }
    public static final class InvalidRequest extends RuntimeException {
        public InvalidRequest() { super("connector diagnostics request is invalid"); }
    }
    public static final class NotFound extends RuntimeException {
        public NotFound() { super("connector diagnostics resource was not found"); }
    }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("connector diagnostics snapshot changed"); }
    }
    public static final class ResponseTooLarge extends RuntimeException {
        public ResponseTooLarge() { super("connector diagnostics response exceeds the allowed bound"); }
    }
    public static final class SourceUnavailable extends RuntimeException {
        public SourceUnavailable() { super("connector diagnostics source is unavailable"); }
    }
    public static final class InternalFailure extends RuntimeException {
        public InternalFailure() { super("connector diagnostics failed"); }
    }
}
