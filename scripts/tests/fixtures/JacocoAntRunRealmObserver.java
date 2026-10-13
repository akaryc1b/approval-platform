import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Passive class-definition and existing-loader metadata; never forces a target load. */
public final class JacocoAntRunRealmObserver {
    private static final Set<ClassLoader> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());

    private static synchronized boolean first(ClassLoader loader) {
        return SEEN.add(loader);
    }

    private static boolean relevant(String name) {
        return name.startsWith("org/jacoco/maven/")
                || name.startsWith("org/apache/maven/plugins/antrun/")
                || name.startsWith("org/apache/maven/ant/tasks/")
                || name.startsWith("org/apache/maven/shared/model/fileset/")
                || name.startsWith("org/codehaus/plexus/util/");
    }

    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] bytes) {
                if (name != null && relevant(name)) {
                    String source = domain != null && domain.getCodeSource() != null
                            ? domain.getCodeSource().getLocation().toExternalForm() : "MISSING";
                    String identity = Integer.toHexString(System.identityHashCode(loader));
                    String realm = String.valueOf(loader).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
                    System.err.println("JAC_ANTRUN_ORIGIN\t" + name + "\t" + identity + "\t" + realm + "\t" + source);
                    boolean mojo = (name.startsWith("org/jacoco/maven/") && name.endsWith("Mojo"))
                            || name.equals("org/apache/maven/plugins/antrun/AntRunMojo");
                    if (mojo && loader instanceof URLClassLoader urls && first(loader)) {
                        for (var url : urls.getURLs()) {
                            System.err.println("JAC_ANTRUN_REALM_URL\t" + identity + "\t" + url.toExternalForm());
                        }
                    }
                }
                return null;
            }
        }, false);
    }
}
