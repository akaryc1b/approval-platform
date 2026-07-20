package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;

import java.util.Objects;

/** Writes immutable audit evidence first, then notification intents in the same transaction. */
public final class NotificationAwareAuditEventSink implements AuditEventSink {

    private final AuditEventSink delegate;
    private final ApprovalNotificationService notifications;

    public NotificationAwareAuditEventSink(
        AuditEventSink delegate,
        ApprovalNotificationService notifications
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.notifications = Objects.requireNonNull(
            notifications,
            "notifications must not be null"
        );
    }

    @Override
    public void append(AuditEvent event) {
        delegate.append(event);
        notifications.enqueueFromAudit(event);
    }
}
