import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;

/** Observe the terminal event before Maven disposes its native plugin realm. */
public final class AssemblyRealmCompletionSpy extends AbstractEventSpy {
    @Override
    public void onEvent(Object value) throws Exception {
        if (!(value instanceof ExecutionEvent event) || event.getMojoExecution() == null) return;
        String type = event.getType().name();
        if (!(type.equals("MojoSucceeded") || type.equals("MojoFailed"))) return;
        var execution = event.getMojoExecution();
        if (!execution.getGroupId().equals("org.apache.maven.plugins")
                || !execution.getArtifactId().equals("maven-assembly-plugin")
                || !execution.getVersion().equals("3.7.1") || !execution.getGoal().equals("single")) return;
        Class.forName("AssemblyRealmObserver", false, ClassLoader.getSystemClassLoader())
            .getMethod("complete", String.class).invoke(null, type);
    }
}
