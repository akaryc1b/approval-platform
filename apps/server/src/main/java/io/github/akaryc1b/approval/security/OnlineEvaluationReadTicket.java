package io.github.akaryc1b.approval.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Single-use, generation-bound proof for one internal read, never a browser session. */
public final class OnlineEvaluationReadTicket {
    public static final String PATH = "/api/approval/tasks/pending";
    public static final String HEADER = "X-Evaluation-Read-Ticket";
    private static final long MAX_LIFETIME_MS = 10_000;
    private static final int MAX_NONCES = 256;
    private final PublicKey publicKey;
    private final String generation;
    private final Set<String> actors;
    private final Clock clock;
    private final Map<String, Long> nonces = new HashMap<>();
    private long lastTime = -1;
    private boolean disabled;

    public OnlineEvaluationReadTicket(String encodedKey, String generation, Set<String> actors, Clock clock) {
        try {
            require(encodedKey != null && encodedKey.length() <= 128);
            byte[] der = Base64.getDecoder().decode(encodedKey);
            require(Base64.getEncoder().encodeToString(der).equals(encodedKey));
            publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            require(Base64.getEncoder().encodeToString(publicKey.getEncoded()).equals(encodedKey));
            require(generation != null && generation.matches("[0-9a-f]{32}") && !generation.matches("0+"));
            require(actors != null && !actors.isEmpty() && actors.size() <= 8);
            for (String actor : actors) require(actor != null && actor.matches("demo-[a-z0-9-]{1,58}")
                && !actor.contains("admin"));
            this.generation = generation;
            this.actors = Set.copyOf(actors);
            this.clock = Objects.requireNonNull(clock);
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_EVALUATION_IDENTITY_CONFIGURATION");
        }
    }

    public record Identity(String actorId, Instant expiresAt) {}

    /** Synchronized admission prevents concurrent replay; live entries are never evicted. */
    public synchronized Identity verify(String ticket, String method, String path) {
        try {
            require(!disabled && "GET".equals(method) && PATH.equals(path));
            require(ticket != null && ticket.length() <= 1024);
            String[] envelope = ticket.split("\\.", -1);
            require(envelope.length == 2);
            byte[] message = decode(envelope[0]);
            byte[] signature = decode(envelope[1]);
            require(message.length <= 512 && signature.length == 64);
            String text = new String(message, StandardCharsets.US_ASCII);
            require(text.matches("[\\x20-\\x7e\\n]+"));
            String[] fields = text.split("\n", -1);
            require(fields.length == 8 && fields[0].equals("AP-EVALUATION-READ-V1")
                && fields[1].equals(method) && fields[2].equals(path)
                && fields[3].equals(generation) && actors.contains(fields[4])
                && fields[5].matches("[1-9][0-9]{0,14}") && fields[6].matches("[1-9][0-9]{0,14}")
                && fields[7].matches("[0-9a-f]{64}"));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            require(verifier.verify(signature));
            long now;
            try { now = clock.millis(); }
            catch (RuntimeException exception) { disabled = true; throw exception; }
            if (now < 0 || now < lastTime) { disabled = true; require(false); }
            lastTime = now;
            long issued = Long.parseLong(fields[5]);
            long expires = Long.parseLong(fields[6]);
            require(issued <= now && expires > now && expires > issued && expires - issued <= MAX_LIFETIME_MS);
            nonces.entrySet().removeIf(entry -> entry.getValue() <= now);
            require(nonces.size() < MAX_NONCES && !nonces.containsKey(fields[7]));
            nonces.put(fields[7], expires);
            return new Identity(fields[4], Instant.ofEpochMilli(expires));
        } catch (Exception exception) {
            // No key, proof, actor, decoder detail or provider error escapes this boundary.
            throw new SecurityException("EVALUATION_READ_AUTHENTICATION_REQUIRED");
        }
    }

    private static byte[] decode(String value) {
        require(value.matches("[A-Za-z0-9_-]+"));
        byte[] result = Base64.getUrlDecoder().decode(value);
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(result).equals(value));
        return result;
    }

    private static void require(boolean condition) {
        if (!condition) throw new SecurityException("EVALUATION_READ_REJECTED");
    }
}
