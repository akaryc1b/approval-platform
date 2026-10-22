import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.project.MavenProject;

/** DRAFT: statically reviewed only, not compiled or executed.
 * Read MavenProject before and after each selected actual goal. No setter,
 * additional goal, independent Maven process, or target API replay here.
 */
public final class ResourcesFlattenCompletionSpy extends AbstractEventSpy {
    private static Class<?> observer() throws Exception {
        return Class.forName("ResourcesFlattenRealmObserver", false, ClassLoader.getSystemClassLoader());
    }
    private static String safe(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\t') >= 0 || value.indexOf('\r') >= 0
            || value.indexOf('\n') >= 0) throw new IllegalArgumentException("unsafe event identity");
        return value;
    }
    private static String quoted(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') result.append('\\').append(ch);
            else if (ch < 32) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
        }
        return result.append('"').toString();
    }
    private static String object(Map<String, String> values) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (!first) result.append(',');
            first = false;
            result.append(quoted(value.getKey())).append(':').append(quoted(value.getValue()));
        }
        return result.append('}').toString();
    }
    private static String state(MavenProject project) {
        Model original = project.getOriginalModel();
        Map<String, String> fields = new TreeMap<>();
        fields.put("projectIdentity", Integer.toHexString(System.identityHashCode(project)));
        fields.put("projectId", project.getId());
        fields.put("projectFile", project.getFile() == null ? null : project.getFile().getAbsolutePath());
        fields.put("projectBasedir", project.getBasedir() == null ? null : project.getBasedir().getAbsolutePath());
        fields.put("originalModelIdentity", original == null ? null : Integer.toHexString(System.identityHashCode(original)));
        fields.put("originalGroupId", original == null ? null : original.getGroupId());
        fields.put("originalArtifactId", original == null ? null : original.getArtifactId());
        fields.put("originalVersion", original == null ? null : original.getVersion());
        fields.put("originalPackaging", original == null ? null : original.getPackaging());
        fields.put("originalModelEncoding", original == null ? null : original.getModelEncoding());
        Parent parent = original == null ? null : original.getParent();
        fields.put("originalParentGroupId", parent == null ? null : parent.getGroupId());
        fields.put("originalParentArtifactId", parent == null ? null : parent.getArtifactId());
        fields.put("originalParentVersion", parent == null ? null : parent.getVersion());
        fields.put("originalParentRelativePath", parent == null ? null : parent.getRelativePath());
        Map<String, String> properties = new TreeMap<>();
        if (original != null) {
            for (String name : original.getProperties().stringPropertyNames()) {
                properties.put(name, original.getProperties().getProperty(name));
            }
        }
        String json = "{\"project\":" + object(fields) + ",\"originalProperties\":" + object(properties) + "}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
    @Override
    public void onEvent(Object value) throws Exception {
        try {
            selectedEvent(value);
        } catch (Throwable failure) {
            // Maven can swallow EventSpy exceptions. Preserve a fatal receipt first.
            System.err.println("RF_FAILURE\tspy-bridge\t" + failure.getClass().getName());
            try {
                observer().getMethod("bridgeFailure", String.class, Throwable.class)
                    .invoke(null, "spy-bridge", failure);
            } catch (Throwable bridgeFailure) {
                failure.addSuppressed(bridgeFailure);
            }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new RuntimeException(failure);
        }
    }
    private void selectedEvent(Object value) throws Exception {
        if (!(value instanceof ExecutionEvent event)) return;
        String type = event.getType().name();
        if (type.equals("SessionEnded")) {
            observer().getMethod("sessionEnded").invoke(null);
            return;
        }
        if (event.getMojoExecution() == null) return;
        var execution = event.getMojoExecution();
        String selected = System.getProperty("rf.owner");
        boolean resources = "resources".equals(selected);
        if (!execution.getGroupId().equals(resources ? "org.apache.maven.plugins" : "org.codehaus.mojo")
            || !execution.getArtifactId().equals(resources ? "maven-resources-plugin" : "flatten-maven-plugin")) return;
        if (!Set.of("MojoStarted", "MojoSucceeded", "MojoFailed", "MojoSkipped").contains(type)) return;
        if (type.equals("MojoSkipped")) throw new IllegalStateException("selected goal skipped without reviewed terminal");
        if (!execution.getVersion().equals(resources ? "3.4.0" : "1.7.3")) {
            throw new IllegalArgumentException("unreviewed selected plugin version");
        }
        MavenProject project = event.getProject();
        Path work = Path.of(System.getProperty("rf.work")).toRealPath();
        Path basedir = project.getBasedir().toPath().toRealPath();
        if (!basedir.startsWith(work)) throw new IllegalArgumentException("selected project outside work directory");
        String projectKey = work.equals(basedir) ? "." : work.relativize(basedir).toString().replace('\\', '/');
        var descriptor = execution.getMojoDescriptor();
        if (descriptor == null || descriptor.getPluginDescriptor() == null) {
            throw new IllegalStateException("actual plugin descriptor unavailable");
        }
        ClassLoader realm = descriptor.getPluginDescriptor().getClassRealm();
        // Maven emits Started before it initializes this native plugin realm.
        // Capture the actual project state now, bind the native realm at terminal.
        if (realm == null && !type.equals("MojoStarted")) {
            throw new IllegalStateException("actual plugin terminal realm unavailable");
        }
        String outputPath = null;
        String outputBase64 = null;
        if (!resources && execution.getGoal().equals("flatten") && type.equals("MojoSucceeded")) {
            // Current reviewed fixtures require updatePomFile=true. The driver
            // independently checks this observed path against its expected output.
            Path file = project.getFile().toPath().toAbsolutePath();
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(work) || Files.size(file) > 262144) {
                throw new IllegalArgumentException("flattened output outside bounded fixture");
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > 262144) throw new IllegalArgumentException("flattened output grew beyond limit");
            outputPath = file.toString();
            outputBase64 = Base64.getEncoder().encodeToString(bytes);
        }
        observer().getMethod("event", String.class, String.class, String.class, String.class,
            String.class, String.class, String.class, ClassLoader.class, String.class,
            String.class, String.class)
            .invoke(null, type, safe(selected), safe(execution.getVersion()), safe(execution.getGoal()),
                safe(execution.getExecutionId()), safe(projectKey), state(project), realm,
                safe(descriptor.getImplementation()), outputPath, outputBase64);
    }
}
