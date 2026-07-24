package io.github.akaryc1b.approval.connector.contract;

/**
 * Plans one connector invocation without executing any provider adapter.
 */
public interface ConnectorOrchestrationPlanner {

    <P, R> ConnectorOrchestrationPlan plan(
        ConnectorOrchestrationPlanningRequest<P, R> planningRequest
    );
}
