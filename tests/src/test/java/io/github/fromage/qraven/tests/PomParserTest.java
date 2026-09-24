package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.DependencyResolver;
import io.github.fromage.qraven.hardcoded.ModuleInfo;
import io.github.fromage.qraven.hardcoded.PomParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PomParserTest {

    static final Path TEST_PROJECTS = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("test-projects");

    static List<ModuleInfo> parseProject(String projectName) {
        Path projectDir = TEST_PROJECTS.resolve(projectName);
        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        return parser.parseProject();
    }

    static ModuleInfo findModule(List<ModuleInfo> modules, String artifactId) {
        return modules.stream()
                .filter(m -> artifactId.equals(m.getArtifactId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Module not found: " + artifactId));
    }

    @Nested
    class SimpleJar {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("simple-jar");
        }

        @Test
        void parsesSingleModule() {
            assertThat(modules).hasSize(1);
        }

        @Test
        void parsesGAV() {
            ModuleInfo info = modules.get(0);
            assertThat(info.getGroupId()).isEqualTo("com.test");
            assertThat(info.getArtifactId()).isEqualTo("simple-jar");
            assertThat(info.getVersion()).isEqualTo("1.0.0");
        }

        @Test
        void parsesPackaging() {
            assertThat(modules.get(0).getPackaging()).isEqualTo("jar");
        }

        @Test
        void parsesProjectName() {
            assertThat(modules.get(0).getProjectName()).isEqualTo("Simple JAR Project");
        }

        @Test
        void detectsJavaSources() {
            ModuleInfo info = modules.get(0);
            assertThat(info.isHasJavaSources()).isTrue();
            assertThat(info.isHasKotlinSources()).isFalse();
            assertThat(info.isHasTestJavaSources()).isFalse();
            assertThat(info.isHasTestKotlinSources()).isFalse();
        }

        @Test
        void detectsResourceDir() {
            List<ModuleInfo.ResourceDir> resourceDirs = modules.get(0).getResourceDirs();
            assertThat(resourceDirs).hasSize(1);
            assertThat(resourceDirs.get(0).directory()).isEqualTo("src/main/resources");
            assertThat(resourceDirs.get(0).filtering()).isFalse();
        }

        @Test
        void extractsCompilerRelease() {
            List<String> args = modules.get(0).getCompilerArgs();
            assertThat(args).contains("--release", "17");
        }

        @Test
        void hasNoReactorDependencies() {
            assertThat(modules.get(0).getReactorDependencies()).isEmpty();
        }

        @Test
        void hasNoExternalClasspath() {
            assertThat(modules.get(0).getCompileClasspath()).isEmpty();
        }

        @Test
        void noJandex() {
            assertThat(modules.get(0).isNeedsJandexIndex()).isFalse();
        }

        @Test
        void noShade() {
            assertThat(modules.get(0).getShadeExecutions()).isEmpty();
        }

        @Test
        void noManifestEntries() {
            assertThat(modules.get(0).getManifestEntries()).isEmpty();
        }

        @Test
        void noQuarkusPlugins() {
            ModuleInfo info = modules.get(0);
            assertThat(info.isHasExtensionPlugin()).isFalse();
            assertThat(info.isHasQuarkusBuildPlugin()).isFalse();
        }
    }

    @Nested
    class MultiModule {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("multi-module");
        }

        @Test
        void parsesAllModules() {
            assertThat(modules).hasSize(4);
        }

        @Test
        void parentIsPomPackaging() {
            ModuleInfo parent = findModule(modules, "multi-module-parent");
            assertThat(parent.getPackaging()).isEqualTo("pom");
        }

        @Test
        void childrenInheritGroupId() {
            for (String childId : List.of("core", "util", "app")) {
                ModuleInfo child = findModule(modules, childId);
                assertThat(child.getGroupId()).isEqualTo("com.test");
            }
        }

        @Test
        void childrenInheritVersion() {
            for (String childId : List.of("core", "util", "app")) {
                ModuleInfo child = findModule(modules, childId);
                assertThat(child.getVersion()).isEqualTo("1.0.0");
            }
        }

        @Test
        void childrenHaveParentRef() {
            ModuleInfo core = findModule(modules, "core");
            assertThat(core.getParentGroupId()).isEqualTo("com.test");
            assertThat(core.getParentArtifactId()).isEqualTo("multi-module-parent");
        }

        @Test
        void coreHasNoReactorDeps() {
            ModuleInfo core = findModule(modules, "core");
            assertThat(core.getReactorDependencies()).isEmpty();
        }

        @Test
        void utilDependsOnCore() {
            ModuleInfo util = findModule(modules, "util");
            assertThat(util.getReactorDependencies()).containsExactly("core");
        }

        @Test
        void appDependsOnUtil() {
            ModuleInfo app = findModule(modules, "app");
            assertThat(app.getReactorDependencies()).contains("util");
        }

        @Test
        void appHasExternalDependency() {
            ModuleInfo app = findModule(modules, "app");
            assertThat(app.getCompileClasspath()).isNotEmpty();
            assertThat(app.getCompileClasspath()).anyMatch(p -> p.contains("commons-lang3"));
        }

        @Test
        void allChildrenHaveJavaSources() {
            for (String childId : List.of("core", "util", "app")) {
                ModuleInfo child = findModule(modules, childId);
                assertThat(child.isHasJavaSources())
                        .as("Module %s should have Java sources", childId)
                        .isTrue();
            }
        }

        @Test
        void allChildrenInheritCompilerRelease() {
            for (String childId : List.of("core", "util", "app")) {
                ModuleInfo child = findModule(modules, childId);
                assertThat(child.getCompilerArgs())
                        .as("Module %s should have --release 17", childId)
                        .contains("--release", "17");
            }
        }

        @Test
        void parentHasNoJavaSources() {
            ModuleInfo parent = findModule(modules, "multi-module-parent");
            assertThat(parent.isHasJavaSources()).isFalse();
        }
    }

    @Nested
    class ResourcesAndFiltering {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("resources-and-filtering");
        }

        @Test
        void parsesGAV() {
            ModuleInfo info = modules.get(0);
            assertThat(info.getGroupId()).isEqualTo("com.test");
            assertThat(info.getArtifactId()).isEqualTo("resources-and-filtering");
            assertThat(info.getVersion()).isEqualTo("2.0.0");
        }

        @Test
        void detectsTwoResourceDirs() {
            List<ModuleInfo.ResourceDir> dirs = modules.get(0).getResourceDirs();
            assertThat(dirs).hasSize(2);
        }

        @Test
        void firstResourceDirNotFiltered() {
            ModuleInfo.ResourceDir dir = modules.get(0).getResourceDirs().get(0);
            assertThat(dir.directory()).isEqualTo("src/main/resources");
            assertThat(dir.filtering()).isFalse();
        }

        @Test
        void secondResourceDirFiltered() {
            ModuleInfo.ResourceDir dir = modules.get(0).getResourceDirs().get(1);
            assertThat(dir.directory()).isEqualTo("src/main/resources-filtered");
            assertThat(dir.filtering()).isTrue();
        }

        @Test
        void filterPropertiesContainProjectCoordinates() {
            Map<String, String> props = modules.get(0).getFilterProperties();
            assertThat(props).containsEntry("project.groupId", "com.test");
            assertThat(props).containsEntry("project.artifactId", "resources-and-filtering");
            assertThat(props).containsEntry("project.version", "2.0.0");
        }

        @Test
        void filterPropertiesContainCustomProperties() {
            Map<String, String> props = modules.get(0).getFilterProperties();
            assertThat(props).containsEntry("app.name", "TestApp");
            assertThat(props).containsEntry("app.version", "2.0");
        }
    }

    @Nested
    class JandexProject {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("jandex-project");
        }

        @Test
        void detectsJandexPlugin() {
            assertThat(modules.get(0).isNeedsJandexIndex()).isTrue();
        }

        @Test
        void hasJavaSources() {
            assertThat(modules.get(0).isHasJavaSources()).isTrue();
        }
    }

    @Nested
    class ManifestEntries {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("manifest-entries");
        }

        @Test
        void extractsManifestEntries() {
            Map<String, String> entries = modules.get(0).getManifestEntries();
            assertThat(entries).containsEntry("Implementation-Title", "TestProject");
            assertThat(entries).containsEntry("Custom-Header", "hello");
        }

        @Test
        void manifestVersionIsInterpolated() {
            Map<String, String> entries = modules.get(0).getManifestEntries();
            assertThat(entries).containsEntry("Implementation-Version", "1.0.0");
        }
    }

    @Nested
    class CompilerConfig {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("compiler-config");
        }

        @Test
        void extractsRelease() {
            List<String> args = modules.get(0).getCompilerArgs();
            assertThat(args).contains("--release", "17");
        }

        @Test
        void extractsParameters() {
            List<String> args = modules.get(0).getCompilerArgs();
            assertThat(args).contains("-parameters");
        }

        @Test
        void extractsExtraCompilerArg() {
            List<String> args = modules.get(0).getCompilerArgs();
            assertThat(args).contains("-Xlint:unchecked");
        }
    }

    @Nested
    class ShadeProject {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("shade-project");
        }

        @Test
        void detectsShadePlugin() {
            assertThat(modules.get(0).getShadeExecutions()).isNotEmpty();
        }

        @Test
        void shadeExecutionHasCorrectId() {
            ModuleInfo.ShadeExecution exec = modules.get(0).getShadeExecutions().get(0);
            assertThat(exec.id()).isEqualTo("shade-commons");
        }

        @Test
        void shadeIsAttached() {
            ModuleInfo.ShadeExecution exec = modules.get(0).getShadeExecutions().get(0);
            assertThat(exec.attached()).isTrue();
        }

        @Test
        void shadeHasClassifier() {
            ModuleInfo.ShadeExecution exec = modules.get(0).getShadeExecutions().get(0);
            assertThat(exec.classifier()).isEqualTo("shaded");
        }

        @Test
        void shadeIncludesCommonsLang3() {
            ModuleInfo.ShadeExecution exec = modules.get(0).getShadeExecutions().get(0);
            assertThat(exec.includeArtifacts()).contains("org.apache.commons:commons-lang3");
        }

        @Test
        void shadeHasFilters() {
            ModuleInfo.ShadeExecution exec = modules.get(0).getShadeExecutions().get(0);
            assertThat(exec.filters()).isNotEmpty();
            ModuleInfo.ShadeFilter filter = exec.filters().get(0);
            assertThat(filter.artifact()).isEqualTo("*:*");
            assertThat(filter.excludes()).contains("META-INF/MANIFEST.MF", "META-INF/*.SF");
        }

        @Test
        void shadeHasMainClass() {
            ModuleInfo.ShadeExecution exec = modules.get(0).getShadeExecutions().get(0);
            assertThat(exec.mainClass()).isEqualTo("com.test.ShadeMain");
        }
    }

    @Nested
    class TestCompilation {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("test-compilation");
        }

        @Test
        void parsesAllModules() {
            assertThat(modules).hasSize(4);
        }

        @Test
        void libHasTestSources() {
            ModuleInfo lib = findModule(modules, "lib");
            assertThat(lib.isHasTestJavaSources()).isTrue();
        }

        @Test
        void appHasTestSources() {
            ModuleInfo app = findModule(modules, "app");
            assertThat(app.isHasTestJavaSources()).isTrue();
        }

        @Test
        void appDependsOnLib() {
            ModuleInfo app = findModule(modules, "app");
            assertThat(app.getReactorDependencies()).contains("lib");
        }

        @Test
        void appHasTestReactorDep() {
            ModuleInfo app = findModule(modules, "app");
            assertThat(app.getTestReactorDependencies()).contains("lib-testutils");
        }

        @Test
        void libTestutilsDependsOnLib() {
            ModuleInfo utils = findModule(modules, "lib-testutils");
            assertThat(utils.getReactorDependencies()).contains("lib");
        }
    }

    @Nested
    class TestJarDependency {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("test-jar-dependency");
        }

        @Test
        void parsesAllModules() {
            assertThat(modules).hasSize(3);
        }

        @Test
        void consumerDependsOnBase() {
            ModuleInfo consumer = findModule(modules, "consumer");
            assertThat(consumer.getReactorDependencies()).contains("base");
        }

        @Test
        void consumerHasTestJarDep() {
            ModuleInfo consumer = findModule(modules, "consumer");
            assertThat(consumer.getTestJarReactorDependencies()).contains("base");
        }

        @Test
        void baseHasTestSources() {
            ModuleInfo base = findModule(modules, "base");
            assertThat(base.isHasTestJavaSources()).isTrue();
        }
    }

    @Nested
    class AnnotationProcessor {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("with-annotation-processor");
        }

        @Test
        void parsesAllModules() {
            assertThat(modules).hasSize(3);
        }

        @Test
        void appHasAnnotationProcessorPaths() {
            ModuleInfo app = findModule(modules, "ap-app");
            assertThat(app.getAnnotationProcessorPaths()).isNotEmpty();
        }

        @Test
        void appApPathContainsProcessor() {
            ModuleInfo app = findModule(modules, "ap-app");
            assertThat(app.getAnnotationProcessorPaths())
                    .anyMatch(p -> p.contains("test-processor"));
        }

        @Test
        void appHasParametersFlag() {
            ModuleInfo app = findModule(modules, "ap-app");
            assertThat(app.getCompilerArgs()).contains("-parameters");
        }

        @Test
        void processorHasNoApPaths() {
            ModuleInfo processor = findModule(modules, "test-processor");
            assertThat(processor.getAnnotationProcessorPaths()).isEmpty();
        }

        @Test
        void appDependsOnProcessor() {
            ModuleInfo app = findModule(modules, "ap-app");
            assertThat(app.getReactorDependencies()).contains("test-processor");
        }
    }

    @Nested
    class FakeQuarkusExtension {

        static List<ModuleInfo> modules;

        @BeforeAll
        static void parse() {
            modules = parseProject("fake-quarkus-extension");
        }

        @Test
        void parsesAllModules() {
            assertThat(modules).hasSize(3);
        }

        @Test
        void runtimeDetectsExtensionPlugin() {
            ModuleInfo runtime = findModule(modules, "fake-extension");
            assertThat(runtime.isHasExtensionPlugin()).isTrue();
        }

        @Test
        void runtimeHasDeploymentArtifact() {
            ModuleInfo runtime = findModule(modules, "fake-extension");
            String deploymentArtifact = runtime.getExtensionDescriptorProperties()
                    .get("deployment-artifact");
            assertThat(deploymentArtifact)
                    .isEqualTo("com.test.ext:fake-extension-deployment:1.0.0");
        }

        @Test
        void runtimeHasParentFirstArtifacts() {
            ModuleInfo runtime = findModule(modules, "fake-extension");
            assertThat(runtime.getExtensionParentFirstArtifacts())
                    .contains("com.test.ext:fake-extension");
        }

        @Test
        void runtimeHasProvidesCapability() {
            ModuleInfo runtime = findModule(modules, "fake-extension");
            assertThat(runtime.getExtensionProvidesCapabilities())
                    .contains("com.test.ext.fake");
        }

        @Test
        void deploymentNotAnExtension() {
            ModuleInfo deployment = findModule(modules, "fake-extension-deployment");
            assertThat(deployment.isHasExtensionPlugin()).isFalse();
        }

        @Test
        void deploymentDependsOnRuntime() {
            ModuleInfo deployment = findModule(modules, "fake-extension-deployment");
            assertThat(deployment.getReactorDependencies()).contains("fake-extension");
        }

        @Test
        void noQuarkusBuildPlugin() {
            ModuleInfo runtime = findModule(modules, "fake-extension");
            assertThat(runtime.isHasQuarkusBuildPlugin()).isFalse();
        }
    }
}
