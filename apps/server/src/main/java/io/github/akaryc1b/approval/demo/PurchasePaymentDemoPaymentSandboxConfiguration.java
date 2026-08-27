package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.config.GenericConnectorProperties;
import org.springframework.beans.factory.annotation.Qualifier;
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

/**
 * Local-only configuration for the shared purchase-payment callback sandbox.
 */
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

    @Bean(initMethod = "start", destroyMethod = "close")
    PurchasePaymentDemoPaymentSandbox purchasePaymentDemoPaymentSandbox(
        Properties properties,
        GenericConnectorProperties connectorProperties,
        @Qualifier("approvalPersistenceObjectMapper") ObjectMapper objectMapper,
        PurchasePaymentDemoScenario scenario,
        Clock approvalClock
    ) {
        properties.validate();
        validateConnector(properties, connectorProperties, scenario);
        byte[] secret = connectorProperties.secretBytes();
        try {
            return new PurchasePaymentDemoPaymentSandbox(
                objectMapper,
                approvalClock,
                secret,
                requireText(
                    connectorProperties.getKeyId(),
                    "approval.connector.generic.key-id"
                ),
                scenario,
                properties.getPort(),
                properties.controlFilePath(),
                properties.statusFilePath(),
                properties.getMaximumClockSkew()
            );
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static void validateConnector(
        Properties properties,
        GenericConnectorProperties connector,
        PurchasePaymentDemoScenario scenario
    ) {
        if (!connector.isEnabled()) {
            return;
        }
        if (!scenario.connectorKey().equals(connector.getConnectorKey())) {
            throw new IllegalStateException(
                "local payment sandbox connector key must match the governed scenario"
            );
        }
        if (properties.getPort() == 0) {
            throw new IllegalStateException(
                "local payment sandbox requires an explicit port when dispatch is enabled"
            );
        }
        URI callback = connector.getCallbackUri();
        if (callback == null
            || !"http".equalsIgnoreCase(callback.getScheme())
            || !"127.0.0.1".equals(callback.getHost())
            || callback.getPort() != properties.getPort()
            || !PurchasePaymentDemoPaymentSandbox.CALLBACK_PATH.equals(
                callback.getPath()
            )) {
            throw new IllegalStateException(
                "generic callback URI must target the configured loopback payment sandbox"
            );
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value.trim();
    }

    @ConfigurationProperties(prefix = "approval.demo.purchase-payment.sandbox")
    public static class Properties {

        private boolean enabled;
        private int port = 18_081;
        private String controlFile;
        private String statusFile;
        private Duration maximumClockSkew = Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
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

        Path controlFilePath() {
            return path(controlFile);
        }

        Path statusFilePath() {
            return path(statusFile);
        }

        void validate() {
            if (!enabled) {
                throw new IllegalStateException(
                    "payment sandbox configuration must be explicitly enabled"
                );
            }
            if (port != 0 && (port < 1024 || port > 65_535)) {
                throw new IllegalStateException(
                    "payment sandbox port must be 0 or between 1024 and 65535"
                );
            }
            if (maximumClockSkew == null
                || maximumClockSkew.isZero()
                || maximumClockSkew.isNegative()) {
                throw new IllegalStateException(
                    "payment sandbox maximum-clock-skew must be positive"
                );
            }
            Path control = controlFilePath();
            Path status = statusFilePath();
            if (control != null && control.equals(status)) {
                throw new IllegalStateException(
                    "payment sandbox control-file and status-file must differ"
                );
            }
        }

        private static Path path(String value) {
            return value == null || value.isBlank()
                ? null
                : Path.of(value.trim()).toAbsolutePath().normalize();
        }
    }
}
