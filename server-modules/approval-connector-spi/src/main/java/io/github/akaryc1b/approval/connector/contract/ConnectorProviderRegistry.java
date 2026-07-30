package io.github.akaryc1b.approval.connector.contract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable, deterministic registry for server-owned provider-operation bindings.
 */
public final class ConnectorProviderRegistry {

    private final Map<String, ProviderRegistration> providers;
    private final String registryFingerprint;

    public ConnectorProviderRegistry(
        Collection<? extends ConnectorProviderBinding<?, ?>> providerBindings
    ) {
        Objects.requireNonNull(providerBindings, "providerBindings must not be null");
        Map<String, MutableRegistration> mutable = new TreeMap<>();
        for (ConnectorProviderBinding<?, ?> binding : providerBindings) {
            Objects.requireNonNull(binding, "providerBindings must not contain null");
            String key = binding.descriptor().providerKey();
            MutableRegistration registration = mutable.computeIfAbsent(
                key,
                ignored -> new MutableRegistration(binding.descriptor())
            );
            registration.add(binding);
        }
        Map<String, ProviderRegistration> completed = new LinkedHashMap<>();
        mutable.forEach((key, registration) -> completed.put(key, registration.complete()));
        providers = Collections.unmodifiableMap(completed);
        registryFingerprint = CanonicalPayloadHash.sha256Utf8(
            providers.values().stream()
                .flatMap(registration -> registration.bindings().values().stream())
                .map(ConnectorProviderBinding::canonicalRegistration)
                .reduce("", (left, right) -> left + right + "\n")
        );
    }

    public List<ProviderDescriptor> descriptors() {
        List<ProviderDescriptor> descriptors = new ArrayList<>();
        providers.values().forEach(registration -> descriptors.add(registration.descriptor()));
        return List.copyOf(descriptors);
    }

    public Optional<ProviderDescriptor> findDescriptor(String providerKey) {
        String key = ConnectorContractSupport.requireSafeIdentifier(
            providerKey,
            "providerKey"
        );
        ProviderRegistration registration = providers.get(key);
        return registration == null
            ? Optional.empty()
            : Optional.of(registration.descriptor());
    }

    public Optional<ConnectorProviderBinding<?, ?>> findBinding(
        String providerKey,
        ConnectorOperation operation
    ) {
        String key = ConnectorContractSupport.requireSafeIdentifier(
            providerKey,
            "providerKey"
        );
        operation = Objects.requireNonNull(operation, "operation must not be null");
        ProviderRegistration registration = providers.get(key);
        return registration == null
            ? Optional.empty()
            : Optional.ofNullable(registration.bindings().get(operation));
    }

    public String registryFingerprint() {
        return registryFingerprint;
    }

    public <P, R> ConnectorProviderBinding<P, R> resolve(
        String providerKey,
        ConnectorOperation operation,
        Class<P> requestPayloadType,
        Class<R> responseType
    ) {
        String key = ConnectorContractSupport.requireSafeIdentifier(
            providerKey,
            "providerKey"
        );
        operation = Objects.requireNonNull(operation, "operation must not be null");
        requestPayloadType = Objects.requireNonNull(
            requestPayloadType,
            "requestPayloadType must not be null"
        );
        responseType = Objects.requireNonNull(responseType, "responseType must not be null");
        ProviderRegistration registration = providers.get(key);
        if (registration == null) {
            throw new IllegalArgumentException("unknown provider key: " + key);
        }
        registration.descriptor().requireEnabledCapability(operation.requiredCapability());
        ConnectorProviderBinding<?, ?> binding = registration.bindings().get(operation);
        if (binding == null) {
            throw new IllegalArgumentException(
                "provider operation is not registered: " + operation.name()
            );
        }
        if (!binding.requestPayloadType().equals(requestPayloadType)
            || !binding.responseType().equals(responseType)) {
            throw new IllegalArgumentException("provider binding type mismatch");
        }
        return cast(binding);
    }

    @SuppressWarnings("unchecked")
    private static <P, R> ConnectorProviderBinding<P, R> cast(
        ConnectorProviderBinding<?, ?> binding
    ) {
        return (ConnectorProviderBinding<P, R>) binding;
    }

    private record ProviderRegistration(
        ProviderDescriptor descriptor,
        Map<ConnectorOperation, ConnectorProviderBinding<?, ?>> bindings
    ) {
    }

    private static final class MutableRegistration {

        private final ProviderDescriptor descriptor;
        private final Map<ConnectorOperation, ConnectorProviderBinding<?, ?>> bindings =
            new EnumMap<>(ConnectorOperation.class);

        private MutableRegistration(ProviderDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        private void add(ConnectorProviderBinding<?, ?> binding) {
            if (!descriptor.canonicalJson().equals(binding.descriptor().canonicalJson())) {
                throw new IllegalArgumentException(
                    "provider key is registered with inconsistent descriptors: "
                        + descriptor.providerKey()
                );
            }
            if (bindings.putIfAbsent(binding.operation(), binding) != null) {
                throw new IllegalArgumentException(
                    "duplicate provider operation: "
                        + descriptor.providerKey()
                        + "/"
                        + binding.operation().name()
                );
            }
        }

        private ProviderRegistration complete() {
            return new ProviderRegistration(
                descriptor,
                Collections.unmodifiableMap(new EnumMap<>(bindings))
            );
        }
    }
}
