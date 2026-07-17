package io.github.akaryc1b.approval.host.web;

import java.time.Instant;

public record HostErrorResponse(
    String code,
    String message,
    String requestId,
    Instant timestamp
) {
}
