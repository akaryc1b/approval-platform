package io.github.akaryc1b.approval.connector.model;

import java.util.List;

public record PageResult<T>(List<T> items, String nextCursor, long total) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
        if (total < -1) {
            throw new IllegalArgumentException("total must be -1 when unknown or zero and above");
        }
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), null, 0);
    }
}
