package io.github.akaryc1b.approval.connector.dingtalk.token;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class DingTalkTokenCache implements AutoCloseable {

    private final Object lock = new Object();
    private final Map<String, DingTalkTokenEntry> entries = new ConcurrentHashMap<>();
    private final int maximumEntries;

    DingTalkTokenCache(int maximumEntries) {
        this.maximumEntries = maximumEntries;
    }

    DingTalkTokenEntry get(String cacheKeyHash) {
        return entries.get(cacheKeyHash);
    }

    int size() {
        return entries.size();
    }

    void removeAndClose(String cacheKeyHash, DingTalkTokenEntry expected) {
        if (expected != null && entries.remove(cacheKeyHash, expected)) {
            expected.close();
        }
    }

    void install(DingTalkTokenEntry entry) {
        synchronized (lock) {
            String cacheKeyHash = entry.cacheKeyHash();
            DingTalkTokenEntry current = entries.get(cacheKeyHash);
            if (current == null && entries.size() >= maximumEntries) {
                entry.close();
                throw failure(DingTalkTokenFailure.CACHE_CAPACITY_EXCEEDED);
            }
            DingTalkTokenEntry previous = entries.put(cacheKeyHash, entry);
            if (previous != null && previous != entry) {
                previous.close();
            }
        }
    }

    void ensureCapacity(String cacheKeyHash) {
        synchronized (lock) {
            if (!entries.containsKey(cacheKeyHash) && entries.size() >= maximumEntries) {
                throw failure(DingTalkTokenFailure.CACHE_CAPACITY_EXCEEDED);
            }
        }
    }

    void rotateFamily(String familyHash, String cacheKeyHash) {
        synchronized (lock) {
            for (var entry : entries.entrySet()) {
                DingTalkTokenEntry value = entry.getValue();
                if (value.familyHash().equals(familyHash)
                    && !entry.getKey().equals(cacheKeyHash)
                    && entries.remove(entry.getKey(), value)) {
                    value.close();
                }
            }
        }
    }

    void invalidateFamily(String familyHash) {
        synchronized (lock) {
            for (var entry : entries.entrySet()) {
                DingTalkTokenEntry value = entry.getValue();
                if (value.familyHash().equals(familyHash)
                    && entries.remove(entry.getKey(), value)) {
                    value.close();
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            entries.values().forEach(DingTalkTokenEntry::close);
            entries.clear();
        }
    }

    private static DingTalkTokenLifecycleException failure(DingTalkTokenFailure failure) {
        return new DingTalkTokenLifecycleException(failure);
    }
}
