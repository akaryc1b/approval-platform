package io.github.akaryc1b.approval.connector.model;

public record PageRequest(int page, int size, String cursor) {

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater");
        }
        if (size < 1 || size > 500) {
            throw new IllegalArgumentException("size must be between 1 and 500");
        }
        cursor = cursor == null || cursor.isBlank() ? null : cursor;
    }

    public static PageRequest first(int size) {
        return new PageRequest(0, size, null);
    }
}
