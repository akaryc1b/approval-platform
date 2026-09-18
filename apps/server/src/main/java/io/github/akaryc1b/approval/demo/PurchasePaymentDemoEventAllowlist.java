package io.github.akaryc1b.approval.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, bounded local callback authorization; never a prefix matcher. */
public final class PurchasePaymentDemoEventAllowlist {
    private static final String HEADER = "PURCHASE_PAYMENT_EXACT_EVENTS_V1";
    private static final String EVENT_TYPE = "purchase-payment.completed.v1";
    private static final int MAXIMUM_BYTES = 128 * 1024;
    private static final int MAXIMUM_EVENTS = 96;
    private static final Pattern LITERAL = Pattern.compile("[0-9A-Za-z._:-]{1,256}");
    private static final Pattern UUID_TEXT = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    );
    private final Map<UUID, Entry> entries;
    private final String sha256;

    private PurchasePaymentDemoEventAllowlist(Map<UUID, Entry> entries, String sha256) {
        this.entries = Map.copyOf(entries);
        this.sha256 = sha256;
    }

    public static PurchasePaymentDemoEventAllowlist load(Path path, String tenantId)
        throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("exact event allowlist must be a regular non-symlink file");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAXIMUM_BYTES + 1);
        }
        if (bytes.length > MAXIMUM_BYTES) {
            throw new IOException("exact event allowlist exceeds the bounded size");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || lines.length > MAXIMUM_EVENTS + 2
            || !lines[0].equals(HEADER + "\t" + tenantId)
            || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("invalid exact event allowlist header or count");
        }
        Map<UUID, Entry> entries = new HashMap<>();
        Set<String> keys = new HashSet<>();
        Set<UUID> aggregates = new HashSet<>();
        Set<String> businessKeys = new HashSet<>();
        Set<String> orders = new HashSet<>();
        for (int index = 1; index < lines.length - 1; index++) {
            String[] cells = lines[index].split("\t", -1);
            if (cells.length != 5) {
                throw new IllegalArgumentException("exact event allowlist requires five fields");
            }
            for (String cell : cells) {
                if (!LITERAL.matcher(cell).matches()) {
                    throw new IllegalArgumentException("allowlist values must be exact bounded literals");
                }
            }
            if (!UUID_TEXT.matcher(cells[0]).matches()
                || !UUID_TEXT.matcher(cells[2]).matches()
                || !cells[1].equals(EVENT_TYPE + ":" + cells[2])) {
                throw new IllegalArgumentException("invalid exact event allowlist identity");
            }
            Entry entry = new Entry(UUID.fromString(cells[0]), cells[1],
                UUID.fromString(cells[2]), cells[3], cells[4]);
            if (entries.putIfAbsent(entry.eventId(), entry) != null
                || !keys.add(entry.idempotencyKey()) || !aggregates.add(entry.aggregateId())
                || !businessKeys.add(entry.businessKey()) || !orders.add(entry.purchaseOrderReference())) {
                throw new IllegalArgumentException("duplicate exact event allowlist identity");
            }
        }
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new PurchasePaymentDemoEventAllowlist(entries, digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public boolean contains(Entry entry) {
        return entry != null && entry.equals(entries.get(entry.eventId()));
    }

    public String sha256() {
        return sha256;
    }

    public int size() {
        return entries.size();
    }

    public record Entry(UUID eventId, String idempotencyKey, UUID aggregateId,
                        String businessKey, String purchaseOrderReference) {
    }
}
