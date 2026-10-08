package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.ModuleInfo;
import io.github.fromage.qraven.hardcoded.runtime.BuildRuntime;
import io.github.fromage.qraven.hardcoded.runtime.DataModuleBuild;
import io.github.fromage.qraven.hardcoded.runtime.ModuleBuild;

import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** A module is its data: what a {@link ModuleInfo} says is what the build sees. */
class DataModuleBuildTest {

    private static final Path POMS = Path.of("/out/flattened-poms");
    private static final String HOME = System.getProperty("user.home");

    private static DataModuleBuild of(ModuleInfo module) {
        return new DataModuleBuild(new BuildRuntime(Path.of(".").toAbsolutePath()), module, POMS);
    }

    private static ModuleInfo module() {
        ModuleInfo m = new ModuleInfo();
        m.setGroupId("com.test");
        m.setArtifactId("demo");
        m.setVersion("1.0");
        m.setBaseDir(Path.of("modules/demo"));
        return m;
    }

    // ---------------------------------------------------------------- what the accessors return

    @Test
    void coordinatesAndFlagsAreTheModulesOwn() {
        ModuleInfo m = module();
        m.setPackaging("pom");
        m.setHasKotlinSources(true);
        m.setNeedsJandexIndex(true);
        m.setPlatformPropertiesFileName("platform.properties");

        ModuleBuild build = of(m);

        assertThat(build.groupId()).isEqualTo("com.test");
        assertThat(build.artifactId()).isEqualTo("demo");
        assertThat(build.version()).isEqualTo("1.0");
        assertThat(build.packaging()).isEqualTo("pom");
        assertThat(build.baseDir()).isEqualTo(Path.of("modules/demo"));
        assertThat(build.hasKotlinSources()).isTrue();
        assertThat(build.hasJavaSources()).isFalse();
        assertThat(build.needsJandexIndex()).isTrue();
        assertThat(build.platformPropertiesFileName()).isEqualTo("platform.properties");
    }

    @Test
    void pathsUnderTheHomeDirectoryAreWrittenWithHomeAndTheOthersAreLeftAlone() {
        ModuleInfo m = module();
        m.setCompileClasspath(new ArrayList<>(List.of(HOME + "/.m2/a.jar", "/opt/b.jar")));
        m.setTestCompileClasspath(new ArrayList<>(List.of(HOME + "/.m2/test.jar")));
        m.setAnnotationProcessorPaths(new ArrayList<>(List.of(HOME + "/.m2/ap.jar")));
        m.setDeploymentClasspath(new ArrayList<>(List.of(HOME + "/.m2/deployment.jar")));

        ModuleBuild build = of(m);

        assertThat(build.compileClasspath()).containsExactly("$HOME/.m2/a.jar", "/opt/b.jar");
        assertThat(build.testCompileClasspath()).containsExactly("$HOME/.m2/test.jar");
        assertThat(build.annotationProcessorPaths()).containsExactly("$HOME/.m2/ap.jar");
        assertThat(build.deploymentClasspath()).containsExactly("$HOME/.m2/deployment.jar");
    }

    @Test
    void optionalClasspathEntriesAreKeptApartFromTheOthers() {
        ModuleInfo m = module();
        m.setCompileClasspath(new ArrayList<>(List.of("/a.jar", "/optional.jar", "/b.jar")));
        m.getOptionalClasspathEntries().add("/optional.jar");

        ModuleBuild build = of(m);

        assertThat(build.compileClasspath()).containsExactly("/a.jar", "/b.jar");
        assertThat(build.optionalCompileClasspath()).containsExactly("/optional.jar");
    }

    @Test
    void extensionDevPropertiesKeepTheEscapedNewlinesTheirConsumersExpect() {
        ModuleInfo m = module();
        m.getExtensionDevProperties().put("io.quarkus:ext", "deployment-artifact=a:b:1\nparent-first-artifacts=x\n");

        ModuleBuild build = of(m);

        assertThat(build.extensionDevProperties()).containsEntry("io.quarkus:ext",
                "deployment-artifact=a:b:1\\nparent-first-artifacts=x\\n");
    }

    @Test
    void otherPropertiesKeepTheirValuesWholeEvenOverSeveralLines() {
        ModuleInfo m = module();
        m.getFilterProperties().put("project.description", "first line\n        second line");

        assertThat(of(m).filterProperties()).containsEntry("project.description",
                "first line\n        second line");
    }

    @Test
    void resourceDirsAreDirectoryFilteringAndTargetPath() {
        ModuleInfo m = module();
        m.getResourceDirs().add(new ModuleInfo.ResourceDir("src/main/resources", true, null));
        m.getResourceDirs().add(new ModuleInfo.ResourceDir("src/other", false, "META-INF"));

        assertThat(of(m).resourceDirs()).isDeepEqualTo(new String[][] {
                { "src/main/resources", "true", null }, { "src/other", "false", "META-INF" } });
    }

    @Test
    void theFlattenedPomIsWhereGenerationPutIt() {
        ModuleInfo with = module();
        with.setFlattenedBomModel(new Model());

        assertThat(of(with).flattenedPomFile()).isEqualTo(POMS.resolve("demo-1.0.pom").toString());
        assertThat(of(module()).flattenedPomFile()).isNull();
    }

    @Test
    void dependenciesAreCarriedOverInTheirOrder() {
        ModuleInfo m = module();
        m.getReactorDependencies().addAll(List.of("b", "a"));
        m.getOptionalReactorDependencies().add("a");
        m.getTestReactorDependencies().add("t");
        m.getTestJarReactorDependencies().add("tj");

        ModuleBuild build = of(m);

        assertThat(build.moduleDependencyIds()).containsExactly("b", "a");
        assertThat(build.optionalModuleDependencyIds()).containsExactly("a");
        assertThat(build.testModuleDependencyIds()).containsExactly("t");
        assertThat(build.testJarModuleDependencyIds()).containsExactly("tj");
    }

    @Test
    void shadingAndSourceGeneratorsBecomeTheBuildsOwnTypes() {
        ModuleInfo m = module();
        m.getShadeExecutions().add(new ModuleInfo.ShadeExecution("shade", true, "shaded", List.of("g:a"),
                List.of(new ModuleInfo.ShadeFilter("g:a", List.of("META-INF/*.SF"))), "com.Main"));
        m.getSourceGenerators().add(new ModuleInfo.SourceGenerator("com.Gen", List.of("arg"), "${skip}"));
        m.setGeneratorClasspath(new ArrayList<>(List.of(HOME + "/.m2/gen.jar")));
        m.getAddedSourceDirs().add("target/generated");

        ModuleBuild build = of(m);

        assertThat(build.shadeExecutions()).containsExactly(new ModuleBuild.ShadeExecution("shade", true, "shaded",
                List.of("g:a"), List.of(new ModuleBuild.ShadeFilter("g:a", List.of("META-INF/*.SF"))), "com.Main"));
        assertThat(build.sourceGenerators()).containsExactly(new ModuleBuild.SourceGenerator("com.Gen",
                List.of("arg"), "${skip}"));
        assertThat(build.generatorClasspath()).containsExactly("$HOME/.m2/gen.jar");
        assertThat(build.addedSourceDirs()).containsExactly("target/generated");
    }

    @Test
    void theGeneratorClasspathOnlyMeansSomethingWithSourceGenerators() {
        ModuleInfo m = module();
        m.setGeneratorClasspath(new ArrayList<>(List.of("/gen.jar")));

        assertThat(of(m).generatorClasspath()).isEmpty();
    }

    // ---------------------------------------------------------------- the fingerprint

    /** Generation bookkeeping that the build never reads, so changing it must not rebuild the module. */
    private static final Set<String> NOT_READ_BY_THE_BUILD = Set.of(
            "inputPomHashes", "declaredAnnotationProcessorPaths", "parentGroupId", "parentArtifactId", "pomFile",
            "reactorDependencyExclusions", "generatorPluginDependencies", "formatterPluginVersion",
            "spotlessPluginVersion", "flattenedBomModel");

    @Test
    void theFingerprintChangesWithEverythingTheBuildReads() throws Exception {
        ModuleInfo original = ModuleInfoFixtures.populated("everything");
        String fingerprint = of(original).buildDataFingerprint();
        Set<String> unnoticed = new TreeSet<>();

        for (Field field : ModuleInfoFixtures.declaredFields()) {
            ModuleInfo changed = ModuleInfoFixtures.copyOf(original);
            field.setAccessible(true);
            Object other = field.getType() == boolean.class
                    ? !(Boolean) field.get(changed)
                    : ModuleInfoFixtures.value(field.getGenericType(), field.getName());
            field.set(changed, other);
            if (of(changed).buildDataFingerprint().equals(fingerprint)) {
                unnoticed.add(field.getName());
            }
        }

        // a field the build reads must change the fingerprint; the ones that don't are listed above, on purpose
        assertThat(unnoticed).containsExactlyInAnyOrderElementsOf(NOT_READ_BY_THE_BUILD);
    }

    @Test
    void theFingerprintIsTheSameForTheSameData() throws Exception {
        ModuleInfo module = ModuleInfoFixtures.populated("a");

        assertThat(of(module).buildDataFingerprint()).isEqualTo(of(module).buildDataFingerprint()).hasSize(32);
        assertThat(of(ModuleInfoFixtures.copyOf(module)).buildDataFingerprint())
                .isEqualTo(of(module).buildDataFingerprint());
    }

    @Test
    void theFingerprintDoesNotDependOnTheOrderMapsWereFilledIn() {
        ModuleInfo one = module();
        one.getQuarkusBuildProperties().put("a", "1");
        one.getQuarkusBuildProperties().put("b", "2");
        ModuleInfo other = module();
        other.getQuarkusBuildProperties().put("b", "2");
        other.getQuarkusBuildProperties().put("a", "1");

        assertThat(of(one).buildDataFingerprint()).isEqualTo(of(other).buildDataFingerprint());
    }
}
