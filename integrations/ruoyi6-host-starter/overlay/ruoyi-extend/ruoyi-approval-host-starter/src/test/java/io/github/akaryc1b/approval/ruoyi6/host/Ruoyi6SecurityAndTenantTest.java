package io.github.akaryc1b.approval.ruoyi6.host;

import org.dromara.common.security.config.properties.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ruoyi6SecurityAndTenantTest {

    private final Ruoyi6SecurityExclusionPostProcessor processor =
        new Ruoyi6SecurityExclusionPostProcessor();

    @Test
    void appendsConnectorPathWithoutReplacingExistingExclusions() {
        SecurityProperties properties = new SecurityProperties();
        properties.setExcludes(new String[]{"/login", "/health"});

        processor.postProcessBeforeInitialization(properties, "securityProperties");

        assertTrue(Arrays.asList(properties.getExcludes()).contains("/login"));
        assertTrue(Arrays.asList(properties.getExcludes()).contains("/health"));
        assertTrue(Arrays.asList(properties.getExcludes()).contains(
            Ruoyi6SecurityExclusionPostProcessor.CONNECTOR_PATH_PATTERN
        ));
    }

    @Test
    void doesNotDuplicateConnectorPath() {
        SecurityProperties properties = new SecurityProperties();
        properties.setExcludes(new String[]{
            Ruoyi6SecurityExclusionPostProcessor.CONNECTOR_PATH_PATTERN
        });

        processor.postProcessBeforeInitialization(properties, "securityProperties");

        long occurrences = Arrays.stream(properties.getExcludes())
            .filter(Ruoyi6SecurityExclusionPostProcessor.CONNECTOR_PATH_PATTERN::equals)
            .count();
        assertEquals(1, occurrences);
    }

    @Test
    void singleTenantBridgeRejectsOtherPlatformTenants() {
        ApprovalHostProperties properties = new ApprovalHostProperties();
        properties.setTenantId("approval-tenant-a");
        properties.setTenantName("Primary RuoYi 6 deployment");
        SingleTenantBridge bridge = new SingleTenantBridge(properties);

        var descriptor = bridge.resolve("approval-tenant-a");
        assertEquals("approval-tenant-a", descriptor.id());
        assertEquals("single-tenant", descriptor.attributes().get("mode"));

        Ruoyi6HostException failure = assertThrows(
            Ruoyi6HostException.class,
            () -> bridge.resolve("approval-tenant-b")
        );
        assertEquals(403, failure.status());
        assertEquals("TENANT_MISMATCH", failure.code());
    }

    @Test
    void ignoresUnrelatedBeans() {
        Object bean = new Object();
        assertEquals(bean, processor.postProcessBeforeInitialization(bean, "other"));
    }
}
