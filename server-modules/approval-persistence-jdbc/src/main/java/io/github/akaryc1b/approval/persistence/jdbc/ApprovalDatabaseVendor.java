package io.github.akaryc1b.approval.persistence.jdbc;

import java.util.Locale;
import java.util.Objects;

/** Supported production database vendors for platform-owned persistence. */
public enum ApprovalDatabaseVendor {
    POSTGRESQL("PostgreSQL", 16, null),
    MYSQL("MySQL", 8, 4);

    private final String productName;
    private final int requiredMajorVersion;
    private final Integer requiredMinorVersion;

    ApprovalDatabaseVendor(
        String productName,
        int requiredMajorVersion,
        Integer requiredMinorVersion
    ) {
        this.productName = productName;
        this.requiredMajorVersion = requiredMajorVersion;
        this.requiredMinorVersion = requiredMinorVersion;
    }

    public String productName() {
        return productName;
    }

    public int requiredMajorVersion() {
        return requiredMajorVersion;
    }

    public Integer requiredMinorVersion() {
        return requiredMinorVersion;
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

    public void requireSupportedVersion(int actualMajorVersion, int actualMinorVersion) {
        boolean majorMismatch = actualMajorVersion != requiredMajorVersion;
        boolean minorMismatch = requiredMinorVersion != null
            && actualMinorVersion != requiredMinorVersion;
        if (majorMismatch || minorMismatch) {
            throw new UnsupportedDatabaseVersionException(
                this,
                requiredMajorVersion,
                requiredMinorVersion,
                actualMajorVersion,
                actualMinorVersion
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
            Integer requiredMinorVersion,
            int actualMajorVersion,
            int actualMinorVersion
        ) {
            super(
                "unsupported " + vendor.productName() + " version "
                    + actualMajorVersion + "." + actualMinorVersion
                    + "; required " + requiredVersion(
                        requiredMajorVersion,
                        requiredMinorVersion
                    )
            );
        }

        private static String requiredVersion(int majorVersion, Integer minorVersion) {
            return minorVersion == null
                ? Integer.toString(majorVersion)
                : majorVersion + "." + minorVersion;
        }
    }
}
