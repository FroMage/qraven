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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndBuildTest {

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

    private int runBuild(Path buildJar, Path projectDir, String... extraArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.add("-jar");
        cmd.add(buildJar.toString());
        for (String arg : extraArgs) {
            cmd.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        pb.inheritIO();
        Process process = pb.start();
        return process.waitFor();
    }

    @Test
    void simpleJar_fullBuild(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("simple-jar", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("simple-jar");

        int exitCode = runBuild(buildJar, projectDir);
        assertThat(exitCode).isEqualTo(0);

        Path targetJar = projectDir.resolve("target/simple-jar-1.0.0.jar");
        assertThat(targetJar).exists();

        Path installedJar = Path.of(System.getProperty("user.home"),
                ".m2/repository/com/test/simple-jar/1.0.0/simple-jar-1.0.0.jar");
        assertThat(installedJar).exists();
    }

    @Test
    void multiModule_fullBuild(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        int exitCode = runBuild(buildJar, projectDir);
        assertThat(exitCode).isEqualTo(0);

        for (String module : List.of("core", "util", "app")) {
            Path targetJar = projectDir.resolve(module + "/target/" + module + "-1.0.0.jar");
            assertThat(targetJar).as("JAR for module %s", module).exists();
        }
    }

    @Test
    void resourcesAndFiltering_fullBuild(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("resources-and-filtering", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("resources-and-filtering");

        int exitCode = runBuild(buildJar, projectDir);
        assertThat(exitCode).isEqualTo(0);

        Path targetJar = projectDir.resolve(
                "target/resources-and-filtering-2.0.0.jar");
        assertThat(targetJar).exists();

        try (var jar = new java.util.jar.JarFile(targetJar.toFile())) {
            var staticEntry = jar.getEntry("static.txt");
            assertThat(staticEntry).isNotNull();

            var configEntry = jar.getEntry("config.properties");
            assertThat(configEntry).isNotNull();
            String configContent = new String(jar.getInputStream(configEntry).readAllBytes());
            assertThat(configContent).contains("app.name=TestApp");
            assertThat(configContent).contains("project.version=2.0.0");
            assertThat(configContent).doesNotContain("${");
        }
    }

    @Test
    void jandexProject_producesIndex(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("jandex-project", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("jandex-project");

        int exitCode = runBuild(buildJar, projectDir);
        assertThat(exitCode).isEqualTo(0);

        Path targetJar = projectDir.resolve("target/jandex-project-1.0.0.jar");
        assertThat(targetJar).exists();

        try (var jar = new java.util.jar.JarFile(targetJar.toFile())) {
            assertThat(jar.getEntry("META-INF/jandex.idx")).isNotNull();
        }
    }

    @Test
    void manifestEntries_inJar(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("manifest-entries", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("manifest-entries");

        int exitCode = runBuild(buildJar, projectDir);
        assertThat(exitCode).isEqualTo(0);

        Path targetJar = projectDir.resolve("target/manifest-entries-1.0.0.jar");
        assertThat(targetJar).exists();

        try (var jar = new java.util.jar.JarFile(targetJar.toFile())) {
            var manifest = jar.getManifest();
            assertThat(manifest.getMainAttributes().getValue("Implementation-Title"))
                    .isEqualTo("TestProject");
            assertThat(manifest.getMainAttributes().getValue("Custom-Header"))
                    .isEqualTo("hello");
        }
    }

    @Test
    void testCompilation_fullBuild(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("test-compilation", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("test-compilation");

        int exitCode = runBuild(buildJar, projectDir);
        assertThat(exitCode).isEqualTo(0);

        for (String module : List.of("lib", "lib-testutils", "app")) {
            Path targetJar = projectDir.resolve(module + "/target/" + module + "-1.0.0.jar");
            assertThat(targetJar).as("JAR for module %s", module).exists();
        }

        Path libTestClasses = projectDir.resolve("lib/target/test-classes/com/test/lib/LibTest.class");
        assertThat(libTestClasses).exists();

        Path appTestClasses = projectDir.resolve("app/target/test-classes/com/test/app/AppTest.class");
        assertThat(appTestClasses).exists();
    }

    @Test
    void annotationProcessor_fullBuild(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("with-annotation-processor", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("with-annotation-processor");

        int exitCode = runBuild(buildJar, projectDir);
        assertThat(exitCode).isEqualTo(0);

        Path processorJar = projectDir.resolve("processor/target/test-processor-1.0.0.jar");
        assertThat(processorJar).exists();

        Path appJar = projectDir.resolve("app/target/ap-app-1.0.0.jar");
        assertThat(appJar).exists();
    }

    @Test
    void multiModule_filteredBuild(@TempDir Path outputDir) throws Exception {
        Path buildJar = generateBuildJar("multi-module", outputDir);
        Path projectDir = TEST_PROJECTS.resolve("multi-module");

        Files.deleteIfExists(projectDir.resolve("core/target/core-1.0.0.jar"));
        Files.deleteIfExists(projectDir.resolve("util/target/util-1.0.0.jar"));
        Files.deleteIfExists(projectDir.resolve("app/target/app-1.0.0.jar"));

        int exitCode = runBuild(buildJar, projectDir, "-pl", "app", "-am");
        assertThat(exitCode).isEqualTo(0);

        assertThat(projectDir.resolve("core/target/core-1.0.0.jar")).exists();
        assertThat(projectDir.resolve("util/target/util-1.0.0.jar")).exists();
        assertThat(projectDir.resolve("app/target/app-1.0.0.jar")).exists();
    }
}
