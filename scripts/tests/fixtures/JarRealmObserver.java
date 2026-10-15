import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Passive natural observations, then explicitly separate symbolic linkage.
 * No target initialization, method invocation, bytecode changes or replacement realm.
 */
public final class JarRealmObserver {
    private static final Set<ClassLoader> realms = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> definitions = new ConcurrentHashMap<>();
    private static volatile ClassLoader jarRealm;
    private static volatile boolean symbolic;
    private static boolean completed;
    private static Path inventory;

    private static String identity(ClassLoader loader) { return Integer.toHexString(System.identityHashCode(loader)); }
    private static String source(Class<?> type) { return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static boolean relevant(String name) {
        return name.startsWith("org/apache/maven/plugins/jar/")
            || name.startsWith("org/apache/maven/archiver/")
            || name.startsWith("org/codehaus/plexus/archiver/")
            || name.startsWith("org/codehaus/plexus/components/io/")
            || name.startsWith("org/codehaus/plexus/util/")
            || name.startsWith("org/codehaus/plexus/interpolation/")
            || name.startsWith("org/apache/commons/io/")
            || name.startsWith("org/apache/commons/compress/")
            || name.startsWith("org/springframework/boot/maven/")
            || name.startsWith("org/springframework/boot/loader/tools/");
    }

    public static void premain(String path, Instrumentation instrumentation) {
        inventory = Path.of(path);
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] bytes) {
                if (name == null || !relevant(name)) return null;
                try {
                    String identity = identity(loader);
                    String source = domain == null || domain.getCodeSource() == null ? "MISSING"
                        : domain.getCodeSource().getLocation().toExternalForm();
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    definitions.put(identity + "\t" + name, hash);
                    System.err.println((symbolic ? "JAR_SYMBOLIC_LOAD\t" : "JAR_NATURAL\t") + name + "\t" + identity + "\t" + source + "\t" + hash);
                    if (name.equals("org/apache/maven/plugins/jar/JarMojo")) {
                        check(jarRealm == null || jarRealm == loader, "one actual JAR realm"); jarRealm = loader;
                    }
                    if ((name.equals("org/apache/maven/plugins/jar/JarMojo")
                            || name.equals("org/apache/maven/plugins/jar/TestJarMojo")
                            || name.equals("org/springframework/boot/maven/RepackageMojo")) && realms.add(loader)) {
                        if (!(loader instanceof URLClassLoader)) throw new AssertionError("native URL class realm required");
                        for (var url : ((URLClassLoader) loader).getURLs()) {
                            System.err.println("JAR_REALM_URL\t" + name + "\t" + identity + "\t" + url.toExternalForm());
                        }
                    }
                } catch (Throwable failure) {
                    System.err.println("JAR_OBSERVER_FAILURE\t" + failure);
                }
                return null;
            }
        }, false);
    }

    private static Class<?> type(String name, ClassLoader loader) throws Exception {
        Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
        System.err.println("JAR_TYPE\t" + name + "\t" + identity(type.getClassLoader()) + "\t" + source(type));
        return type;
    }

    /** Resolve exact public API symbols after real execution; never invoke target handles. */
    public static synchronized void complete() {
        check(!completed && jarRealm != null, "one post-goal linkage phase");
        completed = true; symbolic = true;
        try {
            check(Runtime.version().feature() == 21 && Files.size(inventory) < 262144, "bounded JDK21 inventory");
            var rows = Files.readAllLines(inventory);
            check(rows.size() <= 512 && new HashSet<>(rows).size() == rows.size(), "bounded unique rows");
            int types = 0, members = 0, consumers = 0;
            var lookup = MethodHandles.publicLookup();
            for (String row : rows) {
                String[] p = row.split("\t", -1);
                check(p.length == 3 || p.length == 4 || p.length == 7, "fixed row shape");
                Class<?> consumer = type(p[1], jarRealm);
                if (p[0].equals("consumer")) {
                    check(Path.of(java.net.URI.create(source(consumer))).equals(Path.of(java.net.URI.create(p[2]))), "consumer origin");
                    check(p[3].equals(definitions.get(identity(consumer.getClassLoader()) + "\t" + p[1])), "effective consumer definition");
                    consumers++; System.err.println("JAR_CONSUMER\t" + row); continue;
                }
                ClassLoader loader = consumer.getClassLoader();
                Class<?> owner = type(p[2], loader);
                if (p[0].equals("type")) { lookup.accessClass(owner); types++; continue; }
                check(p[0].equals("member"), "member row");
                boolean isStatic = p[6].equals("static");
                check(isStatic || p[6].equals("instance"), "known invocation kind");
                if (p[3].equals("field")) {
                    Class<?> fieldType = MethodType.fromMethodDescriptorString("()" + p[5], loader).returnType();
                    if (isStatic) lookup.findStaticGetter(owner, p[4], fieldType);
                    else lookup.findGetter(owner, p[4], fieldType);
                } else {
                    check(p[3].equals("method") || p[3].equals("interfaceMethod"), "known method kind");
                    check(!p[3].equals("interfaceMethod") || owner.isInterface(), "interface owner");
                    MethodType signature = MethodType.fromMethodDescriptorString(p[5], loader);
                    if (p[4].equals("<init>")) { check(!isStatic, "instance constructor"); lookup.findConstructor(owner, signature); }
                    else if (isStatic) lookup.findStatic(owner, p[4], signature);
                    else lookup.findVirtual(owner, p[4], signature);
                }
                members++; System.err.println("JAR_MEMBER\t" + row);
            }
            try {
                lookup.findVirtual(type("org/apache/maven/archiver/MavenArchiver", jarRealm),
                    "missingJarFixtureMethod", MethodType.methodType(void.class));
                throw new AssertionError("missing-member negative unexpectedly linked");
            } catch (NoSuchMethodException expected) { System.err.println("JAR_MISSING_MEMBER\tPASSED"); }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(inventory)));
            System.err.println("JAR_LINKAGE_COMPLETE\t" + consumers + "\t" + types + "\t" + members + "\t" + hash);
        } catch (Throwable failure) {
            System.err.println("JAR_LINKAGE_FAILURE\t" + failure); failure.printStackTrace(System.err);
        } finally { symbolic = false; }
    }
}
