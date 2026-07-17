package io.github.akaryc1b.approval.ruoyi5.host;

import org.dromara.common.security.config.properties.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ruoyi5SecurityExclusionPostProcessorTest {

    private final Ruoyi5SecurityExclusionPostProcessor processor =
        new Ruoyi5SecurityExclusionPostProcessor();

    @Test
    void appendsConnectorPathWithoutReplacingExistingExclusions() {
        SecurityProperties properties = new SecurityProperties();
        properties.setExcludes(new String[]{"/login", "/public/**"});

        Object result = processor.postProcessBeforeInitialization(properties, "securityProperties");

        assertEquals(properties, result);
        assertTrue(Arrays.asList(properties.getExcludes()).contains("/login"));
        assertTrue(Arrays.asList(properties.getExcludes()).contains("/public/**"));
        assertTrue(Arrays.asList(properties.getExcludes()).contains(
            Ruoyi5SecurityExclusionPostProcessor.CONNECTOR_PATH_PATTERN
        ));
    }

    @Test
    void doesNotDuplicateConnectorPath() {
        SecurityProperties properties = new SecurityProperties();
        properties.setExcludes(new String[]{
            Ruoyi5SecurityExclusionPostProcessor.CONNECTOR_PATH_PATTERN
        });

        processor.postProcessBeforeInitialization(properties, "securityProperties");

        assertEquals(1, properties.getExcludes().length);
    }

    @Test
    void ignoresUnrelatedBeans() {
        Object bean = new Object();

        assertEquals(bean, processor.postProcessBeforeInitialization(bean, "other"));
    }
}
