package io.github.akaryc1b.approval.example.host;

import io.github.akaryc1b.approval.host.security.DefaultHostRequestVerifier;
import io.github.akaryc1b.approval.host.security.HmacSha256HostSignatureVerifier;
import io.github.akaryc1b.approval.host.security.HostConnectorProperties;
import io.github.akaryc1b.approval.host.security.HostRequestVerifier;
import io.github.akaryc1b.approval.host.security.InMemoryReplayGuard;
import io.github.akaryc1b.approval.host.security.TenantSecretResolver;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;

@Configuration
@EnableConfigurationProperties(ExampleHostProperties.class)
class ExampleHostConfiguration {

    @Bean
    Clock exampleHostClock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationRunner validateExampleHostConfiguration(ExampleHostProperties properties) {
        return arguments -> properties.validate();
    }

    @Bean
    TenantSecretResolver exampleTenantSecretResolver(ExampleHostProperties properties) {
        return (tenantKey, keyId) -> {
            if (!properties.getTenantId().equals(tenantKey)
                || !properties.getKeyId().equals(keyId)) {
                return Optional.empty();
            }
            byte[] secret = properties.secretBytes();
            try {
                return Optional.of(new TenantSecretResolver.SecretMaterial(keyId, secret));
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        };
    }

    @Bean
    HostRequestVerifier exampleHostRequestVerifier(
        ExampleHostProperties properties,
        TenantSecretResolver secretResolver,
        Clock clock
    ) {
        return new DefaultHostRequestVerifier(
            new HostConnectorProperties(
                properties.getSource(),
                properties.getAllowedClockSkew(),
                properties.getNonceTtl()
            ),
            secretResolver,
            new HmacSha256HostSignatureVerifier(),
            new InMemoryReplayGuard(clock),
            clock
        );
    }
}
