import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.springframework.boot.buildpack.platform.docker.configuration.DockerConnectionConfiguration;
import org.springframework.boot.buildpack.platform.docker.transport.DockerConnectionException;
import org.springframework.boot.buildpack.platform.docker.transport.DockerEngineException;
import org.springframework.boot.buildpack.platform.docker.transport.HttpTransport;

/** Ordinary transport compatibility only. All endpoints and identities are fixture-owned. */
public final class BootPluginHttpProbe {
    private static final Map<String, String[]> ARTIFACTS = new HashMap<>();
    private static final Set<String> USES = new TreeSet<>();
    private static final Map<String, Set<String>> CONSUMERS = new HashMap<>();
    private static final Set<String> FAILURE_STAGES = Set.of("manifest", "bindings", "linkage", "http", "unix",
            "tls-trusted", "tls-hostname", "tls-untrusted");
    private static final Set<String> FAILURE_ASSERTIONS = Set.of("setup", "bindings", "symbolic-linkage", "factory",
            "get-uri", "empty-post", "request-header", "post-body", "put-body", "head-header", "head-empty-response",
            "delete", "gzip-decoding", "chunked-response", "docker-json-error", "connection-reuse", "reuse-body",
            "partial-read", "after-close", "complete", "tls-trusted", "tls-repeated", "tls-rejection", "client-close");
    private static final Set<String> FAILURE_CLASSES = Set.of("java.lang.AssertionError", "java.lang.NullPointerException",
            "java.lang.IllegalArgumentException", "java.lang.IllegalStateException", "java.lang.SecurityException",
            "java.lang.UnsupportedOperationException", "java.lang.ClassNotFoundException", "java.lang.NoSuchFieldException",
            "java.lang.NoSuchMethodException", "java.lang.IllegalAccessException", "java.lang.NoClassDefFoundError",
            "java.lang.NoSuchMethodError", "java.lang.NoSuchFieldError", "java.lang.ExceptionInInitializerError",
            "java.lang.reflect.InaccessibleObjectException", "java.lang.reflect.InvocationTargetException",
            "java.io.IOException", "java.io.EOFException", "java.net.ConnectException", "java.net.SocketException",
            "java.net.SocketTimeoutException", "javax.net.ssl.SSLException", "javax.net.ssl.SSLHandshakeException",
            "javax.net.ssl.SSLPeerUnverifiedException", "java.security.cert.CertificateException",
            "org.springframework.boot.buildpack.platform.docker.transport.DockerConnectionException",
            "org.springframework.boot.buildpack.platform.docker.transport.DockerEngineException");
    private static String failureStage = "manifest", failureAssertion = "setup";

    // Only fixed labels and allowlisted types leave this diagnostic boundary.
    // Never include exception messages, stack frames, endpoint values or paths.
    private static void failureWitness(Throwable failure) {
        StringBuilder classes = new StringBuilder();
        for (int count = 0; failure != null && count < 8; count++, failure = failure.getCause()) {
            if (count > 0) classes.append(',');
            String name = failure.getClass().getName();
            classes.append(FAILURE_CLASSES.contains(name) ? name : "OTHER");
        }
        System.out.println("BOOT_HTTP_FAILURE\t" + (FAILURE_STAGES.contains(failureStage) ? failureStage : "unknown")
                + "\t" + (FAILURE_ASSERTIONS.contains(failureAssertion) ? failureAssertion : "unknown") + "\t" + classes);
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }

    private static Class<?> origin(String name, String artifact) throws Exception {
        Class<?> type = Class.forName(name, false, BootPluginHttpProbe.class.getClassLoader());
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        String[] expected = ARTIFACTS.get(artifact);
        check(expected != null && actual.equals(Path.of(expected[0]).toRealPath()), "origin path " + name);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual)));
        check(hash.equals(expected[1]), "origin hash " + name);
        System.out.println("BOOT_HTTP_ORIGIN\t" + name + "\t" + actual + "\t" + hash);
        return type;
    }

    // Bounded adaptation of CheckstyleLangProbe's accepted constant-pool reader.
    // This inventories symbolic members, not dynamic invocation reachability.
    private static int u2(byte[] bytes, int offset) { return (bytes[offset] & 255) << 8 | bytes[offset + 1] & 255; }
    private static int i4(byte[] bytes, int offset) { return u2(bytes, offset) << 16 | u2(bytes, offset + 2); }

    // Instruction sizes and switch/wide handling follow CheckstyleGuavaProbe.
    private static void code(byte[] bytes, Map<Integer, String> targets) {
        for (int pos = 0; pos < bytes.length;) {
            int opcode = bytes[pos] & 255;
            if (opcode >= 178 && opcode <= 185 && targets.containsKey(u2(bytes, pos + 1))) {
                USES.add((opcode == 178 || opcode == 179 || opcode == 184 ? "static" : "instance")
                        + "\t" + targets.get(u2(bytes, pos + 1)));
            }
            int size;
            if (opcode == 170 || opcode == 171) {
                int aligned = (pos + 4) & ~3;
                if (opcode == 170) {
                    long count = (long) i4(bytes, aligned + 8) - i4(bytes, aligned + 4) + 1;
                    if (count < 0 || count > 65536) throw new AssertionError("tableswitch budget");
                    size = aligned - pos + 12 + (int) count * 4;
                } else {
                    int count = i4(bytes, aligned + 4);
                    if (count < 0 || count > 65536) throw new AssertionError("lookupswitch budget");
                    size = aligned - pos + 8 + count * 8;
                }
            } else if (opcode == 196) {
                size = (bytes[pos + 1] & 255) == 132 ? 6 : 4;
            } else {
                size = switch (opcode) {
                    case 16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188 -> 2;
                    case 17, 19, 20, 132, 153, 154, 155, 156, 157, 158, 159, 160,
                         161, 162, 163, 164, 165, 166, 167, 168, 178, 179, 180, 181,
                         182, 183, 184, 187, 189, 192, 193, 198, 199 -> 3;
                    case 197 -> 4;
                    case 185, 186, 200, 201 -> 5;
                    default -> 1;
                };
            }
            if (opcode > 201 || size < 1 || pos + size > bytes.length) throw new AssertionError("invalid instruction");
            pos += size;
        }
    }

    private static void pool(byte[] bytes, Set<String> classes, Set<String> members) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            check(input.readInt() == 0xcafebabe, "class magic");
            input.readUnsignedShort(); input.readUnsignedShort();
            int count = input.readUnsignedShort();
            Object[] values = new Object[count]; int[] tags = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte(); tags[index] = tag;
                switch (tag) {
                    case 1 -> values[index] = input.readUTF();
                    case 3, 4 -> input.readInt();
                    case 5, 6 -> { input.readLong(); index++; }
                    case 7, 8, 16, 19, 20 -> values[index] = input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> values[index] = new int[] {input.readUnsignedShort(), input.readUnsignedShort()};
                    case 15 -> values[index] = new int[] {input.readUnsignedByte(), input.readUnsignedShort()};
                    default -> throw new AssertionError("unknown constant-pool tag " + tag);
                }
            }
            input.readUnsignedShort();
            String consumer = (String) values[(Integer) values[input.readUnsignedShort()]];
            input.readUnsignedShort();
            Map<Integer, String> targets = new HashMap<>();
            for (int index = 1; index < count; index++) {
                if (tags[index] == 7) {
                    String name = (String) values[(Integer) values[index]];
                    if (name.startsWith("org/apache/hc/")) classes.add(name);
                }
                if (tags[index] != 9 && tags[index] != 10 && tags[index] != 11) continue;
                int[] reference = (int[]) values[index];
                String owner = (String) values[(Integer) values[reference[0]]];
                if (!owner.startsWith("org/apache/hc/")) continue;
                int[] member = (int[]) values[reference[1]];
                String value = (tags[index] == 9 ? "field" : "method") + "\t" + owner + "\t" + values[member[0]] + "\t" + values[member[1]];
                members.add(value); targets.put(index, value);
                CONSUMERS.computeIfAbsent(value, ignored -> new TreeSet<>()).add(consumer);
            }
            for (int index = 1; index < count; index++) {
                if (tags[index] != 15) continue;
                int[] handle = (int[]) values[index];
                if (targets.containsKey(handle[1])) {
                    check(handle[0] >= 1 && handle[0] <= 9, "valid method handle kind");
                    USES.add((handle[0] == 2 || handle[0] == 4 || handle[0] == 6 ? "static" : "instance") + "\t" + targets.get(handle[1]));
                }
            }
            for (int interfaces = input.readUnsignedShort(); interfaces > 0; interfaces--) input.readUnsignedShort();
            for (boolean method : new boolean[] {false, true}) {
                for (int entries = input.readUnsignedShort(); entries > 0; entries--) {
                    input.readUnsignedShort(); input.readUnsignedShort(); input.readUnsignedShort();
                    for (int attributes = input.readUnsignedShort(); attributes > 0; attributes--) {
                        String name = (String) values[input.readUnsignedShort()];
                        int size = input.readInt();
                        check(size >= 0 && size <= 2 * 1024 * 1024, "bounded class attribute");
                        byte[] attribute = input.readNBytes(size);
                        check(attribute.length == size, "complete class attribute");
                        if (method && name.equals("Code") && !targets.isEmpty()) {
                            try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(attribute))) {
                                body.readUnsignedShort(); body.readUnsignedShort();
                                int length = body.readInt();
                                check(length >= 0 && length <= 65535, "bytecode budget");
                                byte[] instructions = body.readNBytes(length);
                                check(instructions.length == length, "complete bytecode");
                                code(instructions, targets);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void symbolicLinkage(boolean negative) throws Exception {
        Set<String> classes = new TreeSet<>(), members = new TreeSet<>();
        int count = 0; long bytes = 0;
        for (String artifact : new String[] {"spring-boot-maven-plugin", "spring-boot-buildpack-platform"}) {
            try (ZipFile jar = new ZipFile(ARTIFACTS.get(artifact)[0])) {
                check(jar.size() <= 5000, "bounded Boot JAR entries " + artifact);
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")) continue;
                    check(entry.getSize() > 0 && entry.getSize() <= 2 * 1024 * 1024, "bounded Boot class");
                    try (InputStream input = jar.getInputStream(entry)) {
                        byte[] data = input.readNBytes(2 * 1024 * 1024 + 1);
                        count++; bytes += data.length;
                        check(data.length == entry.getSize() && count <= 5000 && bytes <= 64 * 1024 * 1024, "Boot class inventory budget");
                        pool(data, classes, members);
                    }
                }
            }
        }
        for (String name : classes) {
            String artifact = name.startsWith("org/apache/hc/client5/") ? "httpclient5"
                    : name.startsWith("org/apache/hc/core5/http2/") ? "httpcore5-h2" : "httpcore5";
            origin(name.replace('/', '.'), artifact);
        }
        ClassLoader loader = BootPluginHttpProbe.class.getClassLoader();
        if (negative) members.add("method\torg/apache/hc/client5/http/classic/HttpClient\tmissingFixtureMethod\t()V");
        for (String reference : members) {
            String[] parts = reference.split("\t");
            Class<?> owner = Class.forName(parts[1].replace('/', '.'), false, loader);
            check(Modifier.isPublic(owner.getModifiers()), "public symbolic owner " + parts[1]);
            int modifiers;
            if (parts[0].equals("field")) {
                Field field = owner.getField(parts[2]);
                check(field.getType().descriptorString().equals(parts[3]), "field descriptor " + reference);
                modifiers = field.getModifiers();
            } else {
                MethodType signature = MethodType.fromMethodDescriptorString(parts[3], loader);
                if (parts[2].equals("<init>")) {
                    check(signature.returnType() == void.class, "constructor descriptor " + reference);
                    modifiers = owner.getDeclaredConstructor(signature.parameterArray()).getModifiers();
                } else {
                    var method = owner.getMethod(parts[2], signature.parameterArray());
                    check(method.getReturnType() == signature.returnType(), "method return descriptor " + reference);
                    modifiers = method.getModifiers();
                }
            }
            boolean accessible = Modifier.isPublic(modifiers);
            check(parts[2].equals("<init>") || accessible, "nonconstructor member resolves public " + reference);
            if (Modifier.isProtected(modifiers)) {
                accessible = true;
                for (String consumer : CONSUMERS.get(reference)) {
                    accessible &= owner.isAssignableFrom(Class.forName(consumer.replace('/', '.'), false, loader));
                }
            }
            check(accessible, "accessible symbolic member " + reference);
            String kind = Modifier.isStatic(modifiers) ? "static" : "instance";
            check(!USES.contains((kind.equals("static") ? "instance" : "static") + "\t" + reference), "invocation modifier " + reference);
            System.out.println("BOOT_HTTP_MEMBER\t" + reference + "\t" + kind + "\t" + (Modifier.isPublic(modifiers) ? "public" : "protected"));
        }
        check(!members.isEmpty(), "actual Boot Apache member inventory nonempty");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((String.join("\n", members) + "\n").getBytes(StandardCharsets.UTF_8)));
        System.out.println("BOOT_HTTP_MEMBER_INVENTORY\t" + count + "\t" + classes.size() + "\t" + members.size() + "\t" + hash + "\t" + USES.size());
    }

    private static void bindings() throws Exception {
        String client = "org.apache.hc.client5.http.";
        String core = "org.apache.hc.core5.http.";
        origin(HttpTransport.class.getName(), "spring-boot-buildpack-platform");
        origin("org.springframework.boot.buildpack.platform.json.SharedJsonMapper", "spring-boot-buildpack-platform");
        origin("tools.jackson.core.json.JsonFactory", "jackson-core");
        origin("tools.jackson.databind.json.JsonMapper", "jackson-databind");
        origin("org.apache.hc.core5.http2.config.H2Config", "httpcore5-h2");
        Class<?> httpClient = origin(client + "classic.HttpClient", "httpclient5");
        Class<?> host = origin(core + "HttpHost", "httpcore5");
        httpClient.getMethod("executeOpen", host, Class.forName(core + "ClassicHttpRequest"),
                Class.forName(core + "protocol.HttpContext"));
        Class<?> factory = origin(client + "io.DetachedSocketFactory", "httpclient5");
        factory.getMethod("create", java.net.Proxy.class);
        origin(client + "impl.io.DefaultHttpClientConnectionOperator", "httpclient5").getConstructor(factory,
                Class.forName(client + "SchemePortResolver"), Class.forName(client + "DnsResolver"),
                Class.forName(core + "config.Lookup"));
        origin(client + "impl.io.BasicHttpClientConnectionManager", "httpclient5").getConstructor(
                Class.forName(client + "io.HttpClientConnectionOperator"), Class.forName(core + "io.HttpConnectionFactory"));
        origin(client + "ssl.DefaultClientTlsStrategy", "httpclient5").getConstructor(javax.net.ssl.SSLContext.class);
        origin(client + "impl.io.PoolingHttpClientConnectionManagerBuilder", "httpclient5").getMethod(
                "setTlsSocketStrategy", Class.forName(client + "ssl.TlsSocketStrategy"));
        origin(core + "io.SocketConfig", "httpcore5").getMethod("copy", Class.forName(core + "io.SocketConfig"));
        check(true, "Boot public and internal HttpComponents API bindings");
    }

    private static HttpTransport transport(String host, boolean secure, String certificates, String expected) throws Exception {
        HttpTransport result = HttpTransport.create(new DockerConnectionConfiguration.Host(host, secure, certificates));
        check(result.getClass().getSimpleName().equals(expected), "factory selects " + expected);
        origin(result.getClass().getName(), "spring-boot-buildpack-platform");
        return result;
    }

    // HttpTransport does not expose a client close method. Close its actual owned
    // client after each fixture case; do not alter its configuration or behavior.
    private static void close(HttpTransport transport) throws Exception {
        try {
            Field field = transport.getClass().getSuperclass().getDeclaredField("client");
            field.setAccessible(true);
            ((AutoCloseable) field.get(transport)).close();
        } catch (Exception | Error failure) {
            failureAssertion = "client-close";
            throw failure;
        }
    }

    private static String body(HttpTransport.Response response) throws Exception {
        try (response) {
            InputStream input = response.getContent();
            if (input == null) return "";
            byte[] bytes = input.readNBytes(32769);
            check(bytes.length <= 32768, "bounded response");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static URI uri(String path) { return URI.create("/" + path); }

    static boolean headResponseHasNoEntity(HttpTransport.Response response) throws Exception {
        // Boot 4.0.8 getContent() dereferences the entity, which Apache leaves
        // absent for HEAD. Inspect the actual response without reading a body.
        Field field = response.getClass().getDeclaredField("response");
        field.setAccessible(true);
        var actual = (org.apache.hc.core5.http.ClassicHttpResponse) field.get(response);
        return actual.getEntity() == null;
    }

    private static void ordinary(HttpTransport transport, String label) throws Exception {
        try {
            failureAssertion = "get-uri";
            check(body(transport.get(uri("get?value=caf%C3%A9"))).equals("GET /get?value=caf%C3%A9"), label + " GET URI");
            failureAssertion = "empty-post";
            check(body(transport.post(uri("post"))).equals("POST /post"), label + " empty POST");
            failureAssertion = "request-header";
            check(body(transport.post(uri("auth"), "fixture-only-noncredential")).equals("fixture-only-noncredential"), label + " request header");
            failureAssertion = "post-body";
            check(body(transport.post(uri("body"), "application/json", out -> out.write("{\"value\":\"café\"}".getBytes(StandardCharsets.UTF_8))))
                    .equals("POST|application/json|{\"value\":\"café\"}"), label + " POST body");
            failureAssertion = "put-body";
            check(body(transport.put(uri("body"), "text/plain", out -> out.write("put café".getBytes(StandardCharsets.UTF_8))))
                    .equals("PUT|text/plain|put café"), label + " PUT body");
            failureAssertion = "head-header";
            try (HttpTransport.Response response = transport.head(uri("head"))) {
                check(response.getHeader("X-Fixture").getValue().equals("head"), label + " HEAD header");
                failureAssertion = "head-empty-response";
                check(headResponseHasNoEntity(response), label + " HEAD empty response");
            }
            failureAssertion = "delete";
            check(body(transport.delete(uri("delete"))).equals("DELETE /delete"), label + " DELETE");
            failureAssertion = "gzip-decoding";
            check(body(transport.get(uri("gzip"))).equals("compressed café"), label + " gzip decoding");
            failureAssertion = "chunked-response";
            check(body(transport.get(uri("stream"))).equals("one\ntwo\nthree\n"), label + " chunked response stream");
            failureAssertion = "docker-json-error";
            boolean rejected = false;
            try (HttpTransport.Response ignored = transport.get(uri("error"))) {
                throw new AssertionError("404 was accepted");
            } catch (DockerEngineException failure) {
                check(failure.getStatusCode() == 404 && failure.getResponseMessage() != null
                        && failure.getResponseMessage().getMessage().equals("fixture missing"), label + " Docker JSON error mapping");
                rejected = true;
            }
            check(rejected, label + " ordinary error rejected");
            String connection = null;
            for (int i = 0; i < 3; i++) {
                failureAssertion = "connection-reuse";
                try (HttpTransport.Response response = transport.get(uri("reuse"))) {
                    String current = response.getHeader("X-Connection").getValue();
                    check(connection == null || connection.equals(current), label + " connection reused " + i);
                    connection = current;
                    failureAssertion = "reuse-body";
                    check(new String(response.getContent().readNBytes(100), StandardCharsets.UTF_8).equals("GET /reuse"), label + " reuse body " + i);
                }
            }
            failureAssertion = "partial-read";
            try (HttpTransport.Response response = transport.get(uri("partial"))) {
                check(response.getContent().read() == 'x', label + " partial read");
            }
            failureAssertion = "after-close";
            check(body(transport.get(uri("after-close"))).equals("GET /after-close"), label + " usable after response close");
            failureAssertion = "complete";
            check(true, label + " complete");
        } finally { close(transport); }
    }

    private static void tlsRejected(HttpTransport transport, boolean hostname) throws Exception {
        failureAssertion = "tls-rejection";
        try {
            try (HttpTransport.Response ignored = transport.get(uri("tls"))) {
                throw new AssertionError("invalid TLS peer accepted");
            } catch (DockerConnectionException failure) {
                boolean tls = false, reason = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    tls |= cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException;
                    String message = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
                    reason |= hostname ? (message.contains("subject alternative") || message.contains("doesn't match") || message.contains("no name matching"))
                            : (message.contains("pkix") || message.contains("certification path") || message.contains("trust anchor"));
                    check(!(cause instanceof java.net.ConnectException) && !(cause instanceof java.net.SocketTimeoutException), "TLS negative is not connection failure");
                }
                check(tls && reason, hostname ? "TLS hostname mismatch rejected" : "TLS untrusted certificate rejected");
                System.out.println("BOOT_HTTP_TLS_REJECTION\t" + (hostname ? "hostname" : "untrusted") + "\t" + failure.getCause().getClass().getName());
            }
        } finally { close(transport); }
    }

    public static void main(String[] args) throws Exception {
        try {
            probe(args);
        } catch (Exception | Error failure) {
            failureWitness(failure);
            throw failure;
        }
    }

    private static void probe(String[] args) throws Exception {
        check(args.length == 2 || (args.length == 3 && Set.of("missing-member", "linkage-only").contains(args[2])), "fixture and identity manifests required");
        Properties fixture = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(args[0]))) { fixture.load(input); }
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] fields = line.split("\t");
            check(fields.length == 3 && ARTIFACTS.put(fields[0], new String[] {fields[1], fields[2]}) == null, "unique artifact identity " + fields[0]);
        }
        check(System.getProperty("user.home").equals(fixture.getProperty("home")), "fixture-owned user home");
        check(System.getenv("DOCKER_CONFIG").equals(fixture.getProperty("docker")), "fixture-owned Docker configuration");
        for (String name : new String[] {"DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY"})
            check(System.getenv(name) == null, "ambient Docker setting absent " + name);
        failureStage = "bindings"; failureAssertion = "bindings";
        bindings();
        failureStage = "linkage"; failureAssertion = "symbolic-linkage";
        symbolicLinkage(args.length == 3 && args[2].equals("missing-member"));
        if (args.length == 3 && args[2].equals("linkage-only")) {
            System.out.println("BOOT_HTTP_LINKAGE_COMPLETE diagnostic only; no HTTP, TLS or Unix transport executed");
            return;
        }
        check(args.length == 2, "negative linkage control must fail before transport");
        failureStage = "http"; failureAssertion = "factory";
        ordinary(transport("http://127.0.0.1:" + fixture.getProperty("http"), false, null, "RemoteHttpClientTransport"), "HTTP");
        failureStage = "unix"; failureAssertion = "factory";
        ordinary(transport("unix://" + fixture.getProperty("unix"), false, null, "LocalHttpClientTransport"), "Unix");
        failureStage = "tls-trusted"; failureAssertion = "factory";
        HttpTransport trusted = transport("https://localhost:" + fixture.getProperty("tls"), true,
                fixture.getProperty("trusted"), "RemoteHttpClientTransport");
        try {
            failureAssertion = "tls-trusted";
            check(body(trusted.get(uri("tls"))).equals("GET /tls"), "TLS trusted localhost");
            failureAssertion = "tls-repeated";
            check(body(trusted.get(uri("tls"))).equals("GET /tls"), "TLS repeated request");
        } finally { close(trusted); }
        failureStage = "tls-hostname"; failureAssertion = "factory";
        tlsRejected(transport("https://127.0.0.1:" + fixture.getProperty("tls"), true,
                fixture.getProperty("trusted"), "RemoteHttpClientTransport"), true);
        failureStage = "tls-untrusted"; failureAssertion = "factory";
        tlsRejected(transport("https://localhost:" + fixture.getProperty("tls"), true,
                fixture.getProperty("untrusted"), "RemoteHttpClientTransport"), false);
        System.out.println("BOOT_HTTP_COMPLETE ordinary HTTP, TLS and Linux Unix compatibility; no Docker build or vulnerability reproduction");
    }
}
