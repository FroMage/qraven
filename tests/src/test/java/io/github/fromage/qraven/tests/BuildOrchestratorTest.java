package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.BuildFileGenerator;
import io.github.fromage.qraven.hardcoded.DependencyResolver;
import io.github.fromage.qraven.hardcoded.ModuleInfo;
import io.github.fromage.qraven.hardcoded.PomParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BuildOrchestratorTest {

    static final Path TEST_PROJECTS = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("test-projects");

    private Path generateBuildJar(String projectName, Path outputDir) throws IOException {
        Path projectDir = TEST_PROJECTS.resolve(projectName);
        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        List<ModuleInfo> modules = parser.parseProject();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, 1, resolver);
        generator.generate(modules);
        generator.compileAndPackage();

        return outputDir.resolve("build.jar");
    }

    private String runBuild(Path buildJar, Path projectDir, String... extraArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.add("-jar");
        cmd.add(buildJar.toString());
        cmd.add("-Dno-progress");
        for (String arg : extraArgs) {
            cmd.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        int exitCode = process.waitFor();
        assertThat(exitCode).as("Build exit code, output:\n%s", output).isEqualTo(0);
        return output;
    }

    @Test
    void filteredBuild_buildsOnlySelectedModule(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        String output = runBuild(buildJar, projectDir, "-pl", "core");
        assertThat(output).contains("Filtered to 1 module");
    }

    @Test
    void filteredBuild_withAlsoMake(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        String output = runBuild(buildJar, projectDir, "-pl", "app", "-am");
        assertThat(output).contains("Filtered to");
        assertThat(output).contains("dependencies");
    }

    @Test
    void filteredBuild_multipleModules(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        String output = runBuild(buildJar, projectDir, "-pl", "core,util");
        assertThat(output).contains("Filtered to 2 modules");
    }

    @Test
    void parallelBuild_multiModule(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        String output = runBuild(buildJar, projectDir, "-t", "2");
        assertThat(output).contains("2 threads");
        assertThat(output).contains("succeeded");
    }

    @Test
    void fullBuild_reportsAllSucceeded(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        String output = runBuild(buildJar, projectDir);
        assertThat(output).contains("4 succeeded");
        assertThat(output).contains("0 failed");
    }

    @Test
    void filteredBuild_alsoMakeIncludesTransitiveDeps(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        String output = runBuild(buildJar, projectDir, "-pl", "app", "-am");
        assertThat(output).contains("Filtered to");

        assertThat(projectDir.resolve("core/target/core-1.0.0.jar")).exists();
        assertThat(projectDir.resolve("util/target/util-1.0.0.jar")).exists();
        assertThat(projectDir.resolve("app/target/app-1.0.0.jar")).exists();
    }
}
