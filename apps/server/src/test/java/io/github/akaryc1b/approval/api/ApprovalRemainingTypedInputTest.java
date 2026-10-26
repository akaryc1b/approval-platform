package io.github.akaryc1b.approval.api;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Finite representative binding inventory, independent of production authentication and persistence. */
class ApprovalRemainingTypedInputTest {
    @TestFactory
    Stream<DynamicTest> malformedInputInventory() {
        return Stream.of(
            probe("core", PurchasePaymentController.class, ApprovalApiExceptionHandler.class,
                get("/api/approval/tasks/pending?limit=bad")),
            probe("consistency", ApprovalConsistencyController.class, ApprovalConsistencyApiExceptionHandler.class,
                get("/api/approval/management/consistency/checks?limit=bad")),
            probe("delegation", ApprovalDelegationController.class, ApprovalDelegationApiExceptionHandler.class,
                get("/api/approval/delegations?includeRevoked=bad")),
            probe("design", ApprovalDesignController.class, ApprovalDesignApiExceptionHandler.class,
                get("/api/approval/process-design-drafts?limit=bad")),
            probe("handover", ApprovalHandoverController.class, ApprovalHandoverApiExceptionHandler.class,
                get("/api/approval/handovers?principalId=actor&includeRevoked=bad")),
            probe("migration typed", ApprovalMigrationOperationsController.class, ApprovalMigrationOperationsApiExceptionHandler.class,
                get("/api/approval/management/process-instance-operations/plans?limit=bad")),
            probe("migration validation", ApprovalMigrationOperationsController.class, ApprovalMigrationOperationsApiExceptionHandler.class,
                get("/api/approval/management/process-instance-operations/plans?limit=0")),
            probe("operations typed", ApprovalOperationalFailureQueryController.class, ApprovalOperationalFailureApiExceptionHandler.class,
                get("/api/approval/management/operational-failures?limit=bad")),
            probe("operations validation", ApprovalOperationalFailureQueryController.class, ApprovalOperationalFailureApiExceptionHandler.class,
                get("/api/approval/management/operational-failures?limit=0")),
            probe("release disposition", ApprovalProcessReleaseDispositionController.class, ApprovalProcessReleaseDispositionApiExceptionHandler.class,
                get("/api/approval/version-management/purchase-payment/releases/bad/lifecycle")),
            probe("release migration", ApprovalProcessReleaseMigrationAssessmentController.class, ApprovalProcessReleaseMigrationAssessmentApiExceptionHandler.class,
                post("/api/approval/version-management/purchase-payment/releases/bad/migration-dry-run")
                    .contentType("application/json").content("{}")),
            probe("deployment", ApprovalReleaseDeploymentController.class, ApprovalReleaseDeploymentApiExceptionHandler.class,
                get("/api/approval/release-packages/purchase-payment/bad/deployment")),
            probe("SLA execution typed", ApprovalSlaExecutionManagementController.class, ApprovalSlaExecutionApiExceptionHandler.class,
                get("/api/approval/management/sla/executions?limit=bad")),
            probe("SLA execution validation", ApprovalSlaExecutionManagementController.class, ApprovalSlaExecutionApiExceptionHandler.class,
                get("/api/approval/management/sla/executions?limit=0")),
            probe("collaboration typed", ApprovalTaskCollaborationController.class, ApprovalTaskCollaborationApiExceptionHandler.class,
                get("/api/approval/collaboration/tasks/pending?limit=bad")),
            probe("collaboration validation", ApprovalTaskCollaborationController.class, ApprovalTaskCollaborationApiExceptionHandler.class,
                get("/api/approval/collaboration/tasks/pending?limit=0")),
            probe("calendar validation", ApprovalCalendarManagementController.class, ApprovalSlaApiExceptionHandler.class,
                get("/api/approval/management/sla/calendars?limit=0")),
            probe("messages existing default", ApprovalMessageController.class, ApprovalMessageApiExceptionHandler.class,
                get("/api/approval/messages?limit=bad")),
            new Probe("template strict JSON", ProcessTemplateManagementController.class, ProcessTemplateManagementApiExceptionHandler.class,
                post("/api/approval/management/process-template-imports/previews").contentType("application/json").content("{"), 422)
        ).map(probe -> DynamicTest.dynamicTest(probe.name(), () -> {
            List<Object> services = new ArrayList<>();
            Object controller = instantiate(probe.controller(), services);
            Object advice = instantiate(probe.advice(), new ArrayList<>());
            var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
            var response = mvc.perform(probe.request().header("X-Tenant-Id", "tenant-a")
                .header("X-Operator-Id", "actor").header("X-Request-Id", "request-test")
                .header("Idempotency-Key", "key-test").header("X-Approval-Operation-Reason", "isolated input test"))
                .andReturn().getResponse();
            System.out.println("INPUT_INVENTORY " + probe.name() + " status=" + response.getStatus()
                + " body=" + response.getContentAsString());
            assertEquals(probe.expectedStatus(), response.getStatus());
            if (probe.expectedStatus() == 400 && !probe.name().equals("messages existing default")) {
                var invalid = probe.advice().getDeclaredMethod("invalidRequest", Exception.class,
                    jakarta.servlet.http.HttpServletRequest.class);
                invalid.setAccessible(true);
                var expected = (org.springframework.http.ResponseEntity<?>) invalid.invoke(advice,
                    new IllegalArgumentException("request input is invalid"),
                    new org.springframework.mock.web.MockHttpServletRequest());
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
                assertEquals(mapper.valueToTree(expected.getBody()).get("code"),
                    mapper.readTree(response.getContentAsString()).get("code"));
            }
            verifyNoInteractions(services.toArray());
        }));
    }

    @TestFactory
    Stream<DynamicTest> genericFailuresAndReturnValidationRemainServerErrors() {
        return Stream.of(
            ApprovalApiExceptionHandler.class, ApprovalConsistencyApiExceptionHandler.class,
            ApprovalDelegationApiExceptionHandler.class, ApprovalDesignApiExceptionHandler.class,
            ApprovalHandoverApiExceptionHandler.class, ApprovalMigrationOperationsApiExceptionHandler.class,
            ApprovalOperationalFailureApiExceptionHandler.class, ApprovalProcessReleaseDispositionApiExceptionHandler.class,
            ApprovalProcessReleaseMigrationAssessmentApiExceptionHandler.class, ApprovalReleaseDeploymentApiExceptionHandler.class,
            ApprovalSlaExecutionApiExceptionHandler.class, ApprovalTaskCollaborationApiExceptionHandler.class,
            ApprovalSlaApiExceptionHandler.class
        ).map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> {
            Object advice = instantiate(type, new ArrayList<>());
            var resolver = new org.springframework.web.method.annotation.ExceptionHandlerMethodResolver(type);
            var failure = new RuntimeException("private storage failure");
            var method = resolver.resolveMethod(failure);
            method.setAccessible(true);
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            Object[] arguments = Arrays.stream(method.getParameterTypes())
                .map(parameter -> parameter == jakarta.servlet.http.HttpServletRequest.class ? request : failure)
                .toArray();
            var response = (org.springframework.http.ResponseEntity<?>) method.invoke(advice, arguments);
            assertEquals(500, response.getStatusCode().value());
            assertFalse(response.getBody().toString().contains("private storage failure"));
            for (var candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals("methodValidation")) {
                    var invalidReturn = mock(org.springframework.web.method.annotation.HandlerMethodValidationException.class);
                    when(invalidReturn.isForReturnValue()).thenReturn(true);
                    candidate.setAccessible(true);
                    var returnResponse = (org.springframework.http.ResponseEntity<?>) candidate.invoke(advice, invalidReturn, request);
                    assertEquals(500, returnResponse.getStatusCode().value());
                }
            }
        }));
    }

    private static Probe probe(String name, Class<?> controller, Class<?> advice, MockHttpServletRequestBuilder request) {
        return new Probe(name, controller, advice, request, 400);
    }

    private static Object instantiate(Class<?> type, List<Object> services) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes()).map(parameter -> {
            if (parameter == Clock.class) {
                return Clock.systemUTC();
            }
            if (parameter == ProcessTemplateManagementJsonCodec.class) {
                return new ProcessTemplateManagementJsonCodec(
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                    mock(ProcessTemplatePackageJsonCodec.class), mock(ApprovalArtifactTransferJsonCodec.class)
                );
            }
            Object service = mock(parameter);
            services.add(service);
            return service;
        }).toArray();
        return constructor.newInstance(arguments);
    }

    private record Probe(String name, Class<?> controller, Class<?> advice,
                         MockHttpServletRequestBuilder request, int expectedStatus) { }
}
