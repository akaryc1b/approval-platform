package io.github.akaryc1b.approval.observability;

import com.zaxxer.hikari.HikariDataSource;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxBacklogReader;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Explicit opt-in, isolated from the approval pool and default scheduled business tasks. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "approval.observability.outbox", name = "enabled", havingValue = "true")
public class OutboxBacklogConfiguration {

    @Bean(destroyMethod = "close")
    OutboxBacklogMetrics outboxBacklogMetrics(DataSourceProperties database, MeterRegistry registry,
        Environment environment,
        @Value("${approval.observability.outbox.interval-ms:30000}") long intervalMillis) {
        String jdbcUrl = database.determineUrl();
        if (database.getJndiName() != null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Outbox observation requires an explicit PostgreSQL JDBC configuration");
        }
        int query = jdbcUrl.indexOf('?');
        if (query >= 0) {
            Set<String> boundedProperties = Set.of("connectTimeout", "socketTimeout", "cancelSignalTimeout");
            for (String parameter : jdbcUrl.substring(query + 1).split("&")) {
                String name = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8);
                if (boundedProperties.contains(name)) {
                    throw new IllegalArgumentException("Configure JDBC timeouts as DataSource properties, not URL overrides");
                }
            }
        }
        // Deliberately not a DataSource bean: do not replace/back off Boot's business pool.
        HikariDataSource pool = database.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        try {
            Map<String, String> connectionProperties = Binder.get(environment)
                .bind("spring.datasource.hikari.data-source-properties", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
            connectionProperties.forEach(pool::addDataSourceProperty);
            pool.setPoolName("approval-outbox-observation");
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);
            pool.setAutoCommit(true);
            pool.setReadOnly(true);
            pool.setConnectionTimeout(500);
            pool.setValidationTimeout(250);
            pool.setInitializationFailTimeout(-1);
            pool.addDataSourceProperty("connectTimeout", "2");
            pool.addDataSourceProperty("socketTimeout", "3");
            pool.addDataSourceProperty("cancelSignalTimeout", "1");
            return new OutboxBacklogMetrics(new JdbcOutboxBacklogReader(pool), registry, Duration.ofMillis(intervalMillis), pool::close);
        } catch (RuntimeException failure) {
            pool.close();
            throw failure;
        }
    }
}
