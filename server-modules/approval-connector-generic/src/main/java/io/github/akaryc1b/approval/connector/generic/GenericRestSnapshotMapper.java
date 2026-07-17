package io.github.akaryc1b.approval.connector.generic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.akaryc1b.approval.connector.model.DepartmentSnapshot;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageResult;
import io.github.akaryc1b.approval.connector.model.PositionSnapshot;
import io.github.akaryc1b.approval.connector.model.RoleSnapshot;
import io.github.akaryc1b.approval.connector.model.TenantSnapshot;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import io.github.akaryc1b.approval.connector.port.AuthenticationConnector.AuthenticationResult;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GenericRestSnapshotMapper {

    AuthenticationResult authenticationResult(JsonNode data) {
        return new AuthenticationResult(
            user(requiredObject(data, "principal")),
            tenant(requiredObject(data, "tenant")),
            stringSet(data.path("permissions")),
            instant(requiredText(data, "expiresAt"), "expiresAt"),
            stringMap(data.path("attributes"))
        );
    }

    UserSnapshot user(JsonNode node) {
        return new UserSnapshot(
            externalId(requiredObject(node, "id")),
            requiredText(node, "username"),
            requiredText(node, "displayName"),
            optionalText(node, "email"),
            optionalText(node, "mobile"),
            requiredBoolean(node, "active"),
            externalIdList(node.path("departmentIds")),
            stringSet(node.path("roleCodes")),
            stringSet(node.path("positionCodes")),
            optionalExternalId(node.path("managerId")),
            stringMap(node.path("attributes"))
        );
    }

    TenantSnapshot tenant(JsonNode node) {
        return new TenantSnapshot(
            externalId(requiredObject(node, "id")),
            requiredText(node, "name"),
            requiredBoolean(node, "active"),
            stringMap(node.path("attributes"))
        );
    }

    DepartmentSnapshot department(JsonNode node) {
        return new DepartmentSnapshot(
            externalId(requiredObject(node, "id")),
            requiredText(node, "name"),
            optionalExternalId(node.path("parentId")),
            optionalExternalId(node.path("managerId")),
            requiredBoolean(node, "active"),
            stringMap(node.path("attributes"))
        );
    }

    RoleSnapshot role(JsonNode node) {
        return new RoleSnapshot(
            externalId(requiredObject(node, "id")),
            requiredText(node, "code"),
            requiredText(node, "name"),
            requiredBoolean(node, "active"),
            stringMap(node.path("attributes"))
        );
    }

    PositionSnapshot position(JsonNode node) {
        return new PositionSnapshot(
            externalId(requiredObject(node, "id")),
            requiredText(node, "code"),
            requiredText(node, "name"),
            requiredBoolean(node, "active"),
            stringMap(node.path("attributes"))
        );
    }

    List<UserSnapshot> users(JsonNode node) {
        if (!node.isArray()) {
            throw protocolError("Expected user array");
        }
        List<UserSnapshot> users = new ArrayList<>();
        node.forEach(item -> users.add(user(item)));
        return List.copyOf(users);
    }

    PageResult<UserSnapshot> userPage(JsonNode data) {
        long total = data.path("total").isIntegralNumber() ? data.path("total").longValue() : -1;
        return new PageResult<>(
            users(requiredArray(data, "items")),
            optionalText(data, "nextCursor"),
            total
        );
    }

    ExternalId externalId(JsonNode node) {
        return new ExternalId(
            requiredText(node, "source"),
            requiredText(node, "objectType"),
            requiredText(node, "value")
        );
    }

    private ExternalId optionalExternalId(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull()
            ? null
            : externalId(node);
    }

    private List<ExternalId> externalIdList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw protocolError("Expected external ID array");
        }
        List<ExternalId> identifiers = new ArrayList<>();
        node.forEach(item -> identifiers.add(externalId(item)));
        return List.copyOf(identifiers);
    }

    private Set<String> stringSet(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Set.of();
        }
        if (!node.isArray()) {
            throw protocolError("Expected string array");
        }
        Set<String> values = new LinkedHashSet<>();
        node.forEach(item -> {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw protocolError("Array contains invalid string value");
            }
            values.add(item.textValue());
        });
        return Set.copyOf(values);
    }

    private Map<String, String> stringMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw protocolError("Expected string map");
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw protocolError("Map contains non-string value for " + entry.getKey());
            }
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(values);
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isObject()) {
            throw protocolError("Expected object field: " + field);
        }
        return value;
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw protocolError("Expected array field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw protocolError("Missing text field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw protocolError("Invalid text field: " + field);
        }
        return value.textValue();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw protocolError("Missing boolean field: " + field);
        }
        return value.booleanValue();
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new GenericRestConnectorException(
                "Invalid instant field: " + field,
                200,
                false,
                exception
            );
        }
    }

    private static GenericRestConnectorException protocolError(String message) {
        return new GenericRestConnectorException(
            "Generic REST protocol error: " + message,
            200,
            false
        );
    }
}
