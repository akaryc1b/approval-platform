import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

/** Explicit observed-artifact replay; never labels forced loads as natural goals. */
public final class CheckstyleLangProbe {
    private static int assertions;
    private static int classCount;
    private static long classBytes;
    private static final List<String> BEHAVIOR = new ArrayList<>();

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
        if (label.startsWith("plugin ") || label.startsWith("Doxia ") || label.startsWith("Velocity ")
                || label.startsWith("Commons Text ") || label.startsWith("Lang ")) BEHAVIOR.add(label);
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

    private static void pool(byte[] bytes, Set<String> references) throws Exception {
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
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    default -> throw new AssertionError("unknown constant pool tag " + tag);
                }
            }
            for (int index = 1; index < count; index++) {
                if (tags[index] != 9 && tags[index] != 10 && tags[index] != 11) continue;
                int[] ref = (int[]) values[index];
                String owner = (String) values[(Integer) values[ref[0]]];
                if (!owner.startsWith("org/apache/commons/lang3/")) continue;
                int[] member = (int[]) values[ref[1]];
                references.add((tags[index] == 9 ? "field" : "method") + "\t" + owner + "\t"
                        + values[member[0]] + "\t" + values[member[1]]);
            }
        }
    }

    private static Set<String> references(Map<String, Path> jars) throws Exception {
        Set<String> result = new TreeSet<>();
        for (var artifact : jars.entrySet()) {
            if (artifact.getKey().contains(":commons-lang3:")) continue;
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
                        pool(bytes, result);
                    }
                }
            }
        }
        return result;
    }

    private static void resolve(Set<String> references, URLClassLoader loader, Path lang) throws Exception {
        Set<String> origins = new TreeSet<>();
        for (String ref : references) {
            String[] parts = ref.split("\t", -1);
            Class<?> owner = Class.forName(parts[1].replace('/', '.'), false, loader);
            require(Modifier.isPublic(owner.getModifiers()), "public symbolic owner " + parts[1]);
            if (origins.add(parts[1])) origin(loader, owner.getName(), lang);
            if (parts[0].equals("field")) {
                var field = owner.getField(parts[2]);
                require(field.getType().descriptorString().equals(parts[3]) && Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers()), "public static field descriptor " + parts[1] + "." + parts[2]);
            } else {
                MethodType signature = MethodType.fromMethodDescriptorString(parts[3], loader);
                if (parts[2].equals("<init>")) {
                    var constructor = owner.getConstructor(signature.parameterArray());
                    require(signature.returnType() == void.class && Modifier.isPublic(constructor.getModifiers()),
                            "public constructor descriptor " + parts[1]);
                    continue;
                }
                Method method = owner.getMethod(parts[2], signature.parameterArray());
                boolean expectedStatic = !(parts[1].equals("org/apache/commons/lang3/Range") && parts[2].equals("contains"))
                        && !(parts[1].equals("org/apache/commons/lang3/time/FastDateFormat") && parts[2].equals("format"));
                require(method.getReturnType() == signature.returnType() && Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers()) == expectedStatic,
                        "public static-instance method descriptor " + parts[1] + "." + parts[2]);
            }
        }
    }

    private static URL[] urls(Iterable<Path> paths) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (Path path : paths) urls.add(path.toUri().toURL());
        return urls.toArray(URL[]::new);
    }

    private static void behavior(URLClassLoader loader, Map<String, Path> jars, Path lang) throws Exception {
        Path plugin = jars.get("org.apache.maven.plugins:maven-checkstyle-plugin:jar:3.6.0");
        Class<?> rules = origin(loader, "org.apache.maven.plugins.checkstyle.RuleUtil", plugin);
        Method parse = rules.getMethod("parseMatchers", String[].class);
        require(((List<?>) parse.invoke(null, (Object) new String[] {})).isEmpty(), "plugin empty matcher list");
        List<?> matchers = (List<?>) parse.invoke(null, (Object) new String[] {null, "", "  ", "UnusedImports"});
        require(matchers.size() == 1, "plugin ignores null and blank matchers");
        Class<?> matcher = origin(loader, "org.apache.maven.plugins.checkstyle.RuleUtil$Matcher", plugin);
        Method match = matcher.getMethod("match", String.class);
        require(Boolean.TRUE.equals(match.invoke(matchers.get(0), "com.puppycrawl.tools.checkstyle.checks.imports.UnusedImportsCheck")),
                "plugin matcher accepts intended check");
        require(Boolean.FALSE.equals(match.invoke(matchers.get(0), "com.puppycrawl.tools.checkstyle.checks.imports.AvoidStarImportCheck")),
                "plugin matcher rejects other check");
        Class<?> html = origin(loader, "org.apache.maven.doxia.util.HtmlTools", jars.get("org.apache.maven.doxia:doxia-core:jar:2.0.0"));
        require("café & tea <x>".equals(html.getMethod("unescapeHTML", String.class, boolean.class)
                .invoke(null, "café &amp; tea &lt;x&gt;", false)), "Doxia HTML entity consumer");
        Path tools = jars.get("org.apache.velocity.tools:velocity-tools-generic:jar:3.1");
        Class<?> escape = origin(loader, "org.apache.velocity.tools.generic.EscapeTool", tools);
        Object tool = escape.getConstructor().newInstance();
        require("&lt;x&gt;&amp;".equals(escape.getMethod("html", Object.class).invoke(tool, "<x>&")), "Velocity Tools HTML consumer");
        require("&lt;x&gt;&amp;".equals(escape.getMethod("xml", Object.class).invoke(tool, "<x>&")), "Velocity Tools XML consumer");
        require("a\\nb".equals(escape.getMethod("java", Object.class).invoke(tool, "a\nb")), "Velocity Tools Java consumer");
        require("caf&eacute; &amp; tea".equals(escape.getMethod("html", Object.class).invoke(tool, "café & tea")), "Velocity Tools HTML Unicode entities");
        require("café &amp; tea".equals(escape.getMethod("xml", Object.class).invoke(tool, "café & tea")), "Velocity Tools XML Unicode preserved");
        require(escape.getMethod("html", Object.class).invoke(tool, new Object[] {null}) == null
                && escape.getMethod("xml", Object.class).invoke(tool, new Object[] {null}) == null, "Velocity Tools null behavior");
        Class<?> conversion = origin(loader, "org.apache.velocity.tools.ConversionUtils", tools);
        require(List.of(1, 2, 3).equals(conversion.getMethod("asList", Object.class).invoke(null, (Object) new int[] {1, 2, 3})),
                "Velocity Tools primitive array conversion");
        Path engineJar = jars.get("org.apache.velocity:velocity-engine-core:jar:2.4");
        Class<?> velocity = origin(loader, "org.apache.velocity.app.VelocityEngine", engineJar);
        Class<?> contextType = origin(loader, "org.apache.velocity.VelocityContext", engineJar);
        Class<?> contextInterface = origin(loader, "org.apache.velocity.context.Context", engineJar);
        Object engine = velocity.getConstructor().newInstance();
        // Default loaders initialize; the fixed reader has no include/parse or
        // resource directives, so this test requests no external template.
        velocity.getMethod("init").invoke(engine);
        Object context = contextType.getConstructor(Map.class).newInstance(new LinkedHashMap<>(Map.of("enabled", true, "name", "café", "values", List.of(1, 2, 3))));
        StringWriter rendered = new StringWriter();
        Object evaluated = velocity.getMethod("evaluate", contextInterface, Writer.class, String.class, Reader.class)
                .invoke(engine, context, rendered, "bounded-checkstyle-fixture", new StringReader("#if($enabled)$name:#foreach($n in $values)$n#end#end"));
        require(Boolean.TRUE.equals(evaluated) && "café:123".equals(rendered.toString()), "Velocity engine local conditional and loop");
        Path textJar = jars.get("org.apache.commons:commons-text:jar:1.12.0");
        Class<?> factory = origin(loader, "org.apache.commons.text.lookup.StringLookupFactory", textJar);
        Object lookup = factory.getMethod("constantStringLookup").invoke(factory.getField("INSTANCE").get(null));
        Class<?> lookupInterface = origin(loader, "org.apache.commons.text.lookup.StringLookup", textJar);
        Method lookupMethod = lookupInterface.getMethod("lookup", String.class);
        require("2147483647".equals(lookupMethod.invoke(lookup, "java.lang.Integer.MAX_VALUE")), "Commons Text constant ClassUtils consumer");
        require(lookupMethod.invoke(lookup, "java.lang.Integer.NO_SUCH_FIXTURE_FIELD") == null, "Commons Text missing constant");
        Class<?> classes = origin(loader, "org.apache.commons.lang3.ClassUtils", lang);
        Method getClass = classes.getMethod("getClass", ClassLoader.class, String.class, boolean.class);
        for (Object[] entry : new Object[][] {{"int", int.class}, {"int[]", int[].class},
                {"java.lang.String[][]", String[][].class}, {"java.util.Map.Entry", Map.Entry.class}}) {
            require(getClass.invoke(null, loader, entry[0], false) == entry[1], "Lang ordinary name " + entry[0]);
        }
        try {
            getClass.invoke(null, loader, null, false);
            throw new AssertionError("null class name accepted");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof NullPointerException, "Lang null name exception");
        }
        try {
            getClass.invoke(null, loader, "missing.".repeat(32) + "Absent", false);
            throw new AssertionError("missing class accepted");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof ClassNotFoundException, "Lang finite malformed name exception");
        }
        Class<?> strings = origin(loader, "org.apache.commons.lang3.StringUtils", lang);
        Method replace = strings.getMethod("replace", String.class, String.class, String.class);
        require("café-path".equals(replace.invoke(null, "café path", " ", "-")), "Lang Unicode replacement");
        require(replace.invoke(null, null, "a", "b") == null, "Lang null replacement");
        require(Boolean.TRUE.equals(strings.getMethod("equals", CharSequence.class, CharSequence.class)
                .invoke(null, new Object[] {null, null})), "Lang null equality");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2 && Set.of("3.17.0", "3.18.0").contains(args[1]), "manifest and exact Lang variant");
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
        Path lang = jars.get("org.apache.commons:commons-lang3:jar:" + args[1]);
        require(lang != null && jars.keySet().stream().filter(k -> k.contains(":commons-lang3:")).count() == 1, "only selected Lang variant");
        Set<String> members = references(jars);
        String referencesHash = sha256((String.join("\n", members) + "\n").getBytes(StandardCharsets.UTF_8));
        require(members.size() == 58 && referencesHash.equals("d205262d8477778809dc6c3b8eea2a0edb047ca26223ae71fd19aa0677fe0d21"),
                "exact 58 expected external member identities");
        // Preserve observed private URL order. The explicit logging parent and
        // URLClassLoader do not reproduce the full Maven ClassRealm container.
        try (URLClassLoader parent = new URLClassLoader(urls(imports.values()), ClassLoader.getPlatformClassLoader());
             URLClassLoader loader = new URLClassLoader(urls(jars.values()), parent)) {
            resolve(members, loader, lang);
            behavior(loader, jars, lang);
        }
        require(BEHAVIOR.size() >= 20, "all bounded consumer behaviors completed");
        String behaviorHash = sha256((String.join("\n", BEHAVIOR) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("CHECKSTYLE_API_COMPLETE {\"status\":\"PASSED\",\"langMemberReferences\":" + members.size()
            + ",\"referenceSetSha256\":\"" + referencesHash + "\",\"classFilesInspected\":" + classCount
            + ",\"assertions\":" + assertions + ",\"behaviorAssertionCount\":" + BEHAVIOR.size()
            + ",\"behaviorContractSha256\":\"" + behaviorHash + "\",\"evidenceKind\":\"observed-artifact URLClassLoader replay\""
            + ",\"naturalGoalReachabilityClaim\":false}");
    }
}
