package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseLifecycleService;
import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.config.GenericConnectorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class PurchasePaymentDemoSeedConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(
            PurchasePaymentDemoConfiguration.class,
            PurchasePaymentDemoClockConfiguration.class,
            PurchasePaymentDemoPaymentSandboxConfiguration.class
        )
        .withPropertyValues("approval.demo.purchase-payment.enabled=true")
        .withBean("approvalPersistenceObjectMapper", ObjectMapper.class,
            () -> new ObjectMapper().findAndRegisterModules())
        .withBean(ApprovalFormDesignService.class, () -> mock(ApprovalFormDesignService.class))
        .withBean(ApprovalDesignService.class, () -> mock(ApprovalDesignService.class))
        .withBean(ApprovalReleasePreflightService.class, () -> mock(ApprovalReleasePreflightService.class))
        .withBean(ApprovalProcessReleaseLifecycleService.class,
            () -> mock(ApprovalProcessReleaseLifecycleService.class))
        .withBean(ApprovalReleaseDeploymentService.class, () -> mock(ApprovalReleaseDeploymentService.class))
        .withBean(ApprovalProcessReleaseActivationService.class,
            () -> mock(ApprovalProcessReleaseActivationService.class))
        .withBean(PurchasePaymentApplicationService.class, () -> mock(PurchasePaymentApplicationService.class))
        .withBean(ApprovalFormSubmissionService.class, () -> mock(ApprovalFormSubmissionService.class))
        .withBean(IdempotencyGuard.class, () -> mock(IdempotencyGuard.class))
        .withBean(ApprovalProjectionStore.class, () -> mock(ApprovalProjectionStore.class))
        .withBean(ApprovalAttachmentStore.class, () -> mock(ApprovalAttachmentStore.class))
        .withBean(ApprovalMessageStore.class, () -> mock(ApprovalMessageStore.class))
        .withBean(ApprovalCommentStore.class, () -> mock(ApprovalCommentStore.class))
        .withBean(BeanPostProcessor.class, () -> new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof PurchasePaymentDemoSeeder ? spy(bean) : bean;
            }
        });

    @Test
    void missingOrTrueSeedSwitchRetainsTheRunnerAndIncompleteSeedHealth(CapturedOutput output) {
        for (String property : new String[] { "", "approval.demo.purchase-payment.seed.enabled=true" }) {
            var configured = runner.withPropertyValues("spring.profiles.active=local");
            if (!property.isEmpty()) {
                configured = configured.withPropertyValues(property);
            }
            configured.run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.containsBean("purchasePaymentDemoSeedRunner"));
                assertEquals("DOWN", context.getBean("purchasePaymentDemoSeed", HealthIndicator.class)
                    .health().getStatus().getCode());
                context.publishEvent(mock(ApplicationReadyEvent.class));
                assertFalse(output.getAll().contains("PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED"));
                assertFalse(output.getAll().contains("PURCHASE_PAYMENT_DEMO_SEED_APPLIED"));
            });
        }
    }

    @Test
    void restoreOnlyPreservesDependenciesAndNeverRunsTheSeed(CapturedOutput output) {
        runner.withPropertyValues(
            "spring.profiles.active=local",
            "approval.demo.purchase-payment.seed.enabled=false",
            "approval.demo.purchase-payment.sandbox.enabled=true"
        ).withBean(GenericConnectorProperties.class, () -> {
            var properties = new GenericConnectorProperties();
            properties.setKeyId("restore-only-test");
            properties.setSecret("restore-only-test-secret-material-at-least-32-bytes");
            return properties;
        }).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("purchasePaymentDemoSeedRunner"));
            assertFalse(context.containsBean("purchasePaymentDemoSeed"));
            assertNotNull(context.getBean(PurchasePaymentDemoScenario.class));
            assertNotNull(context.getBean(PurchasePaymentDemoOrganizationConnector.class));
            assertNotNull(context.getBean(PurchasePaymentAssigneeResolver.class));
            assertEquals(0, context.getBean(Clock.class).instant().getNano() % 1_000);
            assertNotNull(context.getBean(PurchasePaymentDemoPaymentSandbox.class));
            assertTrue(context.containsBean("initializePurchasePaymentDemoPaymentSandbox"));
            assertDoesNotThrow(() -> {
                for (ApplicationRunner applicationRunner : context.getBeansOfType(ApplicationRunner.class).values()) {
                    applicationRunner.run(new DefaultApplicationArguments(new String[0]));
                }
            });
            assertFalse(output.getAll().contains("PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED"));
            context.publishEvent(mock(ApplicationReadyEvent.class));
            assertTrue(output.getAll().contains("PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED"));
            assertTrue(output.getAll().contains("restoredDataVerified=false"));
            assertFalse(output.getAll().contains("PURCHASE_PAYMENT_DEMO_SEED_APPLIED"));
            verify(context.getBean(PurchasePaymentDemoSeeder.class), never()).apply();
            assertTrue(context.getBean(PurchasePaymentDemoSeedState.class).evidence().isEmpty());
            verifyNoInteractions(
                context.getBean(ApprovalFormSubmissionService.class),
                context.getBean(IdempotencyGuard.class),
                context.getBean(PurchasePaymentApplicationService.class),
                context.getBean(ApprovalFormDesignService.class),
                context.getBean(ApprovalDesignService.class),
                context.getBean(ApprovalReleasePreflightService.class),
                context.getBean(ApprovalProcessReleaseLifecycleService.class),
                context.getBean(ApprovalReleaseDeploymentService.class),
                context.getBean(ApprovalProcessReleaseActivationService.class),
                context.getBean(ApprovalProjectionStore.class),
                context.getBean(ApprovalAttachmentStore.class),
                context.getBean(ApprovalMessageStore.class),
                context.getBean(ApprovalCommentStore.class)
            );
        });
    }

    @Test
    void invalidSeedSwitchCannotSilentlyDisableSeedAndHealth() {
        for (String value : new String[] { "", "TRUE", "FALSE", "yes", "0", "restore-only", "invalid" }) {
            runner.withPropertyValues(
                "spring.profiles.active=local",
                "approval.demo.purchase-payment.seed.enabled=" + value
            ).run(context -> {
                assertNotNull(context.getStartupFailure());
                Throwable failure = context.getStartupFailure();
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                assertEquals(
                    "approval.demo.purchase-payment.seed.enabled must be exactly true or false",
                    failure.getMessage()
                );
            });
        }
    }

    @Test
    void restoreOnlyDoesNotEnableDemoOutsideLocalOrWithoutTheDemoSwitch(CapturedOutput output) {
        for (String profile : new String[] { "", "prod" }) {
            runner.withPropertyValues(
                "spring.profiles.active=" + profile,
                "approval.demo.purchase-payment.seed.enabled=false"
            ).run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("purchasePaymentDemoStartupMode"));
                assertFalse(context.containsBean("purchasePaymentDemoSeedRunner"));
                assertFalse(context.containsBean("purchasePaymentDemoScenario"));
                assertFalse(context.containsBean("purchasePaymentDemoClock"));
                context.publishEvent(mock(ApplicationReadyEvent.class));
                assertFalse(output.getAll().contains("PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED"));
            });
        }
        runner.withPropertyValues(
            "spring.profiles.active=local",
            "approval.demo.purchase-payment.enabled=false",
            "approval.demo.purchase-payment.seed.enabled=false"
        ).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("purchasePaymentDemoStartupMode"));
            assertFalse(context.containsBean("purchasePaymentDemoScenario"));
        });
    }

    @Test
    void slaDemoAuthoritiesRequireLocalExplicitDemoAndEnabledSeed() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.containsBean("purchasePaymentDemoSlaAuthorities"));
        });
        for (String profile : new String[] { "", "prod", "test", "local,prod", "local,production" }) {
            runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("purchasePaymentDemoSlaAuthorities"));
            });
        }
        for (String property : new String[] { "approval.demo.purchase-payment.enabled=false",
            "approval.demo.purchase-payment.seed.enabled=false" }) {
            runner.withPropertyValues("spring.profiles.active=local", property).run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("purchasePaymentDemoSlaAuthorities"));
            });
        }
    }

}
