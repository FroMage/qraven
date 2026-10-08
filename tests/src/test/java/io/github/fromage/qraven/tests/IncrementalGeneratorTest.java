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
    void whenNothingChanged_theCompiledMainClassIsKept(@TempDir Path out) throws Exception {
        DependencyResolver resolver = new DependencyResolver();
        List<ModuleInfo> all = new PomParser(PROJECT, resolver).parseProject();
        BuildFileGenerator first = generator(out, resolver, false);
        first.generate(all);
        first.compileAndPackage();
        Path mainClass = out.resolve("build-classes/Build.class");
        var compiledAt = Files.getLastModifiedTime(mainClass);
        var packagedAt = Files.getLastModifiedTime(out.resolve("build.jar"));
        Thread.sleep(50);

        BuildFileGenerator second = generator(out, resolver, true);
        second.generate(all);
        second.compileAndPackage();

        assertThat(second.reusedPreviousOutput()).isTrue();
        assertThat(Files.getLastModifiedTime(mainClass)).as("not compiled again").isEqualTo(compiledAt);
        assertThat(Files.getLastModifiedTime(out.resolve("build.jar"))).as("checked, and recorded as such")
                .isGreaterThan(packagedAt);
    }

    @Test
    void aModuleThatDisappears_isGoneFromTheMainClassTheStateAndTheJar(@TempDir Path out) throws Exception {
        DependencyResolver resolver = new DependencyResolver();
        List<ModuleInfo> all = new PomParser(PROJECT, resolver).parseProject();
        BuildFileGenerator first = generator(out, resolver, false);
        first.generate(all);
        first.compileAndPackage();
        assertThat(GeneratedModules.ids(out)).contains("com.test:app");
        assertThat(Files.readString(out.resolve("build-src/Build.java"))).contains("com.test:app");

        // asked to reuse the previous output although a module is gone
        List<ModuleInfo> remaining = all.stream().filter(m -> !m.getArtifactId().equals("app")).toList();
        BuildFileGenerator second = generator(out, resolver, true);
        second.generate(remaining);
        second.compileAndPackage();

        assertThat(GeneratedModules.ids(out)).doesNotContain("com.test:app").contains("com.test:core");
        assertThat(Files.readString(out.resolve("build-src/Build.java"))).doesNotContain("com.test:app")
                .contains("com.test:core");
        // nothing is compiled per module, so there is nothing of the module left in the jar to link
        assertThat(jarEntries(out.resolve("build.jar"))).noneMatch(e -> e.startsWith("Build_"))
                .anyMatch(e -> e.equals("Build.class"));
    }

    @Test
    void outputFromTheTimeOfAClassPerModule_isNotReused(@TempDir Path out) throws Exception {
        DependencyResolver resolver = new DependencyResolver();
        List<ModuleInfo> all = new PomParser(PROJECT, resolver).parseProject();
        BuildFileGenerator first = generator(out, resolver, false);
        first.generate(all);
        first.compileAndPackage();
        // what an older qraven left behind
        Files.writeString(out.resolve("build-src/Build_app.java"), "public class Build_app {}");
        Files.writeString(out.resolve("build-classes/Build_app.class"), "stale");

        BuildFileGenerator second = generator(out, resolver, true);
        second.generate(all);
        second.compileAndPackage();

        assertThat(second.reusedPreviousOutput()).isFalse();
        assertThat(out.resolve("build-src/Build_app.java")).doesNotExist();
        assertThat(out.resolve("build-classes/Build_app.class")).doesNotExist();
        assertThat(jarEntries(out.resolve("build.jar"))).noneMatch(e -> e.startsWith("Build_"));
    }
}
