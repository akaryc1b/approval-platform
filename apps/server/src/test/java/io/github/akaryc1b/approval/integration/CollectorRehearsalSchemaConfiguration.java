package io.github.akaryc1b.approval.integration;

import org.flowable.engine.ProcessEngine;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Objects;

/** Test-only schema setup without demo business records or a replacement Outbox. */
@TestConfiguration(proxyBeanMethods = false)
class CollectorRehearsalSchemaConfiguration {
    @Bean(initMethod = "migrate")
    CollectorSchema collectorRehearsalSchema(DataSource dataSource, ProcessEngine processEngine) {
        Objects.requireNonNull(processEngine, "Flowable schema must exist before repository migrations");
        return new CollectorSchema(dataSource);
    }

    static final class CollectorSchema {
        private final DataSource dataSource;
        CollectorSchema(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }
        public void migrate() {
            Flyway.configure().dataSource(dataSource).baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .locations("classpath:db/migration").load().migrate();
        }
    }
}
