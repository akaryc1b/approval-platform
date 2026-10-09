package io.github.akaryc1b.approval.integration;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Test-only, bounded loopback receiver; verifies requests before any simulated side effect. */
final class SignedCallbackProbe implements AutoCloseable {
    static final String PROVIDER_CANARY = "private-provider-error-not-telemetry";
    record Request(String body, Map<String, String> headers) { }
    record Delivery(Request request, int status) { }

    private final ServerSocket server;
    private final ExecutorService worker;
    private final Consumer<Request> verifier;
    private final Map<String, String> accepted = new ConcurrentHashMap<>();
    private final Set<String> failOnce = ConcurrentHashMap.newKeySet();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean closed;
    private volatile Socket active;

    SignedCallbackProbe(Consumer<Request> verifier) throws IOException {
        this.verifier = java.util.Objects.requireNonNull(verifier);
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(200);
        worker = Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "signed-callback-test-receiver");
            thread.setDaemon(true);
            return thread;
        });
        worker.submit(this::serve);
    }

    URI uri() { return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/callback"); }
    void failFirst(String idempotencyKey) { failOnce.add(idempotencyKey); }
    List<Delivery> deliveries() { return List.copyOf(deliveries); }
    int acceptedCount() { return accepted.size(); }
    void assertHealthy() {
        if (failure.get() != null) throw new AssertionError("callback fixture rejected a request", failure.get());
    }

    private void serve() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                active = socket;
                if (closed) return;
                socket.setSoTimeout(2000);
                Request request = read(socket);
                verifier.accept(request);
                String key = request.headers().get("idempotency-key");
                if (key == null || key.isBlank() || deliveries.size() >= 32) {
                    throw new IOException("invalid or excessive callback request");
                }
                int status = failOnce.remove(key) ? 503 : 204;
                if (status == 204) {
                    String previous = accepted.putIfAbsent(key, request.body());
                    if (previous != null && !previous.equals(request.body())) {
                        throw new IOException("idempotency key reused with a different body");
                    }
                }
                deliveries.add(new Delivery(request, status));
                byte[] body = (status == 503 ? PROVIDER_CANARY : "").getBytes(StandardCharsets.UTF_8);
                String head = "HTTP/1.1 " + status + (status == 204 ? " No Content" : " Service Unavailable")
                    + "\r\nContent-Length: " + body.length
                    + "\r\nX-Request-Id: controlled-callback\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (SocketTimeoutException timeout) {
                if (active != null && !closed) failure.compareAndSet(null, timeout);
            } catch (Exception | AssertionError rejected) {
                if (!closed) failure.compareAndSet(null, rejected);
            } finally {
                active = null;
            }
        }
    }

    private static Request read(Socket socket) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        var input = new BufferedInputStream(socket.getInputStream());
        var head = new ByteArrayOutputStream();
        int end = 0;
        while (end != 0x0d0a0d0a) {
            if (System.nanoTime() >= deadline || head.size() >= 16384) throw new IOException("header limit");
            int next = input.read();
            if (next == -1) throw new IOException("truncated header");
            head.write(next);
            end = (end << 8) | next;
        }
        String[] lines = head.toString(StandardCharsets.US_ASCII).split("\r\n");
        if (!"POST /callback HTTP/1.1".equals(lines[0])) throw new IOException("unexpected request line");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) throw new IOException("invalid header");
            String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, lines[i].substring(colon + 1).trim()) != null) {
                throw new IOException("duplicate header");
            }
        }
        if (headers.containsKey("transfer-encoding")) throw new IOException("transfer encoding not supported");
        String lengthText = headers.getOrDefault("content-length", "");
        if (!lengthText.matches("[0-9]{1,5}")) throw new IOException("invalid content length");
        int length = Integer.parseInt(lengthText);
        if (length > 65536) throw new IOException("body limit");
        var body = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (body.size() < length) {
            if (System.nanoTime() >= deadline) throw new IOException("body deadline");
            int count = input.read(buffer, 0, Math.min(buffer.length, length - body.size()));
            if (count == -1) throw new IOException("truncated body");
            body.write(buffer, 0, count);
        }
        return new Request(body.toString(StandardCharsets.UTF_8), Map.copyOf(headers));
    }

    @Override
    public void close() throws IOException {
        closed = true;
        try {
            server.close();
        } finally {
            Socket current = active;
            try {
                if (current != null) current.close();
            } finally {
                worker.shutdownNow();
                try {
                    if (!worker.awaitTermination(5, TimeUnit.SECONDS)) throw new IOException("receiver did not stop");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("receiver stop interrupted", interrupted);
                }
            }
        }
    }
}
