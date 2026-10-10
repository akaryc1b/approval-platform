import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Observe selected real class definitions, without transforming/loading them. */
public final class CleanRealmObserver {
    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain protection, byte[] bytes) {
                if (name != null && (name.startsWith("org/apache/maven/plugins/clean/")
                        || name.startsWith("org/apache/maven/shared/utils/")
                        || name.startsWith("org/apache/commons/io/"))) {
                    String source = protection != null && protection.getCodeSource() != null
                            ? protection.getCodeSource().getLocation().toExternalForm() : "MISSING";
                    String realm = String.valueOf(loader).replace('\t', ' ').replace('\n', ' ');
                    System.err.println("CLEAN_ORIGIN\t" + name + "\t"
                            + Integer.toHexString(System.identityHashCode(loader)) + "\t" + realm + "\t" + source);
                }
                return null;
            }
        }, false);
    }
}
