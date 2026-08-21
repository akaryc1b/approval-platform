package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Real-FK multi-version release fixture for MySQL lifecycle/effective-release acceptance. */
final class MySqlApprovalReleaseLifecycleFixture {

    private MySqlApprovalReleaseLifecycleFixture() {
    }

    static ApprovalReleasePackage seedRelease(
        JdbcTemplate jdbc,
        ApprovalReleasePackageStore packages,
        String tenantId,
        String definitionKey,
        int releaseVersion,
        String packageHash,
        Instant publishedAt
    ) {
        Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(packages, "packages must not be null");
        String exactTenant = requireText(tenantId, "tenantId");
        String exactDefinition = requireText(definitionKey, "definitionKey");
        if (releaseVersion < 1) {
            throw new IllegalArgumentException("releaseVersion must be positive");
        }
        String exactHash = requireHash(packageHash, "packageHash");
        Instant canonicalPublishedAt = AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(publishedAt, "publishedAt must not be null")
        );
        UUID draftId = fixtureUuid(
            "g2-approval-draft",
            exactTenant,
            exactDefinition,
            releaseVersion
        );
        Timestamp timestamp = Timestamp.from(canonicalPublishedAt);
        jdbc.update(
            """
            insert into ap_approval_design_draft (
                tenant_id, draft_id, definition_key, name,
                definition_version, approval_dsl_json,
                form_package_version, form_package_hash,
                source_definition_version, revision, status,
                published_definition_version, published_release_version,
                created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, ?, ?, cast(? as json), ?, ?,
                null, 1, 'DRAFT', null, null,
                'Fixture-Publisher', 'Fixture-Publisher', ?, ?)
            """,
            exactTenant,
            draftId.toString(),
            exactDefinition,
            "G2 approval draft " + releaseVersion,
            MySqlApprovalProjectionProvenanceFixture.DEFINITION_VERSION,
            "{\"definitionKey\":\"" + exactDefinition + "\"}",
            MySqlApprovalProjectionProvenanceFixture.FORM_PACKAGE_VERSION,
            MySqlApprovalProjectionProvenanceFixture.FORM_PACKAGE_HASH,
            timestamp,
            timestamp
        );
        ApprovalReleasePackage releasePackage = new ApprovalReleasePackage(
            exactTenant,
            exactDefinition,
            releaseVersion,
            MySqlApprovalProjectionProvenanceFixture.DEFINITION_VERSION,
            MySqlApprovalProjectionProvenanceFixture.DEFINITION_HASH,
            MySqlApprovalProjectionProvenanceFixture.FORM_PACKAGE_VERSION,
            MySqlApprovalProjectionProvenanceFixture.FORM_PACKAGE_HASH,
            MySqlApprovalProjectionProvenanceFixture.FORM_VERSION,
            MySqlApprovalProjectionProvenanceFixture.FORM_HASH,
            MySqlApprovalProjectionProvenanceFixture.UI_SCHEMA_VERSION,
            MySqlApprovalProjectionProvenanceFixture.UI_SCHEMA_HASH,
            MySqlApprovalProjectionProvenanceFixture.COMPILER_VERSION,
            "g2-release-" + releaseVersion + ".bpmn20.xml",
            "<definitions/>",
            MySqlApprovalProjectionProvenanceFixture.COMPILED_ARTIFACT_HASH,
            MySqlApprovalProjectionProvenanceFixture.BPMN_HASH,
            null,
            null,
            MySqlApprovalProjectionProvenanceFixture.DEPLOYMENT_METADATA_HASH,
            exactHash,
            draftId,
            "Fixture-Publisher",
            canonicalPublishedAt
        );
        packages.save(releasePackage);
        return packages.find(
            exactTenant,
            exactDefinition,
            releaseVersion
        ).orElseThrow();
    }

    static ApprovalReleaseDeployment seedDeployed(
        ApprovalReleaseDeploymentStore deployments,
        ApprovalReleasePackage releasePackage,
        Instant deployedAt
    ) {
        Objects.requireNonNull(deployments, "deployments must not be null");
        ApprovalReleasePackage exact = Objects.requireNonNull(
            releasePackage,
            "releasePackage must not be null"
        );
        Instant canonical = AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(deployedAt, "deployedAt must not be null")
        );
        ApprovalReleaseDeployment deployment = new ApprovalReleaseDeployment(
            fixtureUuid(
                "g2-deployment",
                exact.tenantId(),
                exact.definitionKey(),
                exact.releaseVersion()
            ),
            exact.tenantId(),
            exact.definitionKey(),
            exact.releaseVersion(),
            exact.packageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            1,
            "engine-deployment-g2-" + exact.releaseVersion(),
            "engine-definition-g2-" + exact.releaseVersion(),
            exact.releaseVersion(),
            null,
            null,
            "Operator-G2",
            canonical.minusSeconds(1),
            canonical,
            canonical
        );
        deployments.save(deployment);
        return deployments.find(
            exact.tenantId(),
            exact.definitionKey(),
            exact.releaseVersion()
        ).orElseThrow();
    }

    private static UUID fixtureUuid(
        String namespace,
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return UUID.nameUUIDFromBytes(
            (namespace
                + ':'
                + tenantId
                + ':'
                + definitionKey
                + ':'
                + releaseVersion)
                .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireHash(String value, String name) {
        String exact = requireText(value, name);
        if (!exact.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return exact;
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        if (exact.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }
}
