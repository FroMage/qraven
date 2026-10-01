package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.BuildFileGenerator;
import io.github.fromage.qraven.hardcoded.DependencyResolver;
import io.github.fromage.qraven.hardcoded.ModuleInfo;
import io.github.fromage.qraven.hardcoded.PomParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * exec-maven-plugin:java bound to generate-sources, and build-helper-maven-plugin:add-source: a module
 * compiling sources written by a generator that lives in another module of the reactor.
 */
class ExecGeneratorBuildTest {

    static final Path FIXTURE = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("test-projects/exec-generator");

    private record BuildResult(int exitCode, String output) {}

    private static Path copyFixture(Path into) throws IOException {
        Path project = into.resolve("exec-generator");
        try (Stream<Path> paths = Files.walk(FIXTURE)) {
            for (Path source : paths.filter(p -> !p.toString().contains("/target")).toList()) {
                Path target = project.resolve(FIXTURE.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        return project;
    }

    private static void generate(Path projectDir, Path outputDir) throws IOException {
        DependencyResolver resolver = new DependencyResolver();
        List<ModuleInfo> modules = new PomParser(projectDir, resolver).parseProject();
        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, 1, resolver);
        generator.generate(modules);
        generator.compileAndPackage();
    }

    private static BuildResult runBuild(Path outputDir, Path projectDir, String... extraArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.add("-jar");
        cmd.add(outputDir.resolve("build.jar").toString());
        cmd.add("-Dno-progress");
        cmd.addAll(List.of(extraArgs));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        return new BuildResult(process.waitFor(), output);
    }

    private static String generatedSource(Path outputDir, String artifactId) throws IOException {
        try (Stream<Path> files = Files.walk(outputDir.resolve("build-src"))) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(c -> c.contains("String artifactId() { return \"" + artifactId + "\"; }"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No generated source for " + artifactId));
        }
    }

    @Test
    void generatedCodeCarriesTheGenerators(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);

        String app = generatedSource(outputDir, "exec-generator-app");
        assertThat(app).contains("new SourceGenerator(\"com.test.generator.MakeSources\", List.of("
                + "\"${project.build.directory}/generated-sources/gen\", \"from-generator\"), \"${exec.skip}\")");
        assertThat(app).contains("exec-generator-maker-1.0.0.jar");
        assertThat(app).contains("public List<String> addedSourceDirs()");

        assertThat(generatedSource(outputDir, "exec-generator-maker")).doesNotContain("sourceGenerators()");
    }

    @Test
    void compilesTheGeneratedSources(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);

        // the app is declared before the generator's module, which must still be built first
        BuildResult result = runBuild(outputDir, project);
        assertThat(result.exitCode()).as(result.output()).isEqualTo(0);

        Path generated = project.resolve(
                "app/target/generated-sources/gen/com/test/app/generated/Generated.java");
        assertThat(generated).exists();
        assertThat(Files.readString(generated)).contains("from-generator");

        try (JarFile jar = new JarFile(project.resolve("app/target/exec-generator-app-1.0.0.jar").toFile())) {
            assertThat(jar.getEntry("com/test/app/App.class")).isNotNull();
            assertThat(jar.getEntry("com/test/app/generated/Generated.class")).isNotNull();
            assertThat(jar.getEntry("com/test/generator/MakeSources.class"))
                    .as("the generator is not part of the app").isNull();
        }
    }

    @Test
    void alsoMakeBuildsTheGeneratorModule(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);

        BuildResult result = runBuild(outputDir, project, "-pl", "exec-generator-app", "-am");
        assertThat(result.exitCode()).as(result.output()).isEqualTo(0);
        assertThat(result.output()).contains("Filtered to 2 modules");
        assertThat(project.resolve("app/target/exec-generator-app-1.0.0.jar")).exists();
    }

    @Test
    void execSkipDisablesTheGenerator(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);

        // without the generated sources the app doesn't compile
        BuildResult result = runBuild(outputDir, project, "-Dexec.skip");
        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(project.resolve("app/target/generated-sources")).doesNotExist();
    }
}
