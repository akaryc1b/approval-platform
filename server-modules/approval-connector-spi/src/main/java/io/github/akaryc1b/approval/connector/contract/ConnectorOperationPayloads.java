package io.github.akaryc1b.approval.connector.contract;

import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Closed provider-neutral payloads for the initial Connector Foundation operations.
 *
 * <p>These records contain business input only. Trusted tenant, operator, authority,
 * audit and credential identities remain server-owned and are deliberately absent.</p>
 */
public final class ConnectorOperationPayloads {

    public static final ConnectorOperationContract<DirectoryReadCommand, DirectoryReadResult>
        DIRECTORY_READ = new ConnectorOperationContract<>(
            "directory-read.v1",
            ConnectorOperation.ORGANIZATION_READ,
            DirectoryReadCommand.class,
            DirectoryReadResult.class
        );

    public static final ConnectorOperationContract<IdentityResolveCommand, IdentityResolveResult>
        IDENTITY_RESOLVE = new ConnectorOperationContract<>(
            "identity-resolve.v1",
            ConnectorOperation.IDENTITY_RESOLVE,
            IdentityResolveCommand.class,
            IdentityResolveResult.class
        );

    public static final ConnectorOperationContract<MessageSendCommand, MessageSendResult>
        MESSAGE_SEND = new ConnectorOperationContract<>(
            "message-send.v1",
            ConnectorOperation.NOTIFICATION_SEND,
            MessageSendCommand.class,
            MessageSendResult.class
        );

    public static final ConnectorOperationContract<ExternalTodoCommand, ExternalTodoResult>
        EXTERNAL_TODO_CREATE = new ConnectorOperationContract<>(
            "external-todo-create.v1",
            ConnectorOperation.EXTERNAL_TODO_CREATE,
            ExternalTodoCommand.class,
            ExternalTodoResult.class
        );

    public static final ConnectorOperationContract<ExternalTodoCommand, ExternalTodoResult>
        EXTERNAL_TODO_UPDATE = new ConnectorOperationContract<>(
            "external-todo-update.v1",
            ConnectorOperation.EXTERNAL_TODO_UPDATE,
            ExternalTodoCommand.class,
            ExternalTodoResult.class
        );

    public static final ConnectorOperationContract<BusinessCallbackCommand, BusinessCallbackResult>
        BUSINESS_CALLBACK_DELIVER = new ConnectorOperationContract<>(
            "business-callback-deliver.v1",
            ConnectorOperation.BUSINESS_CALLBACK_DELIVER,
            BusinessCallbackCommand.class,
            BusinessCallbackResult.class
        );

    private ConnectorOperationPayloads() {
    }

    public record DirectoryReadCommand(
        DirectoryQuery query,
        ExternalId subjectId,
        String queryValue,
        PageRequest page,
        int maximumLevels,
        Map<String, String> filters
    ) implements CanonicalConnectorPayload {

        public DirectoryReadCommand {
            query = Objects.requireNonNull(query, "query must not be null");
            queryValue = ConnectorContractSupport.optionalText(queryValue, "queryValue", 256);
            page = page == null ? PageRequest.first(100) : page;
            filters = ConnectorContractSupport.boundedMetadata(
                filters,
                "filters",
                16,
                64,
                256,
                true
            );
            switch (query) {
                case USER_BY_ID, DEPARTMENT_BY_ID -> {
                    subjectId = Objects.requireNonNull(subjectId, "subjectId must not be null");
                    requireZero(maximumLevels, "maximumLevels");
                }
                case USER_SEARCH -> {
                    subjectId = null;
                    requireZero(maximumLevels, "maximumLevels");
                }
                case ROLE_MEMBERS, POSITION_MEMBERS -> {
                    subjectId = null;
                    queryValue = ConnectorContractSupport.requireSafeIdentifier(
                        queryValue,
                        "queryValue"
                    );
                    requireZero(maximumLevels, "maximumLevels");
                }
                case MANAGER_CHAIN -> {
                    subjectId = Objects.requireNonNull(subjectId, "subjectId must not be null");
                    if (maximumLevels < 1 || maximumLevels > 100) {
                        throw new IllegalArgumentException(
                            "maximumLevels must be between 1 and 100"
                        );
                    }
                }
            }
        }

        @Override
        public String canonicalPayload() {
            return "query=" + query.name()
                + "\nsubjectId=" + external(subjectId)
                + "\nqueryValue=" + optional(queryValue)
                + "\npage=" + page.page()
                + "\nsize=" + page.size()
                + "\ncursor=" + optional(page.cursor())
                + "\nmaximumLevels=" + maximumLevels
                + "\nfilters=" + canonicalMap(filters);
        }
    }

    public enum DirectoryQuery {
        USER_BY_ID,
        USER_SEARCH,
        DEPARTMENT_BY_ID,
        ROLE_MEMBERS,
        POSITION_MEMBERS,
        MANAGER_CHAIN
    }

    public record DirectoryReadResult(
        List<DirectoryEntry> entries,
        String nextCursor,
        long total
    ) {

        public DirectoryReadResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
            if (entries.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("entries must not contain null");
            }
            nextCursor = ConnectorContractSupport.optionalText(
                nextCursor,
                "nextCursor",
                512
            );
            if (total < -1) {
                throw new IllegalArgumentException(
                    "total must be -1 when unknown or zero and above"
                );
            }
        }
    }

    public record DirectoryEntry(
        ExternalId id,
        DirectoryEntryType type,
        String displayName,
        boolean active,
        ExternalId parentId,
        Map<String, String> attributes
    ) {

        public DirectoryEntry {
            id = Objects.requireNonNull(id, "id must not be null");
            type = Objects.requireNonNull(type, "type must not be null");
            displayName = ConnectorContractSupport.requireText(
                displayName,
                "displayName",
                256
            );
            attributes = ConnectorContractSupport.boundedMetadata(
                attributes,
                "attributes",
                16,
                64,
                256,
                true
            );
        }
    }

    public enum DirectoryEntryType {
        USER,
        DEPARTMENT,
        ROLE,
        POSITION
    }

    public record IdentityResolveCommand(
        ExternalId externalUserId,
        String mappingNamespace,
        Map<String, String> hints
    ) implements CanonicalConnectorPayload {

        public IdentityResolveCommand {
            externalUserId = Objects.requireNonNull(
                externalUserId,
                "externalUserId must not be null"
            );
            mappingNamespace = ConnectorContractSupport.requireSafeIdentifier(
                mappingNamespace,
                "mappingNamespace"
            );
            hints = ConnectorContractSupport.boundedMetadata(
                hints,
                "hints",
                8,
                64,
                256,
                true
            );
        }

        @Override
        public String canonicalPayload() {
            return "externalUserId=" + externalUserId.canonicalValue()
                + "\nmappingNamespace=" + mappingNamespace
                + "\nhints=" + canonicalMap(hints);
        }
    }

    public record IdentityResolveResult(
        IdentityResolutionStatus status,
        ExternalId externalUserId,
        String mappingReference,
        UserSnapshot userSnapshot,
        Map<String, String> metadata
    ) {

        public IdentityResolveResult {
            status = Objects.requireNonNull(status, "status must not be null");
            externalUserId = Objects.requireNonNull(
                externalUserId,
                "externalUserId must not be null"
            );
            mappingReference = ConnectorContractSupport.optionalText(
                mappingReference,
                "mappingReference",
                128
            );
            metadata = ConnectorContractSupport.boundedMetadata(
                metadata,
                "metadata",
                8,
                64,
                256,
                true
            );
            if (status == IdentityResolutionStatus.RESOLVED) {
                mappingReference = ConnectorContractSupport.requireSafeIdentifier(
                    mappingReference,
                    "mappingReference"
                );
                userSnapshot = Objects.requireNonNull(
                    userSnapshot,
                    "userSnapshot must not be null for RESOLVED"
                );
                if (!externalUserId.equals(userSnapshot.id())) {
                    throw new IllegalArgumentException(
                        "resolved snapshot ID does not match externalUserId"
                    );
                }
            } else if (userSnapshot != null || mappingReference != null) {
                throw new IllegalArgumentException(
                    "unresolved identity must not contain a mapping or user snapshot"
                );
            }
        }

        public boolean establishesTrustedPlatformIdentity() {
            return false;
        }
    }

    public enum IdentityResolutionStatus {
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS
    }

    public record MessageSendCommand(
        String messageType,
        List<ExternalId> recipients,
        String title,
        String body,
        String actionUrl,
        Map<String, String> variables
    ) implements CanonicalConnectorPayload {

        public MessageSendCommand {
            messageType = ConnectorContractSupport.requireSafeIdentifier(
                messageType,
                "messageType"
            );
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
            if (recipients.isEmpty() || recipients.size() > 500) {
                throw new IllegalArgumentException(
                    "recipients must contain between 1 and 500 entries"
                );
            }
            if (recipients.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("recipients must not contain null");
            }
            title = ConnectorContractSupport.requireText(title, "title", 256);
            body = ConnectorContractSupport.requireText(body, "body", 4000);
            actionUrl = ConnectorContractSupport.optionalText(actionUrl, "actionUrl", 2048);
            variables = ConnectorContractSupport.boundedMetadata(
                variables,
                "variables",
                32,
                64,
                512,
                true
            );
        }

        @Override
        public String canonicalPayload() {
            List<String> sortedRecipients = recipients.stream()
                .map(ExternalId::canonicalValue)
                .sorted()
                .toList();
            return "messageType=" + messageType
                + "\nrecipients=" + String.join(",", sortedRecipients)
                + "\ntitle=" + title
                + "\nbody=" + body
                + "\nactionUrl=" + optional(actionUrl)
                + "\nvariables=" + canonicalMap(variables);
        }
    }

    public record MessageSendResult(
        String providerMessageId,
        MessageDeliveryState state,
        Instant acceptedAt,
        Map<String, String> metadata
    ) {

        public MessageSendResult {
            providerMessageId = ConnectorContractSupport.optionalText(
                providerMessageId,
                "providerMessageId",
                128
            );
            state = Objects.requireNonNull(state, "state must not be null");
            acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
            metadata = ConnectorContractSupport.boundedMetadata(
                metadata,
                "metadata",
                8,
                64,
                256,
                true
            );
        }
    }

    public enum MessageDeliveryState {
        ACCEPTED,
        DELIVERED,
        REJECTED,
        UNKNOWN
    }

    public record ExternalTodoCommand(
        String externalTaskKey,
        ExternalId recipientId,
        String title,
        String description,
        String actionUrl,
        ExternalTodoState state,
        Instant dueAt,
        Map<String, String> attributes
    ) implements CanonicalConnectorPayload {

        public ExternalTodoCommand {
            externalTaskKey = ConnectorContractSupport.requireSafeIdentifier(
                externalTaskKey,
                "externalTaskKey"
            );
            recipientId = Objects.requireNonNull(recipientId, "recipientId must not be null");
            title = ConnectorContractSupport.requireText(title, "title", 256);
            description = description == null ? "" : description.trim();
            if (description.length() > 4000) {
                throw new IllegalArgumentException("description exceeds 4000 characters");
            }
            actionUrl = ConnectorContractSupport.requireText(actionUrl, "actionUrl", 2048);
            state = Objects.requireNonNull(state, "state must not be null");
            attributes = ConnectorContractSupport.boundedMetadata(
                attributes,
                "attributes",
                16,
                64,
                256,
                true
            );
        }

        @Override
        public String canonicalPayload() {
            return "externalTaskKey=" + externalTaskKey
                + "\nrecipientId=" + recipientId.canonicalValue()
                + "\ntitle=" + title
                + "\ndescription=" + description
                + "\nactionUrl=" + actionUrl
                + "\nstate=" + state.name()
                + "\ndueAt=" + (dueAt == null ? "" : dueAt.toString())
                + "\nattributes=" + canonicalMap(attributes);
        }
    }

    public record ExternalTodoResult(
        String providerTaskId,
        ExternalTodoState state,
        Instant synchronizedAt,
        Map<String, String> metadata
    ) {

        public ExternalTodoResult {
            providerTaskId = ConnectorContractSupport.optionalText(
                providerTaskId,
                "providerTaskId",
                128
            );
            state = Objects.requireNonNull(state, "state must not be null");
            synchronizedAt = Objects.requireNonNull(
                synchronizedAt,
                "synchronizedAt must not be null"
            );
            metadata = ConnectorContractSupport.boundedMetadata(
                metadata,
                "metadata",
                8,
                64,
                256,
                true
            );
        }
    }

    public enum ExternalTodoState {
        PENDING,
        APPROVED,
        REJECTED,
        TRANSFERRED,
        COMPLETED,
        CANCELED
    }

    public record BusinessCallbackCommand(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        Map<String, String> fields
    ) implements CanonicalConnectorPayload {

        public BusinessCallbackCommand {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            eventType = ConnectorContractSupport.requireCode(eventType, "eventType");
            aggregateType = ConnectorContractSupport.requireSafeIdentifier(
                aggregateType,
                "aggregateType"
            );
            aggregateId = ConnectorContractSupport.requireSafeIdentifier(
                aggregateId,
                "aggregateId"
            );
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            fields = ConnectorContractSupport.boundedMetadata(
                fields,
                "fields",
                64,
                64,
                1024,
                true
            );
        }

        @Override
        public String canonicalPayload() {
            return "eventId=" + eventId
                + "\neventType=" + eventType
                + "\naggregateType=" + aggregateType
                + "\naggregateId=" + aggregateId
                + "\noccurredAt=" + occurredAt
                + "\nfields=" + canonicalMap(fields);
        }
    }

    public record BusinessCallbackResult(
        String providerRequestId,
        CallbackDeliveryState state,
        Instant completedAt,
        Map<String, String> metadata
    ) {

        public BusinessCallbackResult {
            providerRequestId = ConnectorContractSupport.optionalText(
                providerRequestId,
                "providerRequestId",
                128
            );
            state = Objects.requireNonNull(state, "state must not be null");
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
            metadata = ConnectorContractSupport.boundedMetadata(
                metadata,
                "metadata",
                8,
                64,
                256,
                true
            );
        }
    }

    public enum CallbackDeliveryState {
        DELIVERED,
        REJECTED,
        UNKNOWN
    }

    private static String canonicalMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        return entries.stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce("", (left, right) -> left.isEmpty() ? right : left + "&" + right);
    }

    private static String external(ExternalId id) {
        return id == null ? "" : id.canonicalValue();
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }

    private static void requireZero(int value, String name) {
        if (value != 0) {
            throw new IllegalArgumentException(name + " must be zero for this query");
        }
    }
}
