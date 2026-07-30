package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.ConnectorCredentialResolver.ScopedCredential;

public interface ResolvedScopedCredential extends ScopedCredential, AutoCloseable {

    String versionId();

    CredentialMaterialType credentialType();

    CredentialResolutionEvidence evidence();

    @Override
    void close();
}
