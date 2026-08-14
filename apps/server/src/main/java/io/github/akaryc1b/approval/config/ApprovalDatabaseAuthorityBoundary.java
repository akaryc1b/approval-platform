package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendor;

import java.util.Objects;

/** Server-owned separation between database migration and runtime identities. */
public record ApprovalDatabaseAuthorityBoundary(
    ApprovalDatabaseVendor vendor,
    String runtimeIdentity,
    String migrationIdentity
) {
    public ApprovalDatabaseAuthorityBoundary {
        vendor = Objects.requireNonNull(vendor, "vendor must not be null");
        if (vendor == ApprovalDatabaseVendor.MYSQL) {
            runtimeIdentity = requireIdentity(
                runtimeIdentity,
                "MySQL runtime identity"
            );
            migrationIdentity = requireIdentity(
                migrationIdentity,
                "MySQL migration identity"
            );
            if (runtimeIdentity.equalsIgnoreCase(migrationIdentity)) {
                throw new InvalidDatabaseAuthorityBoundaryException(
                    "MySQL runtime and migration identities must be distinct"
                );
            }
        } else {
            runtimeIdentity = optionalIdentity(runtimeIdentity);
            migrationIdentity = optionalIdentity(migrationIdentity);
        }
    }

    public boolean separated() {
        return runtimeIdentity != null
            && migrationIdentity != null
            && !runtimeIdentity.equalsIgnoreCase(migrationIdentity);
    }

    private static String optionalIdentity(String value) {
        return value == null || value.isBlank() ? null : requireIdentity(value, "identity");
    }

    private static String requireIdentity(String value, String name) {
        String exact = Objects.requireNonNull(
            value,
            name + " must not be null"
        ).strip();
        if (exact.isEmpty()) {
            throw new InvalidDatabaseAuthorityBoundaryException(
                name + " must not be blank"
            );
        }
        if (exact.length() > 128) {
            throw new InvalidDatabaseAuthorityBoundaryException(
                name + " exceeds 128 characters"
            );
        }
        return exact;
    }

    public static final class InvalidDatabaseAuthorityBoundaryException
        extends IllegalStateException {

        public InvalidDatabaseAuthorityBoundaryException(String message) {
            super(message);
        }
    }
}
