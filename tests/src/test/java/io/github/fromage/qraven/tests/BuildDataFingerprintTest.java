package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.QravenCli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pom change doesn't touch any source, so the timestamps can't tell an incremental build that a module has to be
 * rebuilt. Each module records a fingerprint of its build data when it is built instead.
 * <p>
 * The project is made up here, with its own coordinates, because building installs it in ~/.m2.
 */
class BuildDataFingerprintTest {

    private static final String GROUP = "com.test.fingerprint";

    @AfterEach
    void removeInstalledArtifacts() throws IOException {
        Path installed = Path.of(System.getProperty("user.home"), ".m2/repository/com/test/fingerprint");
        if (Files.isDirectory(installed)) {
            try (var walk = Files.walk(installed)) {
                for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
    }

    private record Run(int exit, String output) {
    }

    private static Run cli(Path project, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), QravenCli.class.getName(),
                "-p", project.toString(), "-Dno-progress"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes()).replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "");
        return new Run(p.waitFor(), output);
    }

    private static void module(Path root, String name, String dependsOn) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>%s</groupId>
                    <artifactId>fp-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>fp-%s</artifactId>
                  %s
                </project>
                """.formatted(GROUP, name, dependsOn == null ? "" : """
                <dependencies>
                  <dependency>
                    <groupId>%s</groupId>
                    <artifactId>fp-%s</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>""".formatted(GROUP, dependsOn)));
        Path src = Files.createDirectories(dir.resolve("src/main/java/fp"));
        Files.writeString(src.resolve(name.substring(0, 1).toUpperCase() + name.substring(1) + ".java"),
                "package fp; public class " + name.substring(0, 1).toUpperCase() + name.substring(1) + " {}");
    }

    private static Path project(Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>fp-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>base</module><module>lib</module><module>other</module></modules>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
                """.formatted(GROUP));
        module(root, "base", null);
        module(root, "lib", "base");
        module(root, "other", null);
        return root;
    }

    private static void addDependency(Path pom) throws IOException {
        Files.writeString(pom, Files.readString(pom).replace("</project>", """
                <dependencies>
                  <dependency>
                    <groupId>org.opentest4j</groupId>
                    <artifactId>opentest4j</artifactId>
                    <version>1.3.0</version>
                  </dependency>
                </dependencies>
                </project>"""));
    }

    /** The build's output goes to build.log while it runs, so the explanations are read from there. */
    private static String buildLog(Path project) throws IOException {
        return Files.readString(project.resolve("target/qraven/build.log"));
    }

    private static Run build(Path project) throws Exception {
        Run run = cli(project, "-i");
        assertThat(run.exit()).as(run.output()).isZero();
        return run;
    }

    @Test
    void nothingChanged_rebuildsNothing(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        Run first = build(project);
        // 4 modules: the parent pom, base, lib and other
        assertThat(first.output()).contains("4 succeeded");

        Run second = build(project);

        assertThat(second.output()).contains("4 succeeded (4 up-to-date)");
    }

    @Test
    void aPomChange_rebuildsThatModuleOnly(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        build(project);
        Thread.sleep(1100);
        addDependency(project.resolve("other/pom.xml"));

        Run run = build(project);

        assertThat(buildLog(project)).contains("[incremental] [fp-other] not up-to-date: build data changed");
        // the parent pom changed nothing for the others: base, lib and the parent stay as they were
        assertThat(run.output()).contains("4 succeeded (3 up-to-date)");
    }

    @Test
    void aPomChange_rebuildsTheDependentsToo(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        build(project);
        Thread.sleep(1100);
        addDependency(project.resolve("base/pom.xml"));

        Run run = build(project);

        assertThat(buildLog(project)).contains("[incremental] [fp-base] not up-to-date: build data changed")
                .contains("[incremental] [fp-lib] not up-to-date: dependency rebuilt: fp-base");
        assertThat(run.output()).contains("4 succeeded (2 up-to-date)");
    }

    @Test
    void aModuleWithoutARecordOfItsBuildData_isRebuilt(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        build(project);
        assertThat(project.resolve("lib/target/qraven-build-data")).exists();
        Files.delete(project.resolve("lib/target/qraven-build-data"));

        Run run = build(project);

        assertThat(buildLog(project)).contains("[incremental] [fp-lib] not up-to-date: no record of the build data");
        assertThat(run.output()).contains("4 succeeded (3 up-to-date)");
    }

    @Test
    void aRevertedChange_isRebuiltAgainAndThenSettles(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        build(project);
        Path pom = project.resolve("other/pom.xml");
        String original = Files.readString(pom);
        Thread.sleep(1100);
        addDependency(pom);
        build(project);
        Thread.sleep(1100);
        Files.writeString(pom, original);

        Run reverted = build(project);

        assertThat(buildLog(project)).contains("[incremental] [fp-other] not up-to-date: build data changed");
        assertThat(build(project).output()).contains("4 succeeded (4 up-to-date)");
    }

    @Test
    void aParentPomChange_rebuildsTheModulesThatInheritIt(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        build(project);
        Thread.sleep(1100);
        // the children's own poms are untouched, but their compiler arguments change
        Path parent = project.resolve("pom.xml");
        Files.writeString(parent, Files.readString(parent).replace(
                "<maven.compiler.release>17</maven.compiler.release>",
                "<maven.compiler.release>11</maven.compiler.release>"));

        Run run = build(project);

        assertThat(buildLog(project)).contains("[incremental] [fp-base] not up-to-date: build data changed")
                .contains("[incremental] [fp-other] not up-to-date: build data changed");
        assertThat(run.output()).contains("4 succeeded, 0 failed");
        assertThat(run.output()).doesNotContain("up-to-date)");
    }
}
