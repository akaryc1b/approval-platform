import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Bounded compatibility probe for the current candidate's observed realm.
 * Arguments: IO version, new disposable directory, the three verified real
 * Clean-realm JAR paths, in any order. Use -Xmx96m and a 30-second timeout.
 * This recreates a classpath from an observed realm; it does not claim the
 * vulnerable APIs were invoked by the actual Clean goal.
 */
public final class CleanSharedIoProbe {
    private static final Map<String, String> HASHES = Map.of(
        "maven-clean-plugin-3.2.0.jar", "b657bef2e1eb11e029a70cd688bde6adad29e4e99dacb18516bf651ecca32435",
        "maven-shared-utils-3.3.4.jar", "7925d9c5a0e2040d24b8fae3f612eb399cbffe5838b33ba368777dc7bddf6dda",
        "commons-io-2.20.0.jar", "df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72");
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }

    private static Class<?> origin(URLClassLoader loader, String name, Path expected) throws Exception {
        Class<?> type = Class.forName(name, false, loader);
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(actual.equals(expected) && type.getClassLoader() == loader,
                name + " originates in " + expected.getFileName());
        return type;
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 5, "IO version, directory and exactly three actual realm JARs supplied");
        require("2.20.0".equals(args[0]), "exact candidate IO version");
        Path dir = Path.of(args[1]).toAbsolutePath().normalize();
        require(!Files.exists(dir), "new disposable directory required");
        Map<String, Path> jars = new HashMap<>();
        for (String value : Arrays.copyOfRange(args, 2, args.length)) {
            Path path = Path.of(value).toRealPath();
            require(jars.put(path.getFileName().toString(), path) == null, "unique realm JAR " + path.getFileName());
            String expected = HASHES.get(path.getFileName().toString());
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            require(actual.equals(expected), "approved artifact bytes " + path.getFileName());
        }
        require(jars.keySet().equals(Set.of("maven-clean-plugin-3.2.0.jar", "maven-shared-utils-3.3.4.jar",
                "commons-io-" + args[0] + ".jar")), "only three observed Clean-realm artifacts");
        Files.createDirectory(dir);
        URL[] urls = new URL[jars.size()];
        int index = 0;
        for (Path path : jars.values()) urls[index++] = path.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Path ioJar = jars.get("commons-io-" + args[0] + ".jar");
            Path sharedJar = jars.get("maven-shared-utils-3.3.4.jar");
            origin(loader, "org.apache.commons.io.IOUtils", ioJar);
            Class<?> filenames = origin(loader, "org.apache.commons.io.FilenameUtils", ioJar);
            origin(loader, "org.apache.commons.io.input.XmlStreamReader", ioJar);
            origin(loader, "org.apache.commons.io.output.XmlStreamWriter", ioJar);
            Class<?> sharedFiles = origin(loader, "org.apache.maven.shared.utils.io.FileUtils", sharedJar);
            Class<?> sharedReader = origin(loader, "org.apache.maven.shared.utils.xml.XmlStreamReader", sharedJar);
            Class<?> sharedWriter = origin(loader, "org.apache.maven.shared.utils.xml.XmlStreamWriter", sharedJar);

            byte[] first = new byte[131073];
            for (int i = 0; i < first.length; i++) first[i] = (byte) (i % 251);
            Path a = dir.resolve("a.bin"), b = dir.resolve("b.bin");
            Files.write(a, first); Files.write(b, first);
            var equals = sharedFiles.getMethod("contentEquals", File.class, File.class);
            require((Boolean) equals.invoke(null, a.toFile(), b.toFile()), "Shared FileUtils delegates equal multi-buffer streams to IOUtils");
            first[65539] ^= 1;
            Files.write(b, first);
            require(!(Boolean) equals.invoke(null, a.toFile(), b.toFile()), "Shared FileUtils detects a same-length middle-byte change");
            require("a/c.txt".equals(filenames.getMethod("normalize", String.class, boolean.class)
                    .invoke(null, "a/b/../c.txt", true)), "ordinary path normalization linkage");

            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root>plain &amp; café</root>";
            byte[] utf8 = xml.getBytes(StandardCharsets.UTF_8);
            byte[] bom = new byte[utf8.length + 3];
            bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
            System.arraycopy(utf8, 0, bom, 3, utf8.length);
            try (Reader reader = (Reader) sharedReader.getConstructor(InputStream.class, boolean.class)
                    .newInstance(new ByteArrayInputStream(bom), false)) {
                StringWriter text = new StringWriter(); reader.transferTo(text);
                require(text.toString().equals(xml), "Shared XML reader preserves UTF-8 BOM text through real Commons IO");
            }
            String utf16Xml = xml.replace("UTF-8", "UTF-16");
            try (Reader reader = (Reader) sharedReader.getConstructor(InputStream.class)
                    .newInstance(new ByteArrayInputStream(utf16Xml.getBytes(StandardCharsets.UTF_16)))) {
                StringWriter text = new StringWriter(); reader.transferTo(text);
                require(text.toString().equals(utf16Xml), "Shared XML reader detects UTF-16 BOM");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Writer writer = (Writer) sharedWriter.getConstructor(OutputStream.class).newInstance(output)) {
                writer.write(xml);
            }
            require(output.toString(StandardCharsets.UTF_8).equals(xml), "Shared XML writer links and preserves declared encoding");
        }
        System.out.println("PROBE_COMPLETE: selected-realm Shared Utils/Commons IO API compatibility only; no exploitability or scanner claim.");
    }
}
