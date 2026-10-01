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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * quarkus-platform-bom-maven-plugin goals (platform-properties and flatten-platform-bom), from
 * generated code to installed artifacts.
 */
class PlatformBomBuildTest {

    static final Path FIXTURE = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("test-projects/platform-bom");

    static final Path INSTALLED_PROPS = Path.of(System.getProperty("user.home"),
            ".m2/repository/com/test/test-platform-properties/1.0.0");
    static final Path INSTALLED_BOM = Path.of(System.getProperty("user.home"),
            ".m2/repository/com/test/test-bom/1.0.0");

    private record BuildResult(int exitCode, String output) {}

    private static Path copyFixture(Path into) throws IOException {
        Path project = into.resolve("platform-bom");
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
        cmd.add("-pl");
        cmd.add("test-platform-properties,test-bom");
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
    void generatedCodeCarriesTheGoalConfiguration(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);

        String props = generatedSource(outputDir, "test-platform-properties");
        assertThat(props).contains("String platformPropertiesFileName() { return \"platform-properties.properties\"; }");
        assertThat(props).contains("String flattenedPomFile() { return null; }");

        String bom = generatedSource(outputDir, "test-bom");
        assertThat(bom).contains("String platformPropertiesFileName() { return null; }");
        Path flattened = outputDir.resolve("flattened-poms/test-bom-1.0.0.pom");
        assertThat(bom).contains("String flattenedPomFile() { return \"" + flattened + "\"; }");
        assertThat(flattened).exists();
        assertThat(Files.readString(flattened))
                .contains("<artifactId>alpha</artifactId>")
                .doesNotContain("junit", "only-test", "<parent>");

        String app = generatedSource(outputDir, "platform-app");
        assertThat(app).contains("String platformPropertiesFileName() { return null; }");
        assertThat(app).contains("String flattenedPomFile() { return null; }");
    }

    @Test
    void installsThePropertiesArtifactAndTheFlattenedBom(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);

        BuildResult result = runBuild(outputDir, project);
        assertThat(result.exitCode()).as(result.output()).isEqualTo(0);

        assertThat(Files.readString(INSTALLED_PROPS.resolve("test-platform-properties-1.0.0.properties")))
                .contains("platform.test.value=from-pom")
                .doesNotContain("${");
        assertThat(INSTALLED_PROPS.resolve("test-platform-properties-1.0.0.pom")).exists();

        String bom = Files.readString(INSTALLED_BOM.resolve("test-bom-1.0.0.pom"));
        assertThat(bom).contains("<artifactId>test-platform-properties</artifactId>")
                .contains("<artifactId>alpha</artifactId>")
                .contains("<version>2.0</version>")
                .doesNotContain("junit", "only-test", "<parent>", "<properties>", "<build>");
        assertThat(bom.indexOf("test-platform-properties"))
                .as("platform properties come first").isLessThan(bom.indexOf("alpha"));
        assertThat(bom.indexOf("alpha")).as("alphabetical").isLessThan(bom.indexOf("zed"));
    }

    @Test
    void skipPlatformBomInstallsTheOriginalPom(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);

        BuildResult result = runBuild(outputDir, project, "-DskipPlatformBom");
        assertThat(result.exitCode()).as(result.output()).isEqualTo(0);

        assertThat(Files.readString(INSTALLED_BOM.resolve("test-bom-1.0.0.pom")))
                .contains("<parent>").contains("junit");
    }

    @Test
    void propertiesWithoutThePlatformPrefixFailTheBuild(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Files.writeString(project.resolve("props/src/main/resources/platform-properties.properties"),
                "platform.ok=1\nnot.prefixed=2\n");
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);

        BuildResult result = runBuild(outputDir, project);
        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.output()).contains("missing the 'platform.' prefix").contains("not.prefixed");

        result = runBuild(outputDir, project, "-DskipPlatformPrefixCheck");
        assertThat(result.exitCode()).as(result.output()).isEqualTo(0);
    }

    @Test
    void incrementalBuildTracksPropertiesAndFlattenedBom(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path outputDir = tmp.resolve("out");
        generate(project, outputDir);
        assertThat(runBuild(outputDir, project).exitCode()).isEqualTo(0);

        BuildResult unchanged = runBuild(outputDir, project, "-i");
        assertThat(unchanged.exitCode()).as(unchanged.output()).isEqualTo(0);
        assertThat(unchanged.output()).contains("0 to rebuild");

        Path resource = project.resolve("props/src/main/resources/platform-properties.properties");
        Files.writeString(resource, "platform.test.value=changed\n");
        Files.setLastModifiedTime(resource, FileTime.from(Instant.now().plusSeconds(5)));

        BuildResult changed = runBuild(outputDir, project, "-i");
        assertThat(changed.exitCode()).as(changed.output()).isEqualTo(0);
        assertThat(changed.output()).contains("1 to rebuild");
        assertThat(Files.readString(INSTALLED_PROPS.resolve("test-platform-properties-1.0.0.properties")))
                .contains("platform.test.value=changed");
    }
}
