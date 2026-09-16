package io.github.akaryc1b.approval.config;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.annotation.DirtiesContext;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real configuration/registries and bounded loopback HTTP; no external collector is contacted. */
class ApprovalTelemetryLifecycleConfigurationTest {

    @Test
    void defaultConfigurationDoesNotPushMetricsOnClose() throws Exception {
        verifyExport(false);
    }

    @Test
    void localProfileDoesNotPushMetricsOnClose() throws Exception {
        verifyExport(false, "spring.profiles.active=local");
    }

    @Test
    void observabilityProfileDoesNotImplicitlyEnableMetricPush() throws Exception {
        verifyExport(false, "spring.profiles.active=observability");
    }

    @Test
    void traceOptInDoesNotEnableTheIndependentMetricsExporter() throws Exception {
        verifyExport(false, "APPROVAL_OTLP_TRACING_ENABLED=true");
    }

    @Test
    void explicitMetricsOptInExportsToTheConfiguredLoopbackReceiver() throws Exception {
        verifyExport(true, "APPROVAL_OTLP_METRICS_ENABLED=true");
    }

    @Test
    void standardSpringPropertyCanExplicitlyDisableMetricPush() throws Exception {
        verifyExport(false, "APPROVAL_OTLP_METRICS_ENABLED=true",
            "management.otlp.metrics.export.enabled=false");
    }

    @Test
    void classOwnedDatabaseTestsEvictTheirContextsAtClassCompletion() throws Exception {
        // Annotation contract only. Real database shutdown is checked in the full Maven run.
        for (String name : List.of("PurchasePaymentDemoSeedIntegrationTest",
            "PurchasePaymentSandboxRecoveryIntegrationTest", "ApprovalBusinessMetricsPostgresTest")) {
            Class<?> type = Class.forName("io.github.akaryc1b.approval.integration." + name,
                false, getClass().getClassLoader());
            DirtiesContext lifecycle = type.getAnnotation(DirtiesContext.class);
            assertNotNull(lifecycle, name + " must not cache a context beyond its container");
            assertEquals(DirtiesContext.ClassMode.AFTER_CLASS, lifecycle.classMode(), name);
        }
    }

    private void verifyExport(boolean enabled, String... overrides) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean rejected = new AtomicBoolean();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/v1/metrics", exchange -> {
            try {
                int bytes = exchange.getRequestBody().readNBytes(65_537).length;
                if (!exchange.getRequestMethod().equals("POST") || bytes == 0 || bytes > 65_536) {
                    rejected.set(true);
                    exchange.sendResponseHeaders(400, -1);
                } else {
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                }
            } finally {
                exchange.close();
            }
        });
        receiver.start();
        try {
            String endpoint = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/v1/metrics";
            new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                    PrometheusMetricsExportAutoConfiguration.class, OtlpMetricsExportAutoConfiguration.class))
                .withPropertyValues("spring.config.location=classpath:/application.yml",
                    "management.metrics.use-global-registry=false",
                    "management.otlp.metrics.export.step=1h",
                    "APPROVAL_OTLP_METRICS_ENDPOINT=" + endpoint)
                .withPropertyValues(overrides)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Boolean.valueOf(enabled), context.getEnvironment().getProperty(
                        "management.otlp.metrics.export.enabled", Boolean.class));
                    assertEquals(enabled ? 1 : 0, context.getBeansOfType(OtlpMeterRegistry.class).size());
                    PrometheusMeterRegistry prometheus = context.getBean(PrometheusMeterRegistry.class);
                    prometheus.counter("approval.test.export").increment();
                    assertTrue(prometheus.scrape().contains("approval_test_export_total"));
                    if (enabled) {
                        context.getBean(OtlpMeterRegistry.class).counter("approval.test.export").increment();
                    }
                });
            // The runner has closed its context: include the exporter's final flush, not only startup.
            assertFalse(rejected.get());
            if (enabled) {
                assertTrue(requests.get() > 0, "explicit opt-in must retain actual OTLP HTTP export");
            } else {
                assertEquals(0, requests.get(), "default startup and shutdown must not push metrics");
            }
        } finally {
            receiver.stop(0);
        }
    }
}
