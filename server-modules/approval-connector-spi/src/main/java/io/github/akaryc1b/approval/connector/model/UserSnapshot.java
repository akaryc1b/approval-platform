package io.github.akaryc1b.approval.connector.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record UserSnapshot(
    ExternalId id,
    String username,
    String displayName,
    String email,
    String mobile,
    boolean active,
    List<ExternalId> departmentIds,
    Set<String> roleCodes,
    Set<String> positionCodes,
    ExternalId managerId,
    Map<String, String> attributes
) {

    public UserSnapshot {
        id = Objects.requireNonNull(id, "id must not be null");
        username = requireText(username, "username");
        displayName = requireText(displayName, "displayName");
        email = normalizeOptional(email);
        mobile = normalizeOptional(mobile);
        departmentIds = departmentIds == null ? List.of() : List.copyOf(departmentIds);
        roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
        positionCodes = positionCodes == null ? Set.of() : Set.copyOf(positionCodes);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
