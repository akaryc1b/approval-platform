#!/usr/bin/env bash
set -euo pipefail

cat > apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalManagementPermission.java <<'JAVA'
package io.github.akaryc1b.approval.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the closed management capability required by an Approval administration API. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApprovalManagementPermission {

    String ADMIN_AUTHORITY = "approval.management.admin";

    Requirement value();

    enum Requirement {
        READ("approval.management.read", "read"),
        DESIGN("approval.management.design", "design"),
        PUBLISH("approval.management.publish", "publish"),
        DEPLOY("approval.management.deploy", "deploy"),
        ACTIVATE("approval.management.activate", "activate"),
        TRANSFER("approval.management.transfer", "transfer");

        private final String authority;
        private final String metricTag;

        Requirement(String authority, String metricTag) {
            this.authority = authority;
            this.metricTag = metricTag;
        }

        public String authority() {
            return authority;
        }

        public String metricTag() {
            return metricTag;
        }
    }
}
JAVA

cat > apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalManagementPermissionDeniedException.java <<'JAVA'
package io.github.akaryc1b.approval.api;

/** Stable authorization failure that never exposes the caller's supplied authority set. */
public final class ApprovalManagementPermissionDeniedException extends RuntimeException {

    private final ApprovalManagementPermission.Requirement requirement;
    private final Reason reason;

    public ApprovalManagementPermissionDeniedException(
        ApprovalManagementPermission.Requirement requirement,
        Reason reason
    ) {
        super("operator is not permitted to perform this approval management operation");
        this.requirement = requirement;
        this.reason = reason;
    }

    public ApprovalManagementPermission.Requirement requirement() {
        return requirement;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNAUTHENTICATED,
        MISSING_TRUSTED_HEADER,
        MALFORMED_TRUSTED_HEADER,
        INSUFFICIENT_PERMISSION
    }
}
JAVA

cat > apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalManagementPermissionInterceptor.java <<'JAVA'
package io.github.akaryc1b.approval.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed management authorization with low-cardinality observability. */
public final class ApprovalManagementPermissionInterceptor implements HandlerInterceptor {

    static final String TRUSTED_PERMISSION_HEADER = "X-Approval-Trusted-Permissions";

    private static final Logger LOGGER = LoggerFactory.getLogger(
        ApprovalManagementPermissionInterceptor.class
    );
    private static final Pattern AUTHORITY = Pattern.compile("[a-z][a-z0-9.-]{2,63}");
    private static final Pattern SAFE_LOG_VALUE = Pattern.compile("[^A-Za-z0-9_.:@-]");
    private static final int MAX_HEADER_LENGTH = 2_048;
    private static final int MAX_AUTHORITIES = 32;
    private static final int MAX_LOG_VALUE_LENGTH = 128;
    private static final String AUTHORIZATION_METRIC = "approval.management.authorization";
    private static final String REQUEST_TIMER = "approval.management.request.duration";
    private static final String START_ATTRIBUTE =
        ApprovalManagementPermissionInterceptor.class.getName() + ".start";
    private static final String REQUIREMENT_ATTRIBUTE =
        ApprovalManagementPermissionInterceptor.class.getName() + ".requirement";

    private final boolean enforced;
    private final AuthoritySource authoritySource;
    private final String trustedPermissionHeader;
    private final MeterRegistry meters;

    public ApprovalManagementPermissionInterceptor(
        boolean enforced,
        AuthoritySource authoritySource,
        String trustedPermissionHeader,
        MeterRegistry meters
    ) {
        this.enforced = enforced;
        this.authoritySource = Objects.requireNonNull(authoritySource);
        this.trustedPermissionHeader = requireText(
            trustedPermissionHeader,
            "trustedPermissionHeader"
        );
        this.meters = Objects.requireNonNull(meters);
    }

    @Override
    public boolean preHandle(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler
    ) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        ApprovalManagementPermission permission = permission(handlerMethod);
        if (permission == null) {
            return true;
        }
        ApprovalManagementPermission.Requirement requirement = permission.value();
        if (!enforced) {
            recordAuthorization(requirement, "bypassed");
            startObservation(request, requirement);
            return true;
        }
        boolean allowed = switch (authoritySource) {
            case PRINCIPAL -> principalAllows(request, requirement);
            case TRUSTED_HEADER -> trustedHeaderAllows(request, requirement);
        };
        if (!allowed) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.INSUFFICIENT_PERMISSION
            );
        }
        recordAuthorization(requirement, "allowed");
        startObservation(request, requirement);
        return true;
    }

    @Override
    public void afterCompletion(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler,
        Exception exception
    ) {
        Object startValue = request.getAttribute(START_ATTRIBUTE);
        Object requirementValue = request.getAttribute(REQUIREMENT_ATTRIBUTE);
        if (!(startValue instanceof Long start)
            || !(requirementValue instanceof ApprovalManagementPermission.Requirement requirement)) {
            return;
        }
        String outcome;
        if (exception != null || response.getStatus() >= 500) {
            outcome = "server_error";
        } else if (response.getStatus() >= 400) {
            outcome = "client_error";
        } else {
            outcome = "success";
        }
        Timer.builder(REQUEST_TIMER)
            .tag("requirement", requirement.metricTag())
            .tag("outcome", outcome)
            .register(meters)
            .record(Duration.ofNanos(Math.max(0L, System.nanoTime() - start)));
    }

    private boolean principalAllows(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement
    ) {
        if (request.getUserPrincipal() == null) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.UNAUTHENTICATED
            );
        }
        return request.isUserInRole(ApprovalManagementPermission.ADMIN_AUTHORITY)
            || request.isUserInRole(requirement.authority());
    }

    private boolean trustedHeaderAllows(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement
    ) {
        String header = request.getHeader(trustedPermissionHeader);
        if (header == null || header.isBlank()) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.MISSING_TRUSTED_HEADER
            );
        }
        Set<String> authorities;
        try {
            authorities = parseAuthorities(header);
        } catch (IllegalArgumentException exception) {
            deny(
                request,
                requirement,
                ApprovalManagementPermissionDeniedException.Reason.MALFORMED_TRUSTED_HEADER
            );
            return false;
        }
        return authorities.contains(ApprovalManagementPermission.ADMIN_AUTHORITY)
            || authorities.contains(requirement.authority());
    }

    private void deny(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement,
        ApprovalManagementPermissionDeniedException.Reason reason
    ) {
        recordAuthorization(requirement, "denied");
        LOGGER.warn(
            "event=APPROVAL_MANAGEMENT_ACCESS_DENIED tenantId={} operatorId={} "
                + "requestId={} requiredAuthority={} reason={}",
            safeLogValue(request.getHeader("X-Tenant-Id")),
            operatorIdentity(request),
            safeLogValue(request.getHeader("X-Request-Id")),
            requirement.authority(),
            reason
        );
        throw new ApprovalManagementPermissionDeniedException(requirement, reason);
    }

    private String operatorIdentity(HttpServletRequest request) {
        if (authoritySource == AuthoritySource.PRINCIPAL
            && request.getUserPrincipal() != null) {
            return safeLogValue(request.getUserPrincipal().getName());
        }
        return safeLogValue(request.getHeader("X-Operator-Id"));
    }

    private void recordAuthorization(
        ApprovalManagementPermission.Requirement requirement,
        String outcome
    ) {
        meters.counter(
            AUTHORIZATION_METRIC,
            "requirement",
            requirement.metricTag(),
            "outcome",
            outcome
        ).increment();
    }

    private static ApprovalManagementPermission permission(HandlerMethod handlerMethod) {
        ApprovalManagementPermission method = AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getMethod(),
            ApprovalManagementPermission.class
        );
        if (method != null) {
            return method;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getBeanType(),
            ApprovalManagementPermission.class
        );
    }

    private static Set<String> parseAuthorities(String header) {
        if (header.length() > MAX_HEADER_LENGTH) {
            throw new IllegalArgumentException("permission header is too long");
        }
        String[] values = header.split(",", -1);
        if (values.length > MAX_AUTHORITIES) {
            throw new IllegalArgumentException("too many permissions");
        }
        Set<String> authorities = new LinkedHashSet<>();
        for (String value : values) {
            String authority = value.trim();
            if (!AUTHORITY.matcher(authority).matches()) {
                throw new IllegalArgumentException("malformed permission");
            }
            authorities.add(authority);
        }
        return Set.copyOf(authorities);
    }

    private static void startObservation(
        HttpServletRequest request,
        ApprovalManagementPermission.Requirement requirement
    ) {
        request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        request.setAttribute(REQUIREMENT_ATTRIBUTE, requirement);
    }

    private static String safeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "missing";
        }
        String safe = SAFE_LOG_VALUE.matcher(value.trim()).replaceAll("_");
        return safe.length() > MAX_LOG_VALUE_LENGTH
            ? safe.substring(0, MAX_LOG_VALUE_LENGTH)
            : safe;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public enum AuthoritySource {
        PRINCIPAL,
        TRUSTED_HEADER;

        public static AuthoritySource parse(String value) {
            return valueOf(requireText(value, "authoritySource")
                .replace('-', '_')
                .toUpperCase(Locale.ROOT));
        }
    }
}
JAVA

cat > apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalManagementPermissionExceptionHandler.java <<'JAVA'
package io.github.akaryc1b.approval.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class ApprovalManagementPermissionExceptionHandler {

    @ExceptionHandler(ApprovalManagementPermissionDeniedException.class)
    ResponseEntity<ApiError> denied(
        ApprovalManagementPermissionDeniedException exception,
        HttpServletRequest request
    ) {
        String requestId = requestId(request);
        return ResponseEntity.status(403)
            .header("X-Request-Id", requestId)
            .body(new ApiError(
                "APPROVAL_MANAGEMENT_PERMISSION_DENIED",
                exception.getMessage(),
                false,
                requestId,
                Instant.now()
            ));
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    public record ApiError(
        String code,
        String message,
        boolean retryable,
        String requestId,
        Instant occurredAt
    ) {
    }
}
JAVA

cat > apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalManagementPermissionConfiguration.java <<'JAVA'
package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.api.ApprovalManagementPermissionInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ApprovalManagementPermissionConfiguration implements WebMvcConfigurer {

    private final ApprovalManagementPermissionInterceptor interceptor;

    public ApprovalManagementPermissionConfiguration(
        @Value("${approval.security.management-permissions.enforced:true}") boolean enforced,
        @Value("${approval.security.management-permissions.source:principal}") String source,
        @Value(
            "${approval.security.management-permissions.trusted-header-name:"
                + "X-Approval-Trusted-Permissions}"
        ) String trustedHeaderName,
        MeterRegistry meterRegistry
    ) {
        interceptor = new ApprovalManagementPermissionInterceptor(
            enforced,
            ApprovalManagementPermissionInterceptor.AuthoritySource.parse(source),
            trustedHeaderName,
            meterRegistry
        );
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).order(-100);
    }
}
JAVA

cat > apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalManagementPermissionInterceptorTest.java <<'JAVA'
package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.api.ApprovalManagementPermissionInterceptor.AuthoritySource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalManagementPermissionInterceptorTest {

    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    @Test
    void principalAuthorityAllowsExactManagementCapability() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            true,
            AuthoritySource.PRINCIPAL
        );
        MockHttpServletRequest request = request();
        request.setUserPrincipal(principal("designer"));
        request.addUserRole(Requirement.DESIGN.authority());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handler("design")));
        interceptor.afterCompletion(request, response, handler("design"), null);

        assertEquals(1.0, authorizationCount("design", "allowed"));
        assertEquals(
            1L,
            meters.get("approval.management.request.duration")
                .tag("requirement", "design")
                .tag("outcome", "success")
                .timer()
                .count()
        );
    }

    @Test
    void adminAuthorityAllowsAllManagementCapabilities() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            true,
            AuthoritySource.PRINCIPAL
        );
        MockHttpServletRequest request = request();
        request.setUserPrincipal(principal("administrator"));
        request.addUserRole(ApprovalManagementPermission.ADMIN_AUTHORITY);

        assertTrue(interceptor.preHandle(
            request,
            new MockHttpServletResponse(),
            handler("publish")
        ));
        assertEquals(1.0, authorizationCount("publish", "allowed"));
    }

    @Test
    void missingPrincipalAndWrongRoleAreDeniedWithoutLeakingAuthorities() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            true,
            AuthoritySource.PRINCIPAL
        );
        MockHttpServletRequest missing = request();
        assertThrows(
            ApprovalManagementPermissionDeniedException.class,
            () -> interceptor.preHandle(
                missing,
                new MockHttpServletResponse(),
                handler("design")
            )
        );

        MockHttpServletRequest wrong = request();
        wrong.setUserPrincipal(principal("reader"));
        wrong.addUserRole(Requirement.READ.authority());
        ApprovalManagementPermissionDeniedException exception = assertThrows(
            ApprovalManagementPermissionDeniedException.class,
            () -> interceptor.preHandle(
                wrong,
                new MockHttpServletResponse(),
                handler("design")
            )
        );
        assertEquals(
            "operator is not permitted to perform this approval management operation",
            exception.getMessage()
        );
        assertEquals(2.0, authorizationCount("design", "denied"));
    }

    @Test
    void trustedHeaderModeAcceptsOnlyBoundedCanonicalAuthorities() throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            true,
            AuthoritySource.TRUSTED_HEADER
        );
        MockHttpServletRequest allowed = request();
        allowed.addHeader(
            ApprovalManagementPermissionInterceptor.TRUSTED_PERMISSION_HEADER,
            Requirement.READ.authority() + "," + Requirement.DESIGN.authority()
        );
        assertTrue(interceptor.preHandle(
            allowed,
            new MockHttpServletResponse(),
            handler("design")
        ));

        for (String invalid : new String[] {
            "",
            "APPROVAL.MANAGEMENT.DESIGN",
            "approval.management.design,",
            "approval.management.design<script>"
        }) {
            MockHttpServletRequest request = request();
            request.addHeader(
                ApprovalManagementPermissionInterceptor.TRUSTED_PERMISSION_HEADER,
                invalid
            );
            assertThrows(
                ApprovalManagementPermissionDeniedException.class,
                () -> interceptor.preHandle(
                    request,
                    new MockHttpServletResponse(),
                    handler("design")
                )
            );
        }
    }

    @Test
    void disabledBoundaryIsObservableAndUnannotatedRuntimeHandlerIsIgnored()
        throws Exception {
        ApprovalManagementPermissionInterceptor interceptor = interceptor(
            false,
            AuthoritySource.PRINCIPAL
        );
        assertTrue(interceptor.preHandle(
            request(),
            new MockHttpServletResponse(),
            handler("design")
        ));
        assertEquals(1.0, authorizationCount("design", "bypassed"));

        ApprovalManagementPermissionInterceptor enforced = interceptor(
            true,
            AuthoritySource.PRINCIPAL
        );
        assertTrue(enforced.preHandle(
            request(),
            new MockHttpServletResponse(),
            runtimeHandler()
        ));
    }

    private ApprovalManagementPermissionInterceptor interceptor(
        boolean enforced,
        AuthoritySource source
    ) {
        return new ApprovalManagementPermissionInterceptor(
            enforced,
            source,
            ApprovalManagementPermissionInterceptor.TRUSTED_PERMISSION_HEADER,
            meters
        );
    }

    private double authorizationCount(String requirement, String outcome) {
        return meters.get("approval.management.authorization")
            .tag("requirement", requirement)
            .tag("outcome", outcome)
            .counter()
            .count();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "tenant-a");
        request.addHeader("X-Operator-Id", "operator-a");
        request.addHeader("X-Request-Id", "request-a");
        return request;
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method method = ManagementController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(new ManagementController(), method);
    }

    private static HandlerMethod runtimeHandler() throws NoSuchMethodException {
        Method method = RuntimeController.class.getDeclaredMethod("start");
        return new HandlerMethod(new RuntimeController(), method);
    }

    @ApprovalManagementPermission(Requirement.READ)
    static class ManagementController {
        public void read() {
        }

        @ApprovalManagementPermission(Requirement.DESIGN)
        public void design() {
        }

        @ApprovalManagementPermission(Requirement.PUBLISH)
        public void publish() {
        }
    }

    static class RuntimeController {
        public void start() {
        }
    }
}
JAVA

cat > apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalManagementPermissionCoverageTest.java <<'JAVA'
package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApprovalManagementPermissionCoverageTest {

    @Test
    void managementControllersDeclareClosedCapabilities() {
        assertPermission(ApprovalFormDesignController.class, "findDrafts", Requirement.READ);
        assertPermission(ApprovalFormDesignController.class, "update", Requirement.DESIGN);
        assertPermission(ApprovalFormDesignController.class, "publish", Requirement.PUBLISH);
        assertPermission(ApprovalDesignController.class, "findDrafts", Requirement.READ);
        assertPermission(ApprovalDesignController.class, "simulate", Requirement.DESIGN);
        assertPermission(ApprovalDesignController.class, "publish", Requirement.PUBLISH);
        assertPermission(
            ApprovalBatchSimulationController.class,
            "simulate",
            Requirement.DESIGN
        );
        assertPermission(
            ApprovalReleasePreflightController.class,
            "publication",
            Requirement.PUBLISH
        );
        assertPermission(
            ApprovalReleasePreflightController.class,
            "deployment",
            Requirement.DEPLOY
        );
        assertPermission(
            ApprovalReleaseDeploymentController.class,
            "deploy",
            Requirement.DEPLOY
        );
        assertPermission(
            ApprovalReleaseDeploymentController.class,
            "find",
            Requirement.READ
        );
        assertPermission(
            ApprovalEffectiveReleaseController.class,
            "activate",
            Requirement.ACTIVATE
        );
        assertPermission(
            ApprovalEffectiveReleaseController.class,
            "rollback",
            Requirement.ACTIVATE
        );
        assertPermission(
            ApprovalArtifactTransferController.class,
            "importArtifact",
            Requirement.TRANSFER
        );
        assertPermission(
            ApprovalArtifactTransferController.class,
            "exportRelease",
            Requirement.TRANSFER
        );
        assertPermission(
            ApprovalVersionManagementController.class,
            "findVersionCenter",
            Requirement.READ
        );
    }

    private static void assertPermission(
        Class<?> controller,
        String methodName,
        Requirement expected
    ) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
        ApprovalManagementPermission permission = AnnotatedElementUtils.findMergedAnnotation(
            method,
            ApprovalManagementPermission.class
        );
        if (permission == null) {
            permission = AnnotatedElementUtils.findMergedAnnotation(
                controller,
                ApprovalManagementPermission.class
            );
        }
        assertNotNull(permission, controller.getSimpleName() + '.' + methodName);
        assertEquals(expected, permission.value(), controller.getSimpleName() + '.' + methodName);
    }
}
JAVA

python3 <<'PY'
from pathlib import Path
import re


def annotate_class(path: str, requirement: str) -> None:
    file = Path(path)
    text = file.read_text(encoding='utf-8')
    marker = '@RestController\n@RequestMapping'
    replacement = (
        f'@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.{requirement})\n'
        + marker
    )
    if marker not in text:
        raise SystemExit(f'controller marker not found: {path}')
    file.write_text(text.replace(marker, replacement, 1), encoding='utf-8')


def annotate_method(path: str, method: str, requirement: str) -> None:
    file = Path(path)
    text = file.read_text(encoding='utf-8')
    pattern = re.compile(
        r'(?m)^(    )(@(?:Post|Put|Get)Mapping(?:\([^\n]*\))?\n'
        r'    public [^\n]*\b' + re.escape(method) + r'\()'
    )
    match = pattern.search(text)
    if not match:
        raise SystemExit(f'method marker not found: {path}#{method}')
    replacement = (
        match.group(1)
        + '@ApprovalManagementPermission(ApprovalManagementPermission.Requirement.'
        + requirement
        + ')\n'
        + match.group(1)
        + match.group(2)
    )
    file.write_text(text[:match.start()] + replacement + text[match.end():], encoding='utf-8')


base = 'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
form = base + 'ApprovalFormDesignController.java'
annotate_class(form, 'READ')
for method in ['create', 'createFromPublished', 'update', 'validate', 'archive']:
    annotate_method(form, method, 'DESIGN')
annotate_method(form, 'publish', 'PUBLISH')

process = base + 'ApprovalDesignController.java'
annotate_class(process, 'READ')
for method in ['create', 'createFromPublished', 'update', 'validate', 'simulate', 'archive']:
    annotate_method(process, method, 'DESIGN')
annotate_method(process, 'publish', 'PUBLISH')

batch = base + 'ApprovalBatchSimulationController.java'
annotate_class(batch, 'DESIGN')

releases = base + 'ApprovalReleasePackageController.java'
annotate_class(releases, 'READ')

versions = base + 'ApprovalVersionManagementController.java'
annotate_class(versions, 'READ')

preflight = base + 'ApprovalReleasePreflightController.java'
annotate_method(preflight, 'publication', 'PUBLISH')
annotate_method(preflight, 'deployment', 'DEPLOY')

deployment = base + 'ApprovalReleaseDeploymentController.java'
annotate_class(deployment, 'READ')
annotate_method(deployment, 'deploy', 'DEPLOY')

effective = base + 'ApprovalEffectiveReleaseController.java'
annotate_class(effective, 'READ')
annotate_method(effective, 'activate', 'ACTIVATE')
annotate_method(effective, 'rollback', 'ACTIVATE')

transfer = base + 'ApprovalArtifactTransferController.java'
annotate_class(transfer, 'TRANSFER')

application = Path('apps/server/src/main/resources/application.yml')
text = application.read_text(encoding='utf-8')
old = '''approval:
  connector:'''
new = '''approval:
  security:
    management-permissions:
      enforced: ${APPROVAL_MANAGEMENT_PERMISSIONS_ENFORCED:true}
      source: ${APPROVAL_MANAGEMENT_PERMISSION_SOURCE:principal}
      trusted-header-name: ${APPROVAL_MANAGEMENT_TRUSTED_HEADER:X-Approval-Trusted-Permissions}
  connector:'''
if old not in text:
    raise SystemExit('application approval configuration marker not found')
application.write_text(text.replace(old, new, 1), encoding='utf-8')

local = Path('apps/server/src/main/resources/application-local.yml')
text = local.read_text(encoding='utf-8')
if 'approval:\n' not in text:
    text += '''

approval:
  security:
    management-permissions:
      enforced: false
'''
local.write_text(text, encoding='utf-8')
PY

rm -f .github/scripts/apply-pr53-d9-permissions.sh
