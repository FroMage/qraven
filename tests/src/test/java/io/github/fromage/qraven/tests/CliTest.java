package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.QravenCli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CliTest {

    static final Path QRAVEN_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    private String captureOutput(String... args) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(capture);
        System.setOut(ps);
        System.setErr(ps);
        try {
            QravenCli.main(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return capture.toString();
    }

    @Test
    void help_showsUsage() throws Exception {
        String output = captureOutput("--help");
        assertThat(output).contains("qraven");
        assertThat(output).contains("--help");
        assertThat(output).contains("--projects");
        assertThat(output).contains("--also-make");
        assertThat(output).contains("--incremental");
    }

    @Test
    void version_showsVersion() throws Exception {
        String output = captureOutput("--version");
        assertThat(output).contains("qraven");
    }

    @Test
    void noBuild_generatesWithoutRunning() throws Exception {
        String output = captureOutput(
                "-p", QRAVEN_ROOT.resolve("test-projects/simple-jar").toString(),
                "--no-build");
        assertThat(output).contains("build.jar");
        assertThat(output).contains("Qraven Build Tool");
    }

    @Test
    void noBuild_showsProjectDir() throws Exception {
        Path projectDir = QRAVEN_ROOT.resolve("test-projects/simple-jar");
        String output = captureOutput("-p", projectDir.toString(), "--no-build");
        assertThat(output).contains("Project:");
        assertThat(output).contains(projectDir.toString());
    }

    @Test
    void noBuild_showsThreadCount() throws Exception {
        String output = captureOutput(
                "-p", QRAVEN_ROOT.resolve("test-projects/simple-jar").toString(),
                "-t", "4", "--no-build");
        assertThat(output).contains("Threads:  4");
    }

    @Test
    void forceGenerate_regenerates() throws Exception {
        String output = captureOutput(
                "-p", QRAVEN_ROOT.resolve("test-projects/simple-jar").toString(),
                "--force-generate", "--no-build");
        assertThat(output).contains("Regenerating: forced");
    }

    @Test
    void failure_suggestsReportingABug(@TempDir Path dir) throws Exception {
        // a module that does not compile; System.exit means a subprocess is needed
        Files.copy(QRAVEN_ROOT.resolve("test-projects/simple-jar/pom.xml"), dir.resolve("pom.xml"));
        Path src = Files.createDirectories(dir.resolve("src/main/java/com/test"));
        Files.writeString(src.resolve("Broken.java"), "package com.test; class Broken { this does not compile }");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), QravenCli.class.getName(),
                "-p", dir.toString(), "-Dno-progress")
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(output).isNotZero();
        assertThat(output).contains("qraven --report-bug");
    }

    @Test
    void success_doesNotSuggestReportingABug() throws Exception {
        assertThat(captureOutput("--help")).doesNotContain("should not have failed");
    }

    private record CliResult(int exit, String output) {
    }

    private static CliResult runCli(Path dir, String... extraArgs) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), QravenCli.class.getName(),
                "-p", dir.toString(), "-Dno-progress"));
        cmd.addAll(List.of(extraArgs));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes()).replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "");
        return new CliResult(p.waitFor(), output);
    }

    private static void writePom(Path dir, String artifactId, String modulesAndDeps) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.test</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <packaging>%s</packaging>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  %s
                </project>
                """.formatted(artifactId, modulesAndDeps.contains("<modules>") ? "pom" : "jar", modulesAndDeps));
    }

    @Test
    void missingDependency_failsGenerationWithAClearErrorAndBuildsNothing(@TempDir Path dir) throws Exception {
        writePom(dir, "needs-missing", """
                <dependencies><dependency>
                  <groupId>com.test.nonexistent</groupId><artifactId>missing</artifactId><version>1.2.3</version>
                </dependency></dependencies>""");
        Path src = Files.createDirectories(dir.resolve("src/main/java/com/test"));
        Files.writeString(src.resolve("A.java"), "package com.test; class A {}");

        CliResult r = runCli(dir);

        assertThat(r.exit()).as(r.output()).isNotZero();
        assertThat(r.output()).contains("1 dependency could not be resolved")
                .contains("com.test.nonexistent:missing:jar:1.2.3")
                .contains("needed by 1 module: needs-missing")
                .contains("settings.xml")
                .doesNotContain("Running build").doesNotContain("Building ");
        assertThat(dir.resolve("target/qraven/generate.log")).content()
                .contains("ERROR: 1 dependency could not be resolved")
                .contains("com.test.nonexistent:missing:jar:1.2.3");
        assertThat(dir.resolve("target/qraven/build.jar")).doesNotExist();
    }

    @Test
    void cascadeFailures_areListedOnceNotOneByOne(@TempDir Path dir) throws Exception {
        writePom(dir, "parent", "<modules><module>broken</module><module>mid</module><module>top</module></modules>");
        writePom(dir.resolve("broken"), "broken", "");
        Path src = Files.createDirectories(dir.resolve("broken/src/main/java/com/test"));
        Files.writeString(src.resolve("Broken.java"), "package com.test; class Broken { this does not compile }");
        writePom(dir.resolve("mid"), "mid", dependencyOn("broken"));
        writePom(dir.resolve("top"), "top", dependencyOn("mid"));

        CliResult r = runCli(dir);

        assertThat(r.exit()).as(r.output()).isNotZero();
        assertThat(r.output()).contains("Direct failures: broken")
                .contains("[broken] FAILED")
                .contains("Cascade failures (2)")
                .doesNotContain("SKIPPED: dependency failed");
        assertThat(dir.resolve("target/qraven/build.log")).content()
                .contains("Direct failures: broken").contains("Cascade failures (2)")
                .doesNotContain("SKIPPED: dependency failed");
    }

    @Test
    void manyCascadeFailures_areTruncatedOnTheTerminalButFullInTheLog(@TempDir Path dir) throws Exception {
        int dependents = 15;
        StringBuilder modules = new StringBuilder("<modules><module>broken</module>");
        for (int i = 0; i < dependents; i++) {
            modules.append("<module>dep").append(i).append("</module>");
        }
        writePom(dir, "parent", modules.append("</modules>").toString());
        writePom(dir.resolve("broken"), "broken", "");
        Path src = Files.createDirectories(dir.resolve("broken/src/main/java/com/test"));
        Files.writeString(src.resolve("Broken.java"), "package com.test; class Broken { this does not compile }");
        for (int i = 0; i < dependents; i++) {
            writePom(dir.resolve("dep" + i), "dep" + i, dependencyOn("broken"));
        }

        CliResult r = runCli(dir);

        assertThat(r.exit()).as(r.output()).isNotZero();
        assertThat(r.output()).contains("Cascade failures (15): ").contains("... and 5 more (full list in build.log)");
        String log = Files.readString(dir.resolve("target/qraven/build.log"));
        assertThat(log).contains("Cascade failures (15): ").doesNotContain("more (full list");
        for (int i = 0; i < dependents; i++) {
            assertThat(log).contains("dep" + i);
        }
    }

    private static String dependencyOn(String artifactId) {
        return "<dependencies><dependency><groupId>com.test</groupId><artifactId>" + artifactId
                + "</artifactId><version>1.0.0</version></dependency></dependencies>";
    }
}
