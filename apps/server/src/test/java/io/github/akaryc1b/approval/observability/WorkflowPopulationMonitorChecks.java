package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalWorkflowPopulationReader.Snapshot;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalWorkflowPopulationReader;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

/** The same JDK-only cases run locally and via JUnit; JDBC protocol fixtures do not evaluate SQL. */
final class WorkflowPopulationMonitorChecks {
    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");
    private static final Snapshot BUSY = new Snapshot(NOW, 5, 3, 2, 9, 4, 1);
    private static final Snapshot EMPTY = new Snapshot(NOW, 0, 0, 0, 0, 0, 0);
    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final java.util.List<ToDoubleFunction<Snapshot>> FIELDS = java.util.List.of(
        Snapshot::activeProcesses, Snapshot::coveredProcesses, Snapshot::overdueProcesses,
        Snapshot::activeTasks, Snapshot::coveredTasks, Snapshot::overdueTasks);

    @FunctionalInterface interface Check { void run() throws Exception; }
    static Map<String, Check> cases() {
        Map<String, Check> checks = new LinkedHashMap<>();
        checks.put("unobserved scrapes never query or invent zero", () -> {
            AtomicInteger reads = new AtomicInteger();
            try (var monitor = new WorkflowPopulationMonitor(() -> { reads.incrementAndGet(); return EMPTY; }, INTERVAL, () -> { })) {
                unknown(monitor); truth(Double.isNaN(monitor.timestampSeconds()));
                truth(Double.isNaN(monitor.ageSeconds()));
                for (int i = 0; i < 20; i++) unknown(monitor);
                equal(0, reads.get()); monitor.refresh(); equal(1, reads.get()); equal(1, monitor.up());
                FIELDS.forEach(field -> equal(0, monitor.value(field)));
            }
        });
        checks.put("one snapshot retains exact independent populations", () -> {
            try (var monitor = new WorkflowPopulationMonitor(() -> BUSY, INTERVAL, () -> { })) {
                monitor.refresh(); equal(1, monitor.up());
                FIELDS.forEach(field -> equal(field.applyAsDouble(BUSY), monitor.value(field)));
                equal(NOW.getEpochSecond(), monitor.timestampSeconds());
            }
        });
        checks.put("failed reads invalidate all fields and recovery replaces the sample", () -> {
            AtomicReference<Snapshot> next = new AtomicReference<>(BUSY);
            try (var monitor = new WorkflowPopulationMonitor(() -> {
                if (next.get() == null) throw new IllegalStateException("private detail"); return next.get();
            }, INTERVAL, () -> { })) {
                monitor.refresh(); double timestamp = monitor.timestampSeconds();
                next.set(null); monitor.refresh(); unknown(monitor); equal(1, monitor.errors());
                equal(timestamp, monitor.timestampSeconds()); next.set(EMPTY); monitor.refresh();
                equal(1, monitor.up()); equal(0, monitor.value(Snapshot::activeProcesses)); equal(1, monitor.errors());
            }
        });
        checks.put("first poll failure and null result remain unknown", () -> {
            try (var monitor = new WorkflowPopulationMonitor(() -> null, INTERVAL, () -> { })) {
                monitor.refresh(); unknown(monitor); equal(1, monitor.errors());
                truth(Double.isNaN(monitor.timestampSeconds()));
            }
        });
        checks.put("sample expires exactly after two intervals", () -> {
            AtomicLong time = new AtomicLong();
            try (var monitor = new WorkflowPopulationMonitor(() -> BUSY, INTERVAL, () -> { }, time::get)) {
                monitor.refresh(); time.set(Duration.ofSeconds(10).toNanos() - 1); equal(1, monitor.up());
                time.incrementAndGet(); unknown(monitor); equal(10, monitor.ageSeconds());
                equal(NOW.getEpochSecond(), monitor.timestampSeconds());
            }
        });
        checks.put("slow query time is included in staleness", () -> {
            AtomicLong time = new AtomicLong();
            try (var monitor = new WorkflowPopulationMonitor(() -> {
                time.set(Duration.ofSeconds(11).toNanos()); return BUSY;
            }, INTERVAL, () -> { }, time::get)) { monitor.refresh(); unknown(monitor); }
        });
        checks.put("overlap is rejected and close fences late results", () -> {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
            try (var executor = Executors.newSingleThreadExecutor();
                 var monitor = new WorkflowPopulationMonitor(() -> {
                     reads.incrementAndGet(); entered.countDown(); await(release); return BUSY;
                 }, INTERVAL, () -> { closes.incrementAndGet(); release.countDown(); })) {
                var running = executor.submit(monitor::refresh); await(entered); monitor.refresh(); equal(1, reads.get());
                monitor.close(); running.get(5, TimeUnit.SECONDS); monitor.refresh(); monitor.start(); monitor.close();
                equal(1, reads.get()); equal(1, closes.get()); unknown(monitor);
            } finally { release.countDown(); }
        });
        checks.put("owned background worker starts once and closes", () -> {
            CountDownLatch entered = new CountDownLatch(1); AtomicInteger reads = new AtomicInteger();
            try (var monitor = new WorkflowPopulationMonitor(() -> {
                reads.incrementAndGet(); entered.countDown(); return EMPTY;
            }, INTERVAL, () -> { })) {
                monitor.start(); monitor.start(); await(entered); monitor.close(); monitor.start();
                equal(1, reads.get()); unknown(monitor);
            }
        });
        checks.put("reader close failure cannot reactivate the monitor", () -> {
            AtomicInteger closes = new AtomicInteger();
            var monitor = new WorkflowPopulationMonitor(() -> BUSY, INTERVAL, () -> {
                closes.incrementAndGet(); throw new IllegalStateException("close unavailable");
            });
            monitor.refresh(); rejects(IllegalStateException.class, monitor::close);
            monitor.refresh(); monitor.start(); monitor.close(); equal(1, closes.get()); unknown(monitor);
        });
        checks.put("interval bounds reject unsafe polling", () -> {
            for (Duration interval : new Duration[] { Duration.ZERO, Duration.ofMillis(4999), Duration.ofMinutes(6) }) {
                rejects(IllegalArgumentException.class, () -> new WorkflowPopulationMonitor(() -> EMPTY, interval, () -> { }));
            }
            try (var ignored = new WorkflowPopulationMonitor(() -> EMPTY, Duration.ofMinutes(5), () -> { })) { }
        });
        checks.put("snapshot rejects negative or inconsistent subset populations", () -> {
            for (long[] v : new long[][] {{-1,0,0,0,0,0}, {1,2,0,0,0,0}, {1,1,2,0,0,0},
                {0,0,0,-1,0,0}, {0,0,0,1,2,0}, {0,0,0,1,1,2}, {1,-1,0,0,0,0}, {1,1,-1,0,0,0},
                {0,0,0,1,-1,0}, {0,0,0,1,1,-1}}) {
                rejects(IllegalArgumentException.class, () -> new Snapshot(NOW,v[0],v[1],v[2],v[3],v[4],v[5]));
            }
            rejects(NullPointerException.class, () -> new Snapshot(null,0,0,0,0,0,0));
        });
        checks.put("JDBC uses one bounded read-only aggregate and closes resources", () -> {
            var fixture = new JdbcFixture(); var snapshot = new JdbcApprovalWorkflowPopulationReader(fixture.dataSource()).read();
            truth(BUSY.equals(snapshot)); equal(1, fixture.queries); equal(2, fixture.timeout); equal(1, fixture.maxRows);
            truth(fixture.readOnly && fixture.connectionClosed && fixture.statementClosed && fixture.rowsClosed);
            truth(fixture.sql.contains("statement_timestamp()") && fixture.sql.contains("s.overdue_at <= statement_timestamp()"));
            truth(fixture.sql.contains("s.tenant_id = t.tenant_id") && fixture.sql.contains("s.target_type = t.target_type"));
            truth(!fixture.sql.contains("ACT_") && !fixture.sql.contains("for update"));
        });
        checks.put("JDBC rejects transaction-bound connection before preparing SQL", () -> {
            var fixture = new JdbcFixture(); fixture.autoCommit = false;
            rejects(IllegalStateException.class, () -> new JdbcApprovalWorkflowPopulationReader(fixture.dataSource()).read());
            truth(fixture.connectionClosed && fixture.sql == null); equal(0, fixture.queries);
        });
        checks.put("JDBC query failure closes connection and statement", () -> {
            var fixture = new JdbcFixture(); fixture.failQuery = true;
            rejects(IllegalStateException.class, () -> new JdbcApprovalWorkflowPopulationReader(fixture.dataSource()).read());
            truth(fixture.connectionClosed && fixture.statementClosed);
        });
        checks.put("JDBC missing aggregate row fails rather than fabricating zeros", () -> {
            var fixture = new JdbcFixture(); fixture.missingRow = true;
            rejects(IllegalStateException.class, () -> new JdbcApprovalWorkflowPopulationReader(fixture.dataSource()).read());
            truth(fixture.connectionClosed && fixture.statementClosed && fixture.rowsClosed);
        });
        return checks;
    }

    public static void main(String[] args) throws Exception {
        int count = 0;
        for (var entry : cases().entrySet()) { entry.getValue().run(); count++; System.out.println("PASS " + entry.getKey()); }
        System.out.println("WORKFLOW_POPULATION_JDK_CHECKS_PASSED=" + count);
    }
    private static void unknown(WorkflowPopulationMonitor monitor) {
        equal(0, monitor.up()); FIELDS.forEach(field -> truth(Double.isNaN(monitor.value(field))));
    }
    private static void truth(boolean value) { if (!value) throw new AssertionError("condition failed"); }
    private static void equal(double expected, double actual) {
        if (Double.compare(expected, actual) != 0) throw new AssertionError("expected " + expected + " but was " + actual);
    }
    private static void rejects(Class<? extends Throwable> type, Check action) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return; throw new AssertionError("wrong failure type", failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
    private static void await(CountDownLatch latch) {
        try { truth(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class JdbcFixture {
        boolean autoCommit = true, readOnly, connectionClosed, statementClosed, rowsClosed, failQuery, missingRow;
        int queries, timeout, maxRows, rowIndex; String sql;
        DataSource dataSource() {
            return proxy(DataSource.class, (object, method, args) -> {
                if (method.getName().equals("getConnection")) return connection();
                throw new AssertionError(method.getName());
            });
        }
        Connection connection() {
            return proxy(Connection.class, (object, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setReadOnly" -> { readOnly = (Boolean) args[0]; yield null; }
                case "prepareStatement" -> { sql = (String) args[0]; yield statement(); }
                case "close" -> { connectionClosed = true; yield null; }
                default -> throw new AssertionError(method.getName());
            });
        }
        PreparedStatement statement() {
            return proxy(PreparedStatement.class, (object, method, args) -> switch (method.getName()) {
                case "setQueryTimeout" -> { timeout = (Integer) args[0]; yield null; }
                case "setMaxRows" -> { maxRows = (Integer) args[0]; yield null; }
                case "executeQuery" -> { queries++; if (failQuery) throw new SQLException("private SQL failure"); yield rows(); }
                case "close" -> { statementClosed = true; yield null; }
                default -> throw new AssertionError(method.getName());
            });
        }
        ResultSet rows() {
            Map<String, Long> counts = Map.of("active_processes",5L,"covered_processes",3L,"overdue_processes",2L,
                "active_tasks",9L,"covered_tasks",4L,"overdue_tasks",1L);
            return proxy(ResultSet.class, (object, method, args) -> switch (method.getName()) {
                case "next" -> !missingRow && rowIndex++ == 0;
                case "getLong" -> counts.get((String) args[0]);
                case "getObject" -> OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
                case "close" -> { rowsClosed = true; yield null; }
                default -> throw new AssertionError(method.getName());
            });
        }
        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
        }
    }
}
