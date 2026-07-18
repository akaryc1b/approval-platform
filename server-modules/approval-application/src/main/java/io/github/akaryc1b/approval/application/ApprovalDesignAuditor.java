package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class ApprovalDesignAuditor {

    private final AuditEventSink sink;
    private final Supplier<UUID> identifiers;

    ApprovalDesignAuditor(AuditEventSink sink, Supplier<UUID> identifiers) {
        this.sink = Objects.requireNonNull(sink);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    void append(
        RequestContext context,
        String action,
        ApprovalDesignDraft draft,
        Instant occurredAt,
        Map<String, String> attributes
    ) {
        sink.append(new AuditEvent(
            identifiers.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            "APPROVAL_DESIGN_DRAFT",
            draft.draftId().toString(),
            context.requestId(),
            context.traceId(),
            occurredAt,
            attributes
        ));
    }

    static Map<String, String> releaseAttributes(
        ApprovalReleasePackage releasePackage,
        long revision
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("revision", Long.toString(revision));
        attributes.put(
            "definitionVersion",
            Integer.toString(releasePackage.definitionVersion())
        );
        attributes.put("definitionHash", releasePackage.definitionHash());
        attributes.put("releaseVersion", Integer.toString(releasePackage.releaseVersion()));
        attributes.put("packageHash", releasePackage.packageHash());
        attributes.put("bpmnHash", releasePackage.bpmnHash());
        attributes.put("formPackageHash", releasePackage.formPackageHash());
        return Map.copyOf(attributes);
    }
}
