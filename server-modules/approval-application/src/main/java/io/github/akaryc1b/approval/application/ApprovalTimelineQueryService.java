package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery.ApprovalTimeline;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery.TimelineIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application boundary for participant-authorized approval timeline reads.
 */
public final class ApprovalTimelineQueryService {

    private final ApprovalTimelineQuery timelineQuery;

    public ApprovalTimelineQueryService(ApprovalTimelineQuery timelineQuery) {
        this.timelineQuery = Objects.requireNonNull(
            timelineQuery,
            "timelineQuery must not be null"
        );
    }

    public Optional<ApprovalTimeline> findTimeline(
        String tenantId,
        String operatorId,
        UUID instanceId
    ) {
        return timelineQuery.findTimeline(new TimelineIdentity(
            tenantId,
            operatorId,
            instanceId
        ));
    }
}
