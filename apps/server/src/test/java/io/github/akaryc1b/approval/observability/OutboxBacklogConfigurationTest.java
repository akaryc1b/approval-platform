package io.github.akaryc1b.approval.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxBacklogConfigurationTest {

    @Test
    void disabledByDefaultAndDoesNotRequireADatabase() {
        new ApplicationContextRunner().withUserConfiguration(OutboxBacklogConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(OutboxBacklogMetrics.class).isEmpty());
        });
    }

    @Test
    void explicitOptInCreatesNoSecondDataSourceBeanAndDoesNotQueryBeforeReady() {
        runner("jdbc:postgresql://127.0.0.1:1/not_contacted").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(OutboxBacklogMetrics.class).size());
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertEquals(0.0d, registry.get("approval.outbox.sample.up").gauge().value());
            assertEquals(0.0d, registry.get("approval.outbox.sample.errors").functionCounter().count());
        });
    }

    @Test
    void unsafeUrlTimeoutOverridesAndUnsupportedDatabaseFailConfiguration() {
        for (String url : new String[] {
            "jdbc:postgresql://127.0.0.1:1/test?socketTimeout=0",
            "jdbc:postgresql://127.0.0.1:1/test?connectTimeout=0",
            "jdbc:postgresql://127.0.0.1:1/test?cancelSignalTimeout=0",
            "jdbc:postgresql://127.0.0.1:1/test?%73ocketTimeout=0",
            "jdbc:mysql://127.0.0.1:1/test"
        }) {
            runner(url).run(context -> assertNotNull(context.getStartupFailure()));
        }
    }

    private static ApplicationContextRunner runner(String url) {
        return new ApplicationContextRunner().withUserConfiguration(OutboxBacklogConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(DataSourceProperties.class, () -> {
                DataSourceProperties database = new DataSourceProperties();
                database.setUrl(url);
                database.setUsername("observation-fixture");
                return database;
            })
            .withPropertyValues("approval.observability.outbox.enabled=true");
    }
}
