import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Passive class-definition and existing-loader metadata; no forced target loads. */
public final class CheckstyleRealmObserver {
    private static final Set<ClassLoader> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());

    private static synchronized boolean first(ClassLoader loader) {
        return SEEN.add(loader);
    }

    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] bytes) {
                if (name != null && (name.startsWith("org/apache/maven/plugins/checkstyle/")
                        || name.startsWith("com/puppycrawl/tools/checkstyle/")
                        || name.startsWith("org/apache/commons/lang3/")
                        || name.startsWith("org/apache/commons/text/")
                        || name.startsWith("org/apache/maven/doxia/")
                        || name.startsWith("org/apache/velocity/") || name.startsWith("org/slf4j/"))) {
                    String source = domain != null && domain.getCodeSource() != null
                            ? domain.getCodeSource().getLocation().toExternalForm() : "MISSING";
                    String identity = Integer.toHexString(System.identityHashCode(loader));
                    String realm = String.valueOf(loader).replace('\t', ' ').replace('\n', ' ');
                    System.err.println("CHECKSTYLE_ORIGIN\t" + name + "\t" + identity + "\t" + realm + "\t" + source);
                    if ((name.equals("org/apache/maven/plugins/checkstyle/CheckstyleViolationCheckMojo")
                            || name.equals("org/apache/maven/plugins/checkstyle/CheckstyleReport"))
                            && loader instanceof URLClassLoader urls && first(loader)) {
                        for (var url : urls.getURLs()) {
                            System.err.println("CHECKSTYLE_REALM_URL\t" + identity + "\t" + url.toExternalForm());
                        }
                    }
                }
                return null;
            }
        }, false);
    }
}
