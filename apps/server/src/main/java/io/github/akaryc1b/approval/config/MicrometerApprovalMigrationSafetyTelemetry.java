package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Micrometer adapter with one closed event tag and no tenant or resource identity. */
@Component
public final class MicrometerApprovalMigrationSafetyTelemetry
    implements ApprovalMigrationSafetyTelemetry {

    public static final String METRIC = "approval.migration.safety.event";

    private static final Logger LOGGER = LoggerFactory.getLogger(
        MicrometerApprovalMigrationSafetyTelemetry.class
    );

    private final MeterRegistry meters;
    private final Map<Event, Counter> counters = new EnumMap<>(Event.class);

    public MicrometerApprovalMigrationSafetyTelemetry(MeterRegistry meters) {
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        for (Event event : Event.values()) {
            register(event);
        }
    }

    @Override
    public void record(Event event) {
        Event required = Objects.requireNonNull(event, "event must not be null");
        try {
            Counter counter = counters.get(required);
            if (counter == null) {
                counter = register(required);
            }
            if (counter != null) {
                counter.increment();
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("migration safety telemetry failed; migration semantics remain unchanged");
        }
    }

    private Counter register(Event event) {
        try {
            Counter counter = Counter.builder(METRIC)
                .description("Closed migration safety event counter")
                .tag("event", event.name().toLowerCase(Locale.ROOT))
                .register(meters);
            counters.put(event, counter);
            return counter;
        } catch (RuntimeException exception) {
            LOGGER.warn("migration safety meter registration failed; startup remains fail-open");
            return null;
        }
    }
}
