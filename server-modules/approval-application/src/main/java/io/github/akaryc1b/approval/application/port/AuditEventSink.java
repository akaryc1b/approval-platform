package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.audit.AuditEvent;

/**
 * Persists immutable audit events. Production implementations must be append-only.
 */
public interface AuditEventSink {

    void append(AuditEvent event);
}
