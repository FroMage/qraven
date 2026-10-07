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

/** The generator on its own, reusing the output of a previous generation. */
class IncrementalGeneratorTest {

    private static final Path PROJECT = Path.of(System.getProperty("user.dir")).getParent()
            .resolve("test-projects/multi-module");

    private static List<String> jarEntries(Path jar) throws IOException {
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.stream().map(e -> e.getName()).toList();
        }
    }

    private static BuildFileGenerator generator(Path out, DependencyResolver resolver, boolean incremental) {
        BuildFileGenerator generator = new BuildFileGenerator(PROJECT, out, 1, resolver);
        generator.setIncremental(incremental);
        return generator;
    }

    @Test
    void aModuleThatDisappears_isRemovedFromSourcesClassesAndJar(@TempDir Path out) throws Exception {
        DependencyResolver resolver = new DependencyResolver();
        List<ModuleInfo> all = new PomParser(PROJECT, resolver).parseProject();
        BuildFileGenerator first = generator(out, resolver, false);
        first.generate(all);
        first.compileAndPackage();
        assertThat(out.resolve("build-classes/Build_app.class")).exists();

        // asked to reuse the previous output although a module is gone
        List<ModuleInfo> remaining = all.stream().filter(m -> !m.getArtifactId().equals("app")).toList();
        BuildFileGenerator second = generator(out, resolver, true);
        second.generate(remaining);
        second.compileAndPackage();

        assertThat(out.resolve("build-src/Build_app.java")).doesNotExist();
        assertThat(out.resolve("build-classes/Build_app.class")).doesNotExist();
        assertThat(jarEntries(out.resolve("build.jar"))).noneMatch(e -> e.contains("Build_app"))
                .anyMatch(e -> e.equals("Build_core.class")).anyMatch(e -> e.equals("Build.class"));
        assertThat(Files.readString(out.resolve("build-src/Build.java"))).doesNotContain("Build_app");
        // the classes of the other modules had to be rebuilt too: nothing is trusted once something disappeared
        assertThat(second.reusedPreviousOutput()).isFalse();
    }

    @Test
    void aStaleClassOfAChangedModuleIsReplacedNotLinkedTwice(@TempDir Path out) throws Exception {
        DependencyResolver resolver = new DependencyResolver();
        List<ModuleInfo> all = new PomParser(PROJECT, resolver).parseProject();
        BuildFileGenerator first = generator(out, resolver, false);
        first.generate(all);
        first.compileAndPackage();

        // one module's data changes: only its source and class are redone, and nothing else is added to the jar
        ModuleInfo util = all.stream().filter(m -> m.getArtifactId().equals("util")).findFirst().orElseThrow();
        util.getCompilerArgs().add("-Xlint:all");
        BuildFileGenerator second = generator(out, resolver, true);
        second.generate(all);
        second.compileAndPackage();

        assertThat(second.reusedPreviousOutput()).isTrue();
        assertThat(second.getChangedModules()).containsExactly("util");
        assertThat(jarEntries(out.resolve("build.jar")).stream().filter(e -> e.startsWith("Build_util")))
                .containsExactly("Build_util.class");
        assertThat(jarEntries(out.resolve("build.jar")).stream().filter(e -> e.endsWith(".class")).count())
                .isEqualTo(all.size() + 1);
    }
}
