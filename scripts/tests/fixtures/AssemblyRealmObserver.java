import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Passive natural observations first; explicit symbolic resolution after goal.
 * Never alters bytecode, creates another realm, initializes targets, or invokes
 * target methods. Linkage rows are deliberately separate from natural loads.
 */
public final class AssemblyRealmObserver {
    private static volatile ClassLoader realm;
    private static volatile boolean replay;
    private static Path inventoryFile;
    private static final Map<String, String> defined = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> natural = Collections.synchronizedSet(new TreeSet<>());
    private static final String PREFIX = "ASSEMBLY_";

    private static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static String source(ProtectionDomain domain) {
        return domain == null || domain.getCodeSource() == null ? "MISSING" : domain.getCodeSource().getLocation().toExternalForm();
    }
    private static String identity(ClassLoader loader) { return Integer.toHexString(System.identityHashCode(loader)); }
    private static String loaderName(ClassLoader loader) { return String.valueOf(loader).replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
    private static boolean relevant(String name) {
        return name.startsWith("org/apache/maven/plugins/assembly/") || name.startsWith("org/codehaus/plexus/util/")
            || name.startsWith("org/codehaus/plexus/archiver/") || name.startsWith("org/codehaus/plexus/components/io/")
            || name.startsWith("org/apache/maven/shared/") || name.startsWith("org/sonatype/plexus/")
            || name.startsWith("org/slf4j/");
    }
    public static void premain(String inventory, Instrumentation instrumentation) {
        inventoryFile = Path.of(inventory);
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                try {
                    if (name != null && relevant(name)) defined.put(identity(loader) + "\t" + name, hash(bytes));
                    if (!replay && name != null && relevant(name)) {
                        natural.add(name);
                        System.err.println(PREFIX + "NATURAL\t" + name + "\t" + identity(loader) + "\t" + loaderName(loader)
                            + "\t" + source(domain) + "\t" + hash(bytes));
                    }
                    if (!replay && "org/apache/maven/plugins/assembly/mojos/SingleAssemblyMojo".equals(name)) {
                        check(realm == null || realm == loader, "one Assembly realm"); realm = loader;
                        check(loader instanceof URLClassLoader, "native URL class realm");
                        for (var url : ((URLClassLoader) loader).getURLs())
                            System.err.println(PREFIX + "REALM_URL\t" + identity(loader) + "\t" + url.toExternalForm());
                    }
                } catch (Throwable failure) { System.err.println(PREFIX + "OBSERVER_FAILURE\t" + failure); }
                return null;
            }
        }, false);
    }
    // EventSpy calls the system-loader agent only after the actual goal's
    // terminal event, while its unchanged native realm remains usable.
    public static synchronized void complete(String event) {
        check(!replay && Set.of("MojoSucceeded", "MojoFailed").contains(event), "one goal terminal event");
        replay = true;
        System.err.println(PREFIX + "GOAL_TERMINAL\t" + event);
        try { resolve(inventoryFile); }
        catch (Throwable failure) { System.err.println(PREFIX + "LINKAGE_FAILURE\t" + failure); failure.printStackTrace(System.err); }
    }
    private static Class<?> type(String name, ClassLoader loader) throws Exception {
        Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
        System.err.println(PREFIX + "TYPE\t" + name + "\t" + identity(type.getClassLoader()) + "\t"
            + loaderName(type.getClassLoader()) + "\t" + source(type.getProtectionDomain()));
        return type;
    }
    private static Field field(Class<?> owner, String name, String descriptor) throws Exception {
        for (Field field : owner.getDeclaredFields())
            if (field.getName().equals(name) && field.getType().descriptorString().equals(descriptor)) return field;
        for (Class<?> parent : owner.getInterfaces()) {
            try { return field(parent, name, descriptor); } catch (NoSuchFieldException ignored) { }
        }
        if (owner.getSuperclass() != null) return field(owner.getSuperclass(), name, descriptor);
        throw new NoSuchFieldException(owner.getName() + "." + name + descriptor);
    }
    private static Method method(Class<?> owner, String name, MethodType signature) throws Exception {
        for (Method method : owner.getDeclaredMethods())
            if (method.getName().equals(name) && method.getReturnType() == signature.returnType()
                    && Arrays.equals(method.getParameterTypes(), signature.parameterArray())) return method;
        if (owner.getSuperclass() != null) {
            try { return method(owner.getSuperclass(), name, signature); } catch (NoSuchMethodException ignored) { }
        }
        for (Class<?> parent : owner.getInterfaces()) {
            try { return method(parent, name, signature); } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + signature);
    }
    private static boolean accessible(Class<?> consumer, Class<?> owner, int modifiers) {
        boolean samePackage = consumer.getPackageName().equals(owner.getPackageName()) && consumer.getClassLoader() == owner.getClassLoader();
        return Modifier.isPublic(modifiers) || (Modifier.isPrivate(modifiers) ? consumer.getNestHost() == owner.getNestHost()
            : samePackage || Modifier.isProtected(modifiers) && owner.isAssignableFrom(consumer));
    }
    private static void resolve(Path file) throws Exception {
        check(Runtime.version().feature() == 21 && realm != null && Files.size(file) < 262144, "bounded JDK21 natural realm inventory");
        List<String> rows = Files.readAllLines(file);
        check(rows.size() <= 1000 && new HashSet<>(rows).size() == rows.size(), "bounded distinct inventory rows");
        int members = 0, types = 0; Set<String> consumers = new TreeSet<>();
        for (String row : rows) {
            String[] p = row.split("\t", -1);
            check(p.length == 3 || p.length == 4 || p.length == 7, "exact inventory row shape");
            Class<?> consumer = type(p[1], realm); consumers.add(p[1]);
            if (p[0].equals("consumer")) {
                check(Path.of(java.net.URI.create(source(consumer.getProtectionDomain()))).equals(Path.of(java.net.URI.create(p[2]))), "effective consumer origin " + row);
                check(p[3].equals(defined.get(identity(consumer.getClassLoader()) + "\t" + p[1])), "effective consumer definition bytes " + row);
                System.err.println(PREFIX + "CONSUMER\t" + row); continue;
            }
            // An imported consumer resolves its own symbolic references through
            // its actual defining loader, preserving Maven's XML API imports.
            ClassLoader loader = consumer.getClassLoader();
            Class<?> owner = type(p[2], loader);
            check(accessible(consumer, owner, owner.getModifiers()), "symbolic type access " + row);
            if (p[0].equals("type")) { types++; continue; }
            check(p[0].equals("member"), "member row kind");
            Member member;
            if (p[3].equals("field")) member = field(owner, p[4], p[5]);
            else {
                MethodType signature = MethodType.fromMethodDescriptorString(p[5], loader);
                if (p[4].equals("<init>")) {
                    check(signature.returnType() == void.class, "constructor void descriptor");
                    member = owner.getDeclaredConstructor(signature.parameterArray());
                } else member = method(owner, p[4], signature);
                check(!p[3].equals("interfaceMethod") || owner.isInterface(), "interface invocation owner");
            }
            check(accessible(consumer, member.getDeclaringClass(), member.getModifiers()), "symbolic member access " + row);
            check(Modifier.isStatic(member.getModifiers()) == p[6].equals("static"), "static/instance invocation " + row);
            System.err.println(PREFIX + "MEMBER\t" + row + "\t" + member.getDeclaringClass().getName()); members++;
        }
        // A deliberate absent-member lookup makes missing-member detection
        // observable without another Maven goal or invoking a target method.
        try { type("org/codehaus/plexus/util/FileUtils", realm).getMethod("missingAssemblyFixtureMethod");
            throw new AssertionError("missing-member negative unexpectedly resolved");
        } catch (NoSuchMethodException expected) { System.err.println(PREFIX + "MISSING_MEMBER\tPASSED"); }
        System.err.println(PREFIX + "LINKAGE_COMPLETE\t" + consumers.size() + "\t" + types + "\t" + members + "\t" + hash(Files.readAllBytes(file)));
    }
}
