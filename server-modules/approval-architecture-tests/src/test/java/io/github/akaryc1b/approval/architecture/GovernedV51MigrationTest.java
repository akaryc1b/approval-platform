package io.github.akaryc1b.approval.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GovernedV51MigrationTest {
    @TempDir Path root;

    @Test void exactOwnerIsExcludedAndHistoricalFilesRetained() throws Exception {
        Path owned = copyOwner();
        Path old = write("server-modules/sample/src/main/resources/db/migration/V49__historical.sql", "old");
        assertEquals(List.of(old), GovernedV51Migration.historical(root, List.of(old, owned)));
        assertEquals(List.of(old), GovernedV51Migration.historical(root, List.of(old)));
        assertThrows(AssertionError.class, () -> GovernedV51Migration.historical(root, List.of(owned, owned)));
    }

    @Test void unknownVersionsWrongOwnerAndTamperFailClosed() throws Exception {
        Path owned = copyOwner();
        for (String name : List.of("V51__unknown.sql", "V52__unknown.sql")) {
            Path unknown = write("server-modules/sample/src/main/resources/db/migration/" + name, "unknown");
            assertThrows(AssertionError.class, () -> GovernedV51Migration.historical(root, List.of(owned, unknown)));
            Files.delete(unknown);
        }
        Path wrongOwner = write("other/" + GovernedV51Migration.OWNER, Files.readString(owned));
        assertThrows(AssertionError.class, () -> GovernedV51Migration.historical(root, List.of(wrongOwner)));
        Files.delete(wrongOwner);
        Files.writeString(owned, Files.readString(owned) + "\n-- tampered\n");
        assertThrows(AssertionError.class, () -> GovernedV51Migration.historical(root, List.of(owned)));
    }

    @Test void allSourceLocationsAreCheckedWithoutNarrowingOriginalScope() throws Exception {
        Path owned = copyOwner();
        Path ordinary = write("misc/original-input.txt", "preserved");
        assertEquals(List.of(ordinary), GovernedV51Migration.historical(root, List.of(ordinary, owned)));
        for (String location : List.of("server-modules/approval-persistence-jdbc/src/main/resources/m6f/db/migration/",
            "server-modules/approval-persistence-jdbc/src/main/resources/other/",
            "server-modules/other/src/main/resources/db/migration/", "server-modules/other/src/main/java/db/migration/")) {
            for (String name : List.of("V51__unknown.sql", "V52__unknown.sql", "V52__Unknown.java")) {
                Path unknown = write(location + name, "unknown");
                assertThrows(AssertionError.class, () -> GovernedV51Migration.historical(root, List.of(owned)));
                Files.delete(unknown);
            }
        }
        Path generated = write("target/other/src/main/resources/V52__generated.sql", "generated");
        assertEquals(List.of(generated), GovernedV51Migration.historical(root, List.of(generated, owned)));
        write(".git/other/src/main/resources/V52__object.sql", "metadata");
        assertEquals(List.of(ordinary), GovernedV51Migration.historical(root, List.of(ordinary, owned)));
        Files.delete(owned);
        assertThrows(AssertionError.class, () -> GovernedV51Migration.historical(root, List.of(ordinary)));
    }

    private Path copyOwner() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (repository != null && !Files.exists(repository.resolve(GovernedV51Migration.OWNER))) repository = repository.getParent();
        if (repository == null) throw new IllegalStateException("repository source not found");
        return write(GovernedV51Migration.OWNER, Files.readString(repository.resolve(GovernedV51Migration.OWNER)));
    }
    private Path write(String relative, String content) throws Exception {
        Path target = root.resolve(relative); Files.createDirectories(target.getParent());
        return Files.writeString(target, content);
    }
}
