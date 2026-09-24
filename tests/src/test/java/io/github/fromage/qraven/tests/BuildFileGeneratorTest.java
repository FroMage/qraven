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
    void simpleJar_generatesSourceFiles(@TempDir Path outputDir) throws IOException {
        Path projectDir = TEST_PROJECTS.resolve("simple-jar");
        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        List<ModuleInfo> modules = parser.parseProject();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, 1, resolver);
        generator.generate(modules);

        Path srcDir = outputDir.resolve("build-src");
        assertThat(srcDir).exists();

        long javaFileCount;
        try (var stream = Files.walk(srcDir)) {
            javaFileCount = stream.filter(p -> p.toString().endsWith(".java")).count();
        }
        assertThat(javaFileCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void multiModule_generatesSourcesForAllModules(@TempDir Path outputDir) throws IOException {
        Path projectDir = TEST_PROJECTS.resolve("multi-module");
        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        List<ModuleInfo> modules = parser.parseProject();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, 1, resolver);
        generator.generate(modules);

        Path srcDir = outputDir.resolve("build-src");
        long javaFileCount;
        try (var stream = Files.walk(srcDir)) {
            javaFileCount = stream.filter(p -> p.toString().endsWith(".java")).count();
        }
        assertThat(javaFileCount).isGreaterThanOrEqualTo(5);
    }
}
