package io.github.akaryc1b.approval.connector.dingtalk;

import java.util.List;
import java.util.Objects;

record DingTalkUserDetail(
    String userId,
    String unionId,
    String name,
    String email,
    String mobile,
    boolean active,
    String managerUserId,
    List<String> departmentIds,
    String title,
    String jobNumber
) {

    DingTalkUserDetail {
        userId = requireText(userId, "userId");
        unionId = optionalText(unionId);
        name = requireText(name, "name");
        email = optionalText(email);
        mobile = optionalText(mobile);
        managerUserId = optionalText(managerUserId);
        departmentIds = departmentIds == null ? List.of() : List.copyOf(departmentIds);
        if (departmentIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("departmentIds must not contain blank values");
        }
        title = optionalText(title);
        jobNumber = optionalText(jobNumber);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
