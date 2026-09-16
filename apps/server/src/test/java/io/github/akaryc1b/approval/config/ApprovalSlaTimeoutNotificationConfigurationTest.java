package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaTimeoutEventRecorder;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import io.github.akaryc1b.approval.integration.outbox.BusinessCallbackResolver;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.SlaTimeoutNotificationConnector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ApprovalSlaTimeoutNotificationConfigurationTest {
    private final BusinessCallbackConnector payment = mock(BusinessCallbackConnector.class);
    private final OrganizationConnector organization = mock(OrganizationConnector.class);

    @Test
    void defaultOffDoesNotRequireDependenciesOrRegisterARecorder() {
        new ApplicationContextRunner().withUserConfiguration(ApprovalSlaTimeoutNotificationConfiguration.class)
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(0, context.getBeansOfType(ApprovalSlaTimeoutEventRecorder.class).size());
                assertEquals(0, context.getBeansOfType(BusinessCallbackResolver.class).size());
            });
    }

    @Test
    void enabledRouteDecoratesOnlyTheReservedKeyAndDoesNotSendOnStartup() {
        configured(true, true, "generic-rest").run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(ApprovalSlaTimeoutEventRecorder.class));
            var routes = context.getBean(BusinessCallbackResolver.class);
            assertInstanceOf(SlaTimeoutNotificationConnector.class, routes.resolve(SlaTimeoutNotificationConnector.ROUTE));
            assertSame(payment, routes.resolve("generic-rest"));
            assertThrows(IllegalArgumentException.class, () -> routes.resolve("unknown"));
            assertEquals(1, context.getBeansOfType(DataSource.class).size());
            assertEquals(0, context.getBeansOfType(BusinessCallbackConnector.class).size());
            verifyNoInteractions(payment, organization);
        });
    }

    @Test
    void enabledWithoutAnActiveOriginalDeliveryPathOrWithAReservedKeyFailsClosed() {
        configured(false, true, "generic-rest").run(context -> assertNotNull(context.getStartupFailure()));
        configured(true, false, "generic-rest").run(context -> assertNotNull(context.getStartupFailure()));
        configured(true, true, SlaTimeoutNotificationConnector.ROUTE)
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void disabledFeatureCannotLeakPreviouslyQueuedTimeoutsToThePaymentCallback() {
        configured(true, true, "generic-rest")
            .withPropertyValues("approval.sla.operational-notifications.enabled=false").run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(0, context.getBeansOfType(ApprovalSlaTimeoutEventRecorder.class).size());
                var routes = context.getBean(BusinessCallbackResolver.class);
                var rejected = routes.resolve(SlaTimeoutNotificationConnector.ROUTE).deliver(null, null);
                assertEquals(BusinessCallbackConnector.DeliveryStatus.PERMANENT_FAILURE, rejected.status());
                assertEquals("SLA_NOTIFICATION_DISABLED", rejected.errorMessage());
                assertSame(payment, routes.resolve("generic-rest"));
                verifyNoInteractions(payment, organization);
            });
    }

    private ApplicationContextRunner configured(boolean generic, boolean dispatch, String connectorKey) {
        var properties = new GenericConnectorProperties();
        properties.setEnabled(generic); properties.setConnectorKey(connectorKey);
        properties.getDispatch().setEnabled(dispatch);
        return new ApplicationContextRunner().withUserConfiguration(ApprovalSlaTimeoutNotificationConfiguration.class)
            .withPropertyValues("approval.sla.operational-notifications.enabled=true",
                "approval.connector.generic.enabled=" + generic)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ApprovalSlaExecutionStore.class, () -> mock(ApprovalSlaExecutionStore.class))
            .withBean(ApprovalSlaActionStateRecorder.class, () -> mock(ApprovalSlaActionStateRecorder.class))
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(GenericConnectorProperties.class, () -> properties)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(OrganizationConnector.class, () -> organization)
            .withBean("businessCallbackResolver", BusinessCallbackResolver.class, () -> key -> {
                if (!"generic-rest".equals(key)) throw new IllegalArgumentException("original route rejected");
                return payment;
            });
    }
}
