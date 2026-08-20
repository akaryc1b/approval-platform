package io.github.akaryc1b.approval.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Duration;

/**
 * Aligns the explicitly enabled local demo clock with PostgreSQL timestamptz precision.
 *
 * <p>PostgreSQL stores timestamps at microsecond precision. Keeping the governed demo services on
 * the same precision prevents false projection conflicts after an exact database round trip. This
 * primary clock exists only while the local purchase-payment demo switch is enabled; production
 * and ordinary local runs retain the platform clock.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(
    prefix = "approval.demo.purchase-payment",
    name = "enabled",
    havingValue = "true"
)
public class PurchasePaymentDemoClockConfiguration {

    private static final Duration POSTGRESQL_TIMESTAMP_TICK = Duration.ofNanos(1_000);

    @Bean
    @Primary
    Clock purchasePaymentDemoClock() {
        return Clock.tick(Clock.systemUTC(), POSTGRESQL_TIMESTAMP_TICK);
    }
}
