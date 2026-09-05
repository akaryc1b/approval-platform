package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Keeps the H1 integration test call site narrow while seeding full V38 provenance. */
final class MySqlH1MigrationPlanAuthorityFixtureAdapter {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");

    private MySqlH1MigrationPlanAuthorityFixtureAdapter() {
    }

    static void seed(
        String tenantId,
        UUID planId,
        UUID intentId,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleasePackage targetRelease,
        ApprovalReleaseDeployment targetDeployment
    ) {
        MySqlH1MigrationPlanAuthorityFixture.seed(
            MySqlApprovalProjectionStoreIntegrationSupport.jdbc,
            new ObjectMapper().findAndRegisterModules(),
            tenantId,
            planId,
            intentId,
            uuid(tenantId, "instance"),
            MySqlApprovalProjectionStoreIntegrationSupport.DEFINITION_KEY,
            "worker-h1",
            NOW,
            "4".repeat(64),
            "9".repeat(64),
            sourceRelease,
            targetRelease,
            targetDeployment
        );
    }

    private static UUID uuid(String tenant, String value) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h1:" + tenant + ':' + value).getBytes(StandardCharsets.UTF_8)
        );
    }
}
