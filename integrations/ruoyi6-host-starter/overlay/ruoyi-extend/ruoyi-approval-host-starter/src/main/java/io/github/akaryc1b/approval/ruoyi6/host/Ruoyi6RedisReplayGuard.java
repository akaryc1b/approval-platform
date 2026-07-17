package io.github.akaryc1b.approval.ruoyi6.host;

import io.github.akaryc1b.approval.host.security.ReplayGuard;
import org.dromara.common.redis.utils.RedisUtils;

import java.time.Duration;
import java.time.Instant;

final class Ruoyi6RedisReplayGuard implements ReplayGuard {

    private static final String PREFIX = "approval:host:nonce:";

    @Override
    public boolean reserve(String tenantKey, String nonce, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        String key = PREFIX + tenantKey + ':' + nonce;
        return RedisUtils.setObjectIfAbsent(key, "1", ttl);
    }
}
