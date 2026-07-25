package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CredentialReference;

import java.util.Optional;

@FunctionalInterface
public interface CredentialBindingCatalog {

    Optional<CredentialBindingDescriptor> find(CredentialReference reference);
}
