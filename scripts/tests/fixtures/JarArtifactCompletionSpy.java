import org.apache.maven.artifact.Artifact;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;

/** Read the actual project's attachment state at each reviewed JAR terminal event. */
public final class JarArtifactCompletionSpy extends AbstractEventSpy {
    private static String safe(String value) {
        if (value == null) return "-";
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("unsafe fixture observation");
        }
        return value;
    }

    private static void artifact(String execution, String role, Artifact artifact) {
        System.err.println("JAR_ARTIFACT\t" + execution + "\t" + role + "\t"
            + safe(artifact.getType()) + "\t" + safe(artifact.getClassifier()) + "\t"
            + safe(artifact.getFile() == null ? null : artifact.getFile().getAbsolutePath()));
    }

    @Override
    public void onEvent(Object value) throws Exception {
        if (!(value instanceof ExecutionEvent event) || event.getMojoExecution() == null) return;
        var execution = event.getMojoExecution();
        if (!execution.getGroupId().equals("org.apache.maven.plugins")
                || !execution.getArtifactId().equals("maven-jar-plugin")) return;
        String type = event.getType().name();
        if (!(type.equals("MojoSucceeded") || type.equals("MojoFailed"))) return;
        if (!(execution.getVersion().equals("3.5.0") || execution.getVersion().equals("3.5.1"))
                || !(execution.getGoal().equals("jar") || execution.getGoal().equals("test-jar"))) {
            throw new IllegalArgumentException("unreviewed JAR goal");
        }
        String id = safe(execution.getExecutionId());
        System.err.println("JAR_TERMINAL\t" + type + "\t" + execution.getVersion()
            + "\t" + execution.getGoal() + "\t" + id);
        artifact(id, "main", event.getProject().getArtifact());
        for (Artifact attached : event.getProject().getAttachedArtifacts()) artifact(id, "attached", attached);
        if (id.equals(System.getProperty("jar.fixture.lastExecution"))) {
            Class.forName("JarRealmObserver", false, ClassLoader.getSystemClassLoader())
                .getMethod("complete").invoke(null);
        }
    }
}
