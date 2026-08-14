package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendor;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendorResolver.DatabaseIdentity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Server-owned separation between database migration and runtime identities. */
public record ApprovalDatabaseAuthorityBoundary(
    ApprovalDatabaseVendor vendor,
    String runtimeIdentity,
    String migrationIdentity
) {
    private static final Set<String> FORBIDDEN_MYSQL_RUNTIME_IDENTITIES = Set.of(
        "root",
        "mysql.sys",
        "mysql.session",
        "mysql.infoschema"
    );

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
            if (FORBIDDEN_MYSQL_RUNTIME_IDENTITIES.contains(
                runtimeIdentity.toLowerCase(Locale.ROOT)
            )) {
                throw new InvalidDatabaseAuthorityBoundaryException(
                    "MySQL runtime identity must not be a privileged system account"
                );
            }
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

    static ApprovalDatabaseAuthorityBoundary resolve(
        DataSource dataSource,
        DatabaseIdentity identity,
        String expectedRuntimeIdentity,
        String migrationIdentity
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        DatabaseIdentity exactIdentity = Objects.requireNonNull(
            identity,
            "identity must not be null"
        );
        if (exactIdentity.vendor() != ApprovalDatabaseVendor.MYSQL) {
            return new ApprovalDatabaseAuthorityBoundary(
                exactIdentity.vendor(),
                expectedRuntimeIdentity,
                migrationIdentity
            );
        }
        String expected = requireIdentity(
            expectedRuntimeIdentity,
            "MySQL runtime identity"
        );
        String actual;
        try (Connection connection = source.getConnection()) {
            actual = accountName(connection.getMetaData().getUserName());
        } catch (SQLException exception) {
            throw new InvalidDatabaseAuthorityBoundaryException(
                "failed to resolve the trusted MySQL runtime identity",
                exception
            );
        }
        if (!actual.equals(expected)) {
            throw new InvalidDatabaseAuthorityBoundaryException(
                "configured MySQL runtime identity does not match the JDBC session"
            );
        }
        return new ApprovalDatabaseAuthorityBoundary(
            exactIdentity.vendor(),
            actual,
            migrationIdentity
        );
    }

    public boolean separated() {
        return runtimeIdentity != null
            && migrationIdentity != null
            && !runtimeIdentity.equalsIgnoreCase(migrationIdentity);
    }

    private static String accountName(String value) {
        String exact = requireIdentity(value, "MySQL JDBC runtime identity");
        int separator = exact.indexOf('@');
        String account = separator < 0 ? exact : exact.substring(0, separator);
        account = account.strip();
        if (account.length() >= 2) {
            char first = account.charAt(0);
            char last = account.charAt(account.length() - 1);
            if ((first == '\'' && last == '\'')
                || (first == '`' && last == '`')) {
                account = account.substring(1, account.length() - 1);
            }
        }
        return requireIdentity(account, "MySQL JDBC runtime identity");
    }

    private static String optionalIdentity(String value) {
        return value == null || value.isBlank() ? null : requireIdentity(value, "identity");
    }

    private static String requireIdentity(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidDatabaseAuthorityBoundaryException(
                name + " must not be blank"
            );
        }
        String exact = value.strip();
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

        public InvalidDatabaseAuthorityBoundaryException(
            String message,
            Throwable cause
        ) {
            super(message, cause);
        }
    }
}
