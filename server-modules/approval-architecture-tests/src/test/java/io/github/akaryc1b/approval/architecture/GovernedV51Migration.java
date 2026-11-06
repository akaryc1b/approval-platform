package io.github.akaryc1b.approval.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Separately validates the precision fix before preserving older slices' migration assertions. */
final class GovernedV51Migration {
    static final String OWNER = "server-modules/approval-persistence-jdbc/src/main/resources/db/migration/"
        + "V51__preserve_migration_epoch_microsecond_precision.sql";
    static final String HASH = "d56d21e4a1eacf8701adfd8e689b60e3b25916cbff736a7e9052f53c1825d917";
    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.(sql|java)");
    private GovernedV51Migration() { }

    static List<Path> historical(Path root, Stream<Path> original) throws IOException {
        try (original) { return historical(root, original.toList()); }
    }

    static List<Path> historical(Path root, List<Path> original) throws IOException {
        List<Path> inventory = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = directory.getFileName().toString();
                return !directory.equals(root) && (name.equals("target") || name.equals(".git"))
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (attributes.isRegularFile() && (relative.contains("/src/main/resources/")
                    || relative.contains("/src/main/java/"))) inventory.add(path);
                return FileVisitResult.CONTINUE;
            }
        });
        validatedHistorical(root, inventory, true);
        return validatedHistorical(root, original, false);
    }

    private static List<Path> validatedHistorical(Path root, List<Path> migrations, boolean requireOwner) throws IOException {
        List<Path> historical = new ArrayList<>();
        int governed = 0;
        for (Path path : migrations) {
            var version = VERSION.matcher(path.getFileName().toString());
            if (version.matches() && Integer.parseInt(version.group(1)) >= 51
                && (requireOwner || OWNER.equals(root.relativize(path).toString().replace('\\', '/')))) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!OWNER.equals(relative) || !HASH.equals(sha256(Files.readAllBytes(path)))) {
                    throw new AssertionError("unknown or modified migration: " + relative);
                }
                governed++;
            } else {
                historical.add(path);
            }
        }
        if (governed > 1 || (requireOwner && governed != 1)) throw new AssertionError("expected exactly one owned V51 migration, found " + governed);
        return List.copyOf(historical);
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
