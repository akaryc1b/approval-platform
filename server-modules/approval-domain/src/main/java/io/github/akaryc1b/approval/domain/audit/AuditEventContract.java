package io.github.akaryc1b.approval.domain.audit;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable schema assignment and required-field validation for platform audit events. */
public record AuditEventContract(
    String schemaName,
    int schemaVersion,
    Set<String> requiredAttributes
) {

    public static final int CURRENT_VERSION = 1;

    public AuditEventContract {
        schemaName = requireText(schemaName, "schemaName");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        requiredAttributes = requiredAttributes == null
            ? Set.of()
            : Set.copyOf(requiredAttributes);
    }

    public static AuditEventContract resolve(String action) {
        String normalized = requireText(action, "action");
        return new AuditEventContract(
            schemaName(normalized),
            CURRENT_VERSION,
            requiredAttributes(normalized)
        );
    }

    public void validate(
        String actualSchemaName,
        int actualSchemaVersion,
        Map<String, String> attributes
    ) {
        if (!schemaName.equals(actualSchemaName)) {
            throw new IllegalArgumentException(
                "audit schemaName does not match action contract: " + schemaName
            );
        }
        if (actualSchemaVersion != schemaVersion) {
            throw new IllegalArgumentException(
                "audit schemaVersion does not match action contract: " + schemaVersion
            );
        }
        Map<String, String> values = attributes == null ? Map.of() : attributes;
        for (String required : requiredAttributes) {
            String value = values.get(required);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    "audit attribute is required by contract: " + required
                );
            }
        }
    }

    private static String schemaName(String action) {
        if (action.startsWith("MANAGEMENT_")) {
            return "approval.management-security";
        }
        if (action.startsWith("AUDIT_")) {
            return "approval.audit.operation";
        }
        if (action.startsWith("OPERATIONAL_FAILURE_")) {
            return "approval.operational-failure";
        }
        if (action.contains("CONSISTENCY")) {
            return "approval.consistency";
        }
        if (action.contains("DELEGATION")) {
            return "approval.delegation";
        }
        if (action.contains("HANDOVER")) {
            return "approval.employee-handover";
        }
        if (action.contains("COLLABORATION")
            || action.contains("ADD_SIGN")
            || action.contains("REMOVE_SIGN")
            || action.contains("VOTE")
            || action.contains("WEIGHT")) {
            return "approval.collaboration";
        }
        if (action.contains("NOTIFICATION")
            || action.contains("DELIVERY")
            || action.contains("DEAD_LETTER")) {
            return "approval.notification";
        }
        if (action.contains("COMMENT")) {
            return "approval.comment";
        }
        if (action.startsWith("TASK_")) {
            return "approval.task-lifecycle";
        }
        if (action.startsWith("INSTANCE_") || action.startsWith("PROCESS_")) {
            return "approval.process-lifecycle";
        }
        return "approval.generic";
    }

    private static Set<String> requiredAttributes(String action) {
        return switch (action) {
            case "MANAGEMENT_HIGH_RISK_AUTHORIZED" -> Set.of(
                "requirement",
                "reason",
                "resourceScope",
                "authorizationDecision",
                "matchedRole"
            );
            case "PROCESS_RELEASE_PUBLISH_AUTHORIZED" -> Set.of(
                "draftId",
                "definitionKey",
                "targetDefinitionVersion",
                "targetReleaseVersion",
                "preflightHash",
                "reason"
            );
            case "PROCESS_RELEASE_ACTIVATION_AUTHORIZED",
                 "PROCESS_RELEASE_ROLLBACK_AUTHORIZED" -> Set.of(
                     "operation",
                     "definitionKey",
                     "targetReleaseVersion",
                     "targetReleasePackageHash",
                     "targetLifecycleState",
                     "reason"
                 );
            case "INSTANCE_COMMENT_CREATED",
                 "INSTANCE_COMMENT_EDITED",
                 "INSTANCE_COMMENT_DELETED" -> Set.of(
                     "commentId",
                     "commentRevision",
                     "visibility"
                 );
            /*
             * INSTANCE_COMMENTED is the pre-revision compatibility action used by the existing
             * notification path. New persisted lifecycle events use the three versioned actions
             * above, while legacy emitters remain readable and processable.
             */
            case "INSTANCE_COMMENTED" -> Set.of();
            case "AUDIT_EXPORTED" -> Set.of(
                "format",
                "recordCount",
                "rangeStart",
                "rangeEnd"
            );
            case "AUDIT_INTEGRITY_VERIFIED" -> Set.of(
                "valid",
                "checkedCount",
                "rangeStart",
                "rangeEnd"
            );
            case "CONSISTENCY_CHECK_EXECUTED" -> Set.of(
                "checkId",
                "scope",
                "status",
                "findingCount",
                "detectOnly"
            );
            case "OPERATIONAL_FAILURE_REPLAYED" -> Set.of(
                "category",
                "sourceId",
                "outcome",
                "replacementSourceId"
            );
            default -> Set.of();
        };
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
