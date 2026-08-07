package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "approval.database", ignoreUnknownFields = false)
public final class ApprovalDatabaseCompatibilityProperties {
    private ApprovalDatabaseVendor expectedVendor = ApprovalDatabaseVendor.POSTGRESQL;

    public ApprovalDatabaseVendor getExpectedVendor() {
        return expectedVendor;
    }

    public void setExpectedVendor(ApprovalDatabaseVendor expectedVendor) {
        this.expectedVendor = expectedVendor;
    }
}
