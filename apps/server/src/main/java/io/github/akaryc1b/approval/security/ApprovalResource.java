package io.github.akaryc1b.approval.security;

import java.util.Objects;
import java.util.regex.Pattern;

/** Server-resolved management resource being authorized. */
public record ApprovalResource(
    String tenantId,
    Level level,
    String departmentId
) {

    private static final Pattern IDENTIFIER = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}"
    );

    public ApprovalResource {
        tenantId = requireIdentifier(tenantId, "tenantId");
        level = Objects.requireNonNull(level, "level must not be null");
        if (level == Level.DEPARTMENT) {
            departmentId = requireIdentifier(departmentId, "departmentId");
        } else if (departmentId != null) {
            throw new IllegalArgumentException(
                "departmentId is only valid for a department resource"
            );
        }
    }

    public static ApprovalResource tenant(String tenantId) {
        return new ApprovalResource(tenantId, Level.TENANT, null);
    }

    public static ApprovalResource department(String tenantId, String departmentId) {
        return new ApprovalResource(tenantId, Level.DEPARTMENT, departmentId);
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " is malformed");
        }
        return normalized;
    }

    public enum Level {
        TENANT("tenant"),
        DEPARTMENT("department");

        private final String metricTag;

        Level(String metricTag) {
            this.metricTag = metricTag;
        }

        public String metricTag() {
            return metricTag;
        }
    }
}
