import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;

/** Complete only the selected real goal, preserving its native class realm. */
public final class PluginLangRealmCompletionSpy extends AbstractEventSpy {
    @Override
    public void onEvent(Object value) throws Exception {
        if (!(value instanceof ExecutionEvent event) || event.getMojoExecution() == null) return;
        String type = event.getType().name();
        if (!(type.equals("MojoSucceeded") || type.equals("MojoFailed"))) return;
        var execution = event.getMojoExecution();
        if (!execution.getGroupId().equals("org.apache.maven.plugins")) return;
        boolean assembly = System.getProperty("fixture.lang.owner").equals("assembly");
        if (!execution.getArtifactId().equals(assembly ? "maven-assembly-plugin" : "maven-enforcer-plugin")
                || !execution.getVersion().equals(assembly ? "3.7.1" : "3.5.0")
                || !execution.getGoal().equals(assembly ? "single" : "enforce")
                || !assembly && !execution.getExecutionId().equals("enforce-toolchain")) return;
        Class.forName("PluginLangRealmObserver", false, ClassLoader.getSystemClassLoader())
            .getMethod("complete", String.class, Object.class, Object.class)
            .invoke(null, type, event.getProject(), event.getSession());
    }
}
