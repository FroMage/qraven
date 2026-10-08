package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.BuildFileGenerator;
import io.github.fromage.qraven.hardcoded.DependencyResolver;
import io.github.fromage.qraven.hardcoded.ModuleInfo;
import io.github.fromage.qraven.hardcoded.PomParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuildFileGeneratorTest {

    static final Path TEST_PROJECTS = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("test-projects");

    @Test
    void simpleJar_generatesBuildJar(@TempDir Path outputDir) throws IOException {
        Path projectDir = TEST_PROJECTS.resolve("simple-jar");
        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        List<ModuleInfo> modules = parser.parseProject();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, 1, resolver);
        generator.generate(modules);
        generator.compileAndPackage();

        Path buildJar = outputDir.resolve("build.jar");
        assertThat(buildJar).exists();
        assertThat(Files.size(buildJar)).isGreaterThan(0);
    }

    @Test
    void multiModule_generatesBuildJar(@TempDir Path outputDir) throws IOException {
        Path projectDir = TEST_PROJECTS.resolve("multi-module");
        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        List<ModuleInfo> modules = parser.parseProject();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, 1, resolver);
        generator.generate(modules);
        generator.compileAndPackage();

        Path buildJar = outputDir.resolve("build.jar");
        assertThat(buildJar).exists();
        assertThat(Files.size(buildJar)).isGreaterThan(0);
    }

    @Test
    void simpleJar_generatesTheBuildClassAndTheState(@TempDir Path outputDir) throws IOException {
        Path projectDir = TEST_PROJECTS.resolve("simple-jar");
        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        List<ModuleInfo> modules = parser.parseProject();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, 1, resolver);
        generator.generate(modules);

        // the main class is the only thing generated as code; each module is data in the state
        assertThat(outputDir.resolve("build-src/Build.java")).exists();
        try (var stream = Files.list(outputDir.resolve("build-src"))) {
            assertThat(stream.map(p -> p.getFileName().toString())).containsExactly("Build.java");
        }
        assertThat(GeneratedModules.ids(outputDir)).containsExactly("com.test:simple-jar");
        assertThat(GeneratedModules.module(outputDir, "simple-jar").hasJavaSources()).isTrue();
    }

    @Test
    void multiModule_putsAllModulesInTheState(@TempDir Path outputDir) throws IOException {
        Path projectDir = TEST_PROJECTS.resolve("multi-module");
        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        List<ModuleInfo> modules = parser.parseProject();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, 1, resolver);
        generator.generate(modules);

        assertThat(GeneratedModules.ids(outputDir)).hasSize(modules.size()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(Files.readString(outputDir.resolve("build-src/Build.java")))
                .contains("com.test:core").contains("com.test:util").contains("com.test:app");
    }
}
