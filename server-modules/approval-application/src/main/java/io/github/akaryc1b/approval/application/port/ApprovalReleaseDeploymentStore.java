package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;

import java.util.List;
import java.util.Optional;

/** Platform-owned deployment projection store; it never reads engine database tables. */
public interface ApprovalReleaseDeploymentStore {

    void lock(String tenantId, String definitionKey, int releaseVersion);

    Optional<ApprovalReleaseDeployment> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    );

    default List<ApprovalReleaseDeployment> findByDefinition(
        String tenantId,
        String definitionKey
    ) {
        return List.of();
    }

    void save(ApprovalReleaseDeployment deployment);

    boolean update(ApprovalReleaseDeployment deployment, int expectedAttemptCount);
}
