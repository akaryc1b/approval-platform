import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

/** Actual Checkstyle consumers replayed with the observed, byte-verified realm JARs. */
public final class CheckstyleGuavaProbe {
    private static final String ENGINE = "com.puppycrawl.tools:checkstyle:jar:9.3";
    private static final String CONSUMER = "com/puppycrawl/tools/checkstyle/utils/ModuleReflectionUtil";
    private static final String REFERENCES = "e2c5e81005d7620b6fa48d4157f9e7413b05f9f8e07176c7e461ac9af2d2b833";
    private static final List<String> BEHAVIOR = new ArrayList<>();
    private static final Set<String> USES = new TreeSet<>();
    private static int assertions;
    private static int classCount;
    private static long classBytes;

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
        if (label.startsWith("consumer ")) BEHAVIOR.add(label);
        System.out.println("PASS " + label);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Class<?> origin(URLClassLoader loader, String name, Path expected) throws Exception {
        Class<?> type = Class.forName(name, false, loader);
        require(type.getClassLoader() == loader && expected != null
                && Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),
                "origin " + name);
        return type;
    }

    private static int u2(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 8 | bytes[offset + 1] & 255;
    }

    private static int i4(byte[] bytes, int offset) {
        return u2(bytes, offset) << 16 | u2(bytes, offset + 2);
    }

    private static void use(int index, String invocation, Map<Integer, String> targets) {
        if (targets.containsKey(index)) USES.add(invocation + "\t" + targets.get(index));
    }

    private static void code(byte[] bytes, Map<Integer, String> targets) {
        for (int pos = 0; pos < bytes.length;) {
            int opcode = bytes[pos] & 255;
            if (opcode >= 178 && opcode <= 185) {
                String kind = switch (opcode) {
                    case 182 -> "virtual";
                    case 184 -> "static";
                    default -> "unsupported-" + opcode;
                };
                use(u2(bytes, pos + 1), kind, targets);
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
            if (opcode > 201 || size < 1 || pos + size > bytes.length) throw new AssertionError("invalid bytecode instruction");
            pos += size;
        }
    }

    private static void attributes(DataInputStream in, Object[] pool, Map<Integer, String> targets,
            boolean method) throws Exception {
        int count = in.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            String name = (String) pool[in.readUnsignedShort()];
            int size = in.readInt();
            if (size < 0 || size > 2 * 1024 * 1024) throw new AssertionError("attribute budget");
            byte[] bytes = in.readNBytes(size);
            if (bytes.length != size) throw new AssertionError("truncated attribute");
            if (method && name.equals("Code")) {
                try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    body.readUnsignedShort(); body.readUnsignedShort();
                    int length = body.readInt();
                    if (length < 0 || length > 65535) throw new AssertionError("bytecode budget");
                    byte[] instructions = body.readNBytes(length);
                    if (instructions.length != length) throw new AssertionError("truncated bytecode");
                    code(instructions, targets);
                }
            }
        }
    }

    private static void pool(byte[] bytes, Set<String> targetClasses, Set<String> references,
            String artifact, String className) throws Exception {
        classCount++; classBytes += bytes.length;
        if (classCount > 30000 || classBytes > 256L * 1024 * 1024) throw new AssertionError("class inspection budget");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xcafebabe) throw new AssertionError("class magic");
            in.readUnsignedShort(); in.readUnsignedShort();
            int count = in.readUnsignedShort();
            Object[] values = new Object[count]; int[] tags = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = in.readUnsignedByte(); tags[index] = tag;
                switch (tag) {
                    case 1 -> values[index] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); index++; }
                    case 7, 8, 16, 19, 20 -> values[index] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> values[index] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                    case 15 -> values[index] = new int[] {in.readUnsignedByte(), in.readUnsignedShort()};
                    default -> throw new AssertionError("unknown constant pool tag " + tag);
                }
            }
            Map<Integer, String> targets = new LinkedHashMap<>();
            for (int index = 1; index < count; index++) {
                if (tags[index] != 9 && tags[index] != 10 && tags[index] != 11) continue;
                int[] ref = (int[]) values[index];
                String owner = (String) values[(Integer) values[ref[0]]];
                // failureaccess owns its internal package. The selected Guava JAR's
                // actual class inventory defines this transition's member boundary.
                if (!targetClasses.contains(owner)) continue;
                if (!artifact.equals(ENGINE) || !className.equals(CONSUMER)) {
                    throw new AssertionError("unexpected external Guava consumer " + artifact + "!" + className);
                }
                int[] member = (int[]) values[ref[1]];
                String reference = (tags[index] == 9 ? "field" : "method") + "\t" + owner + "\t"
                        + values[member[0]] + "\t" + values[member[1]];
                references.add(reference); targets.put(index, reference);
            }
            if (targets.isEmpty()) return;
            // The ClassInfo::load method reference is a CONSTANT_MethodHandle.
            // Direct calls are read from Code instructions, not inferred from names.
            for (int index = 1; index < count; index++) {
                if (tags[index] == 15) {
                    int[] handle = (int[]) values[index];
                    use(handle[1], handle[0] == 5 ? "virtual" : "unsupported-handle-" + handle[0], targets);
                }
            }
            in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedShort();
            for (int countInterfaces = in.readUnsignedShort(); countInterfaces > 0; countInterfaces--) in.readUnsignedShort();
            for (boolean method : new boolean[] {false, true}) {
                for (int members = in.readUnsignedShort(); members > 0; members--) {
                    in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedShort();
                    attributes(in, values, targets, method);
                }
            }
        }
    }

    private static Set<String> references(Map<String, Path> jars, Path guava) throws Exception {
        Set<String> targets = new TreeSet<>(), result = new TreeSet<>();
        try (ZipFile zip = new ZipFile(guava.toFile())) {
            if (zip.size() > 40000) throw new AssertionError("target JAR entry budget");
            zip.stream().filter(e -> e.getName().endsWith(".class"))
                    .forEach(e -> targets.add(e.getName().substring(0, e.getName().length() - 6)));
        }
        require(targets.size() > 1000, "actual selected Guava class inventory");
        for (var artifact : jars.entrySet()) {
            if (artifact.getValue().equals(guava)) continue;
            try (ZipFile zip = new ZipFile(artifact.getValue().toFile())) {
                if (zip.size() > 40000) throw new AssertionError("JAR entry budget");
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")) continue;
                    if (entry.getSize() < 0 || entry.getSize() > 2 * 1024 * 1024) throw new AssertionError("class size budget");
                    try (var input = zip.getInputStream(entry)) {
                        byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                        if (bytes.length != entry.getSize()) throw new AssertionError("class byte count");
                        pool(bytes, targets, result, artifact.getKey(), entry.getName().replaceFirst("\\.class$", ""));
                    }
                }
            }
        }
        return result;
    }

    private static void resolve(Set<String> references, URLClassLoader loader, Path guava) throws Exception {
        Set<String> expectedUses = new TreeSet<>();
        for (String reference : references) {
            String[] fields = reference.split("\t", -1);
            require(fields[0].equals("method"), "only exact external Guava methods");
            Class<?> owner = origin(loader, fields[1].replace('/', '.'), guava);
            MethodType signature = MethodType.fromMethodDescriptorString(fields[3], loader);
            var method = owner.getMethod(fields[2], signature.parameterArray());
            boolean isStatic = fields[1].equals("com/google/common/reflect/ClassPath") && fields[2].equals("from");
            require(Modifier.isPublic(owner.getModifiers()) && Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers()) == isStatic && method.getReturnType() == signature.returnType(),
                    "public exact static-instance descriptor " + fields[1] + "." + fields[2]);
            // ImmutableSet.stream is inherited from java.util.Collection. Validate
            // its symbolic Guava owner and exact inherited method descriptor.
            expectedUses.add((isStatic ? "static" : "virtual") + "\t" + reference);
        }
        require(USES.equals(expectedUses), "exact actual bytecode and method-handle invocation kinds");
    }

    private static URL[] urls(Iterable<Path> paths) throws Exception {
        List<URL> result = new ArrayList<>();
        for (Path path : paths) result.add(path.toUri().toURL());
        return result.toArray(URL[]::new);
    }

    private static Set<String> names(Object result, URLClassLoader loader, Path engine) throws Exception {
        require(result instanceof Set<?>, "discovery returns set");
        Set<String> names = new TreeSet<>();
        for (Object value : (Set<?>) result) {
            require(value instanceof Class<?>, "discovery returns classes");
            Class<?> type = (Class<?>) value;
            require(origin(loader, type.getName(), engine) == type, "discovered class ownership");
            names.add(type.getName());
        }
        return names;
    }

    private static void behavior(URLClassLoader loader, Map<String, Path> jars) throws Exception {
        Path engine = jars.get(ENGINE);
        Class<?> utility = origin(loader, CONSUMER.replace('/', '.'), engine);
        var discover = utility.getMethod("getCheckstyleModules", Collection.class, ClassLoader.class);
        String packageName = "com.puppycrawl.tools.checkstyle.checks.imports";
        Set<String> expected = new TreeSet<>();
        for (String name : List.of("AvoidStarImportCheck", "AvoidStaticImportCheck", "CustomImportOrderCheck",
                "IllegalImportCheck", "ImportControlCheck", "ImportOrderCheck", "RedundantImportCheck", "UnusedImportsCheck")) {
            expected.add(packageName + "." + name);
        }
        require(names(discover.invoke(null, List.of(packageName), loader), loader, engine).equals(expected),
                "consumer exact eight import modules");
        require(names(discover.invoke(null, List.of(packageName, packageName), loader), loader, engine).equals(expected),
                "consumer duplicate packages deduplicated");
        require(names(discover.invoke(null, List.of(), loader), loader, engine).isEmpty(), "consumer empty packages");
        require(names(discover.invoke(null, List.of("missing.bounded.fixture"), loader), loader, engine).isEmpty(),
                "consumer missing package");
        require(names(discover.invoke(null, List.of("com.puppycrawl.tools.checkstyle.checks"), loader), loader, engine)
                .stream().noneMatch(expected::contains), "consumer package scan is nonrecursive");
        Set<String> combined = new TreeSet<>(expected);
        combined.add("com.puppycrawl.tools.checkstyle.filefilters.BeforeExecutionExclusionFileFilter");
        require(names(discover.invoke(null, List.of(packageName, "com.puppycrawl.tools.checkstyle.filefilters"), loader),
                loader, engine).equals(combined), "consumer exact multiple-package inventory");
        var isModule = utility.getMethod("isCheckstyleModule", Class.class);
        require(Boolean.FALSE.equals(isModule.invoke(null, String.class)), "consumer excludes ordinary classes");
        require(Boolean.FALSE.equals(isModule.invoke(null,
                origin(loader, "com.puppycrawl.tools.checkstyle.api.AbstractCheck", engine))), "consumer excludes abstract checks");
        Class<?> factory = origin(loader, "com.puppycrawl.tools.checkstyle.PackageObjectFactory", engine);
        Object instance = factory.getConstructor(Set.class, ClassLoader.class).newInstance(new java.util.LinkedHashSet<>(Set.of(packageName)), loader);
        var create = factory.getMethod("createModule", String.class);
        for (String name : List.of("UnusedImports", "UnusedImportsCheck", packageName + ".UnusedImportsCheck")) {
            Object module = create.invoke(instance, name);
            require(module.getClass() == origin(loader, packageName + ".UnusedImportsCheck", engine),
                    "consumer factory expected module " + name);
        }
        // Missing short names force the real third-party discovery fallback;
        // success above covers the standard-name path separately.
        for (String name : List.of("NoSuchBoundedGuavaFixture", "NoSuchBoundedGuavaFixture")) {
            try {
                create.invoke(instance, name);
                throw new AssertionError("missing module accepted");
            } catch (InvocationTargetException expectedFailure) {
                Throwable cause = expectedFailure.getCause();
                require(cause.getClass().getName().equals("com.puppycrawl.tools.checkstyle.api.CheckstyleException")
                        && cause.getMessage().contains(name), "consumer missing short module deterministic failure");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2 && Set.of("31.0.1-jre", "32.0.1-jre").contains(args[1]), "manifest and exact Guava variant");
        Path manifest = Path.of(args[0]).toRealPath();
        require(Files.size(manifest) < 65536, "bounded manifest");
        Map<String, Path> jars = new LinkedHashMap<>(), imports = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest)) {
            String[] fields = line.split("\t", -1);
            require(fields.length == 4 && fields[2].matches("[0-9a-f]{64}"), "valid artifact row");
            require(fields[0].equals("private") || fields[0].equals("maven-import"), "explicit artifact role");
            Path file = Path.of(fields[3]).toRealPath();
            require(Files.size(file) <= 32 * 1024 * 1024, "bounded JAR " + file.getFileName());
            require(sha256(Files.readAllBytes(file)).equals(fields[2]), "actual observed bytes " + file.getFileName());
            require((fields[0].equals("private") ? jars : imports).put(fields[1], file) == null, "unique coordinate " + fields[1]);
        }
        require(jars.size() == 54 && imports.size() == 1
                && imports.containsKey("org.slf4j:slf4j-api:jar:1.7.36"), "54 observed private artifacts and one core logging import");
        Path guava = jars.get("com.google.guava:guava:jar:" + args[1]);
        require(guava != null && jars.keySet().stream().filter(k -> k.startsWith("com.google.guava:guava:jar:")).count() == 1,
                "only selected Guava variant");
        require(jars.containsKey("org.apache.commons:commons-lang3:jar:3.18.0"), "retained Lang 3.18.0");
        Set<String> members = references(jars, guava);
        String referenceHash = sha256((String.join("\n", members) + "\n").getBytes(StandardCharsets.UTF_8));
        require(members.size() == 4 && referenceHash.equals(REFERENCES), "exact four expected external Guava member identities");
        try (URLClassLoader parent = new URLClassLoader(urls(imports.values()), ClassLoader.getPlatformClassLoader());
             URLClassLoader loader = new URLClassLoader(urls(jars.values()), parent)) {
            resolve(members, loader, guava);
            behavior(loader, jars);
        }
        require(BEHAVIOR.size() == 13, "all bounded Checkstyle discovery behaviors completed");
        String behaviorHash = sha256((String.join("\n", BEHAVIOR) + "\n").getBytes(StandardCharsets.UTF_8));
        String usesHash = sha256((String.join("\n", USES) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("CHECKSTYLE_API_COMPLETE {\"status\":\"PASSED\",\"guavaMemberReferences\":" + members.size()
                + ",\"referenceSetSha256\":\"" + referenceHash + "\",\"classFilesInspected\":" + classCount
                + ",\"invocationKindsSha256\":\"" + usesHash + "\",\"assertions\":" + assertions
                + ",\"behaviorAssertionCount\":" + BEHAVIOR.size() + ",\"behaviorContractSha256\":\"" + behaviorHash
                + "\",\"evidenceKind\":\"observed-artifact URLClassLoader Checkstyle consumer replay\""
                + ",\"naturalGoalReachabilityClaim\":false,\"successfulCustomModuleNaturalGoalClaim\":false}");
    }
}
