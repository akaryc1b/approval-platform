import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.maven.ant.tasks.AttachArtifactTask;
import org.apache.maven.ant.tasks.support.VersionMapper;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugins.antrun.AntRunMojo;
import org.apache.maven.plugins.antrun.MavenAntRunProject;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.apache.tools.ant.Project;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.Expand;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.jacoco.maven.FileFilter;

/**
 * Baseline-compiled, bounded in-process consumers and exact symbolic-member replay.
 * This is API compatibility evidence, not Maven goal execution or natural realm evidence.
 * The harness supplies a sealed classpath with the selected private Plexus JAR first.
 */
public final class JacocoAntRunPlexusProbe {
    private static final String MEMBER_SHA256 = "75ead047e322776e765ef52c6bb712b7e6e61de96f7054c678a614777b26c613";
    private static final Map<String, String[]> ARTIFACTS = new TreeMap<>();
    private static final Set<String> ORIGINS = new TreeSet<>();
    private static final List<String> IGNORE_FILES = List.of(".cvsignore", ".gitignore", ".gitattributes", ".hgignore");
    private static final List<String> IGNORE_PATTERNS = IGNORE_FILES.stream().map(name -> "**/" + name).toList();
    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String fileHash(Path file) throws Exception {
        check(Files.isRegularFile(file) && Files.size(file) <= 64 * 1024 * 1024, "bounded artifact " + file.getFileName());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16384];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Class<?> origin(String name, String artifact) throws Exception {
        Class<?> type = Class.forName(name, false, JacocoAntRunPlexusProbe.class.getClassLoader());
        check(type.getProtectionDomain() != null && type.getProtectionDomain().getCodeSource() != null,
                "code source present " + name);
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        String[] expected = ARTIFACTS.get(artifact);
        check(expected != null && actual.equals(Path.of(expected[0])) && fileHash(actual).equals(expected[1]), "origin " + name);
        if (ORIGINS.add(name)) System.out.println("JAC_ANTRUN_API_ORIGIN\t" + name + "\t" + actual + "\t" + expected[1]);
        return type;
    }

    private static void plexusType(Class<?> type) throws Exception {
        while (type.isArray()) type = type.getComponentType();
        if (type.getName().startsWith("org.codehaus.plexus.util.")) origin(type.getName(), "plexus-utils");
    }

    private static void identities(Path file, String variant) throws Exception {
        check(Files.size(file) < 65536, "bounded identity manifest");
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        check(!rows.isEmpty() && rows.size() <= 128, "bounded identity count");
        for (String row : rows) {
            String[] p = row.split("\t", -1);
            check(p.length == 3 && p[0].matches("[A-Za-z0-9_.-]+") && Path.of(p[1]).isAbsolute()
                    && p[2].matches("[a-f0-9]{64}"), "artifact identity shape");
            Path path = Path.of(p[1]).toRealPath();
            check(fileHash(path).equals(p[2]), "artifact SHA-256 " + p[0]);
            check(ARTIFACTS.put(p[0], new String[] {path.toString(), p[2]}) == null, "unique artifact " + p[0]);
        }
        String version = switch (variant) {
            case "jacoco-baseline" -> "3.0.24";
            case "antrun-baseline" -> "3.4.1";
            case "candidate" -> "3.6.1";
            default -> throw new AssertionError("unknown variant");
        };
        check(ARTIFACTS.containsKey("plexus-utils"), "selected private Plexus identity");
        try (JarFile jar = new JarFile(ARTIFACTS.get("plexus-utils")[0])) {
            var entry = jar.getJarEntry("META-INF/maven/org.codehaus.plexus/plexus-utils/pom.properties");
            check(entry != null && entry.getSize() >= 0 && entry.getSize() < 4096, "Plexus coordinate metadata");
            Properties coordinates = new Properties();
            try (InputStream input = jar.getInputStream(entry)) { coordinates.load(input); }
            check("org.codehaus.plexus".equals(coordinates.getProperty("groupId"))
                    && "plexus-utils".equals(coordinates.getProperty("artifactId"))
                    && version.equals(coordinates.getProperty("version")), "Plexus variant " + version);
        }
    }

    // Columns: owning scope (jacoco/antrun/both), invocation kind, internal owner, member, descriptor.
    // The digest seals the exact reviewed 34-member union, including scope and invocation shape.
    private static void linkage(Path file, String variant, boolean negative) throws Exception {
        check(Files.size(file) < 16384 && hash(Files.readAllBytes(file)).equals(MEMBER_SHA256), "exact reviewed member inventory");
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        check(rows.size() == 34 && new HashSet<>(rows).size() == 34, "34 distinct observed member identities");
        int selected = 0;
        for (String row : rows) {
            String[] p = row.split("\t", -1);
            check(p.length == 5 && Set.of("jacoco", "antrun", "both").contains(p[0])
                    && Set.of("static", "instance").contains(p[1])
                    && p[2].startsWith("org/codehaus/plexus/util/"), "member inventory shape");
            if (!variant.equals("candidate") && !p[0].equals("both") && !variant.startsWith(p[0] + "-")) continue;
            Class<?> owner = origin(p[2].replace('/', '.'), "plexus-utils");
            MethodType signature = MethodType.fromMethodDescriptorString(p[4], owner.getClassLoader());
            plexusType(signature.returnType());
            for (Class<?> parameter : signature.parameterArray()) plexusType(parameter);
            int modifiers;
            if (p[3].equals("<init>")) {
                modifiers = owner.getConstructor(signature.parameterArray()).getModifiers();
                check(signature.returnType() == void.class, "constructor return " + p[2]);
            } else {
                Method method = owner.getMethod(p[3], signature.parameterArray());
                modifiers = method.getModifiers();
                plexusType(method.getDeclaringClass());
                check(method.getReturnType() == signature.returnType(), "member return descriptor " + p[2] + "." + p[3]);
            }
            check(Modifier.isPublic(owner.getModifiers()) && Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers) == p[1].equals("static"), "member visibility and invocation " + p[2] + "." + p[3]);
            selected++;
            System.out.println("JAC_ANTRUN_MEMBER\t" + row);
        }
        int expected = variant.equals("candidate") ? 34 : variant.equals("jacoco-baseline") ? 18 : 17;
        check(selected == expected, "exact owner member subset " + selected);
        if (negative) {
            origin("org.codehaus.plexus.util.FileUtils", "plexus-utils").getMethod("missingFixtureMethod");
            throw new AssertionError("missing-member negative unexpectedly resolved");
        }
        System.out.println("JAC_ANTRUN_LINKAGE\t" + variant + "\t" + selected + "\t" + MEMBER_SHA256);
    }

    private static void write(Path root, String name, String text) throws Exception {
        Path file = root.resolve(name).normalize();
        check(file.startsWith(root) && !file.equals(root), "fixture path containment " + name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static Set<String> names(String[] paths) {
        Set<String> values = new TreeSet<>();
        for (String path : paths) values.add(path.replace(File.separatorChar, '/'));
        check(values.size() == paths.length, "unique scanner results");
        return values;
    }

    private static Set<String> names(List<String> paths) { return names(paths.toArray(String[]::new)); }

    private static DirectoryScanner scanner(Path root, List<String> includes, List<String> excludes) {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(root.toFile());
        scanner.setIncludes(includes.toArray(String[]::new));
        scanner.setExcludes(excludes.toArray(String[]::new));
        scanner.setFollowSymlinks(false);
        scanner.addDefaultExcludes();
        scanner.scan();
        return scanner;
    }

    private static FileSet fileSet(Path root, boolean defaults, List<String> includes, List<String> excludes) {
        FileSet files = new FileSet();
        files.setDirectory(root.toString());
        files.setIncludes(includes);
        files.setExcludes(excludes);
        files.setUseDefaultExcludes(defaults);
        files.setFollowSymlinks(false);
        return files;
    }

    private static void selection(Path work, String variant) throws Exception {
        origin(FileFilter.class.getName(), "jacoco-maven-plugin");
        origin(FileSet.class.getName(), "file-management");
        origin(FileSetManager.class.getName(), "file-management");
        origin(DirectoryScanner.class.getName(), "plexus-utils");
        origin("org.codehaus.plexus.util.AbstractScanner", "plexus-utils");
        Path root = Files.createDirectory(work.resolve("selection"));
        Set<String> ordinary = Set.of("ordinary.txt", ".hidden", "nested/keep.txt", "nested/drop.tmp", "café/你好.txt");
        Set<String> scm = Set.of(".git/config", ".hg/store");
        Set<String> all = new TreeSet<>(ordinary); all.addAll(IGNORE_FILES); all.addAll(scm);
        for (String name : all) write(root, name, "bounded fixture " + name + "\n");

        FileFilter filter = new FileFilter(null, null);
        check(names(filter.getFileNames(root.toFile())).equals(all), "JaCoCo actual FileFilter unchanged exact filenames");
        Set<String> returnedFiles = new TreeSet<>();
        for (File file : filter.getFiles(root.toFile())) {
            Path actual = file.toPath().toRealPath();
            check(actual.startsWith(root) && Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS), "JaCoCo selected bounded regular file");
            returnedFiles.add(root.relativize(actual).toString().replace(File.separatorChar, '/'));
        }
        check(returnedFiles.equals(all), "JaCoCo actual FileFilter unchanged exact File objects");
        FileFilter custom = new FileFilter(List.of("nested/**", "café/**"), List.of("**/*.tmp"));
        check("nested/**,café/**".equals(custom.getIncludes()) && "**/*.tmp".equals(custom.getExcludes())
                && names(custom.getFileNames(root.toFile())).equals(Set.of("nested/keep.txt", "café/你好.txt")),
                "JaCoCo actual FileFilter custom includes excludes and Unicode");
        System.out.println("JAC_ANTRUN_FILE_FILTER\t" + variant + "\tignoreFilesIncluded=4\tfiles=" + all.size());

        Set<String> expected = new TreeSet<>(ordinary);
        if (variant.equals("candidate")) expected.addAll(IGNORE_FILES);
        Set<String> excluded = new TreeSet<>(all); excluded.removeAll(expected);
        DirectoryScanner scanner = scanner(root, List.of("**"), List.of());
        check(names(scanner.getIncludedFiles()).equals(expected), "DirectoryScanner exact default file selection");
        check(names(scanner.getExcludedFiles()).equals(excluded), "DirectoryScanner exact default file exclusions");
        check(names(scanner.getIncludedDirectories()).equals(Set.of("", "nested", "café"))
                && names(scanner.getExcludedDirectories()).equals(Set.of(".git", ".hg")), "DirectoryScanner SCM directory boundaries");
        FileSetManager manager = new FileSetManager();
        FileSet defaults = fileSet(root, true, List.of("**"), List.of());
        check(names(manager.getIncludedFiles(defaults)).equals(expected)
                && names(manager.getExcludedFiles(defaults)).equals(excluded), "FileSetManager explicit default-excludes exact files");
        check(names(manager.getIncludedDirectories(defaults)).equals(Set.of("", "nested", "café"))
                && names(manager.getExcludedDirectories(defaults)).equals(Set.of(".git", ".hg")), "FileSetManager SCM directory boundaries");
        check(names(manager.getIncludedFiles(fileSet(root, false, List.of("**"), List.of()))).equals(all),
                "FileSetManager explicit disabled defaults retain all four ignore files and SCM contents");
        check(names(scanner(root, List.of("**"), IGNORE_PATTERNS).getIncludedFiles()).equals(ordinary)
                && names(manager.getIncludedFiles(fileSet(root, true, List.of("**"), IGNORE_PATTERNS))).equals(ordinary),
                "explicit four-name exclusions restore baseline selection");
        Set<String> customExpected = Set.of("nested/keep.txt", "café/你好.txt");
        check(names(scanner(root, List.of("nested/**", "café/**"), List.of("**/*.tmp")).getIncludedFiles()).equals(customExpected)
                && names(manager.getIncludedFiles(fileSet(root, true, List.of("nested/**", "café/**"), List.of("**/*.tmp")))).equals(customExpected),
                "scanner and FileSetManager custom pattern Unicode selection");
        System.out.println("JAC_ANTRUN_SCANNER\t" + variant + "\tdefaultIgnoreFilesIncluded="
                + (variant.equals("candidate") ? 4 : 0) + "\texplicitIgnoreFilesIncluded=0\tscmFilesIncluded=0");
    }

    private static void directCopyAndClose(Path work) throws Exception {
        origin(FileUtils.class.getName(), "plexus-utils");
        origin(IOUtil.class.getName(), "plexus-utils");
        Path root = Files.createDirectory(work.resolve("direct-api"));
        Path source = Files.createDirectory(root.resolve("source"));
        write(source, "nested/café.txt", "bounded copy café & <value>\n");
        Path original = source.resolve("nested/café.txt");
        Path copy = root.resolve("single.txt");
        FileUtils.copyFile(original.toFile(), copy.toFile());
        check(Arrays.equals(Files.readAllBytes(original), Files.readAllBytes(copy)), "direct API copyFile used by instrument consumer");
        Path restored = root.resolve("restored");
        FileUtils.copyDirectoryStructure(source.toFile(), restored.toFile());
        check(Arrays.equals(Files.readAllBytes(original), Files.readAllBytes(restored.resolve("nested/café.txt"))),
                "direct API copyDirectoryStructure used by restore consumer");
        int[] closed = new int[2];
        InputStream input = new ByteArrayInputStream(new byte[] {1}) {
            @Override public void close() throws IOException { closed[0]++; throw new IOException("bounded close fixture"); }
        };
        OutputStream output = new ByteArrayOutputStream() {
            @Override public void close() throws IOException { closed[1]++; throw new IOException("bounded close fixture"); }
        };
        IOUtil.close(input); IOUtil.close(output);
        IOUtil.close((InputStream) null); IOUtil.close((OutputStream) null);
        check(Arrays.equals(closed, new int[] {1, 1}), "direct API IOUtil close overloads suppress bounded IOException");
        System.out.println("JAC_ANTRUN_DIRECT_API\tcopyFile\tcopyDirectoryStructure\tcloseInput\tcloseOutput");
    }

    private static void antConsumers(Path work) throws Exception {
        origin(VersionMapper.class.getName(), "maven-antrun-plugin");
        origin(AttachArtifactTask.class.getName(), "maven-antrun-plugin");
        origin(MavenAntRunProject.class.getName(), "maven-antrun-plugin");
        origin(Project.class.getName(), "ant");
        origin(MavenProject.class.getName(), "maven-core");
        origin(MavenProjectHelper.class.getName(), "maven-core");
        origin(DefaultArtifactHandler.class.getName(), "maven-core");
        origin(DefaultArtifact.class.getName(), "maven-artifact");
        VersionMapper mapper = new VersionMapper();
        mapper.setFrom("1.2" + File.pathSeparator + "1.2.3" + File.pathSeparator + "2.0");
        String path = "café" + File.separator + "artifact-1.2.3-tests.jar";
        check(Arrays.equals(mapper.mapFileName(path), new String[] {"café" + File.separator + "artifact-tests.jar"}),
                "AntRun actual VersionMapper longest matching version and Unicode path");
        mapper.setTo("flatten");
        check(Arrays.equals(mapper.mapFileName(path), new String[] {"artifact-tests.jar"})
                && Arrays.equals(mapper.mapFileName("unchanged.jar"), new String[] {"unchanged.jar"}), "AntRun actual VersionMapper flatten and unmatched filename");

        Path attachment = work.resolve("bounded-attachment.txt");
        write(work, "bounded-attachment.txt", "bounded attachment café\n");
        MavenProject maven = new MavenProject();
        maven.setGroupId("fixture"); maven.setArtifactId("bounded"); maven.setVersion("1");
        maven.setArtifact(new DefaultArtifact("fixture", "bounded", "1", "compile", "jar", null, new DefaultArtifactHandler("jar")));
        int[] calls = {0};
        MavenProjectHelper helper = new MavenProjectHelper() {
            @Override public void attachArtifact(MavenProject project, String type, String classifier, File file) {
                check(project == maven && "txt".equals(type) && "fixture".equals(classifier)
                        && file.equals(attachment.toFile()), "AntRun actual attachment inferred extension and bounded target");
                check(calls[0]++ == 0, "one in-memory attachment");
                Artifact artifact = new DefaultArtifact("fixture", "bounded", "1", "compile", type, classifier, new DefaultArtifactHandler(type));
                artifact.setFile(file);
                project.addAttachedArtifact(artifact);
            }
            @Override public void attachArtifact(MavenProject project, File file, String classifier) { throw new AssertionError("unexpected helper overload"); }
            @Override public void attachArtifact(MavenProject project, String type, File file) { throw new AssertionError("unexpected helper overload"); }
            @Override public void addResource(MavenProject project, String directory, List<String> includes, List<String> excludes) { throw new AssertionError("unexpected resource mutation"); }
            @Override public void addTestResource(MavenProject project, String directory, List<String> includes, List<String> excludes) { throw new AssertionError("unexpected test resource mutation"); }
        };
        Project ant = new Project();
        ant.addReference(AntRunMojo.DEFAULT_MAVEN_PROJECT_REF_REFID, new MavenAntRunProject(maven));
        ant.addReference(AntRunMojo.DEFAULT_MAVEN_PROJECT_HELPER_REFID, helper);
        AttachArtifactTask task = new AttachArtifactTask();
        task.setProject(ant); task.setFile(attachment.toFile()); task.setClassifier("fixture"); task.execute();
        check(calls[0] == 1 && maven.getAttachedArtifacts().size() == 1
                && maven.getAttachedArtifacts().get(0).getFile().equals(attachment.toFile()), "attachment recorded only in in-memory MavenProject");
        System.out.println("JAC_ANTRUN_ANT_CONSUMERS\tVersionMapper\tAttachArtifactTask\tattachmentType=txt\tattachments=1");
    }

    private static void zip(Path path, String name, byte[] bytes) throws Exception {
        check(name.length() < 4096 && bytes.length <= 128, "bounded single-entry ZIP input");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
            ZipEntry entry = new ZipEntry(name); entry.setTime(0);
            output.putNextEntry(entry); output.write(bytes); output.closeEntry();
        }
        check(Files.size(path) <= 8192, "bounded ZIP archive");
    }

    private static void expand(Path archive, Path destination) throws Exception {
        Expand expand = new Expand();
        expand.setSrc(archive.toFile()); expand.setDest(destination.toFile()); expand.setOverwrite(false);
        expand.execute();
    }

    // A direct upstream API regression only. No plugin member inventory references Expand.
    // Both the extraction tree and the sibling target are created inside this owned output.
    private static void expansionContainment(Path work, String variant) throws Exception {
        origin(Expand.class.getName(), "plexus-utils");
        Path root = Files.createDirectory(work.resolve("expand-api"));
        byte[] payload = "bounded containment fixture café\n".getBytes(StandardCharsets.UTF_8);
        Path validArchive = root.resolve("valid.zip");
        zip(validArchive, "nested/café.txt", payload);
        Path valid = Files.createDirectory(root.resolve("valid"));
        expand(validArchive, valid);
        check(Arrays.equals(payload, Files.readAllBytes(valid.resolve("nested/café.txt"))), "Expand valid Unicode ZIP extraction");
        check(File.separatorChar == '/', "bounded absolute ZIP fixture requires Linux path semantics");
        for (String kind : List.of("relative", "absolute")) {
            Path caseRoot = Files.createDirectory(root.resolve(kind));
            // The shared string prefix exposes the old startsWith(String) check without escaping work.
            Path destination = Files.createDirectory(caseRoot.resolve("sibling"));
            Path sibling = caseRoot.resolve("sibling.txt");
            String entry = kind.equals("relative") ? "../sibling.txt" : sibling.toString();
            Path resolved = destination.resolve(entry).normalize();
            check(resolved.equals(sibling) && resolved.startsWith(work) && !resolved.startsWith(destination)
                    && !Files.exists(sibling, LinkOption.NOFOLLOW_LINKS), "bounded owned " + kind + " sibling target");
            // Expand delegates to resolveFile, whose absolute branch uses new File(entry).
            // Check the actual library resolver before extraction as well as the intended path.
            check(FileUtils.resolveFile(destination.toFile(), entry).toPath().equals(sibling),
                    "Expand actual resolver stays at owned " + kind + " sibling target");
            Path archive = caseRoot.resolve("fixture.zip");
            zip(archive, entry, payload);
            Exception failure = null;
            try { expand(archive, destination); } catch (Exception caught) { failure = caught; }
            if (variant.equals("candidate")) {
                boolean containment = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof IOException && cause.getMessage() != null
                            && cause.getMessage().contains("outside the target directory")) containment = true;
                }
                check(containment && !Files.exists(sibling, LinkOption.NOFOLLOW_LINKS),
                        "Expand candidate rejects " + kind + " sibling before creating output");
            } else {
                check(failure == null && Files.isRegularFile(sibling, LinkOption.NOFOLLOW_LINKS)
                        && Arrays.equals(payload, Files.readAllBytes(sibling)), "Expand baseline exhibits bounded " + kind + " string-prefix behavior");
            }
            try (var outputs = Files.walk(caseRoot)) {
                Set<Path> found = new HashSet<>(outputs.toList());
                Set<Path> expected = new HashSet<>(Set.of(caseRoot, destination, archive));
                if (!variant.equals("candidate")) expected.add(sibling);
                check(found.equals(expected), "Expand exact bounded " + kind + " output tree");
            }
            System.out.println("JAC_ANTRUN_EXPAND\t" + variant + "\t" + kind + "\t"
                    + (variant.equals("candidate") ? "rejected-no-sibling" : "baseline-owned-sibling") + "\tdirect-api-only");
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 4 || (args.length == 5 && args[4].equals("missing-member")), "identity member variant output and optional negative arguments");
        check(Set.of("jacoco-baseline", "antrun-baseline", "candidate").contains(args[2]), "known owner variant");
        identities(Path.of(args[0]), args[2]);
        linkage(Path.of(args[1]), args[2], args.length == 5);
        Path requested = Path.of(args[3]).toAbsolutePath().normalize();
        Path work = requested.toRealPath();
        check(requested.equals(work) && Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS), "real disposable output directory without symlink redirection");
        try (var contents = Files.list(work)) { check(contents.findAny().isEmpty(), "empty disposable output directory"); }
        selection(work, args[2]);
        directCopyAndClose(work);
        antConsumers(work);
        expansionContainment(work, args[2]);
        System.out.println("JAC_ANTRUN_COMPLETE\t" + args[2] + "\t" + checks + "\tapi-replay-only");
    }
}
