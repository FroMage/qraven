package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.QravenCli;
import io.github.fromage.qraven.hardcoded.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generation rewrites one progress line in place, with no newline, so a message printed in the meantime must clear it
 * first or it ends up glued to the end of it ("Scanning modules... 352 found (x)Resolved Kotlin compiler plugin ...").
 */
class GenerationOutputTest {

    private static final String ERASE = Status.ERASE_LINE;

    @Test
    void aStatusLineClearsTheProgressLineBeforeItself() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(both, true, StandardCharsets.UTF_8);
        System.setOut(captured);
        System.setErr(captured);
        try {
            System.err.print(ERASE + "  Scanning modules... 3 found (x)");
            Status.line("Resolved something");
            Status.warning("WARNING: something else");
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertThat(both.toString(StandardCharsets.UTF_8)).isEqualTo(
                ERASE + "  Scanning modules... 3 found (x)" + ERASE + "Resolved something\n"
                        + ERASE + "WARNING: something else\n");
    }

    private static Path copy(String project, Path to) throws IOException {
        Path from = Path.of(System.getProperty("user.dir")).getParent().resolve("test-projects").resolve(project);
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> !f.toString().contains("/target"))::iterator) {
                Path target = to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        return to;
    }

    @Test
    void messagesPrintedWhileGeneratingStartOnALineOfTheirOwn(@TempDir Path tmp) throws Exception {
        // the ANTLR project makes generation look for the ANTLR tool, and it reports what it finds as it goes
        Path project = copy("antlr-project", tmp.resolve("project"));
        List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), QravenCli.class.getName(),
                "-p", project.toString(), "-nb", "-Dno-progress");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String raw = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(raw).isZero();

        Matcher resolved = Pattern.compile("(Resolved (protoc|ANTLR4|Eclipse JDT|ktfmt|Kotlin compiler plugin)|WARNING: could not)").matcher(raw);
        int found = 0;
        while (resolved.find()) {
            found++;
            String before = raw.substring(Math.max(0, resolved.start() - ERASE.length()), resolved.start());
            assertThat(before).as("what precedes: %s", raw.substring(Math.max(0, resolved.start() - 60),
                    resolved.end() + 20).replace("\r", "\\r").replace("\u001b", "\\e")).isEqualTo(ERASE);
        }
        assertThat(found).as(raw).isGreaterThan(0);
    }
}
