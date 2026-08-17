package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApprovalRuntimeBindingEvidenceConfigurationTest {

    @Test
    void productionWrappersComposeOnePrimaryAuditChainWithExplicitDelegates() {
        Method projection = method(
            ApprovalRuntimeBindingEvidenceConfiguration.class,
            "runtimeBindingEnforcingProjectionStore"
        );
        Method runtimeAudit = method(
            ApprovalRuntimeBindingEvidenceConfiguration.class,
            "runtimeBindingRecordingAuditEventSink"
        );
        Method notificationAudit = method(
            ApprovalNotificationConfiguration.class,
            "notificationAwareAuditEventSink"
        );

        assertPrimaryBean(projection, ApprovalProjectionStore.class);
        assertPrimaryBean(runtimeAudit, AuditEventSink.class);
        assertBean(notificationAudit, AuditEventSink.class);
        assertNull(notificationAudit.getAnnotation(Primary.class));
        assertQualifier(projection.getParameters()[0], "approvalProjectionStore");
        assertEquals(MeterRegistry.class, projection.getParameterTypes()[2]);
        assertQualifier(
            runtimeAudit.getParameters()[0],
            "notificationAwareAuditEventSink"
        );
        assertQualifier(runtimeAudit.getParameters()[1], "approvalProjectionStore");
        assertQualifier(notificationAudit.getParameters()[0], "auditEventSink");
    }

    private static Method method(Class<?> configuration, String name) {
        return Arrays.stream(configuration.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static void assertPrimaryBean(Method method, Class<?> returnType) {
        assertBean(method, returnType);
        assertNotNull(method.getAnnotation(Primary.class));
    }

    private static void assertBean(Method method, Class<?> returnType) {
        assertEquals(returnType, method.getReturnType());
        assertNotNull(method.getAnnotation(Bean.class));
    }

    private static void assertQualifier(Parameter parameter, String expected) {
        Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
        assertNotNull(qualifier);
        assertEquals(expected, qualifier.value());
    }
}
