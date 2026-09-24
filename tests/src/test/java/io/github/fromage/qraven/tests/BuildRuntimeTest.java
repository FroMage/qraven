package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.BuildRuntime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class BuildRuntimeTest {

    static final Path TEST_PROJECTS = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("test-projects");

    static BuildRuntime runtime;

    @BeforeAll
    static void init() {
        runtime = new BuildRuntime(TEST_PROJECTS);
    }

    @AfterAll
    static void cleanup() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void compile_simpleJavaFiles(@TempDir Path outputDir) {
        Path sourceDir = TEST_PROJECTS.resolve("simple-jar/src/main/java");
        runtime.compile(sourceDir, outputDir, List.of(), List.of(), false,
                List.of("--release", "17"));

        assertThat(outputDir.resolve("com/test/Hello.class")).exists();
    }

    @Test
    void compile_withParametersFlag(@TempDir Path outputDir) {
        Path sourceDir = TEST_PROJECTS.resolve("simple-jar/src/main/java");
        runtime.compile(sourceDir, outputDir, List.of(), List.of(), false,
                List.of("--release", "17", "-parameters"));

        assertThat(outputDir.resolve("com/test/Hello.class")).exists();
    }

    @Test
    void compile_emptySourceDir(@TempDir Path outputDir) throws IOException {
        Path emptyDir = outputDir.resolve("empty-src");
        Files.createDirectories(emptyDir);
        runtime.compile(emptyDir, outputDir.resolve("classes"), List.of(), List.of(), false,
                List.of("--release", "17"));

        assertThat(outputDir.resolve("classes")).doesNotExist();
    }

    @Test
    void copyResources_copiesAllFiles(@TempDir Path outputDir) throws IOException {
        Path resourceDir = TEST_PROJECTS.resolve("resources-and-filtering/src/main/resources");
        runtime.copyResources(resourceDir, outputDir);

        assertThat(outputDir.resolve("static.txt")).exists();
        String content = Files.readString(outputDir.resolve("static.txt"));
        assertThat(content).contains("No ${property} interpolation here");
    }

    @Test
    void copyResourcesFiltered_interpolatesProperties(@TempDir Path outputDir) throws IOException {
        Path resourceDir = TEST_PROJECTS.resolve("resources-and-filtering/src/main/resources-filtered");
        Map<String, String> properties = Map.of(
                "app.name", "TestApp",
                "app.version", "2.0",
                "project.version", "2.0.0",
                "project.groupId", "com.test",
                "project.artifactId", "resources-and-filtering"
        );
        runtime.copyResourcesFiltered(resourceDir, outputDir, properties);

        String content = Files.readString(outputDir.resolve("config.properties"));
        assertThat(content).contains("app.name=TestApp");
        assertThat(content).contains("app.version=2.0");
        assertThat(content).contains("project.version=2.0.0");
        assertThat(content).doesNotContain("${");
    }

    @Test
    void copyResourcesFiltered_interpolatesMetaInfFiles(@TempDir Path outputDir) throws IOException {
        Path resourceDir = TEST_PROJECTS.resolve("resources-and-filtering/src/main/resources-filtered");
        Map<String, String> properties = Map.of(
                "project.groupId", "com.test",
                "project.artifactId", "resources-and-filtering",
                "project.version", "2.0.0"
        );
        runtime.copyResourcesFiltered(resourceDir, outputDir, properties);

        String content = Files.readString(outputDir.resolve("META-INF/info.txt"));
        assertThat(content).contains("Built by com.test:resources-and-filtering:2.0.0");
    }

    @Test
    void createJar_createsValidJar(@TempDir Path workDir) throws IOException {
        Path classesDir = workDir.resolve("classes");
        Path sourceDir = TEST_PROJECTS.resolve("simple-jar/src/main/java");
        runtime.compile(sourceDir, classesDir, List.of(), List.of(), false,
                List.of("--release", "17"));

        Path jarFile = workDir.resolve("test.jar");
        runtime.createJar(classesDir, jarFile);

        assertThat(jarFile).exists();
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            assertThat(jar.getEntry("com/test/Hello.class")).isNotNull();
        }
    }

    @Test
    void createJar_withManifestEntries(@TempDir Path workDir) throws IOException {
        Path classesDir = workDir.resolve("classes");
        Path sourceDir = TEST_PROJECTS.resolve("simple-jar/src/main/java");
        runtime.compile(sourceDir, classesDir, List.of(), List.of(), false,
                List.of("--release", "17"));

        Path jarFile = workDir.resolve("test.jar");
        Map<String, String> entries = Map.of(
                "Implementation-Title", "TestProject",
                "Custom-Header", "hello"
        );
        runtime.createJar(classesDir, jarFile, entries);

        try (JarFile jar = new JarFile(jarFile.toFile())) {
            Manifest manifest = jar.getManifest();
            assertThat(manifest.getMainAttributes().getValue("Implementation-Title"))
                    .isEqualTo("TestProject");
            assertThat(manifest.getMainAttributes().getValue("Custom-Header"))
                    .isEqualTo("hello");
        }
    }

    @Test
    void generateJandexIndex(@TempDir Path workDir) throws IOException {
        Path classesDir = workDir.resolve("classes");
        Path sourceDir = TEST_PROJECTS.resolve("jandex-project/src/main/java");
        runtime.compile(sourceDir, classesDir, List.of(), List.of(), false,
                List.of("--release", "17"));

        runtime.generateJandexIndex(classesDir);

        assertThat(classesDir.resolve("META-INF/jandex.idx")).exists();
    }

    @Test
    void install_copiesJarAndPom(@TempDir Path workDir) throws IOException {
        Path classesDir = workDir.resolve("classes");
        Path sourceDir = TEST_PROJECTS.resolve("simple-jar/src/main/java");
        runtime.compile(sourceDir, classesDir, List.of(), List.of(), false,
                List.of("--release", "17"));

        Path jarFile = workDir.resolve("test.jar");
        runtime.createJar(classesDir, jarFile);

        Path pomFile = TEST_PROJECTS.resolve("simple-jar/pom.xml");
        Path m2 = workDir.resolve("m2/repository");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", workDir.resolve("m2-home").toString());
            Files.createDirectories(workDir.resolve("m2-home/.m2/repository"));
            runtime.install(jarFile, pomFile, "com.test", "simple-jar", "1.0.0", "jar");

            Path installedJar = workDir.resolve("m2-home/.m2/repository/com/test/simple-jar/1.0.0/simple-jar-1.0.0.jar");
            Path installedPom = workDir.resolve("m2-home/.m2/repository/com/test/simple-jar/1.0.0/simple-jar-1.0.0.pom");
            assertThat(installedJar).exists();
            assertThat(installedPom).exists();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void compile_testSourcesWithMainClasspath(@TempDir Path workDir) throws IOException {
        Path mainClassesDir = workDir.resolve("main-classes");
        Path mainSourceDir = TEST_PROJECTS.resolve("test-compilation/lib/src/main/java");
        runtime.compile(mainSourceDir, mainClassesDir, List.of(), List.of(), false,
                List.of("--release", "17"));
        assertThat(mainClassesDir.resolve("com/test/lib/Lib.class")).exists();

        Path testClassesDir = workDir.resolve("test-classes");
        Path testSourceDir = TEST_PROJECTS.resolve("test-compilation/lib/src/test/java");

        String junitJar = findJar("junit-jupiter-api");
        List<String> testClasspath = new ArrayList<>();
        testClasspath.add(mainClassesDir.toString());
        if (junitJar != null) {
            testClasspath.add(junitJar);
        }
        runtime.compile(testSourceDir, testClassesDir, testClasspath, List.of(), false,
                List.of("--release", "17"));
        assertThat(testClassesDir.resolve("com/test/lib/LibTest.class")).exists();
    }

    @Test
    void compile_testSourcesWithReactorDeps(@TempDir Path workDir) throws IOException {
        Path libClassesDir = workDir.resolve("lib-classes");
        runtime.compile(TEST_PROJECTS.resolve("test-compilation/lib/src/main/java"),
                libClassesDir, List.of(), List.of(), false, List.of("--release", "17"));

        Path utilClassesDir = workDir.resolve("util-classes");
        runtime.compile(TEST_PROJECTS.resolve("test-compilation/lib-testutils/src/main/java"),
                utilClassesDir, List.of(libClassesDir.toString()), List.of(), false,
                List.of("--release", "17"));
        assertThat(utilClassesDir.resolve("com/test/testutils/TestHelper.class")).exists();

        Path appClassesDir = workDir.resolve("app-classes");
        runtime.compile(TEST_PROJECTS.resolve("test-compilation/app/src/main/java"),
                appClassesDir, List.of(libClassesDir.toString()), List.of(), false,
                List.of("--release", "17"));

        Path testClassesDir = workDir.resolve("test-classes");
        String junitJar = findJar("junit-jupiter-api");
        List<String> testClasspath = new ArrayList<>();
        testClasspath.add(appClassesDir.toString());
        testClasspath.add(libClassesDir.toString());
        testClasspath.add(utilClassesDir.toString());
        if (junitJar != null) {
            testClasspath.add(junitJar);
        }
        runtime.compile(TEST_PROJECTS.resolve("test-compilation/app/src/test/java"),
                testClassesDir, testClasspath, List.of(), false, List.of("--release", "17"));
        assertThat(testClassesDir.resolve("com/test/app/AppTest.class")).exists();
    }

    @Test
    void compile_withAnnotationProcessor(@TempDir Path workDir) throws IOException {
        Path processorClassesDir = workDir.resolve("processor-classes");
        runtime.compile(
                TEST_PROJECTS.resolve("with-annotation-processor/processor/src/main/java"),
                processorClassesDir, List.of(), List.of(), false, List.of("--release", "17"));

        runtime.copyResources(
                TEST_PROJECTS.resolve("with-annotation-processor/processor/src/main/resources"),
                processorClassesDir);
        assertThat(processorClassesDir.resolve(
                "META-INF/services/javax.annotation.processing.Processor")).exists();

        Path appClassesDir = workDir.resolve("app-classes");
        List<String> classpath = List.of(processorClassesDir.toString());
        List<String> apPaths = List.of(processorClassesDir.toString());

        runtime.compile(
                TEST_PROJECTS.resolve("with-annotation-processor/app/src/main/java"),
                appClassesDir, classpath, apPaths, false,
                List.of("--release", "17", "-parameters"));
        assertThat(appClassesDir.resolve("com/test/AppWithAP.class")).exists();
    }

    private static String findJar(String artifactPrefix) {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        try (var stream = Files.walk(m2, 6)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(artifactPrefix))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
