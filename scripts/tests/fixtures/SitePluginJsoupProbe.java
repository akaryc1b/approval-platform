import java.nio.file.Path;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

/** Bounded compatibility checks against only the resolved Site plugin classpath. */
public class SitePluginJsoupProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) throws Exception {
        Path origin = Path.of(Jsoup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        check(origin.toRealPath().equals(Path.of(args[0]).toRealPath()),
                "Site realm jsoup origin is the resolved 1.23.2 JAR: " + origin);
        Document html = Jsoup.parse("<h1>Site fixture</h1><div data-marker='raw-html'>"
                + "<strong>raw &amp; bounded</strong><a href='nested.html'>nested</a></div>");
        check(html.select("div[data-marker=raw-html] strong").text().equals("raw & bounded"),
                "raw HTML and entity parsing");
        check(html.select("a[href=nested.html]").size() == 1, "relative links remain relative");
        check(Jsoup.clean("<p>safe<script>bad()</script><strong>bold</strong></p>", Safelist.basic())
                .equals("<p>safe<strong>bold</strong></p>"), "bounded cleaner compatibility");
        check(Jsoup.parse("<p>one<b>two<p>three").text().equals("onetwo three"),
                "malformed HTML recovery");
        int depth = 2048;
        String xml = "<node>".repeat(depth) + "<leaf marker='bounded'>nested-value</leaf>"
                + "</node>".repeat(depth);
        check(xml.length() < 32768, "nested XML input remains below 32 KiB");
        Document nested = Jsoup.parse(xml, "", Parser.xmlParser());
        check(nested.select("leaf[marker=bounded]").text().equals("nested-value"),
                "bounded nested XML parse and traversal");
        check(nested.clone().outerHtml().contains("nested-value"), "nested XML clone and serialization");
        StringBuilder namespaces = new StringBuilder("<root xmlns:n='urn:root'>");
        int namespaceDepth = 256;
        for (int index = 0; index < namespaceDepth; index++) {
            namespaces.append("<n:node xmlns:n='urn:level-").append(index)
                    .append("' xmlns:p").append(index).append("='urn:prefix-").append(index).append("'>");
        }
        namespaces.append("<n:leaf id='deep'/><p0:leaf id='inherited'/>");
        namespaces.append("</n:node>".repeat(namespaceDepth));
        namespaces.append("<n:leaf id='rebound'/></root>");
        check(namespaces.length() < 32768, "namespace-heavy XML remains below 32 KiB");
        Document namespaced = Jsoup.parse(namespaces.toString(), "", Parser.xmlParser());
        check(namespaced.getElementById("deep").tag().namespace().equals("urn:level-255"),
                "nested namespace shadowing is preserved");
        check(namespaced.getElementById("inherited").tag().namespace().equals("urn:prefix-0"),
                "outer namespace binding survives 256 nested declarations");
        check(namespaced.getElementById("rebound").tag().namespace().equals("urn:root"),
                "namespace binding rebounds after nested elements close");
        check(namespaced.clone().getElementById("deep").tag().namespace().equals("urn:level-255"),
                "namespace identity survives clone");
        System.out.println("PROBE_COMPLETE: local bounded compatibility only; no exploitability or scanner claim");
    }
}
