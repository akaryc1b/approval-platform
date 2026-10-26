package io.github.akaryc1b.approval.example.host;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExampleNetworkDefaultsTest {

    @Test
    void directJarDefaultsToLoopback() {
        assertEquals("127.0.0.1", resolvedAddress(Map.of()));
    }

    @Test
    void isolatedContainerCanExplicitlyBindItsNetworkInterface() {
        assertEquals("0.0.0.0", resolvedAddress(Map.of("SERVER_ADDRESS", "0.0.0.0")));
    }

    @Test
    void explicitLoopbackAddressIsPreserved() {
        assertEquals("127.0.0.2", resolvedAddress(Map.of("SERVER_ADDRESS", "127.0.0.2")));
    }

    @Test
    void embeddedHostServesHealthOnDefaultLoopback() throws Exception {
        String temporaryKey = UUID.randomUUID().toString();
        SpringApplication application = new SpringApplication(GenericSpringHostApplication.class);
        application.setEnvironment(isolatedEnvironment(Map.of()));
        try (ConfigurableApplicationContext context = application.run(
            "--server.port=0",
            "--example.host.secret=" + temporaryKey,
            "--example.host.bearer-token=" + UUID.randomUUID()
        )) {
            assertEquals("127.0.0.1", context.getEnvironment().getProperty("server.address"));
            String port = context.getEnvironment().getRequiredProperty("local.server.port");
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/actuator/health")
                ).timeout(Duration.ofSeconds(10)).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
            }
        }
    }

    private String resolvedAddress(Map<String, Object> values) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        return isolatedEnvironment(values).resolveRequiredPlaceholders(properties.getProperty("server.address"));
    }

    private StandardEnvironment isolatedEnvironment(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        return environment;
    }
}
