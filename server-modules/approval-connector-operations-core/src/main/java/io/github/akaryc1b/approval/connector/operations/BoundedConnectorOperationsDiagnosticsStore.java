package io.github.akaryc1b.approval.connector.operations;

import io.github.akaryc1b.approval.connector.invocation.ConnectorInvocationObservationSink;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationEvidence;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .StableFailureCode;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticEntry;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsCriteria;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsSummary;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .InvocationOutcome;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .PageCursor;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .QueryWindow;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded process-local store. It is not persistence, audit or recovery. */
public final class BoundedConnectorOperationsDiagnosticsStore
    implements ConnectorInvocationObservationSink, ConnectorOperationsDiagnosticsSource {

    private final int maximumEntries;
    private final int maximumEntriesPerTenant;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<StoredEntry> entries = new ArrayDeque<>();
    private final Map<String, Integer> tenantCounts = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean available = new AtomicBoolean(true);

    public BoundedConnectorOperationsDiagnosticsStore(
        int maximumEntries,
        int maximumEntriesPerTenant
    ) {
        if (maximumEntries < 1 || maximumEntries > 100_000) {
            throw new IllegalArgumentException("maximumEntries is outside the closed bound");
        }
        if (maximumEntriesPerTenant < 1 || maximumEntriesPerTenant > maximumEntries) {
            throw new IllegalArgumentException("maximumEntriesPerTenant is outside the closed bound");
        }
        this.maximumEntries = maximumEntries;
        this.maximumEntriesPerTenant = maximumEntriesPerTenant;
    }

    @Override
    public void record(InvocationEvidence evidence, Instant evaluatedAt) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        requireAvailable();
        long next = sequence.incrementAndGet();
        DiagnosticEntry entry = DiagnosticEntry.from(evidence, evaluatedAt);
        StoredEntry stored = new StoredEntry(next, evidence.tenantHash(), entry);
        lock.lock();
        try {
            entries.addLast(stored);
            tenantCounts.merge(stored.tenantHash(), 1, Integer::sum);
            trimTenant(stored.tenantHash());
            trimGlobal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public QueryWindow query(
        String tenantHash,
        DiagnosticsCriteria criteria,
        PageCursor cursor
    ) {
        requireAvailable();
        Objects.requireNonNull(tenantHash, "tenantHash must not be null");
        Objects.requireNonNull(criteria, "criteria must not be null");
        lock.lock();
        try {
            long currentHigh = sequence.get();
            long highWatermark = cursor == null ? currentHigh : cursor.highWatermark();
            long before = cursor == null ? highWatermark : cursor.beforeSequence();
            List<DiagnosticEntry> matched = new ArrayList<>(criteria.pageSize() + 1);
            for (var iterator = entries.descendingIterator(); iterator.hasNext();) {
                StoredEntry stored = iterator.next();
                DiagnosticEntry entry = stored.entry();
                if (!tenantHash.equals(stored.tenantHash())
                    || stored.sequence() > highWatermark
                    || stored.sequence() > before
                    || !matches(entry, criteria)) {
                    continue;
                }
                matched.add(entry);
                if (matched.size() > criteria.pageSize()) {
                    break;
                }
            }
            boolean more = matched.size() > criteria.pageSize();
            if (more) {
                matched.removeLast();
            }
            long nextBefore = 0;
            if (!matched.isEmpty()) {
                int retained = matched.size();
                int seen = 0;
                for (var iterator = entries.descendingIterator(); iterator.hasNext();) {
                    StoredEntry stored = iterator.next();
                    if (!tenantHash.equals(stored.tenantHash())
                        || stored.sequence() > highWatermark
                        || stored.sequence() > before
                        || !matches(stored.entry(), criteria)) {
                        continue;
                    }
                    seen++;
                    if (seen == retained) {
                        nextBefore = Math.max(stored.sequence() - 1, 0);
                        break;
                    }
                }
            }
            return new QueryWindow(matched, highWatermark, nextBefore, more);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public DiagnosticsSummary summarize(String tenantHash, Instant evaluatedAt) {
        requireAvailable();
        Objects.requireNonNull(tenantHash, "tenantHash must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        lock.lock();
        try {
            Map<InvocationOutcome, Long> outcomes = new EnumMap<>(InvocationOutcome.class);
            Map<StableFailureCode, Long> failures = new EnumMap<>(StableFailureCode.class);
            long total = 0;
            for (StoredEntry stored : entries) {
                if (!tenantHash.equals(stored.tenantHash())) {
                    continue;
                }
                total++;
                outcomes.merge(stored.entry().invocationOutcome(), 1L, Long::sum);
                failures.merge(stored.entry().stableFailureCode(), 1L, Long::sum);
            }
            return new DiagnosticsSummary(
                total,
                outcomes,
                failures,
                evaluatedAt,
                true,
                false,
                false,
                false
            );
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    public int tenantSize(String tenantHash) {
        lock.lock();
        try {
            return tenantCounts.getOrDefault(tenantHash, 0);
        } finally {
            lock.unlock();
        }
    }

    public void setAvailable(boolean value) {
        available.set(value);
    }

    private void trimTenant(String tenantHash) {
        while (tenantCounts.getOrDefault(tenantHash, 0) > maximumEntriesPerTenant) {
            StoredEntry candidate = null;
            for (StoredEntry entry : entries) {
                if (tenantHash.equals(entry.tenantHash())) {
                    candidate = entry;
                    break;
                }
            }
            if (candidate == null) {
                return;
            }
            entries.remove(candidate);
            decrement(candidate.tenantHash());
        }
    }

    private void trimGlobal() {
        while (entries.size() > maximumEntries) {
            StoredEntry removed = entries.removeFirst();
            decrement(removed.tenantHash());
        }
    }

    private void decrement(String tenantHash) {
        tenantCounts.computeIfPresent(tenantHash, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    private void requireAvailable() {
        if (!available.get()) {
            throw new ConnectorOperationsDiagnosticsExceptions.SourceUnavailable();
        }
    }

    private static boolean matches(DiagnosticEntry entry, DiagnosticsCriteria criteria) {
        return (criteria.provider() == null || criteria.provider().equals(entry.provider()))
            && (criteria.capability() == null || criteria.capability() == entry.capability())
            && (criteria.connectorOperation() == null
                || criteria.connectorOperation() == entry.connectorOperation())
            && (criteria.apiFamily() == null || criteria.apiFamily() == entry.apiFamily())
            && (criteria.transportProfile() == null
                || criteria.transportProfile() == entry.transportProfile())
            && (criteria.invocationOutcome() == null
                || criteria.invocationOutcome() == entry.invocationOutcome())
            && (criteria.dispatchAttempted() == null
                || criteria.dispatchAttempted() == entry.dispatchAttempted())
            && (criteria.stableFailureCode() == null
                || criteria.stableFailureCode() == entry.stableFailureCode());
    }

    private record StoredEntry(long sequence, String tenantHash, DiagnosticEntry entry) {
        private StoredEntry {
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            Objects.requireNonNull(tenantHash, "tenantHash must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
        }
    }
}
