package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver;
import io.github.akaryc1b.approval.application.port.ProcessTemplateTenantRegistryResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTemplateImportConfigurationTest {

    @Test
    void configurationIsExplicitAndUsesOnlyLocalResolverImplementations() {
        Configuration configuration = ProcessTemplateImportConfiguration.class.getAnnotation(
            Configuration.class
        );
        assertNotNull(configuration);
        assertFalse(configuration.proxyBeanMethods());

        Set<Class<?>> returnTypes = Arrays.stream(
            ProcessTemplateImportConfiguration.class.getDeclaredMethods()
        ).map(Method::getReturnType).collect(java.util.stream.Collectors.toSet());

        assertTrue(returnTypes.contains(ProcessTemplateTenantRegistryResolver.class));
        assertTrue(returnTypes.contains(ProcessTemplateFormPackageEvidenceResolver.class));
        assertTrue(returnTypes.contains(ProcessTemplateGovernedImportCoordinator.class));
    }

    @Test
    void capabilityConfigurationDefaultsAreClosedAndCsvIsDeterministic() {
        assertEquals(Set.of(), ProcessTemplateImportConfiguration.csv(""));
        assertEquals(
            Set.of("business.read", "directory.read"),
            ProcessTemplateImportConfiguration.csv(
                " directory.read, business.read,directory.read "
            )
        );
    }
}
