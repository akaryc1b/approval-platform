package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAuditRecord;

/** Contract only; persistence is intentionally outside the first M6-D slice. */
@FunctionalInterface
public interface AiAdvisoryAuditSink {

    void record(AiAuditRecord record);

    static AiAdvisoryAuditSink noop() {
        return record -> {
        };
    }
}
