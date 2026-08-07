package io.github.akaryc1b.approval.persistence.jdbc;

import java.util.Locale;
import java.util.Objects;

/** Supported production database vendors for platform-owned persistence. */
public enum ApprovalDatabaseVendor {
    POSTGRESQL("PostgreSQL", 16),
    MYSQL("MySQL", 8);

    private final String productName;
    private final int requiredMajorVersion;

    ApprovalDatabaseVendor(String productName, int requiredMajorVersion) {
        this.productName = productName;
        this.requiredMajorVersion = requiredMajorVersion;
    }

    public String productName() {
        return productName;
    }

    public int requiredMajorVersion() {
        return requiredMajorVersion;
    }

    public static ApprovalDatabaseVendor parseExpected(String value) {
        String normalized = Objects.requireNonNull(
            value,
            "expected database vendor must not be null"
        ).strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "POSTGRESQL" -> POSTGRESQL;
            case "MYSQL" -> MYSQL;
            default -> throw new IllegalArgumentException(
                "unsupported expected database vendor: " + normalized
            );
        };
    }

    static ApprovalDatabaseVendor fromProductName(String value) {
        String exact = Objects.requireNonNull(
            value,
            "database product name must not be null"
        ).strip();
        return switch (exact) {
            case "PostgreSQL" -> POSTGRESQL;
            case "MySQL" -> MYSQL;
            default -> throw new UnsupportedDatabaseVendorException(exact);
        };
    }

    public void requireSupportedMajorVersion(int actualMajorVersion) {
        if (actualMajorVersion != requiredMajorVersion) {
            throw new UnsupportedDatabaseVersionException(
                this,
                requiredMajorVersion,
                actualMajorVersion
            );
        }
    }

    public static final class UnsupportedDatabaseVendorException
        extends IllegalStateException {

        public UnsupportedDatabaseVendorException(String productName) {
            super("unsupported database product: " + productName);
        }
    }

    public static final class UnsupportedDatabaseVersionException
        extends IllegalStateException {

        public UnsupportedDatabaseVersionException(
            ApprovalDatabaseVendor vendor,
            int requiredMajorVersion,
            int actualMajorVersion
        ) {
            super(
                "unsupported " + vendor.productName() + " major version "
                    + actualMajorVersion + "; required " + requiredMajorVersion
            );
        }
    }
}
