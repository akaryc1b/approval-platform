package org.springframework.boot.maven;

import java.io.*;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.apache.commons.compress.compressors.lz77support.LZ77Compressor;
import org.apache.commons.compress.compressors.lz77support.Parameters;
import org.apache.commons.lang3.ArrayFill;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.SystemProperties;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugins.shade.filter.SimpleFilter;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ComponentsXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.PluginXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.AppendingTransformer;
import org.codehaus.plexus.util.*;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.io.CachingOutputStream;
import org.codehaus.plexus.util.xml.*;
import org.sonatype.plexus.build.incremental.DefaultBuildContext;
import org.springframework.boot.buildpack.platform.io.Content;
import org.springframework.boot.buildpack.platform.io.Owner;
import org.springframework.boot.buildpack.platform.io.TarArchive;

/** Baseline-compiled, bounded in-process consumers. This does not execute Maven goals. */
public final class BootPluginUtilitiesProbe {
    private static final Map<String, String[]> ARTIFACTS = new TreeMap<>();
    private static final Set<String> ORIGINS = new TreeSet<>();
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static Class<?> origin(String name, String artifact) throws Exception {
        Class<?> type = Class.forName(name, false, BootPluginUtilitiesProbe.class.getClassLoader());
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        String[] expected = ARTIFACTS.get(artifact);
        check(expected != null && actual.equals(Path.of(expected[0]).toRealPath())
                && hash(Files.readAllBytes(actual)).equals(expected[1]), "origin " + name);
        if (ORIGINS.add(name)) System.out.println("BOOT_UTIL_ORIGIN\t" + name + "\t" + actual + "\t" + expected[1]);
        return type;
    }
    private static Method privateMethod(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
    private static Throwable cause(InvocationTargetException failure) { return failure.getCause(); }
    private static ByteArrayInputStream input(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] bounded(InputStream input) throws Exception {
        try (input) {
            byte[] bytes = input.readNBytes(32769);
            check(bytes.length <= 32768 && input.read() == -1, "bounded complete stream");
            return bytes;
        }
    }

    // Descriptor resolution follows the reviewed Boot HTTP/Checkstyle probes.
    // The checked-in list is the exact 31-member static inventory, not an API-wide claim.
    private static void linkage(Path members, boolean negative) throws Exception {
        check(Files.size(members) < 16384, "bounded member inventory");
        List<String> lines = Files.readAllLines(members);
        check(lines.size() == 31 && new HashSet<>(lines).size() == 31, "31 distinct observed members");
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            check(p.length == 4 && Set.of("static", "instance").contains(p[0]), "member shape");
            Class<?> owner = origin(p[1].replace('/', '.'), p[1].startsWith("org/codehaus/") ? "plexus-utils" : "commons-lang3");
            MethodType signature = MethodType.fromMethodDescriptorString(p[3], owner.getClassLoader());
            int modifiers;
            if (p[2].equals("<init>")) {
                modifiers = owner.getConstructor(signature.parameterArray()).getModifiers();
                check(signature.returnType() == void.class, "constructor return " + p[1]);
            } else {
                Method method = owner.getMethod(p[2], signature.parameterArray());
                modifiers = method.getModifiers();
                check(method.getReturnType() == signature.returnType(), "return descriptor " + p[1] + "." + p[2]);
            }
            check(Modifier.isPublic(owner.getModifiers()) && Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers) == p[0].equals("static"), "visibility and invocation kind " + p[1] + "." + p[2]);
            System.out.println("BOOT_UTIL_MEMBER\t" + line);
        }
        for (String type : List.of("Scanner", "xml.XmlStreamReader", "xml.XmlStreamWriter"))
            origin("org.codehaus.plexus.util." + type, "plexus-utils");
        if (negative) CommandLineUtils.class.getMethod("missingFixtureMethod");
        System.out.println("BOOT_UTIL_LINKAGE\t31\t" + hash(Files.readAllBytes(members)));
    }

    private static void bootArguments() throws Exception {
        origin(RunArguments.class.getName(), "spring-boot-maven-plugin");
        Object[][] examples = {
            {null, new String[0]}, {"", new String[0]}, {" \t\n ", new String[0]},
            {"alpha beta", new String[] {"alpha", "beta"}},
            {"a 'two words' \"three words\"", new String[] {"a", "two words", "three words"}},
            // Plexus 3.x emits a token only when its buffer is nonempty.
            {"'' \"\" tail", new String[] {"tail"}},
            {"a\tb\nc", new String[] {"a", "b", "c"}},
            {"\"café\tline\nvalue\"", new String[] {"café line value"}},
            {"--path='a b' --name=\"café\"", new String[] {"--path=a b", "--name=café"}},
        };
        for (int i = 0; i < examples.length; i++) {
            String text = (String) examples[i][0]; String[] expected = (String[]) examples[i][1];
            check(Arrays.equals(RunArguments.parseArgs(text), expected), "Boot RunArguments parse case " + i);
            check(Arrays.equals(new RunArguments(text).asArray(), expected), "Boot RunArguments constructor case " + i);
        }
        for (String bad : List.of("'unclosed", "\"unclosed")) {
            try { RunArguments.parseArgs(bad); throw new AssertionError("unbalanced argument accepted"); }
            catch (IllegalArgumentException expected) {
                check(expected.getCause() instanceof org.codehaus.plexus.util.cli.CommandLineException,
                        "Boot RunArguments wraps unmatched quote");
            }
        }
        check(Arrays.equals(CommandLineUtils.translateCommandline("one 'two three'"), new String[] {"one", "two three"}),
                "Plexus direct commandline linkage");
    }

    private static Xpp3Dom child(Xpp3Dom parent, String name, String value) {
        Xpp3Dom node = new Xpp3Dom(name); node.setValue(value); parent.addChild(node); return node;
    }
    private static void compilerConfiguration() throws Exception {
        origin(JavaCompilerPluginConfiguration.class.getName(), "spring-boot-maven-plugin");
        origin(MavenProject.class.getName(), "maven-core"); origin(Plugin.class.getName(), "maven-model");
        MavenProject project = new MavenProject();
        JavaCompilerPluginConfiguration compiler = new JavaCompilerPluginConfiguration(project);
        check(compiler.getSourceMajorVersion() == null && compiler.getTargetMajorVersion() == null
                && compiler.getReleaseVersion() == null, "Boot AOT absent compiler configuration");
        project.getProperties().put("maven.compiler.source", "1.8");
        project.getProperties().put("maven.compiler.target", "17");
        project.getProperties().put("maven.compiler.release", 21);
        check("8".equals(compiler.getSourceMajorVersion()) && "17".equals(compiler.getTargetMajorVersion())
                && "21".equals(compiler.getReleaseVersion()), "Boot AOT compiler property normalization");
        Build build = new Build(); project.setBuild(build);
        Plugin plugin = new Plugin(); plugin.setGroupId("org.apache.maven.plugins"); plugin.setArtifactId("maven-compiler-plugin");
        build.addPlugin(plugin);
        Xpp3Dom root = new Xpp3Dom("configuration"); plugin.setConfiguration(root);
        child(root, "source", "11"); child(root, "target", "1.8"); child(root, "release", "17");
        check("11".equals(compiler.getSourceMajorVersion()) && "8".equals(compiler.getTargetMajorVersion())
                && "17".equals(compiler.getReleaseVersion()), "Boot AOT XML overrides compiler properties");
        root.getChild("release").setValue(null);
        check("21".equals(compiler.getReleaseVersion()), "Boot AOT null XML value falls back");
        root.getChild("release").setValue("");
        check("".equals(compiler.getReleaseVersion()), "Boot AOT empty XML value preserved");
        Xpp3Dom nested = child(root, "nested", null); child(nested, "value", "23");
        Method value = privateMethod(JavaCompilerPluginConfiguration.class, "getNodeValue", Xpp3Dom.class, String[].class);
        check("23".equals(value.invoke(compiler, root, new String[] {"nested", "value"}))
                && value.invoke(compiler, root, new String[] {"nested", "absent"}) == null,
                "Boot AOT nested Xpp3Dom lookup");
        plugin.setConfiguration("not XML");
        check("8".equals(compiler.getSourceMajorVersion()), "Boot AOT non-XML configuration falls back");
    }

    private static void scanner(Path work, boolean candidate) throws Exception {
        origin(DefaultBuildContext.class.getName(), "plexus-build-api");
        Path root = Files.createDirectory(work.resolve("scan"));
        for (String name : List.of("normal.txt", ".gitignore", ".cvsignore", ".hidden", "src/keep.txt", "src/drop.tmp", ".git/config")) {
            Path file = root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, name);
        }
        org.codehaus.plexus.util.Scanner scanner = new DefaultBuildContext().newScanner(root.toFile());
        scanner.addDefaultExcludes(); scanner.scan();
        // 3.5.1 already includes .gitignore; 3.6.1 additionally includes .cvsignore.
        // Assert both files individually, rather than inferring a two-file delta from release prose.
        Set<String> expected = new TreeSet<>(Set.of("normal.txt", ".hidden", ".gitignore", "src/keep.txt", "src/drop.tmp"));
        if (candidate) expected.add(".cvsignore");
        check(new TreeSet<>(Arrays.asList(scanner.getIncludedFiles())).equals(expected),
                "BuildContext scanner exact default inclusion set");
        System.out.println("BOOT_UTIL_SCANNER\t" + (candidate ? "3.6.1" : "3.5.1") + "\t.gitignore=true\t.cvsignore=" + candidate);
        DirectoryScanner filtered = new DirectoryScanner(); filtered.setBasedir(root.toFile());
        filtered.setIncludes(new String[] {"src/**"}); filtered.setExcludes(new String[] {"**/*.tmp"});
        filtered.addDefaultExcludes(); filtered.scan();
        check(Arrays.equals(filtered.getIncludedFiles(), new String[] {"src/keep.txt"}), "scanner include exclude directory boundaries");
        check(new DefaultBuildContext().newDeleteScanner(root.toFile()).getIncludedFiles().length == 0, "BuildContext empty delete scanner");
    }

    private static Map<String, byte[]> jarEntries(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new TreeMap<>();
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                check(entries.size() < 8 && !entries.containsKey(entry.getName()), "bounded unique resource entries");
                byte[] data = jar.readNBytes(32769); check(data.length <= 32768, "resource byte budget"); entries.put(entry.getName(), data);
            }
        }
        return entries;
    }
    private static void shadeAndXml(Path work) throws Exception {
        origin(SimpleFilter.class.getName(), "maven-shade-plugin");
        origin(ComponentsXmlResourceTransformer.class.getName(), "maven-shade-plugin");
        origin(PluginXmlResourceTransformer.class.getName(), "maven-shade-plugin");
        SimpleFilter filter = new SimpleFilter(Set.of(), Set.of("example/**"), Set.of("**/Hidden*"));
        check(!filter.isFiltered("example/Visible.class") && filter.isFiltered("example/Hidden.class")
                && filter.isFiltered("other/Visible.class"), "Shade real glob filter");
        SimpleRelocator relocate = new SimpleRelocator("example", "shaded.example", List.of("example.**"), List.of("example.Hidden"));
        check(relocate.canRelocateClass("example.Visible") && !relocate.canRelocateClass("example.Hidden")
                && "shaded.example.Visible".equals(relocate.relocateClass("example.Visible")), "Shade real relocation");
        check(SelectorUtils.match("artifact-*", "artifact-core") && !SelectorUtils.match("artifact-?", "artifact-core")
                && SelectorUtils.matchPath("a/**", "a/b/c") && !SelectorUtils.matchPath("A/**", "a/b", true), "Plexus selector overload linkage");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root kind=\"café\"><item>a &amp; b</item><item>second</item></root>";
        Xpp3Dom dom;
        try (Reader reader = ReaderFactory.newXmlReader(input(xml))) { dom = Xpp3DomBuilder.build(reader); }
        check(dom.getName().equals("root") && dom.getChildCount() == 2 && dom.getChildren().length == 2
                && dom.getChildren("item").length == 2 && dom.getChild(0).getValue().equals("a & b")
                && dom.getChild("item").getValue().equals("second") && dom.getAttribute("kind").equals("café"), "Plexus XML complete accessor linkage");
        dom.setAttribute("extra", "<&"); child(dom, "tail", "café");
        ByteArrayOutputStream xmlBytes = new ByteArrayOutputStream();
        try (Writer writer = WriterFactory.newXmlWriter(xmlBytes)) { Xpp3DomWriter.write(writer, dom); }
        Path xmlFile = work.resolve("fixture.xml");
        try (Writer writer = WriterFactory.newXmlWriter(xmlFile.toFile())) { Xpp3DomWriter.write(writer, dom); }
        check(Arrays.equals(xmlBytes.toByteArray(), Files.readAllBytes(xmlFile)), "Plexus XML stream and file writer equality");
        Xpp3Dom roundTrip = Xpp3DomBuilder.build(new StringReader(xmlBytes.toString(StandardCharsets.UTF_8)));
        check(roundTrip.getAttribute("extra").equals("<&") && roundTrip.getChild("tail").getValue().equals("café"), "Plexus XML escaped Unicode round trip");
        byte[] content = "short reads café".getBytes(StandardCharsets.UTF_8);
        InputStream shortReads = new ByteArrayInputStream(content) {
            @Override public synchronized int read(byte[] bytes, int off, int len) { return super.read(bytes, off, Math.min(len, 3)); }
            @Override public int available() { return 0; }
        };
        ByteArrayOutputStream copy = new ByteArrayOutputStream(); IOUtil.copy(shortReads, copy);
        check(Arrays.equals(content, copy.toByteArray()) && Arrays.equals(content, IOUtil.toByteArray(new ByteArrayInputStream(content)))
                && "café".equals(IOUtil.toString(new StringReader("café"))), "Plexus stream copy byte and reader linkage");
        Path cached = work.resolve("cached.txt");
        try (OutputStream output = new CachingOutputStream(cached.toFile())) { output.write(content); }
        check(Arrays.equals(content, Files.readAllBytes(cached)), "Plexus CachingOutputStream content");
        ComponentsXmlResourceTransformer components = new ComponentsXmlResourceTransformer();
        components.processResource("META-INF/plexus/components.xml", input("<component-set><components><component><role>example.Role</role><role-hint>fixture</role-hint><implementation>example.Visible</implementation></component></components></component-set>"), List.of(relocate), 0);
        ByteArrayOutputStream componentJar = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(componentJar)) { components.modifyOutputStream(out); }
        String componentXml = new String(jarEntries(componentJar.toByteArray()).get("META-INF/plexus/components.xml"), StandardCharsets.UTF_8);
        check(componentXml.contains("shaded.example.Visible") && componentXml.contains("shaded.example.Role"), "Shade actual components XML transform");
        PluginXmlResourceTransformer plugin = new PluginXmlResourceTransformer();
        plugin.processResource("META-INF/maven/plugin.xml", input("<plugin><mojos><mojo><goal>fixture</goal><implementation>example.Visible</implementation><parameters><parameter><name>x</name><type>example.Visible</type></parameter></parameters><configuration><x implementation=\"example.Visible\"/></configuration></mojo></mojos></plugin>"), List.of(relocate), 0);
        ByteArrayOutputStream pluginJar = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(pluginJar)) { plugin.modifyOutputStream(out); }
        String pluginXml = new String(jarEntries(pluginJar.toByteArray()).get("META-INF/maven/plugin.xml"), StandardCharsets.UTF_8);
        check(pluginXml.contains("shaded.example.Visible") && pluginXml.contains("implementation=\"shaded.example.Visible\""), "Shade actual plugin XML transform attributes");
        AppendingTransformer appender = new AppendingTransformer();
        Field resource = AppendingTransformer.class.getDeclaredField("resource"); resource.setAccessible(true); resource.set(appender, "fixture.txt");
        appender.processResource("fixture.txt", input("one"), List.of(), 0);
        appender.processResource("fixture.txt", input("café"), List.of(), 0);
        ByteArrayOutputStream appended = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(appended)) { appender.modifyOutputStream(out); }
        check(Arrays.equals("one\ncafé\n".getBytes(StandardCharsets.UTF_8), jarEntries(appended.toByteArray()).get("fixture.txt")), "Shade actual stream resource append");
    }

    private static void tar(Path work) throws Exception {
        origin(TarArchive.class.getName(), "spring-boot-buildpack-platform");
        origin("org.springframework.boot.buildpack.platform.io.TarLayoutWriter", "spring-boot-buildpack-platform");
        origin(TarArchiveEntry.class.getName(), "commons-compress");
        origin(TarArchiveOutputStream.class.getName(), "commons-compress");
        String longName = "nested/" + "n".repeat(120) + ".txt";
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("normal.txt", "ordinary".getBytes(StandardCharsets.UTF_8));
        expected.put("café.txt", "非ASCII".getBytes(StandardCharsets.UTF_8));
        expected.put(longName, "long name".getBytes(StandardCharsets.UTF_8)); expected.put("empty.txt", new byte[0]);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TarArchive.of(layout -> { for (var entry : expected.entrySet()) layout.file(entry.getKey(), Owner.ROOT, Content.of(entry.getValue())); }).writeTo(bytes);
        byte[] archive = bytes.toByteArray(); Files.write(work.resolve("boot.tar"), archive);
        check(archive.length < 32768 && archive.length % 512 == 0
                && Arrays.equals(Arrays.copyOfRange(archive, archive.length - 1024, archive.length), new byte[1024]), "Boot TAR EOF and 512-byte padding");
        Map<String, byte[]> actual = new LinkedHashMap<>();
        try (TarArchiveInputStream input = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                check(actual.size() < 4 && entry.getSize() < 1024 && entry.getLongUserId() == 0 && entry.getLongGroupId() == 0, "Boot TAR bounded normalized entries");
                actual.put(entry.getName(), input.readNBytes((int) entry.getSize()));
            }
        }
        check(actual.keySet().equals(expected.keySet()), "Boot TAR exact normal Unicode long empty names");
        for (var entry : expected.entrySet()) check(Arrays.equals(entry.getValue(), actual.get(entry.getKey())), "Boot TAR exact content " + entry.getKey());
        ByteArrayOutputStream empty = new ByteArrayOutputStream(); TarArchive.of(layout -> {}).writeTo(empty);
        Files.write(work.resolve("empty.tar"), empty.toByteArray());
        check(empty.size() == 1024 && Arrays.equals(empty.toByteArray(), new byte[1024]), "Boot empty TAR two EOF blocks");
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            check("Windows 11".equals(SystemProperties.getOsName()) && "root/file.txt".equals(new TarArchiveEntry("C:/root/file.txt").getName()), "Compress Lang Windows TAR normalization");
            System.setProperty("os.name", "Linux");
            check("root/file.txt".equals(new TarArchiveEntry("/root/file.txt").getName()), "Compress Lang Unix TAR normalization");
        } finally { if (original == null) System.clearProperty("os.name"); else System.setProperty("os.name", original); }
        check(Arrays.equals(ArrayFill.fill(new byte[2], (byte) 7), new byte[] {7, 7})
                && Arrays.equals(ArrayFill.fill(new int[2], -1), new int[] {-1, -1}), "Lang direct fill binary linkage");
    }

    private static void decoders() throws Exception {
        origin("org.apache.commons.compress.archivers.zip.BinaryTree", "commons-compress");
        origin("org.apache.commons.compress.compressors.deflate64.HuffmanDecoder", "commons-compress");
        origin(LZ77Compressor.class.getName(), "commons-compress");
        // Two complete six-bit trees (64 values each), followed by eight literal
        // records. Eight 9-bit records have no ambiguous trailing padding bits.
        ByteArrayOutputStream implode = new ByteArrayOutputStream();
        implode.write(new byte[] {3, (byte) 0xf5, (byte) 0xf5, (byte) 0xf5, (byte) 0xf5, 3, (byte) 0xf5, (byte) 0xf5, (byte) 0xf5, (byte) 0xf5});
        byte[] literal = "ABCDEFGH".getBytes(StandardCharsets.US_ASCII); long bits = 0; int bitCount = 0;
        for (byte value : literal) {
            bits |= (long) (1 | (value & 255) << 1) << bitCount; bitCount += 9;
            while (bitCount >= 8) { implode.write((int) bits & 255); bits >>>= 8; bitCount -= 8; }
        }
        check(bitCount == 0, "implode fixture exact bit boundary");
        Class<?> exploding = Class.forName("org.apache.commons.compress.archivers.zip.ExplodingInputStream");
        Constructor<?> constructor = exploding.getDeclaredConstructor(int.class, int.class, InputStream.class); constructor.setAccessible(true);
        check(Arrays.equals(bounded((InputStream) constructor.newInstance(4096, 2, new ByteArrayInputStream(implode.toByteArray()))), literal), "Compress implode exact literal fixture");
        try { bounded((InputStream) constructor.newInstance(4096, 2, new ByteArrayInputStream(new byte[] {3, (byte) 0xf5}))); throw new AssertionError("truncated implode accepted"); }
        catch (IOException expected) { check(true, "Compress implode truncated tree rejected"); }
        check(Arrays.equals(bounded(new Deflate64CompressorInputStream(new ByteArrayInputStream(new byte[] {0x73, 0x74, 0x72, 0x06, 0x00}))), new byte[] {65, 66, 67}), "Compress Deflate64 fixed Huffman exact content");
        try { bounded(new Deflate64CompressorInputStream(new ByteArrayInputStream(new byte[] {7}))); throw new AssertionError("invalid Deflate64 accepted"); }
        catch (IOException expected) { check(true, "Compress Deflate64 invalid block rejected"); }
        ByteArrayOutputStream expanded = new ByteArrayOutputStream(); int[] counts = new int[3];
        LZ77Compressor compressor = new LZ77Compressor(Parameters.builder(256).build(), block -> {
            if (block instanceof LZ77Compressor.LiteralBlock literalBlock) {
                counts[0]++; expanded.write(literalBlock.getData(), literalBlock.getOffset(), literalBlock.getLength());
            } else if (block instanceof LZ77Compressor.BackReference reference) {
                counts[1]++;
                for (int i = 0; i < reference.getLength(); i++) {
                    byte[] current = expanded.toByteArray();
                    if (reference.getOffset() < 1 || reference.getOffset() > current.length || current.length >= 4096) throw new IOException("invalid bounded LZ77 output");
                    expanded.write(current[current.length - reference.getOffset()]);
                }
            } else { counts[2]++; }
        });
        byte[] source = "abcabcabcabcabcabcxyzxyzxyzxyz".getBytes(StandardCharsets.US_ASCII);
        compressor.compress(source, 0, 7); compressor.compress(source, 7, source.length - 7); compressor.finish();
        check(Arrays.equals(source, expanded.toByteArray()) && counts[0] > 0 && counts[1] > 0 && counts[2] == 1, "Compress LZ77 literal backreference and end reconstruction");
    }

    public static final class Fields { private final String value = "café"; }
    private static void reflection() throws Exception {
        Class<?> adapter = origin("org.apache.commons.compress.harmony.unpack200.Pack200UnpackerAdapter", "commons-compress");
        Method read = privateMethod(adapter, "readField", Object.class, String.class);
        check("café".equals(read.invoke(null, new Fields(), "value"))
                && "café".equals(FieldUtils.readField(new Fields(), "value", true)), "Compress Pack200 actual reflective field success");
        try { read.invoke(null, new Fields(), "missing"); throw new AssertionError("missing reflective field accepted"); }
        catch (InvocationTargetException expected) { check(cause(expected) instanceof IllegalArgumentException, "Compress Pack200 missing field rejected"); }
        try { read.invoke(null, "fixture", "value"); throw new AssertionError("JDK closed field accessible"); }
        catch (InvocationTargetException expected) { check(cause(expected) instanceof InaccessibleObjectException, "Compress Pack200 JDK module access rejected"); }
        check(ClassUtils.getClass("int[]") == int[].class && ClassUtils.getClass("java.util.Map.Entry") == Map.Entry.class, "Lang library ordinary class-name regression");
        try { ClassUtils.getClass("missing.".repeat(32) + "Absent"); throw new AssertionError("missing class accepted"); }
        catch (ClassNotFoundException expected) { check(true, "Lang library bounded missing class-name regression"); }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 4 || args.length == 5, "identity member variant work arguments");
        check(Set.of("baseline", "candidate").contains(args[2]), "known utility variant");
        Path identities = Path.of(args[0]); check(Files.size(identities) < 65536, "bounded identity manifest");
        for (String row : Files.readAllLines(identities)) {
            String[] p = row.split("\t", -1);
            check(p.length == 3 && p[2].matches("[a-f0-9]{64}") && ARTIFACTS.put(p[0], new String[] {p[1], p[2]}) == null, "unique artifact identity");
        }
        Path work = Path.of(args[3]).toRealPath();
        linkage(Path.of(args[1]), args.length == 5 && args[4].equals("missing-member"));
        bootArguments(); compilerConfiguration(); scanner(work, args[2].equals("candidate"));
        shadeAndXml(work); tar(work); decoders(); reflection();
        System.out.println("BOOT_UTIL_COMPLETE\t" + args[2] + "\t" + checks);
    }
}
