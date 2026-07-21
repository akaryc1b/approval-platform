package io.github.akaryc1b.approval.security;

import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachingApprovalResponsibilityResolverTest {

    private static final Instant NOW = Instant.parse("2026-07-21T06:00:00Z");

    @Test
    void cacheIsBoundedBySubjectFingerprintAndExplicitInvalidation() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger calls = new AtomicInteger();
        ApprovalResponsibilityResolver delegate = countingResolver(clock, calls);
        CachingApprovalResponsibilityResolver cache =
            new CachingApprovalResponsibilityResolver(
                delegate,
                clock,
                Duration.ofSeconds(30),
                16
            );
        ApprovalPrincipal principal = tenantAdmin();
        ApprovalResource resource = ApprovalResource.tenant("tenant-a");

        assertTrue(cache.resolve(principal, Requirement.DESIGN, resource).allowed());
        assertTrue(cache.resolve(principal, Requirement.DESIGN, resource).allowed());
        assertEquals(1, calls.get());
        assertEquals(1, cache.cacheSize());

        cache.invalidate("tenant-a", "operator-a");
        assertTrue(cache.resolve(principal, Requirement.DESIGN, resource).allowed());
        assertEquals(2, calls.get());

        ApprovalPrincipal participant = ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            Set.of(new ApprovalResponsibilityAssignment(
                ApprovalEnterpriseRole.PARTICIPANT,
                ApprovalResponsibilitySourceType.PERSON,
                "operator-a",
                ApprovalResourceScope.tenant()
            )),
            null
        );
        assertFalse(cache.resolve(
            participant,
            Requirement.DESIGN,
            resource
        ).allowed());
        assertEquals(3, calls.get());
    }

    @Test
    void cacheExpiresAtResponsibilityValidityBoundary() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger calls = new AtomicInteger();
        ApprovalResponsibilityResolver delegate = countingResolver(clock, calls);
        CachingApprovalResponsibilityResolver cache =
            new CachingApprovalResponsibilityResolver(
                delegate,
                clock,
                Duration.ofMinutes(5),
                16
            );
        ApprovalResponsibilityAssignment assignment =
            new ApprovalResponsibilityAssignment(
                ApprovalEnterpriseRole.PROCESS_DESIGNER,
                ApprovalResponsibilitySourceType.USER_GROUP,
                "group-designers",
                ApprovalResourceScope.tenant(),
                NOW.minusSeconds(60),
                NOW.plusSeconds(10),
                4
            );
        ApprovalPrincipal principal = ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            Set.of(assignment),
            null
        );

        assertTrue(cache.resolve(
            principal,
            Requirement.DESIGN,
            ApprovalResource.tenant("tenant-a")
        ).allowed());
        clock.set(NOW.plusSeconds(10));
        assertFalse(cache.resolve(
            principal,
            Requirement.DESIGN,
            ApprovalResource.tenant("tenant-a")
        ).allowed());
        assertEquals(2, calls.get());
    }

    private static ApprovalResponsibilityResolver countingResolver(
        Clock clock,
        AtomicInteger calls
    ) {
        DefaultApprovalResponsibilityResolver delegate =
            new DefaultApprovalResponsibilityResolver(clock);
        return (principal, requirement, resource) -> {
            calls.incrementAndGet();
            return delegate.resolve(principal, requirement, resource);
        };
    }

    private static ApprovalPrincipal tenantAdmin() {
        return ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            Set.of(new ApprovalResponsibilityAssignment(
                ApprovalEnterpriseRole.TENANT_ADMIN,
                ApprovalResponsibilitySourceType.ROLE,
                "tenant-admin-role",
                ApprovalResourceScope.tenant()
            )),
            null
        );
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported in this test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
