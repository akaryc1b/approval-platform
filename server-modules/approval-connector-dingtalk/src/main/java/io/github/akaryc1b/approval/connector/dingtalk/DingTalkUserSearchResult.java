package io.github.akaryc1b.approval.connector.dingtalk;

import java.util.List;

public record DingTalkUserSearchResult(
    List<String> userIds,
    boolean hasMore,
    long totalCount
) {

    public DingTalkUserSearchResult {
        userIds = userIds == null ? List.of() : List.copyOf(userIds);
        if (userIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("userIds must not contain blank values");
        }
        if (totalCount < 0) {
            throw new IllegalArgumentException("totalCount must not be negative");
        }
    }
}
