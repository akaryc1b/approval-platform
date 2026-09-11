package io.github.akaryc1b.approval.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Single-use internal request proof. Browser cookies never become backend principals. */
public final class OnlineEvaluationBusinessTicket {
    public static final String HEADER = "X-Evaluation-Business-Ticket";
    public static final int MAX_BODY_BYTES = 1_048_576;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private final PublicKey publicKey;
    private final String generation;
    private final Set<String> actors;
    private final Clock clock;
    private final Map<String, Long> nonces = new HashMap<>();
    private long lastTime = -1;
    private boolean disabled;

    public OnlineEvaluationBusinessTicket(String key, String generation, Set<String> actors, Clock clock) {
        try {
            require(key != null && key.length() <= 128);
            byte[] der = Base64.getDecoder().decode(key);
            publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            require(Base64.getEncoder().encodeToString(publicKey.getEncoded()).equals(key));
            require(generation != null && generation.matches("[0-9a-f]{32}") && !generation.matches("0+"));
            require(actors != null && actors.size() == 5);
            for (String actor : actors) require(actor != null && actor.matches("demo-[a-z0-9-]{1,58}") && !actor.contains("admin"));
            this.generation = generation;
            this.actors = Set.copyOf(actors);
            this.clock = Objects.requireNonNull(clock);
        } catch (Exception failure) {
            throw new IllegalArgumentException("INVALID_EVALUATION_BUSINESS_CONFIGURATION");
        }
    }

    public enum Route { READ, START, APPROVE, UPLOAD }
    public record Identity(String actorId, Instant expiresAt, String requestId) { }

    /** Must match the gateway's literal route policy; never normalize a signed target. */
    public static Route route(String method, String target) {
        require(Set.of("GET", "POST").contains(method) && target != null && target.length() <= 1024
            && target.matches("[\\x21-\\x7e]+") && !target.contains("#") && !target.contains("\\")
            && !target.contains(";") && !target.contains("//"));
        String[] parts = target.split("\\?", -1);
        require(parts.length <= 2);
        String path = parts[0]; String query = parts.length == 2 ? parts[1] : null;
        require(path.startsWith("/api/approval/") && !path.contains("%") && !path.contains("/."));
        if (method.equals("GET") && Set.of("/api/approval/tasks/pending", "/api/approval/instances/started",
            "/api/approval/tasks/processed").contains(path)) {
            if (query != null) {
                require(!query.isEmpty()); Set<String> seen = new HashSet<>();
                for (String pair : query.split("&", -1)) {
                    String[] field = pair.split("=", 2);
                    require(field.length == 2);
                    String name = URLDecoder.decode(field[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(field[1], StandardCharsets.UTF_8);
                    require(seen.add(name));
                    if (name.equals("limit") || name.equals("offset")) {
                        require(value.matches("0|[1-9][0-9]{0,2}"));
                        int number = Integer.parseInt(value);
                        require(number <= (name.equals("limit") ? 20 : 100) && (!name.equals("limit") || number >= 1));
                    } else require(name.equals("keyword") && value.length() <= 80 && !value.matches("(?s).*[\\x00-\\x1f\\x7f\\ufffd].*"));
                }
            }
            return Route.READ;
        }
        if (method.equals("GET") && path.equals("/api/approval/tasks")) {
            require(query != null && query.matches("instanceId=" + UUID)); return Route.READ;
        }
        require(query == null);
        if (method.equals("GET") && path.matches("/api/approval/(?:instances/" + UUID + "(?:/timeline|/form-snapshot)?|tasks/pending/"
            + UUID + "|tasks/" + UUID + "/(?:form-runtime|delegation)|attachments/" + UUID + "(?:/content)?)")) return Route.READ;
        if (method.equals("GET") && path.matches("/api/approval/(?:forms/purchase-payment/versions/1(?:/runtime)?"
            + "|ui-schemas/forms/purchase-payment/versions/1/latest)")) return Route.READ;
        if (method.equals("POST") && path.matches("/api/approval/tasks/" + UUID + "/approve")) return Route.APPROVE;
        if (method.equals("POST") && path.equals("/api/approval/forms/purchase-payment/versions/1/submissions")) return Route.START;
        if (method.equals("POST") && path.equals("/api/approval/attachments")) return Route.UPLOAD;
        throw new SecurityException("EVALUATION_BUSINESS_ROUTE_REJECTED");
    }

    public synchronized Identity verify(String ticket, String method, String target, String contentType,
        String bodySha256, String idempotencyKey, String requestId) {
        try {
            require(!disabled); Route route = route(method, target);
            require(ticket != null && ticket.length() <= 4096 && bodySha256 != null && bodySha256.matches("[0-9a-f]{64}")
                && requestId != null && requestId.matches(IDENTIFIER));
            require(route == Route.READ ? "".equals(contentType) && "".equals(idempotencyKey)
                : (route == Route.UPLOAD ? "multipart/form-data" : "application/json").equals(contentType)
                    && idempotencyKey != null && idempotencyKey.matches(IDENTIFIER));
            String[] envelope = ticket.split("\\.", -1); require(envelope.length == 2);
            byte[] message = decode(envelope[0]); byte[] signature = decode(envelope[1]);
            require(message.length <= 2048 && signature.length == 64);
            String text = new String(message, StandardCharsets.US_ASCII);
            require(text.matches("[\\x20-\\x7e\\n]+"));
            String[] f = text.split("\n", -1);
            require(f.length == 12 && f[0].equals("AP-EVALUATION-BUSINESS-V1") && f[1].equals(method)
                && f[2].equals(target) && f[3].equals(generation) && actors.contains(f[4])
                && f[5].matches("[1-9][0-9]{0,14}") && f[6].matches("[1-9][0-9]{0,14}") && f[7].matches("[0-9a-f]{64}")
                && f[8].equals(contentType.isEmpty() ? "-" : contentType) && f[9].equals(bodySha256)
                && f[10].equals(idempotencyKey.isEmpty() ? "-" : idempotencyKey) && f[11].equals(requestId));
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(publicKey);
            verifier.update(message); require(verifier.verify(signature));
            long now;
            try { now = clock.millis(); } catch (RuntimeException failure) { disabled = true; throw failure; }
            if (now < 0 || now < lastTime) { disabled = true; require(false); }
            lastTime = now;
            long issued = Long.parseLong(f[5]); long expires = Long.parseLong(f[6]);
            require(issued <= now && expires > now && expires > issued && expires - issued <= 10_000);
            nonces.entrySet().removeIf(entry -> entry.getValue() <= now);
            require(nonces.size() < 256 && !nonces.containsKey(f[7])); nonces.put(f[7], expires);
            return new Identity(f[4], Instant.ofEpochMilli(expires), requestId);
        } catch (Exception failure) { throw new SecurityException("EVALUATION_BUSINESS_AUTHENTICATION_REQUIRED"); }
    }

    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException("EVALUATION_DIGEST_UNAVAILABLE"); }
    }
    public static String uploadDigest(String fileName, String type, byte[] bytes) {
        require(fileName != null && fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") && !fileName.contains("..")
            && bytes != null && bytes.length > 0 && bytes.length <= MAX_BODY_BYTES - 4096);
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        require(type != null && switch (type) {
            case "application/pdf" -> name.endsWith(".pdf") && bytes.length >= 5
                && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
            case "image/png" -> name.endsWith(".png") && bytes.length >= 8
                && java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, 8), new byte[] {-119, 80, 78, 71, 13, 10, 26, 10});
            case "image/jpeg" -> (name.endsWith(".jpg") || name.endsWith(".jpeg")) && bytes.length >= 3
                && bytes[0] == -1 && bytes[1] == -40 && bytes[2] == -1;
            case "text/plain" -> name.endsWith(".txt") && java.util.Arrays.equals(bytes,
                new String(bytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
            default -> false;
        });
        return digest(String.join("\n", "AP-EVALUATION-UPLOAD-V1", fileName, type, digest(bytes)).getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] decode(String value) {
        require(value.matches("[A-Za-z0-9_-]+")); byte[] bytes = Base64.getUrlDecoder().decode(value);
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)); return bytes;
    }
    private static void require(boolean value) { if (!value) throw new SecurityException("EVALUATION_BUSINESS_REJECTED"); }
}
