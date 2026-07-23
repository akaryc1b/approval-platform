package io.github.akaryc1b.approval.security;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Resource boundary carried by a trusted enterprise responsibility assignment. */
public record ApprovalResourceScope(
    Kind kind,
    Set<String> departmentIds
) {

    private static final Pattern IDENTIFIER = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}"
    );
    private static final int MAX_DEPARTMENTS = 256;

    public ApprovalResourceScope {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        departmentIds = normalizeDepartments(departmentIds);
        if (kind == Kind.DEPARTMENT && departmentIds.isEmpty()) {
            throw new IllegalArgumentException(
                "department scope requires at least one departmentId"
            );
        }
        if (kind != Kind.DEPARTMENT && !departmentIds.isEmpty()) {
            throw new IllegalArgumentException(
                "departmentIds are only valid for department scope"
            );
        }
    }

    public static ApprovalResourceScope platform() {
        return new ApprovalResourceScope(Kind.PLATFORM, Set.of());
    }

    public static ApprovalResourceScope tenant() {
        return new ApprovalResourceScope(Kind.TENANT, Set.of());
    }

    public static ApprovalResourceScope departments(Set<String> departmentIds) {
        return new ApprovalResourceScope(Kind.DEPARTMENT, departmentIds);
    }

    public boolean allows(ApprovalResource resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        return switch (kind) {
            case PLATFORM, TENANT -> true;
            case DEPARTMENT -> resource.level() == ApprovalResource.Level.DEPARTMENT
                && departmentIds.contains(resource.departmentId());
        };
    }

    private static Set<String> normalizeDepartments(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.size() > MAX_DEPARTMENTS) {
            throw new IllegalArgumentException(
                "departmentIds must not exceed " + MAX_DEPARTMENTS
            );
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            Objects.requireNonNull(value, "departmentId must not be null");
            String departmentId = value.trim();
            if (!IDENTIFIER.matcher(departmentId).matches()) {
                throw new IllegalArgumentException("departmentId is malformed");
            }
            normalized.add(departmentId);
        }
        return Set.copyOf(normalized);
    }

    public enum Kind {
        PLATFORM("platform"),
        TENANT("tenant"),
        DEPARTMENT("department");

        private final String metricTag;

        Kind(String metricTag) {
            this.metricTag = metricTag;
        }

        public String metricTag() {
            return metricTag;
        }
    }
}
