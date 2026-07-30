package io.github.akaryc1b.approval.connector.dingtalk.token;

import java.time.Duration;
import java.util.Objects;

public record DingTalkTokenPolicy(
    String policyVersion,
    Duration refreshBeforeExpiry,
    Duration minimumValidity,
    Duration maximumLifetime,
    Duration singleFlightWait,
    int maximumEntries
) {

    public DingTalkTokenPolicy {
        policyVersion = DingTalkTokenSupport.identifier(policyVersion, "policyVersion");
        refreshBeforeExpiry = positive(refreshBeforeExpiry, "refreshBeforeExpiry");
        minimumValidity = positive(minimumValidity, "minimumValidity");
        maximumLifetime = positive(maximumLifetime, "maximumLifetime");
        singleFlightWait = positive(singleFlightWait, "singleFlightWait");
        if (refreshBeforeExpiry.compareTo(maximumLifetime) >= 0) {
            throw new IllegalArgumentException("refreshBeforeExpiry must be below maximumLifetime");
        }
        if (minimumValidity.compareTo(maximumLifetime) > 0) {
            throw new IllegalArgumentException("minimumValidity must not exceed maximumLifetime");
        }
        if (singleFlightWait.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("singleFlightWait exceeds 30 seconds");
        }
        if (maximumEntries < 1 || maximumEntries > 1_024) {
            throw new IllegalArgumentException("maximumEntries is outside the closed range");
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
