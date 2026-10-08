import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.buildpack.platform.json.SharedJsonMapper;
import org.springframework.boot.buildpack.platform.json.JsonStream;
import org.springframework.boot.buildpack.platform.docker.type.Image;
import tools.jackson.core.JsonParser;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

public class BootPluginJacksonProbe {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    static ByteArrayInputStream input(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        JsonMapper mapper = SharedJsonMapper.get();
        String coreSource = JsonFactory.class.getProtectionDomain().getCodeSource().getLocation().toString();
        String databindSource = JsonMapper.class.getProtectionDomain().getCodeSource().getLocation().toString();
        check(coreSource.endsWith("jackson-core-3.1.7.jar"), "actual plugin classpath Jackson core 3.1.7: " + coreSource);
        check(databindSource.endsWith("jackson-databind-3.1.7.jar"), "actual plugin classpath Jackson databind 3.1.7: " + databindSource);
        String json = mapper.writeValueAsString(Map.of("status", "Pull complete", "path", "a/b"));
        check(mapper.readTree(json).get("status").asString().equals("Pull complete"), "Boot SharedJsonMapper serialize/parse");
        List<String> events = new ArrayList<>();
        new JsonStream(mapper).get(input("{\"status\":\"one\"}\n{\"status\":\"two\"}\n"), node -> events.add(node.get("status").asString()));
        check(events.equals(List.of("one", "two")), "Boot Docker JSON stream preserves multiple events");
        Image image = Image.of(input("{\"RepoDigests\":[],\"Config\":{},\"RootFS\":{\"Layers\":[]},\"Os\":\"linux\",\"Architecture\":\"amd64\"}"));
        check(image.getOs().equals("linux") && image.getArchitecture().equals("amd64") && image.getLayers().isEmpty(), "Boot image metadata parser");
        boolean rejected = false;
        try (JsonParser parser = JsonFactory.builder().build().createParser(tools.jackson.core.ObjectReadContext.empty(), (DataInput) new DataInputStream(input("{\"a\":t" + "x".repeat(32768) + " }")))) {
            while (parser.nextToken() != null) { }
        } catch (tools.jackson.core.JacksonException failure) {
            check(failure.getMessage().length() < 1024, "malformed DataInput token error remains bounded: " + failure.getMessage().length());
            rejected = true;
        }
        check(rejected, "malformed DataInput rejected");
        System.out.println("PROBE_COMPLETE: compatibility and bounded-token regression only; no Docker build or exploitability claim");
    }
}
