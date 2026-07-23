package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApprovalRuntimeBindingEvidenceConfigurationTest {

    @Test
    void productionWrappersArePrimaryAndUseExplicitRawDelegates() {
        Method projection = method("runtimeBindingEnforcingProjectionStore");
        Method audit = method("runtimeBindingRecordingAuditEventSink");

        assertBean(projection, ApprovalProjectionStore.class);
        assertBean(audit, AuditEventSink.class);
        assertQualifier(projection.getParameters()[0], "approvalProjectionStore");
        assertQualifier(audit.getParameters()[0], "auditEventSink");
        assertQualifier(audit.getParameters()[1], "approvalProjectionStore");
    }

    private static Method method(String name) {
        return Arrays.stream(
            ApprovalRuntimeBindingEvidenceConfiguration.class.getDeclaredMethods()
        ).filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static void assertBean(Method method, Class<?> returnType) {
        assertEquals(returnType, method.getReturnType());
        assertNotNull(method.getAnnotation(Bean.class));
        assertNotNull(method.getAnnotation(Primary.class));
    }

    private static void assertQualifier(Parameter parameter, String expected) {
        Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
        assertNotNull(qualifier);
        assertEquals(expected, qualifier.value());
    }
}
