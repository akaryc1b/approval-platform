package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "approval.database", ignoreUnknownFields = false)
public final class ApprovalDatabaseCompatibilityProperties {
    private ApprovalDatabaseVendor expectedVendor = ApprovalDatabaseVendor.POSTGRESQL;
    private String runtimeIdentity;
    private String migrationIdentity;

    public ApprovalDatabaseVendor getExpectedVendor() {
        return expectedVendor;
    }

    public void setExpectedVendor(ApprovalDatabaseVendor expectedVendor) {
        this.expectedVendor = expectedVendor;
    }

    public String getRuntimeIdentity() {
        return runtimeIdentity;
    }

    public void setRuntimeIdentity(String runtimeIdentity) {
        this.runtimeIdentity = runtimeIdentity;
    }

    public String getMigrationIdentity() {
        return migrationIdentity;
    }

    public void setMigrationIdentity(String migrationIdentity) {
        this.migrationIdentity = migrationIdentity;
    }
}
