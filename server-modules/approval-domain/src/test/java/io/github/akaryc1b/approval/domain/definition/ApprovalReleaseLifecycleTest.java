package io.github.akaryc1b.approval.domain.definition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalReleaseLifecycleTest {

    @Test
    void exposesClosedAllowedTransitionSets() {
        assertEquals(Set.of(State.PUBLISHED), ApprovalReleaseLifecycle.allowedTargets(State.DRAFT));
        assertEquals(Set.of(State.ACTIVE, State.RETIRED), ApprovalReleaseLifecycle.allowedTargets(State.PUBLISHED));
        assertEquals(Set.of(State.DEPRECATED), ApprovalReleaseLifecycle.allowedTargets(State.ACTIVE));
        assertEquals(Set.of(State.ACTIVE, State.RETIRED), ApprovalReleaseLifecycle.allowedTargets(State.DEPRECATED));
        assertEquals(Set.of(), ApprovalReleaseLifecycle.allowedTargets(State.RETIRED));
    }

    @Test
    void permitsGovernedReactivationOfDeprecatedRelease() {
        assertTrue(ApprovalReleaseLifecycle.permits(State.DEPRECATED, State.ACTIVE));
        assertDoesNotThrow(() -> new ApprovalReleaseLifecycle.Transition(State.DEPRECATED, State.ACTIVE));
    }

    @Test
    void rejectsSameStateDirectRetirementAndTerminalTransitions() {
        assertThrows(
            ApprovalReleaseLifecycle.InvalidTransitionException.class,
            () -> new ApprovalReleaseLifecycle.Transition(State.ACTIVE, State.ACTIVE)
        );
        assertThrows(
            ApprovalReleaseLifecycle.InvalidTransitionException.class,
            () -> new ApprovalReleaseLifecycle.Transition(State.ACTIVE, State.RETIRED)
        );
        assertThrows(
            ApprovalReleaseLifecycle.InvalidTransitionException.class,
            () -> new ApprovalReleaseLifecycle.Transition(State.RETIRED, State.PUBLISHED)
        );
    }

    @Test
    void publishedAndLaterStatesKeepContentImmutable() {
        assertFalse(ApprovalReleaseLifecycle.contentImmutable(State.DRAFT));
        assertTrue(ApprovalReleaseLifecycle.contentImmutable(State.PUBLISHED));
        assertTrue(ApprovalReleaseLifecycle.contentImmutable(State.ACTIVE));
        assertTrue(ApprovalReleaseLifecycle.contentImmutable(State.DEPRECATED));
        assertTrue(ApprovalReleaseLifecycle.contentImmutable(State.RETIRED));
    }

    @Test
    void onlyActiveIsDefaultStartEligibleAndOnlyRetiredIsTerminal() {
        for (State state : ApprovalReleaseLifecycle.State.values()) {
            assertEquals(state == State.ACTIVE, ApprovalReleaseLifecycle.defaultStartEligible(state));
            assertEquals(state == State.RETIRED, ApprovalReleaseLifecycle.terminal(state));
        }
    }
}
