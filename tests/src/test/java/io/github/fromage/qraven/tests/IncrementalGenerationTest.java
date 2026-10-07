package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.QravenCli;
import io.github.fromage.qraven.hardcoded.StateStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The oracle for incremental generation: after any edit, what {@code --incremental} produces must be exactly what a
 * full regeneration produces. Each scenario generates a project, edits it, regenerates incrementally, snapshots the
 * result, regenerates everything in the same directory and snapshots again; the two must be identical.
 */
class IncrementalGenerationTest {

    private static final Path TEST_PROJECTS = Path.of(System.getProperty("user.dir")).getParent()
            .resolve("test-projects");

    private record Run(int exit, String output) {
    }

    private interface Edit {
        void apply(Path project) throws Exception;
    }

    // ---------------------------------------------------------------- running qraven

    private static Run cli(Path project, List<String> jvmArgs, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        cmd.addAll(jvmArgs);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), QravenCli.class.getName(),
                "-p", project.toString(), "-nb", "-Dno-progress"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes()).replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "");
        return new Run(p.waitFor(), output);
    }

    private static Run cli(Path project, String... args) throws Exception {
        return cli(project, List.of(), args);
    }

    private static Path copyProject(String name, Path to) throws IOException {
        Path from = TEST_PROJECTS.resolve(name);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().equals("target")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        return to;
    }

    /** Everything generation produces: the generated sources, the flattened poms and each module's stored data. */
    private static Map<String, String> snapshot(Path project) throws Exception {
        Map<String, String> files = new TreeMap<>();
        Path qraven = project.resolve("target/qraven");
        for (String dir : List.of("build-src", "bootstrap-src", "flattened-poms")) {
            Path root = qraven.resolve(dir);
            if (Files.isDirectory(root)) {
                try (var walk = Files.walk(root)) {
                    for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                        files.put(dir + "/" + root.relativize(f),
                                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(f))));
                    }
                }
            }
        }
        var state = StateStore.read(qraven.resolve("state.cbor"), null, null, null);
        assertThat(state).as("generation state is written").isPresent();
        for (int i = 0; i < state.get().size(); i++) {
            files.put("state/" + state.get().moduleIds().get(i), StateStore.toJson(state.get().module(i)));
        }
        return files;
    }

    private static void edit(Path pom, String from, String to) throws IOException {
        String text = Files.readString(pom);
        assertThat(text).as("the pom to edit contains %s", from).contains(from);
        Files.writeString(pom, text.replace(from, to));
    }

    private static final String OPENTEST4J = """
            <dependency>
                <groupId>org.opentest4j</groupId>
                <artifactId>opentest4j</artifactId>
                <version>1.3.0</version>
            </dependency>
            """;

    private static void addDependency(Path pom, String dependency) throws IOException {
        String text = Files.readString(pom);
        if (text.contains("<dependencies>") && !text.contains("<dependencyManagement>")) {
            Files.writeString(pom, text.replaceFirst("<dependencies>", "<dependencies>\n" + dependency));
        } else if (text.contains("</dependencyManagement>")) {
            Files.writeString(pom, text.replace("</dependencyManagement>",
                    "</dependencyManagement>\n<dependencies>\n" + dependency + "</dependencies>"));
        } else {
            Files.writeString(pom, text.replace("</project>", "<dependencies>\n" + dependency + "</dependencies>\n</project>"));
        }
    }

    /**
     * Generates, edits, regenerates incrementally and checks the result against a full regeneration.
     * Returns the output of the incremental run.
     */
    private static String check(String projectName, Path tmp, Edit edit) throws Exception {
        Path project = copyProject(projectName, tmp.resolve("project"));
        Run first = cli(project);
        assertThat(first.exit()).as(first.output()).isZero();

        // poms are compared with the time build.jar was written, so make sure the edit is newer
        Thread.sleep(1100);
        edit.apply(project);

        Run incremental = cli(project, "-i");
        assertThat(incremental.exit()).as(incremental.output()).isZero();
        Map<String, String> incrementalResult = snapshot(project);

        Run full = cli(project, "-fg");
        assertThat(full.exit()).as(full.output()).isZero();
        Map<String, String> fullResult = snapshot(project);

        assertThat(incrementalResult).as("incremental generation vs full regeneration").isEqualTo(fullResult);
        return incremental.output();
    }

    // ---------------------------------------------------------------- scenarios where it must stay incremental

    @Test
    void addingADependencyToOneModule_recomputesOnlyThatModule(@TempDir Path tmp) throws Exception {
        String output = check("multi-module", tmp, p -> addDependency(p.resolve("util/pom.xml"), OPENTEST4J));

        assertThat(output).contains("Incremental generation: 1 of 4 modules recomputed (com.test:util)")
                .contains("(1 changed)");
    }

    @Test
    void changingAParentProperty_recomputesTheModulesThatInheritIt(@TempDir Path tmp) throws Exception {
        String output = check("multi-module", tmp, p -> edit(p.resolve("pom.xml"),
                "<maven.compiler.release>17</maven.compiler.release>",
                "<maven.compiler.release>21</maven.compiler.release>"));

        assertThat(output).contains("Incremental generation: 4 of 4 modules recomputed");
    }

    @Test
    void anEditThatChangesNothingGenerated_stillMatches(@TempDir Path tmp) throws Exception {
        String output = check("multi-module", tmp, p -> edit(p.resolve("core/pom.xml"),
                "<name>Multi-Module Core</name>", "<name>Multi-Module Core, renamed</name>"));

        assertThat(output).contains("Incremental generation: 1 of 4 modules recomputed (com.test:core)");
    }

    @Test
    void touchingPomsWithoutChangingThem_recomputesNothingAndSettles(@TempDir Path tmp) throws Exception {
        Path project = copyProject("multi-module", tmp.resolve("project"));
        assertThat(cli(project).exit()).isZero();
        Map<String, String> before = snapshot(project);
        Thread.sleep(1100);
        Files.writeString(project.resolve("util/pom.xml"), Files.readString(project.resolve("util/pom.xml")));

        Run touched = cli(project, "-i");

        assertThat(touched.output()).contains("Incremental generation: 0 of 4 modules recomputed");
        assertThat(snapshot(project)).isEqualTo(before);
        // the generation is recorded as checked, so the next run has nothing to do
        assertThat(cli(project, "-i").output()).contains("Build files up to date");
    }

    @Test
    void anExtensionAndItsDeploymentModule_stayConsistent(@TempDir Path tmp) throws Exception {
        String output = check("fake-quarkus-extension", tmp, p -> edit(p.resolve("runtime/pom.xml"),
                "<provides>com.test.ext.fake</provides>",
                "<provides>com.test.ext.fake</provides><provides>com.test.ext.fake2</provides>"));

        assertThat(output).contains("Incremental generation: 1 of 3 modules recomputed");
    }

    @Test
    void addingADependencyToTheDeploymentModule(@TempDir Path tmp) throws Exception {
        String output = check("fake-quarkus-extension", tmp,
                p -> addDependency(p.resolve("deployment/pom.xml"), OPENTEST4J));

        assertThat(output).contains("Incremental generation: 1 of 3 modules recomputed");
    }

    // ---------------------------------------------------------------- scenarios that must fall back to a full run

    @Test
    void addingAModule_fallsBackToFullGeneration(@TempDir Path tmp) throws Exception {
        String output = check("multi-module", tmp, p -> {
            Path extra = Files.createDirectories(p.resolve("extra"));
            Files.writeString(extra.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>com.test</groupId>
                        <artifactId>multi-module-parent</artifactId>
                        <version>1.0.0</version>
                      </parent>
                      <artifactId>extra</artifactId>
                    </project>
                    """);
            edit(p.resolve("pom.xml"), "<module>app</module>", "<module>app</module>\n<module>extra</module>");
        });

        assertThat(output).contains("Incremental generation not possible (modules were added or removed)");
    }

    @Test
    void withoutAPreviousState_fallsBackToFullGeneration(@TempDir Path tmp) throws Exception {
        String output = check("multi-module", tmp, p -> {
            Files.delete(p.resolve("target/qraven/state.cbor"));
            addDependency(p.resolve("util/pom.xml"), OPENTEST4J);
        });

        assertThat(output).contains("Incremental generation not possible (no usable previous generation state)");
    }

    @Test
    void aCorruptState_fallsBackToFullGeneration(@TempDir Path tmp) throws Exception {
        String output = check("multi-module", tmp, p -> {
            Path state = p.resolve("target/qraven/state.cbor");
            byte[] bytes = Files.readAllBytes(state);
            bytes[bytes.length / 2] ^= 0x55;
            Files.write(state, bytes);
            addDependency(p.resolve("util/pom.xml"), OPENTEST4J);
        });

        assertThat(output).contains("Incremental generation not possible (no usable previous generation state)");
    }

    @Test
    void aDifferentEnvironment_fallsBackToFullGeneration(@TempDir Path tmp) throws Exception {
        Path project = copyProject("multi-module", tmp.resolve("project"));
        assertThat(cli(project).exit()).isZero();
        Thread.sleep(1100);
        addDependency(project.resolve("util/pom.xml"), OPENTEST4J);

        // a -D can change how poms are interpolated and which profiles are active
        Run run = cli(project, List.of("-Dsome.user.property=changed"), "-i");

        assertThat(run.output()).contains("Incremental generation not possible (no usable previous generation state)");
    }

    // ---------------------------------------------------------------- failures

    @Test
    void anUnresolvableDependency_failsLikeAFullGenerationAndRecovers(@TempDir Path tmp) throws Exception {
        Path project = copyProject("multi-module", tmp.resolve("project"));
        assertThat(cli(project).exit()).isZero();
        Thread.sleep(1100);
        Path pom = project.resolve("util/pom.xml");
        String original = Files.readString(pom);
        addDependency(pom, """
                <dependency>
                    <groupId>com.test.nonexistent</groupId>
                    <artifactId>missing</artifactId>
                    <version>1.2.3</version>
                </dependency>
                """);

        Run failed = cli(project, "-i");

        assertThat(failed.exit()).as(failed.output()).isNotZero();
        assertThat(failed.output()).contains("com.test.nonexistent:missing:jar:1.2.3");

        // the failed attempt must not leave a state that describes files it did not finish writing
        Thread.sleep(1100);
        Files.writeString(pom, original);
        Run recovered = cli(project, "-i");
        assertThat(recovered.exit()).as(recovered.output()).isZero();
        Map<String, String> incremental = snapshot(project);
        assertThat(cli(project, "-fg").exit()).isZero();
        assertThat(incremental).isEqualTo(snapshot(project));
    }
}
