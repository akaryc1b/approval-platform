package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Real source Release Lifecycle provenance required by H2 Runtime Binding fixtures. */
final class MySqlH2MigrationAttemptProvisioningFixture {

    private MySqlH2MigrationAttemptProvisioningFixture() {
    }

    static void seedActiveSourceRelease(
        DataSource dataSource,
        ApprovalReleasePackage releasePackage,
        String workerId,
        Instant happenedAt
    ) {
        ApprovalReleasePackage exact = Objects.requireNonNull(
            releasePackage,
            "releasePackage must not be null"
        );
        String worker = requireText(workerId, "workerId");
        Instant canonical = AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(happenedAt, "happenedAt must not be null")
        );
        ApprovalProcessReleaseStore releases = JdbcApprovalProcessReleaseStoreFactory.create(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        ApprovalProcessRelease.Transition publish = new ApprovalProcessRelease.Transition(
            uuid(exact, "publish"),
            exact.tenantId(),
            exact.definitionKey(),
            exact.releaseVersion(),
            exact.packageHash(),
            State.DRAFT,
            State.PUBLISHED,
            1,
            "Publish source release for H2 provisioning fixture",
            "h2-publish-" + exact.releaseVersion(),
            exact.publishedBy(),
            "request-h2-publish-" + exact.releaseVersion(),
            "trace-h2",
            "audit-event:h2-publish-" + exact.releaseVersion(),
            canonical.minusSeconds(2)
        );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(exact, publish);
        releases.savePublished(published, publish);

        ApprovalProcessRelease.Transition activate = new ApprovalProcessRelease.Transition(
            uuid(exact, "activate"),
            exact.tenantId(),
            exact.definitionKey(),
            exact.releaseVersion(),
            exact.packageHash(),
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "Activate source release for H2 provisioning fixture",
            "h2-activate-" + exact.releaseVersion(),
            worker,
            "request-h2-activate-" + exact.releaseVersion(),
            "trace-h2",
            "audit-event:h2-activate-" + exact.releaseVersion(),
            canonical.minusSeconds(1)
        );
        if (!releases.transition(
            published.transitioned(activate),
            published.revision(),
            activate
        )) {
            throw new IllegalStateException("H2 source release activation lost revision CAS");
        }
    }

    private static UUID uuid(ApprovalReleasePackage releasePackage, String operation) {
        return UUID.nameUUIDFromBytes(
            ("mysql-h2:"
                + releasePackage.tenantId()
                + ':'
                + releasePackage.definitionKey()
                + ':'
                + releasePackage.releaseVersion()
                + ':'
                + operation)
                .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        if (exact.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }
}
