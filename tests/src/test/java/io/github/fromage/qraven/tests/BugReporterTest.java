package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.BugReporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class BugReporterTest {

    private record Result(int exit, String output, List<String> submitted) {
    }

    private static Result run(Path project, String input) throws Exception {
        Path outputDir = project.resolve("target/qraven");
        Files.createDirectories(outputDir.resolve("build-src"));
        Files.writeString(outputDir.resolve("build.log"), "line\n=== BUILD FAILURE SUMMARY ===\nDirect failures: core\n");
        Files.writeString(outputDir.resolve("build-src/Build.java"), "class Build {}");
        Files.write(outputDir.resolve("state.cbor"), new byte[] { (byte) 0x82, 0x40, 0x00 });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<String> submitted = new ArrayList<>();
        int exit = new BugReporter(project, outputDir, new BufferedReader(new StringReader(input)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                (title, body, zip) -> {
                    submitted.add(title);
                    submitted.add(body);
                    submitted.add(zip.toString());
                    return "submitted";
                }, "test-version", List.of("--report-bug")).run();
        return new Result(exit, bytes.toString(StandardCharsets.UTF_8), submitted);
    }

    private static Set<String> zipEntries(Path dir) throws IOException {
        try (var files = Files.list(dir.resolve("target/qraven"))) {
            Path zip = files.filter(f -> f.toString().endsWith(".zip")).findFirst().orElseThrow();
            try (ZipFile z = new ZipFile(zip.toFile())) {
                return z.stream().map(e -> e.getName()).collect(java.util.stream.Collectors.toSet());
            }
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false", "-c", "protocol.file.allow=always"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(String.join(" ", cmd) + "\n" + out).isZero();
    }

    /** A clone of a "remote" repository, so there is an upstream. */
    private static Path clonedProject(Path tmp) throws Exception {
        Path remote = tmp.resolve("remote.git");
        Path seed = tmp.resolve("seed");
        Files.createDirectories(seed);
        git(seed, "init", "-q", "-b", "main");
        Files.writeString(seed.resolve("pom.xml"), "<project/>");
        Files.writeString(seed.resolve("Foo.java"), "class Foo {}");
        git(seed, "add", ".");
        git(seed, "commit", "-q", "-m", "init");
        git(tmp, "clone", "-q", "--bare", seed.toString(), remote.toString());
        Path project = tmp.resolve("project");
        git(tmp, "clone", "-q", remote.toString(), project.toString());
        return project;
    }

    @Test
    void declining_createsNothing(@TempDir Path tmp) throws Exception {
        Result r = run(tmp, "n\n");
        assertThat(r.output()).contains("PRIVACY").contains("Cancelled");
        assertThat(r.submitted()).isEmpty();
        assertThat(java.util.Arrays.stream(tmp.resolve("target/qraven").toFile().list())
                .filter(n -> n.endsWith(".zip"))).isEmpty();
    }

    @Test
    void decliningTheZip_createsNothingAndDoesNotSubmit(@TempDir Path tmp) throws Exception {
        Result r = run(tmp, "y\nit broke\n\nn\n");
        assertThat(r.output()).contains("will contain").contains("Cancelled, nothing was created");
        assertThat(r.submitted()).isEmpty();
        assertThat(java.util.Arrays.stream(tmp.resolve("target/qraven").toFile().list())
                .filter(n -> n.endsWith(".zip"))).isEmpty();
    }

    @Test
    void fallbackMode_includesPomsListingAndLogs_butNoSources(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("sub/src/main/java"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Files.writeString(tmp.resolve("sub/pom.xml"), "<project/>");
        Files.writeString(tmp.resolve("sub/src/main/java/Secret.java"), "class Secret {}");
        Files.writeString(tmp.resolve("sub/.env"), "TOKEN=abc");
        Files.writeString(tmp.resolve("settings.xml"), "<settings/>");

        Result r = run(tmp, "y\nBuild fails on sub\nwith an error\n\ny\ny\n");

        assertThat(r.exit()).isZero();
        Set<String> entries = zipEntries(tmp);
        assertThat(entries).contains("poms/pom.xml", "poms/sub/pom.xml", "file-listing.txt",
                "logs/build.log", "generated/build-src/Build.java", "generated/state.cbor", "environment.txt", "description.txt");
        assertThat(entries).noneMatch(e -> e.contains("Secret.java") || e.contains(".env") || e.contains("settings.xml"));
        // the listing names files but must not leak sensitive ones
        try (var files = Files.list(tmp.resolve("target/qraven"))) {
            Path zip = files.filter(f -> f.toString().endsWith(".zip")).findFirst().orElseThrow();
            try (ZipFile z = new ZipFile(zip.toFile())) {
                String listing = new String(z.getInputStream(z.getEntry("file-listing.txt")).readAllBytes());
                assertThat(listing).contains("sub/src/main/java/Secret.java").doesNotContain(".env");
            }
        }
        assertThat(r.output()).contains("source code is NOT included");
        assertThat(r.submitted().get(0)).isEqualTo("Build problem: Build fails on sub");
        assertThat(r.submitted().get(1)).contains("Direct failures: core").contains("test-version");
    }

    @Test
    void gitMode_recordsCommitAndOnlyIncludesPatchWhenAccepted(@TempDir Path tmp) throws Exception {
        Path project = clonedProject(tmp);
        Files.writeString(project.resolve("Foo.java"), "class Foo { int changed; }");
        Files.writeString(project.resolve("New.java"), "class New {}");
        Files.writeString(project.resolve(".env"), "TOKEN=abc");

        // decline the patch
        Result declined = run(project, "y\nbroken\n\nn\ny\ny\n");
        Set<String> entries = zipEntries(project);
        assertThat(entries).contains("git.txt").doesNotContain("poms/pom.xml", "file-listing.txt");
        assertThat(entries).noneMatch(e -> e.startsWith("changes/"));
        assertThat(declined.output()).contains("Foo.java").contains("New.java").contains("NOT included");
        assertThat(declined.submitted().get(1)).contains("base commit");
    }

    @Test
    void gitMode_withPatch_includesChangesButNotSensitiveFiles(@TempDir Path tmp) throws Exception {
        Path project = clonedProject(tmp);
        Files.writeString(project.resolve("Foo.java"), "class Foo { int changed; }");
        Files.writeString(project.resolve("New.java"), "class New {}");
        Files.writeString(project.resolve(".env"), "TOKEN=abc");

        run(project, "y\nbroken\n\ny\ny\ny\n");
        Set<String> entries = zipEntries(project);
        assertThat(entries).contains("changes/changes.patch", "changes/untracked/New.java");
        assertThat(entries).noneMatch(e -> e.contains(".env"));
        try (var files = Files.list(project.resolve("target/qraven"))) {
            Path zip = files.filter(f -> f.toString().endsWith(".zip")).findFirst().orElseThrow();
            try (ZipFile z = new ZipFile(zip.toFile())) {
                String patch = new String(z.getInputStream(z.getEntry("changes/changes.patch")).readAllBytes());
                assertThat(patch).contains("int changed").doesNotContain("TOKEN");
            }
        }
    }

    @Test
    void gitMode_unpushedCommitIsInThePatch(@TempDir Path tmp) throws Exception {
        Path project = clonedProject(tmp);
        Files.writeString(project.resolve("Foo.java"), "class Foo { int committed; }");
        git(project, "commit", "-q", "-am", "local only");

        run(project, "y\nbroken\n\ny\ny\ny\n");
        try (var files = Files.list(project.resolve("target/qraven"))) {
            Path zip = files.filter(f -> f.toString().endsWith(".zip")).findFirst().orElseThrow();
            try (ZipFile z = new ZipFile(zip.toFile())) {
                assertThat(new String(z.getInputStream(z.getEntry("changes/changes.patch")).readAllBytes()))
                        .contains("int committed");
            }
        }
    }

    @Test
    void decliningTheIssue_keepsTheZipButDoesNotSubmit(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Result r = run(tmp, "y\nbroken\n\ny\nn\n");
        assertThat(r.submitted()).isEmpty();
        assertThat(zipEntries(tmp)).contains("poms/pom.xml");
        assertThat(r.output()).contains("Not creating the issue");
    }

    @Test
    void prefilledUrl_staysWithinLimitAndIsEncoded() {
        String url = BugReporter.prefilledUrl("Build problem: a & b", "x".repeat(50_000));
        assertThat(url.length()).isLessThan(8000);
        assertThat(url).startsWith("https://github.com/FroMage/qraven/issues/new?title=Build%20problem%3A%20a%20%26%20b");
        assertThat(url).contains("truncated");
    }
}
