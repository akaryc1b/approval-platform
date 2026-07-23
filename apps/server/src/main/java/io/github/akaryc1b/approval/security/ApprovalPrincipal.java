package io.github.akaryc1b.approval.security;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Server-authenticated approval identity. Request headers are never authoritative for this model. */
public record ApprovalPrincipal(
    String tenantId,
    String operatorId,
    Set<String> authorities,
    Set<ApprovalResponsibilityAssignment> responsibilities,
    AccountStatus accountStatus,
    Instant sessionExpiresAt
) implements Principal {

    private static final Pattern IDENTIFIER = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}"
    );
    private static final Pattern AUTHORITY = Pattern.compile("[a-z][a-z0-9.-]{2,127}");
    private static final int MAX_AUTHORITIES = 64;
    private static final int MAX_RESPONSIBILITIES = 128;

    public ApprovalPrincipal(
        String tenantId,
        String operatorId,
        Set<String> authorities,
        AccountStatus accountStatus,
        Instant sessionExpiresAt
    ) {
        this(
            tenantId,
            operatorId,
            authorities,
            Set.of(),
            accountStatus,
            sessionExpiresAt
        );
    }

    public ApprovalPrincipal {
        tenantId = requireIdentifier(tenantId, "tenantId");
        operatorId = requireIdentifier(operatorId, "operatorId");
        accountStatus = Objects.requireNonNull(
            accountStatus,
            "accountStatus must not be null"
        );
        authorities = normalizeAuthorities(authorities);
        responsibilities = normalizeResponsibilities(responsibilities);
    }

    public static ApprovalPrincipal active(
        String tenantId,
        String operatorId,
        Set<String> authorities,
        Instant sessionExpiresAt
    ) {
        return active(
            tenantId,
            operatorId,
            authorities,
            Set.of(),
            sessionExpiresAt
        );
    }

    public static ApprovalPrincipal active(
        String tenantId,
        String operatorId,
        Set<String> authorities,
        Set<ApprovalResponsibilityAssignment> responsibilities,
        Instant sessionExpiresAt
    ) {
        return new ApprovalPrincipal(
            tenantId,
            operatorId,
            authorities,
            responsibilities,
            AccountStatus.ACTIVE,
            sessionExpiresAt
        );
    }

    @Override
    public String getName() {
        return operatorId;
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    public boolean hasEnterpriseRole(ApprovalEnterpriseRole role) {
        Objects.requireNonNull(role, "role must not be null");
        return responsibilities.stream().anyMatch(assignment -> assignment.role() == role);
    }

    public boolean isExpiredAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return sessionExpiresAt != null && !sessionExpiresAt.isAfter(instant);
    }

    private static Set<String> normalizeAuthorities(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.size() > MAX_AUTHORITIES) {
            throw new IllegalArgumentException("authorities must not exceed " + MAX_AUTHORITIES);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            Objects.requireNonNull(value, "authority must not be null");
            String authority = value.trim();
            if (!AUTHORITY.matcher(authority).matches()) {
                throw new IllegalArgumentException("authority is malformed");
            }
            normalized.add(authority);
        }
        return Set.copyOf(normalized);
    }

    private static Set<ApprovalResponsibilityAssignment> normalizeResponsibilities(
        Set<ApprovalResponsibilityAssignment> values
    ) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.size() > MAX_RESPONSIBILITIES) {
            throw new IllegalArgumentException(
                "responsibilities must not exceed " + MAX_RESPONSIBILITIES
            );
        }
        Set<ApprovalResponsibilityAssignment> normalized = new LinkedHashSet<>();
        for (ApprovalResponsibilityAssignment value : values) {
            normalized.add(Objects.requireNonNull(
                value,
                "responsibility must not be null"
            ));
        }
        return Set.copyOf(normalized);
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " is malformed");
        }
        return normalized;
    }

    public enum AccountStatus {
        ACTIVE,
        DISABLED
    }
}
