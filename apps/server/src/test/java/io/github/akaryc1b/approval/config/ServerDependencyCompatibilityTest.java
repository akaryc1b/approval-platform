package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDependencyCompatibilityTest {

    @Test
    void resolvedServerClasspathKeepsBothJacksonFamiliesAndEmbeddedTomcatAligned() throws Exception {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("org.springframework.boot.SpringApplication", "4.0.8"),
            Map.entry("org.springframework.context.ApplicationContext", "7.0.9"),
            Map.entry("org.springframework.web.servlet.DispatcherServlet", "7.0.9"),
            Map.entry("com.fasterxml.jackson.annotation.JsonProperty", "2.21"),
            Map.entry("com.fasterxml.jackson.core.JsonFactory", "2.21.7"),
            Map.entry("com.fasterxml.jackson.databind.ObjectMapper", "2.21.7"),
            Map.entry("tools.jackson.core.json.JsonFactory", "3.1.7"),
            Map.entry("tools.jackson.databind.ObjectMapper", "3.1.7"),
            Map.entry("org.apache.catalina.startup.Tomcat", "11.0.26"),
            Map.entry("org.apache.el.ExpressionFactoryImpl", "11.0.26"),
            Map.entry("org.apache.tomcat.websocket.WsWebSocketContainer", "11.0.26")
        );
        for (var entry : expected.entrySet()) {
            Class<?> implementation = Class.forName(entry.getKey(), false, getClass().getClassLoader());
            assertEquals(entry.getValue(), implementation.getPackage().getImplementationVersion(), entry.getKey());
        }
    }

    @Test
    void persistenceAndJackson3ExchangeAnnotatedApprovalDataWithoutLosingPrecision() throws Exception {
        var persistenceMapper = new ApprovalPlatformConfiguration().approvalPersistenceObjectMapper();
        var jackson3Mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var expected = new ApprovalPayload(
            UUID.fromString("12345678-1234-4321-9876-123456789012"), new BigDecimal("12345678901234567890.12")
        );

        String persistenceJson = persistenceMapper.writeValueAsString(expected);
        String jackson3Json = jackson3Mapper.writeValueAsString(expected);
        assertEquals(expected, jackson3Mapper.readValue(persistenceJson, ApprovalPayload.class));
        assertEquals(expected, persistenceMapper.readValue(jackson3Json, ApprovalPayload.class));
        assertTrue(jackson3Mapper.readTree(persistenceJson).has("request_id"));
        assertFalse(jackson3Mapper.readTree(persistenceJson).has("requestId"));
        assertTrue(persistenceMapper.readTree(jackson3Json).has("request_id"));
        assertFalse(persistenceMapper.readTree(jackson3Json).has("requestId"));
    }

    public record ApprovalPayload(
        @JsonProperty("request_id") UUID requestId,
        @JsonProperty("amount") BigDecimal amount
    ) {
    }
}
