package io.github.akaryc1b.approval.persistence.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Objects;

/** Resolves and verifies the server-owned database identity from JDBC metadata. */
public final class ApprovalDatabaseVendorResolver {

    public DatabaseIdentity resolve(DataSource dataSource) {
        return resolveDetected(dataSource);
    }

    public DatabaseIdentity resolve(
        DataSource dataSource,
        ApprovalDatabaseVendor expectedVendor
    ) {
        ApprovalDatabaseVendor expected = Objects.requireNonNull(
            expectedVendor,
            "expectedVendor must not be null"
        );
        DatabaseIdentity identity = resolveDetected(dataSource);
        if (identity.vendor() != expected) {
            throw new DatabaseVendorMismatchException(expected, identity.vendor());
        }
        return identity;
    }

    private static DatabaseIdentity resolveDetected(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        try (Connection connection = source.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String productName = requireText(
                metadata.getDatabaseProductName(),
                "database product name"
            );
            String productVersion = requireText(
                metadata.getDatabaseProductVersion(),
                "database product version"
            );
            int majorVersion = metadata.getDatabaseMajorVersion();
            int minorVersion = metadata.getDatabaseMinorVersion();
            ApprovalDatabaseVendor detected = ApprovalDatabaseVendor.fromProductName(
                productName
            );
            detected.requireSupportedVersion(majorVersion, minorVersion);
            return new DatabaseIdentity(
                detected,
                productName,
                productVersion,
                majorVersion,
                minorVersion
            );
        } catch (SQLException exception) {
            throw new DatabaseVendorResolutionException(exception);
        }
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null").strip();
        if (exact.isEmpty()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return exact;
    }

    public record DatabaseIdentity(
        ApprovalDatabaseVendor vendor,
        String productName,
        String productVersion,
        int majorVersion,
        int minorVersion
    ) {
        public DatabaseIdentity {
            vendor = Objects.requireNonNull(vendor, "vendor must not be null");
            productName = requireText(productName, "productName");
            productVersion = requireText(productVersion, "productVersion");
            if (majorVersion < 0 || minorVersion < 0) {
                throw new IllegalArgumentException(
                    "database version numbers must not be negative"
                );
            }
        }
    }

    public static final class DatabaseVendorMismatchException
        extends IllegalStateException {

        public DatabaseVendorMismatchException(
            ApprovalDatabaseVendor expected,
            ApprovalDatabaseVendor detected
        ) {
            super(
                "database vendor mismatch; expected " + expected
                    + " but detected " + detected
            );
        }
    }

    public static final class DatabaseVendorResolutionException
        extends IllegalStateException {

        public DatabaseVendorResolutionException(SQLException cause) {
            super("database identity resolution failed", cause);
        }
    }
}
