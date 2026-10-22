import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** DRAFT: statically reviewed only, not compiled or executed.
 * Passive definitions, exact selected execution events, then separately marked
 * controlled API replay and symbolic validation in each existing native realm.
 * No target bytecode transformation, replacement realm, or target initialization
 * during symbolic validation. Symbolic validation is not invocation coverage.
 */
public final class ResourcesFlattenRealmObserver {
    private static final String PREFIX = "RF_";
    private static final Map<String, String> definitions = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String, ClassLoader> realms = new LinkedHashMap<>();
    private static volatile String phase = "NATURAL";
    private static volatile boolean observerFailed;
    private static Path inventoryFile, terminalFile;
    private static String owner, replayMode, utilsVersion;
    private static List<String[]> expected;
    private static String[] active;
    private static ClassLoader activeRealm;
    private static int completed;
    private static boolean finished;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String id(ClassLoader loader) {
        return Integer.toHexString(System.identityHashCode(loader));
    }
    private static String loaderName(ClassLoader loader) {
        return String.valueOf(loader).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
    private static String source(ProtectionDomain domain) {
        return domain == null || domain.getCodeSource() == null ? "MISSING"
            : domain.getCodeSource().getLocation().toExternalForm();
    }
    private static boolean relevant(String name) {
        return name.startsWith("org/apache/maven/plugins/resources/")
            || name.startsWith("org/codehaus/mojo/flatten/")
            || name.startsWith("org/apache/maven/shared/filtering/")
            || name.startsWith("org/sonatype/plexus/build/")
            || name.startsWith("org/codehaus/plexus/util/")
            || name.startsWith("org/codehaus/plexus/interpolation/");
    }
    private static void fail(String where, Throwable failure) {
        observerFailed = true;
        System.err.println(PREFIX + "FAILURE\t" + where + "\t"
            + failure.toString().replace('\t', ' ').replace('\r', ' ').replace('\n', ' '));
    }
    public static synchronized void bridgeFailure(String where, Throwable failure) {
        fail(where, failure);
    }
    private static List<String> boundedLines(Path path, int bytes, int rows, boolean distinct) throws Exception {
        check(path.isAbsolute() && !Files.isSymbolicLink(path) && Files.isRegularFile(path)
            && Files.size(path) <= bytes, "bounded regular input: " + path);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        check(!lines.isEmpty() && lines.size() <= rows
            && (!distinct || new HashSet<>(lines).size() == lines.size()), "bounded nonempty rows: " + path);
        return lines;
    }

    public static void premain(String inventory, Instrumentation instrumentation) throws Exception {
        inventoryFile = Path.of(inventory);
        terminalFile = Path.of(System.getProperty("rf.terminals"));
        owner = System.getProperty("rf.owner");
        replayMode = System.getProperty("rf.replay", "none");
        utilsVersion = System.getProperty("rf.utils.version");
        check(Runtime.version().feature() == 21, "fixed JDK21 runtime");
        check(Set.of("resources", "flatten").contains(owner), "fixed owner");
        check(Set.of("none", "api").contains(replayMode), "fixed replay mode");
        check(Set.of("4.0.2", "4.0.3").contains(utilsVersion), "fixed Utils version");
        expected = new ArrayList<>();
        // Repeating the same goal/execution/project is deliberate in repeat/clean cases.
        for (String line : boundedLines(terminalFile, 65536, 128, false)) {
            String[] p = line.split("\t", -1);
            check(p.length == 6 && p[0].equals(owner), "expected terminal row shape/owner");
            check(p[1].equals(owner.equals("resources") ? "3.4.0" : "1.7.3"), "fixed goal version");
            check((owner.equals("resources") ? Set.of("resources", "testResources", "copy-resources")
                : Set.of("flatten", "clean")).contains(p[2]), "fixed goal");
            check(!p[3].isEmpty() && !p[4].isEmpty() && !p[4].startsWith("/")
                && !Arrays.asList(p[4].split("/", -1)).contains(".."), "execution/project key");
            check(Set.of("MojoSucceeded", "MojoFailed").contains(p[5]), "terminal outcome");
            expected.add(p);
        }
        boundedLines(inventoryFile, 262144, 1000, true);
        System.err.println(PREFIX + "PLAN\t" + owner + "\t" + utilsVersion + "\t"
            + replayMode + "\t" + expected.size() + "\t" + hash(Files.readAllBytes(terminalFile))
            + "\t" + hash(Files.readAllBytes(inventoryFile)));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] bytes) {
                if (name == null || !relevant(name)) return null;
                try {
                    String digest = hash(bytes);
                    String key = id(loader) + "\t" + name;
                    String previous = definitions.putIfAbsent(key, digest);
                    check(previous == null || previous.equals(digest), "definition changed: " + key);
                    System.err.println(PREFIX + phase + "_LOAD\t" + name + "\t" + id(loader)
                        + "\t" + loaderName(loader) + "\t" + source(domain) + "\t" + digest);
                } catch (Throwable failure) { fail("transform", failure); }
                return null;
            }
        }, false);
    }

    /** EventSpy passes only the actual selected project's state and native realm. */
    public static synchronized void event(String event, String observedOwner, String version,
            String goal, String execution, String projectKey, String stateBase64,
            ClassLoader loader, String implementation, String outputPath,
            String outputBase64) throws Exception {
        check(!observerFailed && !finished && completed < expected.size(), "selected event after failure/completion");
        String[] row = expected.get(completed);
        String[] actual = {observedOwner, version, goal, execution, projectKey};
        check(Arrays.equals(Arrays.copyOf(row, 5), actual), "selected event identity/order: " + Arrays.toString(actual));
        check(loader instanceof URLClassLoader || loader == null && event.equals("MojoStarted"),
            "existing native plugin URL realm or explicitly pending start");
        String selectedId = loader == null ? "PENDING" : id(loader);
        if (event.equals("MojoStarted")) {
            check(active == null, "no overlapping selected goal; parallel Maven unsupported");
            active = row;
            activeRealm = loader;
        } else {
            check(event.equals(row[5]) && active == row && (activeRealm == null || activeRealm == loader),
                "one matching started/terminal pair and exact result");
            String mojo = implementation.replace('.', '/');
            check(definitions.containsKey(selectedId + "\t" + mojo), "actual Mojo definition not naturally observed");
        }
        if (loader != null) {
            ClassLoader previous = realms.putIfAbsent(selectedId, loader);
            check(previous == null || previous == loader, "loader identity collision");
            if (previous == null) {
                check(realms.size() <= 8, "bounded native realm count");
                for (var url : ((URLClassLoader) loader).getURLs()) {
                    System.err.println(PREFIX + "REALM_URL\t" + selectedId + "\t"
                        + loaderName(loader) + "\t" + url.toExternalForm());
                }
            }
        }
        // stateBase64 is UTF-8 JSON from actual MavenProject getters, no target calls.
        check(stateBase64.length() <= 65536, "bounded project state");
        Base64.getDecoder().decode(stateBase64);
        System.err.println(PREFIX + "EVENT\t" + completed + "\t" + event + "\t"
            + String.join("\t", actual) + "\t" + selectedId + "\t" + implementation
            + "\t" + stateBase64);
        boolean flattened = owner.equals("flatten") && goal.equals("flatten") && event.equals("MojoSucceeded");
        check(flattened == (outputPath != null && outputBase64 != null), "snapshot matches successful actual flatten");
        if (flattened) {
            check(outputBase64.length() <= 349528, "bounded output encoding");
            byte[] output = Base64.getDecoder().decode(outputBase64);
            check(output.length <= 262144, "bounded actual flattened output");
            System.err.println(PREFIX + "OUTPUT\t" + completed + "\t" + projectKey + "\t"
                + Base64.getEncoder().encodeToString(outputPath.getBytes(StandardCharsets.UTF_8))
                + "\t" + output.length + "\t" + hash(output) + "\t" + outputBase64);
        }
        if (event.equals("MojoStarted")) return;
        completed++;
        active = null;
        activeRealm = null;
        if (completed == expected.size()) complete();
    }

    private static Class<?> type(String name, ClassLoader loader, String selectedId) throws Exception {
        Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
        String definition = definitions.get(id(type.getClassLoader()) + "\t" + name);
        check(definition != null, "effective definition bytes unavailable: " + name);
        System.err.println(PREFIX + "TYPE\t" + phase + "\t" + selectedId + "\t" + name
            + "\t" + id(type.getClassLoader()) + "\t" + loaderName(type.getClassLoader())
            + "\t" + source(type.getProtectionDomain()) + "\t" + definition);
        return type;
    }
    private static Field field(Class<?> owner, String name, String descriptor) throws Exception {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getName().equals(name) && field.getType().descriptorString().equals(descriptor)) return field;
        }
        for (Class<?> parent : owner.getInterfaces()) {
            try { return field(parent, name, descriptor); } catch (NoSuchFieldException ignored) { }
        }
        if (owner.getSuperclass() != null) return field(owner.getSuperclass(), name, descriptor);
        throw new NoSuchFieldException(owner.getName() + "." + name + descriptor);
    }
    private static Method method(Class<?> owner, String name, MethodType signature) throws Exception {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getReturnType() == signature.returnType()
                && Arrays.equals(method.getParameterTypes(), signature.parameterArray())) return method;
        }
        if (owner.getSuperclass() != null) {
            try { return method(owner.getSuperclass(), name, signature); } catch (NoSuchMethodException ignored) { }
        }
        for (Class<?> parent : owner.getInterfaces()) {
            try { return method(parent, name, signature); } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + signature);
    }
    private static boolean accessible(Class<?> consumer, Class<?> owner, int modifiers) {
        boolean samePackage = consumer.getPackageName().equals(owner.getPackageName())
            && consumer.getClassLoader() == owner.getClassLoader();
        return Modifier.isPublic(modifiers) || (Modifier.isPrivate(modifiers)
            ? consumer.getNestHost() == owner.getNestHost()
            : samePackage || Modifier.isProtected(modifiers) && owner.isAssignableFrom(consumer));
    }
    private static void resolve(ClassLoader realm) throws Exception {
        String selectedId = id(realm);
        List<String> rows = boundedLines(inventoryFile, 262144, 1000, true);
        int consumers = 0, types = 0, members = 0;
        for (String row : rows) {
            String[] p = row.split("\t", -1);
            check(p.length == 3 || p.length == 4 || p.length == 7, "fixed inventory row shape");
            Class<?> consumer = type(p[1], realm, selectedId);
            if (p[0].equals("consumer")) {
                check(p.length == 4 && Path.of(URI.create(source(consumer.getProtectionDomain())))
                    .equals(Path.of(URI.create(p[2]))), "effective consumer origin");
                check(p[3].equals(definitions.get(id(consumer.getClassLoader()) + "\t" + p[1])),
                    "effective consumer bytes");
                consumers++;
                System.err.println(PREFIX + "CONSUMER\t" + selectedId + "\t" + row);
                continue;
            }
            // Core-imported consumers resolve through their real defining loader.
            ClassLoader defining = consumer.getClassLoader();
            Class<?> target = type(p[2], defining, selectedId);
            check(accessible(consumer, target, target.getModifiers()), "symbolic type access");
            if (p[0].equals("type")) {
                check(p.length == 3, "type row shape");
                types++;
                System.err.println(PREFIX + "TYPE_LINK\t" + selectedId + "\t" + row
                    + "\t" + id(target.getClassLoader()));
                continue;
            }
            check(p[0].equals("member") && p.length == 7, "member row shape");
            check(Set.of("static", "instance").contains(p[6]), "known invocation kind");
            Member linked;
            if (p[3].equals("field")) linked = field(target, p[4], p[5]);
            else {
                check(Set.of("method", "interfaceMethod").contains(p[3]), "known member kind");
                MethodType signature = MethodType.fromMethodDescriptorString(p[5], defining);
                if (p[4].equals("<init>")) {
                    check(signature.returnType() == void.class && p[6].equals("instance"), "constructor descriptor");
                    linked = target.getDeclaredConstructor(signature.parameterArray());
                } else linked = method(target, p[4], signature);
                check(!p[3].equals("interfaceMethod") || target.isInterface(), "interface invocation owner");
            }
            check(accessible(consumer, linked.getDeclaringClass(), linked.getModifiers()), "symbolic member access");
            check(Modifier.isStatic(linked.getModifiers()) == p[6].equals("static"), "static/instance compatibility");
            members++;
            System.err.println(PREFIX + "MEMBER\t" + selectedId + "\t" + row + "\t" + linked.getDeclaringClass().getName());
        }
        try {
            type("org/codehaus/plexus/util/StringUtils", realm, selectedId).getMethod("missingResourcesFlattenFixtureMethod");
            throw new AssertionError("missing member unexpectedly resolved");
        } catch (NoSuchMethodException expected) {
            System.err.println(PREFIX + "MISSING_MEMBER\t" + selectedId + "\tPASSED");
        }
        System.err.println(PREFIX + "LINKAGE_COMPLETE\t" + selectedId + "\t" + consumers
            + "\t" + types + "\t" + members + "\t" + hash(Files.readAllBytes(inventoryFile)));
    }
    private static void controlled(ClassLoader realm) throws Exception {
        String selectedId = id(realm);
        Class<?> strings = type("org/codehaus/plexus/util/StringUtils", realm, selectedId);
        Method empty = strings.getMethod("isEmpty", String.class);
        check(Boolean.TRUE.equals(empty.invoke(null, new Object[] {null})), "StringUtils null");
        check(Boolean.TRUE.equals(empty.invoke(null, "")), "StringUtils empty");
        check(Boolean.FALSE.equals(empty.invoke(null, "a")), "StringUtils nonempty");
        Class<?> selectors = type("org/codehaus/plexus/util/SelectorUtils", realm, selectedId);
        Method match = selectors.getMethod("matchPath", String.class, String.class);
        boolean fixed = utilsVersion.equals("4.0.3");
        for (String prefix : List.of("%ant[", "%regex[")) {
            String pattern = prefix + "a]";
            check(Boolean.valueOf(fixed).equals(match.invoke(null, pattern, "a")), "single-character prefixed API");
            check(Boolean.valueOf(!fixed).equals(match.invoke(null, pattern, pattern)), "single-character literal API");
            check(Boolean.TRUE.equals(match.invoke(null, prefix + "ab]", "ab")), "multi-character prefixed API");
        }
        System.err.println(PREFIX + "CONTROLLED_PASS\t" + selectedId + "\tStringUtils-and-SelectorUtils-bounded-API");
    }
    private static void complete() throws Exception {
        check(!observerFailed && !finished && active == null && !realms.isEmpty(), "completion state");
        System.err.println(PREFIX + "GOALS_COMPLETE\t" + completed + "\t" + realms.size());
        try {
            phase = "CONTROLLED";
            if (replayMode.equals("api")) for (ClassLoader realm : realms.values()) controlled(realm);
            System.err.println(PREFIX + "CONTROLLED_COMPLETE\t" + (replayMode.equals("api") ? "PASSED" : "SKIPPED"));
            phase = "SYMBOLIC";
            for (ClassLoader realm : realms.values()) resolve(realm);
            check(!observerFailed, "passive observer failure");
            finished = true;
            System.err.println(PREFIX + "COMPLETE\t" + owner + "\t" + completed + "\t" + realms.size());
        } catch (Throwable failure) {
            fail(phase, failure);
            throw failure;
        } finally {
            // Later unrelated Maven loads cannot be misclassified as replay.
            phase = "AFTER";
        }
    }

    /** The driver must require this receipt, even when a goal fails as expected. */
    public static synchronized void sessionEnded() {
        check(!observerFailed && finished && completed == expected.size() && active == null,
            "session ended before exact selected event set and linkage completed");
        System.err.println(PREFIX + "SESSION_COMPLETE\t" + owner + "\t" + completed);
    }
}
