package io.github.akaryc1b.approval.domain.definition;

import java.util.Objects;
import java.util.Set;

/**
 * Closed lifecycle for a tenant-scoped process release.
 *
 * <p>Published content is immutable. ACTIVE only selects the default release for new
 * instances and never changes an existing runtime binding. DEPRECATED releases remain
 * available for explicitly governed rollback. RETIRED is terminal.</p>
 */
public final class ApprovalReleaseLifecycle {

    private ApprovalReleaseLifecycle() {
    }

    public static Set<State> allowedTargets(State state) {
        Objects.requireNonNull(state, "state must not be null");
        return switch (state) {
            case DRAFT -> Set.of(State.PUBLISHED);
            case PUBLISHED -> Set.of(State.ACTIVE, State.RETIRED);
            case ACTIVE -> Set.of(State.DEPRECATED);
            case DEPRECATED -> Set.of(State.ACTIVE, State.RETIRED);
            case RETIRED -> Set.of();
        };
    }

    public static boolean permits(State from, State to) {
        Objects.requireNonNull(to, "to must not be null");
        return allowedTargets(from).contains(to);
    }

    public static void requirePermitted(State from, State to) {
        if (!permits(from, to)) {
            throw new InvalidTransitionException(from, to);
        }
    }

    public static boolean contentImmutable(State state) {
        return Objects.requireNonNull(state, "state must not be null") != State.DRAFT;
    }

    public static boolean defaultStartEligible(State state) {
        return Objects.requireNonNull(state, "state must not be null") == State.ACTIVE;
    }

    public static boolean terminal(State state) {
        return Objects.requireNonNull(state, "state must not be null") == State.RETIRED;
    }

    public enum State {
        DRAFT,
        PUBLISHED,
        ACTIVE,
        DEPRECATED,
        RETIRED
    }

    public record Transition(State from, State to) {
        public Transition {
            from = Objects.requireNonNull(from, "from must not be null");
            to = Objects.requireNonNull(to, "to must not be null");
            requirePermitted(from, to);
        }
    }

    public static final class InvalidTransitionException extends IllegalArgumentException {

        private final State from;
        private final State to;

        public InvalidTransitionException(State from, State to) {
            super("release lifecycle transition is not permitted: " + from + " -> " + to);
            this.from = from;
            this.to = to;
        }

        public State from() {
            return from;
        }

        public State to() {
            return to;
        }
    }
}
