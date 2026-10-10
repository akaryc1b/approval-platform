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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded wrapper/API compatibility using the JARs observed during actual compilation. */
public final class CompilerSharedIoProbe {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }

    private static Class<?> origin(URLClassLoader loader, String name, Path expected) throws Exception {
        Class<?> type = Class.forName(name, false, loader);
        require(type.getClassLoader() == loader
                && Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),
                "origin " + name);
        return type;
    }

    private static String text(Reader reader) throws Exception {
        try (reader) {
            StringWriter out = new StringWriter();
            reader.transferTo(out);
            return out.toString();
        }
    }

    private static InputStream shortReads(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override public int available() { return 0; }
            @Override public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 7));
            }
        };
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 14, "exactly twelve observed realm JARs");
        Path dir = Path.of(args[0]).toAbsolutePath().normalize();
        require(!Files.exists(dir), "new disposable API directory");
        Map<String, String> hashes = new HashMap<>();
        var matcher = Pattern.compile("\"([^\"]+)\":\\s*\"([a-f0-9]{64})\"").matcher(Files.readString(Path.of(args[1])));
        while (matcher.find()) {
            String[] coordinate = matcher.group(1).split(":");
            require(coordinate.length == 4, "valid pinned coordinate");
            hashes.put(coordinate[1] + "-" + coordinate[3] + ".jar", matcher.group(2));
        }
        require(hashes.size() == 14, "complete resolved owner hash inventory");
        Map<String, Path> jars = new HashMap<>();
        for (String argument : Arrays.copyOfRange(args, 2, args.length)) {
            Path file = Path.of(argument).toRealPath();
            require(jars.put(file.getFileName().toString(), file) == null, "unique JAR " + file.getFileName());
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
                    .equals(hashes.get(file.getFileName().toString())), "pinned bytes " + file.getFileName());
        }
        require(!jars.containsKey("slf4j-api-1.7.36.jar") && !jars.containsKey("javax.inject-1.jar"),
                "Maven imports excluded from the actual private realm");
        Files.createDirectory(dir);
        URL[] urls = new URL[jars.size()]; int index = 0;
        for (Path jar : jars.values()) urls[index++] = jar.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Path ioJar = jars.get("commons-io-2.20.0.jar"), sharedJar = jars.get("maven-shared-utils-3.4.2.jar");
            Class<?> io = origin(loader, "org.apache.commons.io.IOUtils", ioJar);
            origin(loader, "org.apache.commons.io.input.XmlStreamReader", ioJar);
            origin(loader, "org.apache.commons.io.output.XmlStreamWriter", ioJar);
            Class<?> files = origin(loader, "org.apache.maven.shared.utils.io.FileUtils", sharedJar);
            Class<?> factory = origin(loader, "org.apache.maven.shared.utils.ReaderFactory", sharedJar);
            Class<?> reader = origin(loader, "org.apache.maven.shared.utils.xml.XmlStreamReader", sharedJar);
            Class<?> writer = origin(loader, "org.apache.maven.shared.utils.xml.XmlStreamWriter", sharedJar);
            byte[] content = new byte[131073];
            for (int i = 0; i < content.length; i++) content[i] = (byte) (i % 251);
            Path a = dir.resolve("equal one.bin"), b = dir.resolve("equal café.bin");
            Files.write(a, content); Files.write(b, content);
            var equalFiles = files.getMethod("contentEquals", File.class, File.class);
            require((Boolean) equalFiles.invoke(null, a.toFile(), b.toFile()), "Shared FileUtils equal multi-buffer content");
            content[65539] ^= 1; Files.write(b, content);
            require(!(Boolean) equalFiles.invoke(null, a.toFile(), b.toFile()), "Shared FileUtils unequal middle byte");
            var equalStreams = io.getMethod("contentEquals", InputStream.class, InputStream.class);
            require((Boolean) equalStreams.invoke(null, shortReads(content), shortReads(content)), "IOUtils bounded short reads ignore underreported available");
            require(!(Boolean) equalStreams.invoke(null, shortReads(content), shortReads(Arrays.copyOf(content, content.length - 1))), "IOUtils unequal stream lengths");
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root>café &amp; tea</root>";
            byte[] encoded = xml.getBytes(StandardCharsets.UTF_8), bom = new byte[encoded.length + 3];
            bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
            System.arraycopy(encoded, 0, bom, 3, encoded.length);
            Path xmlFile = dir.resolve("document café.xml"); Files.write(xmlFile, bom);
            require(xml.equals(text((Reader) factory.getMethod("newXmlReader", File.class).invoke(null, xmlFile.toFile()))), "ReaderFactory file XML BOM");
            require(xml.equals(text((Reader) factory.getMethod("newXmlReader", InputStream.class).invoke(null, shortReads(bom)))), "ReaderFactory stream XML short reads");
            require(xml.equals(text((Reader) factory.getMethod("newXmlReader", URL.class).invoke(null, xmlFile.toUri().toURL()))), "ReaderFactory local file URL XML");
            require(xml.equals(text((Reader) reader.getConstructor(File.class).newInstance(xmlFile.toFile()))), "Shared XmlStreamReader file constructor");
            require(xml.equals(text((Reader) reader.getConstructor(InputStream.class).newInstance(shortReads(bom)))), "Shared XmlStreamReader stream constructor");
            require(xml.equals(text((Reader) reader.getConstructor(InputStream.class, boolean.class).newInstance(shortReads(bom), false))), "Shared XmlStreamReader strict constructor");
            String utf16 = xml.replace("UTF-8", "UTF-16");
            Reader unicode = (Reader) reader.getConstructor(InputStream.class).newInstance(shortReads(utf16.getBytes(StandardCharsets.UTF_16)));
            require(reader.getMethod("getEncoding").invoke(unicode).toString().startsWith("UTF-16"), "Shared XmlStreamReader detected UTF-16 encoding");
            require(utf16.equals(text(unicode)), "Shared XmlStreamReader UTF-16 BOM content and close");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Writer out = (Writer) writer.getConstructor(OutputStream.class).newInstance(output)) { out.write(xml); }
            require(xml.equals(output.toString(StandardCharsets.UTF_8)), "Shared XmlStreamWriter output stream constructor");
            Path written = dir.resolve("written.xml");
            try (Writer out = (Writer) writer.getConstructor(File.class).newInstance(written.toFile())) { out.write(xml); }
            require(xml.equals(Files.readString(written)), "Shared XmlStreamWriter file constructor");
        }
        System.out.println("COMPILER_API_COMPLETE: wrapper/API compatibility only; no natural goal reachability or scanner claim.");
    }
}
