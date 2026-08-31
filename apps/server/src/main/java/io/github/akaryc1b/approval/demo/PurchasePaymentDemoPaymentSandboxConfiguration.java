package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.config.GenericConnectorProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(
    prefix = "approval.demo.purchase-payment.sandbox",
    name = "enabled",
    havingValue = "true"
)
@EnableConfigurationProperties(
    PurchasePaymentDemoPaymentSandboxConfiguration.Properties.class
)
public class PurchasePaymentDemoPaymentSandboxConfiguration {

    @Bean
    PurchasePaymentDemoPaymentSandbox purchasePaymentDemoPaymentSandbox(
        ObjectMapper approvalPersistenceObjectMapper,
        Clock approvalClock,
        PurchasePaymentDemoScenario scenario,
        GenericConnectorProperties connectorProperties,
        Properties properties
    ) {
        properties.validate();
        URI endpoint = resolveEndpoint(connectorProperties, properties);
        validateConnector(connectorProperties, scenario, endpoint);
        byte[] secret = connectorProperties.secretBytes();
        try {
            return new PurchasePaymentDemoPaymentSandbox(
                approvalPersistenceObjectMapper,
                approvalClock,
                secret,
                connectorProperties.getKeyId(),
                scenario,
                endpoint,
                path(properties.getControlFile()),
                path(properties.getStatusFile()),
                properties.getMaximumClockSkew()
            );
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Bean
    ApplicationRunner initializePurchasePaymentDemoPaymentSandbox(
        PurchasePaymentDemoPaymentSandbox sandbox
    ) {
        return arguments -> sandbox.initialize();
    }

    private static URI resolveEndpoint(
        GenericConnectorProperties connectorProperties,
        Properties properties
    ) {
        return connectorProperties.isEnabled()
            ? connectorProperties.getCallbackUri()
            : properties.getEndpoint();
    }

    private static void validateConnector(
        GenericConnectorProperties connectorProperties,
        PurchasePaymentDemoScenario scenario,
        URI endpoint
    ) {
        validateEndpoint(endpoint, connectorProperties.isEnabled());
        if (!connectorProperties.isEnabled()) {
            return;
        }
        if (!scenario.assigneeRules().connectorKey().equals(
            connectorProperties.getConnectorKey()
        )) {
            throw new IllegalStateException(
                "generic connector key must match the governed demo scenario"
            );
        }
        if (!endpoint.equals(connectorProperties.getCallbackUri())) {
            throw new IllegalStateException(
                "generic connector callback URI must match the local payment sandbox"
            );
        }
    }

    private static void validateEndpoint(URI endpoint, boolean connectorEnabled) {
        if (endpoint == null) {
            throw new IllegalStateException("payment sandbox endpoint must not be null");
        }
        String host = endpoint.getHost() == null
            ? ""
            : endpoint.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(host)
            || "127.0.0.1".equals(host)
            || "::1".equals(host);
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) || !loopback) {
            throw new IllegalStateException(
                "payment sandbox endpoint must use loopback HTTP"
            );
        }
        if (!PurchasePaymentDemoPaymentSandbox.CALLBACK_PATH.equals(endpoint.getPath())) {
            throw new IllegalStateException("payment sandbox callback path is invalid");
        }
        if (endpoint.getUserInfo() != null
            || endpoint.getQuery() != null
            || endpoint.getFragment() != null) {
            throw new IllegalStateException(
                "payment sandbox endpoint must not contain credentials, query or fragment"
            );
        }
        int port = endpoint.getPort();
        if (connectorEnabled && (port < 1024 || port > 65_535)) {
            throw new IllegalStateException(
                "enabled payment sandbox endpoint port must be between 1024 and 65535"
            );
        }
        if (!connectorEnabled && port != 0 && (port < 1024 || port > 65_535)) {
            throw new IllegalStateException(
                "payment sandbox endpoint port must be zero or between 1024 and 65535"
            );
        }
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    @ConfigurationProperties(prefix = "approval.demo.purchase-payment.sandbox")
    public static class Properties {

        private URI endpoint = URI.create(
            "http://127.0.0.1:0" + PurchasePaymentDemoPaymentSandbox.CALLBACK_PATH
        );
        private String controlFile;
        private String statusFile;
        private Duration maximumClockSkew = Duration.ofMinutes(5);

        public URI getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(URI endpoint) {
            this.endpoint = endpoint;
        }

        public String getControlFile() {
            return controlFile;
        }

        public void setControlFile(String controlFile) {
            this.controlFile = controlFile;
        }

        public String getStatusFile() {
            return statusFile;
        }

        public void setStatusFile(String statusFile) {
            this.statusFile = statusFile;
        }

        public Duration getMaximumClockSkew() {
            return maximumClockSkew;
        }

        public void setMaximumClockSkew(Duration maximumClockSkew) {
            this.maximumClockSkew = maximumClockSkew;
        }

        void validate() {
            validateEndpoint(endpoint, false);
            if (maximumClockSkew == null
                || maximumClockSkew.isNegative()
                || maximumClockSkew.isZero()) {
                throw new IllegalStateException(
                    "payment sandbox maximum-clock-skew must be positive"
                );
            }
        }
    }
}
