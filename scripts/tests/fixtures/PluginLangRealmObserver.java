import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Three separate phases in the unchanged native Maven realm: natural goal,
 * controlled API calls after its terminal event, then symbolic resolution.
 * No bytecode changes, extra realms, or vulnerable stack-overflow experiment.
 */
public final class PluginLangRealmObserver {
    private static volatile ClassLoader realm;
    private static volatile boolean replay;
    private static volatile String phase = "NATURAL";
    private static String owner, mode;
    private static Path work, lang;
    private static Path inventoryFile;
    private static final Map<String, String> defined = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> natural = Collections.synchronizedSet(new TreeSet<>());
    private static final String PREFIX = "PLUGIN_LANG_";

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
            || name.startsWith("org/slf4j/") || name.startsWith("org/apache/commons/lang3/")
            || name.startsWith("org/apache/commons/compress/") || name.startsWith("org/apache/maven/enforcer/")
            || name.startsWith("org/apache/maven/plugins/enforcer/");
    }
    public static void premain(String inventory, Instrumentation instrumentation) {
        inventoryFile = Path.of(inventory);
        owner = System.getProperty("fixture.lang.owner"); mode = System.getProperty("fixture.lang.mode");
        work = Path.of(System.getProperty("fixture.lang.work")); lang = Path.of(System.getProperty("fixture.lang.jar"));
        check(Set.of("assembly", "enforcer").contains(owner) && Set.of("none", "api").contains(mode), "fixed observer scope");
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                try {
                    if (name != null && relevant(name)) defined.put(identity(loader) + "\t" + name, hash(bytes));
                    if (name != null && relevant(name)) {
                        if (!replay) natural.add(name);
                        System.err.println(PREFIX + phase + "_LOAD\t" + name + "\t" + identity(loader) + "\t" + loaderName(loader)
                            + "\t" + source(domain) + "\t" + hash(bytes));
                    }
                    String mojo = owner.equals("assembly") ? "org/apache/maven/plugins/assembly/mojos/SingleAssemblyMojo"
                        : "org/apache/maven/plugins/enforcer/EnforceMojo";
                    if (!replay && mojo.equals(name)) {
                        check(realm == null || realm == loader, "one selected plugin realm"); realm = loader;
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
    public static synchronized void complete(String event, Object project, Object session) {
        check(!replay && Set.of("MojoSucceeded", "MojoFailed").contains(event), "one goal terminal event");
        replay = true;
        System.err.println(PREFIX + "GOAL_TERMINAL\t" + event);
        try {
            phase = "CONTROLLED";
            if (mode.equals("api")) behavior(project, session);
            else System.err.println(PREFIX + "CONTROLLED_COMPLETE\tSKIPPED");
            phase = "SYMBOLIC";
            resolve(inventoryFile);
        } catch (Throwable failure) {
            System.err.println(PREFIX + "FAILURE\t" + phase + "\t" + failure); failure.printStackTrace(System.err);
        }
    }
    private static Class<?> type(String name, ClassLoader loader) throws Exception {
        Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
        String bytes = defined.get(identity(type.getClassLoader()) + "\t" + name);
        check(bytes != null, "observed definition bytes " + name);
        System.err.println(PREFIX + "TYPE\t" + phase + "\t" + name + "\t" + identity(type.getClassLoader()) + "\t"
            + loaderName(type.getClassLoader()) + "\t" + source(type.getProtectionDomain()) + "\t" + bytes);
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
        check(rows.size() <= 64 && new HashSet<>(rows).size() == rows.size(), "bounded distinct inventory rows");
        int members = 0, types = 0; Set<String> consumers = new TreeSet<>();
        for (String row : rows) {
            String[] p = row.split("\t", -1);
            check(p.length == 3 || p.length == 4 || p.length == 7, "exact inventory row shape");
            Class<?> consumer = type(p[1], realm); consumers.add(p[1]);
            if (p[0].equals("consumer")) {
                check(Path.of(java.net.URI.create(source(consumer.getProtectionDomain()))).equals(Path.of(java.net.URI.create(p[2]))), "effective consumer origin " + row);
                check(consumer.getClassLoader() == realm, "private consumer defining loader " + row);
                check(p[3].equals(defined.get(identity(consumer.getClassLoader()) + "\t" + p[1])), "effective consumer definition bytes " + row);
                System.err.println(PREFIX + "CONSUMER\t" + row); continue;
            }
            // An imported consumer resolves its own symbolic references through
            // its actual defining loader, preserving Maven's XML API imports.
            ClassLoader loader = consumer.getClassLoader();
            Class<?> owner = langType(p[2], loader);
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
        try { langType("org/apache/commons/lang3/ClassUtils", realm).getMethod("missingLangFixtureMethod");
            throw new AssertionError("missing-member negative unexpectedly resolved");
        } catch (NoSuchMethodException expected) { System.err.println(PREFIX + "MISSING_MEMBER\tPASSED"); }
        System.err.println(PREFIX + "LINKAGE_COMPLETE\t" + consumers.size() + "\t" + types + "\t" + members + "\t" + hash(Files.readAllBytes(file)));
    }
    private static Class<?> langType(String name, ClassLoader loader) throws Exception {
        Class<?> value = type(name, loader);
        check(value.getClassLoader() == realm && Path.of(value.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(lang),
            "selected private Lang origin " + name);
        return value;
    }
    private static void pass(String label) { System.err.println(PREFIX + "CONTROLLED_PASS\t" + label); }
    private static void resourceFiltering(Object project, Object session) throws Exception {
        // Assembly's real archive path uses MavenReaderFilter. This separate
        // controlled call exercises ResourcesFiltering's Lang-dependent log.
        Class<?> projectType = Class.forName("org.apache.maven.project.MavenProject", false, realm);
        Class<?> sessionType = Class.forName("org.apache.maven.execution.MavenSession", false, realm);
        Class<?> resourceType = Class.forName("org.apache.maven.model.Resource", false, realm);
        check(projectType.isInstance(project) && sessionType.isInstance(session), "actual completed goal project/session");
        check(((java.io.File) projectType.getMethod("getBasedir").invoke(project)).toPath().toRealPath()
            .equals(work.toRealPath()), "controlled resource project boundary");
        Path destination = work.resolve("controlled-resources");
        check(!Files.exists(destination), "fresh controlled resource output");
        Class<?> contextType = type("org/sonatype/plexus/build/incremental/BuildContext", realm);
        Object context = type("org/sonatype/plexus/build/incremental/DefaultBuildContext", realm).getConstructor().newInstance();
        Class<?> fileFilterType = type("org/apache/maven/shared/filtering/MavenFileFilter", realm);
        Object fileFilter = type("org/apache/maven/shared/filtering/DefaultMavenFileFilter", realm)
            .getConstructor(contextType).newInstance(context);
        Class<?> resourcesType = type("org/apache/maven/shared/filtering/DefaultMavenResourcesFiltering", realm);
        check(resourcesType.getClassLoader() == realm, "controlled resources defining loader");
        Object resources = resourcesType.getConstructor(fileFilterType, contextType).newInstance(fileFilter, context);
        Object resource = resourceType.getConstructor().newInstance();
        resourceType.getMethod("setDirectory", String.class).invoke(resource, work.resolve("filter").toString());
        resourceType.getMethod("setTargetPath", String.class).invoke(resource, "nested");
        resourceType.getMethod("setFiltering", boolean.class).invoke(resource, true);
        resourceType.getMethod("setIncludes", List.class).invoke(resource, List.of("value.txt"));
        Class<?> executionType = type("org/apache/maven/shared/filtering/MavenResourcesExecution", realm);
        Object execution = executionType.getConstructor(List.class, java.io.File.class, projectType,
            String.class, List.class, List.class, sessionType).newInstance(List.of(resource), destination.toFile(),
                project, "UTF-8", List.of(), List.of(), session);
        executionType.getMethod("setResourcesBaseDirectory", java.io.File.class).invoke(execution, work.toFile());
        executionType.getMethod("setUseDefaultFilterWrappers", boolean.class).invoke(execution, true);
        System.err.println(PREFIX + "CONTROLLED_RESOURCE_BEGIN\tfilter/value.txt\tcontrolled-resources/nested/value.txt");
        resourcesType.getMethod("filterResources", executionType).invoke(resources, execution);
        byte[] payload = Files.readAllBytes(destination.resolve("nested/value.txt"));
        check(Arrays.equals(payload, "café & <fixture> 非ASCII\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "controlled exact UTF-8 resource interpolation");
        langType("org/apache/commons/lang3/StringUtils", resourcesType.getClassLoader());
        System.err.println(PREFIX + "CONTROLLED_RESOURCE_END\tcontrolled-resources/nested/value.txt\t" + hash(payload));
        pass("DefaultMavenResourcesFiltering filtered UTF-8 copy and StringUtils.isEmpty log branch");
    }
    private static void behavior(Object project, Object session) throws Exception {
        check(realm != null && Runtime.version().feature() == 21, "controlled native JDK21 realm");
        if (owner.equals("assembly")) {
            resourceFiltering(project, session);
            Class<?> filtering = type("org/apache/maven/shared/filtering/FilteringUtils", realm);
            check(filtering.getClassLoader() == realm, "controlled filtering defining loader");
            // A relative name must reach the IS_OS_WINDOWS branch; absolute
            // paths return before that field read and cannot prove this path.
            String relative = "filter/value.txt";
            check(!new java.io.File(relative).isAbsolute(), "relative filtering input");
            Object actual = filtering.getMethod("resolveFile", java.io.File.class, String.class)
                .invoke(null, work.toFile(), relative);
            check(actual instanceof java.io.File && ((java.io.File) actual).getCanonicalFile()
                .equals(work.resolve(relative).toFile().getCanonicalFile()), "FilteringUtils relative path result");
            langType("org/apache/commons/lang3/SystemUtils", filtering.getClassLoader());
            pass("FilteringUtils relative path and SystemUtils.IS_OS_WINDOWS");
        }
        Class<?> classes = langType("org/apache/commons/lang3/ClassUtils", realm);
        Method getClass = classes.getMethod("getClass", ClassLoader.class, String.class, boolean.class);
        for (Object[] entry : new Object[][] {{"int", int.class}, {"int[]", int[].class},
                {"java.lang.String[][]", String[][].class}, {"java.util.Map.Entry", Map.Entry.class}}) {
            check(getClass.invoke(null, realm, entry[0], false) == entry[1], "ordinary class name " + entry[0]);
            pass("ClassUtils " + entry[0]);
        }
        try {
            getClass.invoke(null, realm, null, false); throw new AssertionError("null name accepted");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof NullPointerException, "null name exception"); pass("ClassUtils null");
        }
        for (String name : List.of("fixture.MissingClass", "missing.".repeat(32) + "Absent")) {
            check(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024, "bounded invalid name");
            try {
                getClass.invoke(null, realm, name, false); throw new AssertionError("missing name accepted");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof ClassNotFoundException, "missing name exception");
            }
        }
        pass("ClassUtils missing and bounded invalid names");
        System.err.println(PREFIX + "CONTROLLED_COMPLETE\tPASSED");
    }

}
