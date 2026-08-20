package io.github.akaryc1b.approval.demo;

import org.flowable.engine.ProcessEngine;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Applies repository-owned migrations after Flowable creates its local schema.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(
    prefix = "approval.demo.purchase-payment",
    name = "enabled",
    havingValue = "true"
)
public class PurchasePaymentDemoMigrationConfiguration {

    @Bean(initMethod = "migrate")
    PurchasePaymentDemoDatabaseMigration purchasePaymentDemoDatabaseMigration(
        DataSource dataSource,
        ProcessEngine processEngine
    ) {
        Objects.requireNonNull(processEngine, "processEngine must not be null");
        return new PurchasePaymentDemoDatabaseMigration(dataSource);
    }

    static final class PurchasePaymentDemoDatabaseMigration {

        private final DataSource dataSource;

        private PurchasePaymentDemoDatabaseMigration(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(
                dataSource,
                "dataSource must not be null"
            );
        }

        public void migrate() {
            Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        }
    }
}
