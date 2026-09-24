package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.BuildFileGenerator;
import io.github.fromage.qraven.hardcoded.DependencyResolver;
import io.github.fromage.qraven.hardcoded.ModuleInfo;
import io.github.fromage.qraven.hardcoded.PomParser;

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

class IncrementalBuildTest {

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
    void incrementalBuild_skipsUpToDate(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("simple-jar", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("simple-jar");

        runBuild(buildJar, projectDir);

        String output = runBuild(buildJar, projectDir, "-i");
        assertThat(output).contains("up-to-date");
    }

    @Test
    void incrementalBuild_rebuildsOnSourceChange(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("simple-jar", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("simple-jar");

        runBuild(buildJar, projectDir);

        Path sourceFile = projectDir.resolve("src/main/java/com/test/Hello.java");
        String original = Files.readString(sourceFile);
        try {
            Thread.sleep(1100);
            Files.writeString(sourceFile, original);

            String output = runBuild(buildJar, projectDir, "-i");
            assertThat(output).doesNotContain("0 to rebuild");
        } finally {
            Files.writeString(sourceFile, original);
        }
    }

    @Test
    void incrementalBuild_rebuildsDependent(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        runBuild(buildJar, projectDir);

        Path coreSource = projectDir.resolve("core/src/main/java/com/test/core/CoreUtil.java");
        String original = Files.readString(coreSource);
        try {
            Thread.sleep(1100);
            Files.writeString(coreSource, original);

            String output = runBuild(buildJar, projectDir, "-i");
            assertThat(output).doesNotContain("0 to rebuild");
        } finally {
            Files.writeString(coreSource, original);
        }
    }
}
