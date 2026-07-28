package io.github.akaryc1b.approval.connector;

import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.port.AuthenticationConnector;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.ExternalTodoConnector;
import io.github.akaryc1b.approval.connector.port.FileConnector;
import io.github.akaryc1b.approval.connector.port.FormDataSourceConnector;
import io.github.akaryc1b.approval.connector.port.NotificationConnector;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers the capabilities implemented by one host or office-platform connector.
 */
public interface ConnectorProvider {

    ConnectorDescriptor descriptor();

    /**
     * M6-A provider-neutral descriptor. Existing providers remain source compatible.
     */
    default ProviderDescriptor providerDescriptor() {
        return ProviderDescriptor.fromLegacy(descriptor());
    }

    default Optional<AuthenticationConnector> authentication() {
        return Optional.empty();
    }

    default Optional<OrganizationConnector> organization() {
        return Optional.empty();
    }

    default Optional<FileConnector> files() {
        return Optional.empty();
    }

    default Optional<NotificationConnector> notifications() {
        return Optional.empty();
    }

    default Optional<BusinessCallbackConnector> businessCallbacks() {
        return Optional.empty();
    }

    default Optional<FormDataSourceConnector> formDataSources() {
        return Optional.empty();
    }

    default Optional<ExternalTodoConnector> externalTodos() {
        return Optional.empty();
    }

    record ConnectorDescriptor(
        String key,
        String displayName,
        String version,
        Set<Capability> capabilities,
        Map<String, String> attributes
    ) {
        public ConnectorDescriptor {
            key = requireText(key, "key");
            displayName = requireText(displayName, "displayName");
            version = requireText(version, "version");
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    enum Capability {
        AUTHENTICATION,
        ORGANIZATION,
        FILE_STORAGE,
        NOTIFICATION,
        BUSINESS_CALLBACK,
        FORM_DATA_SOURCE,
        EXTERNAL_TODO;

        public static Capability parse(String value) {
            String normalized = requireText(value, "capability").toUpperCase(Locale.ROOT);
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "unknown connector capability: " + normalized,
                    exception
                );
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
