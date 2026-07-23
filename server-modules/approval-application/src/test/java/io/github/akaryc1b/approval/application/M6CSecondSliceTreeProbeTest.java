package io.github.akaryc1b.approval.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class M6CSecondSliceTreeProbeTest {

    @Test
    void reportsCheckedOutTreeForAtomicGitObjectCommit() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD^{tree}")
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            .trim();
        assertEquals(0, process.waitFor(), output);
        System.out.println("M6C_SECOND_BASE_TREE=" + output);
    }
}
