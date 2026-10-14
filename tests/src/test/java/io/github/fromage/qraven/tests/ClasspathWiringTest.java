package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.BuildOrchestrator;
import io.github.fromage.qraven.hardcoded.runtime.BuildRuntime;
import io.github.fromage.qraven.hardcoded.runtime.ModuleBuild;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathWiringTest {

    @TempDir
    Path projectRoot;

    BuildRuntime runtime;

    @BeforeEach
    void setup() {
        runtime = new BuildRuntime(projectRoot);
    }

    private StubModuleBuild module(String artifactId) {
        return new StubModuleBuild(runtime, "com.test", artifactId, "1.0.0");
    }

    private String installPath(StubModuleBuild m) {
        return "$HOME/.m2/repository/"
                + m.groupId().replace('.', '/') + "/"
                + m.artifactId() + "/"
                + m.version() + "/"
                + m.artifactId() + "-" + m.version() + ".jar";
    }

    private void wireDependencies(StubModuleBuild... modules) {
        List<ModuleBuild> list = List.of(modules);
        Map<String, ModuleBuild> byId = new LinkedHashMap<>();
        for (ModuleBuild m : list) {
            byId.put(m.artifactId(), m);
        }
        BuildOrchestrator.wireDependencies(list, byId);
    }

    @Nested
    class DeploymentModulesInBuildOrdering {

        @Test
        void deploymentModuleAddedToDependenciesForBuildOrdering() {
            StubModuleBuild extRuntime = module("ext-runtime");
            StubModuleBuild extDeployment = module("ext-deployment")
                    .withModuleDeps("ext-runtime");
            StubModuleBuild app = module("app")
                    .withModuleDeps("ext-runtime")
                    .withQuarkusBuildPlugin()
                    .withDeploymentClasspath(installPath(extDeployment));

            wireDependencies(extRuntime, extDeployment, app);

            assertThat(app.getDependencies())
                    .extracting(ModuleBuild::artifactId)
                    .containsExactly("ext-runtime", "ext-deployment");
        }

        @Test
        void deploymentModuleNotAddedWithoutQuarkusPlugin() {
            StubModuleBuild extRuntime = module("ext-runtime");
            StubModuleBuild extDeployment = module("ext-deployment")
                    .withModuleDeps("ext-runtime");
            StubModuleBuild app = module("app")
                    .withModuleDeps("ext-runtime")
                    .withDeploymentClasspath(installPath(extDeployment));

            wireDependencies(extRuntime, extDeployment, app);

            assertThat(app.getDependencies())
                    .extracting(ModuleBuild::artifactId)
                    .containsExactly("ext-runtime");
        }
    }

    @Nested
    class ModulesNeedingKotlin {

        private Set<String> skipped(StubModuleBuild... modules) {
            Map<String, String> installPaths = new LinkedHashMap<>();
            for (StubModuleBuild m : modules) {
                installPaths.put(installPath(m), m.artifactId());
            }
            return BuildOrchestrator.modulesNeedingKotlin(List.of(modules), installPaths);
        }

        @Test
        void kotlinModulesAndTheModulesThatDependOnThemAreSkipped() {
            StubModuleBuild runtime = module("ext-kotlin").withKotlinSources();
            StubModuleBuild deployment = module("ext-kotlin-deployment").withModuleDeps("ext-kotlin");
            StubModuleBuild consumer = module("consumer").withModuleDeps("ext-kotlin-deployment");
            StubModuleBuild unrelated = module("unrelated");

            assertThat(skipped(runtime, deployment, consumer, unrelated))
                    .containsExactlyInAnyOrder("ext-kotlin", "ext-kotlin-deployment", "consumer");
        }

        @Test
        void aTestDependencyOnAKotlinModuleCountsSinceTheTestsAreCompiled() {
            StubModuleBuild kotlin = module("kotlin-lib").withKotlinSources();
            StubModuleBuild tested = module("tested").withTestModuleDeps("kotlin-lib");

            assertThat(skipped(kotlin, tested)).containsExactlyInAnyOrder("kotlin-lib", "tested");
        }

        @Test
        void anOptionalDependencyOnAKotlinModuleDoesNotSkipTheModule() {
            StubModuleBuild kotlin = module("ext-kotlin").withKotlinSources();
            StubModuleBuild ext = module("ext").withOptionalModuleDeps("ext-kotlin");
            StubModuleBuild consumer = module("consumer").withModuleDeps("ext");

            assertThat(skipped(kotlin, ext, consumer)).containsExactly("ext-kotlin");
        }

        @Test
        void anApplicationBuiltWithAKotlinModulesDeploymentJarIsSkipped() {
            StubModuleBuild kotlinDeployment = module("ext-kotlin-deployment").withKotlinSources();
            StubModuleBuild app = module("app").withQuarkusBuildPlugin()
                    .withDeploymentClasspath(installPath(kotlinDeployment));

            assertThat(skipped(kotlinDeployment, app)).containsExactlyInAnyOrder("ext-kotlin-deployment", "app");
        }

        @Test
        void nothingIsSkippedWithoutKotlinModules() {
            assertThat(skipped(module("a"), module("b").withModuleDeps("a"))).isEmpty();
        }
    }

    @Nested
    class VersionMediation {

        private static final String M2 = "/home/u/.m2/repository/";

        @Test
        void dependencyBuiltAgainstAnotherVersionDoesNotAddASecondOne() {
            StubModuleBuild lib = module("lib")
                    .withCompileClasspath(M2 + "io/acme/core/3.0/core-3.0.jar", M2 + "io/acme/util/1.0/util-1.0.jar");
            StubModuleBuild app = module("app")
                    .withModuleDeps("lib")
                    .withCompileClasspath(M2 + "io/acme/core/4.0/core-4.0.jar");

            wireDependencies(lib, app);
            lib.markBuilt();

            assertThat(app.computeCompileClasspath())
                    .contains(M2 + "io/acme/core/4.0/core-4.0.jar", M2 + "io/acme/util/1.0/util-1.0.jar")
                    .doesNotContain(M2 + "io/acme/core/3.0/core-3.0.jar");
        }
    }

    @Nested
    class TestDependencyFiltering {

        @Test
        void testDepNotFilteredWhenOnlyInDeploymentClasspath() {
            StubModuleBuild extRuntime = module("ext-runtime");
            StubModuleBuild extDeployment = module("ext-deployment")
                    .withModuleDeps("ext-runtime");
            StubModuleBuild app = module("app")
                    .withModuleDeps("ext-runtime")
                    .withTestModuleDeps("ext-deployment")
                    .withQuarkusBuildPlugin()
                    .withDeploymentClasspath(installPath(extDeployment));

            wireDependencies(extRuntime, extDeployment, app);

            assertThat(app.getTestDependencies())
                    .extracting(ModuleBuild::artifactId)
                    .containsExactly("ext-deployment");
        }

        @Test
        void testDepFilteredWhenAlreadyInCompileDeps() {
            StubModuleBuild extRuntime = module("ext-runtime");
            StubModuleBuild app = module("app")
                    .withModuleDeps("ext-runtime")
                    .withTestModuleDeps("ext-runtime");

            wireDependencies(extRuntime, app);

            assertThat(app.getTestDependencies())
                    .as("ext-runtime is already a compile dep, should be filtered from test deps")
                    .isEmpty();
        }

        @Test
        void testDepFilteredWhenAlreadyInOptionalDeps() {
            StubModuleBuild optLib = module("optional-lib");
            StubModuleBuild app = module("app")
                    .withOptionalModuleDeps("optional-lib")
                    .withTestModuleDeps("optional-lib");

            wireDependencies(optLib, app);

            assertThat(app.getTestDependencies())
                    .as("optional-lib is an optional compile dep, should be filtered from test deps")
                    .isEmpty();
        }
    }

    @Nested
    class CompileClasspathConstruction {

        @Test
        void compileClasspathIncludesDirectDeps() throws Exception {
            StubModuleBuild lib = module("lib");
            lib.markBuilt();
            Path libJar = createFakeJar(lib);

            StubModuleBuild app = module("app")
                    .withModuleDeps("lib");

            wireDependencies(lib, app);

            List<String> classpath = app.computeCompileClasspath();
            assertThat(classpath).contains(libJar.toString());
        }

        @Test
        void compileClasspathIncludesOptionalDeps() throws Exception {
            StubModuleBuild optLib = module("optional-lib");
            optLib.markBuilt();
            Path optJar = createFakeJar(optLib);

            StubModuleBuild app = module("app")
                    .withOptionalModuleDeps("optional-lib");

            wireDependencies(optLib, app);

            List<String> classpath = app.computeCompileClasspath();
            assertThat(classpath).contains(optJar.toString());
        }

        @Test
        void compileClasspathExcludesDeploymentDeps() throws Exception {
            StubModuleBuild extRuntime = module("ext-runtime");
            extRuntime.markBuilt();
            Path runtimeJar = createFakeJar(extRuntime);

            StubModuleBuild extDeployment = module("ext-deployment")
                    .withModuleDeps("ext-runtime");
            extDeployment.markBuilt();
            Path deploymentJar = createFakeJar(extDeployment);

            StubModuleBuild app = module("app")
                    .withModuleDeps("ext-runtime")
                    .withQuarkusBuildPlugin()
                    .withDeploymentClasspath(installPath(extDeployment));

            wireDependencies(extRuntime, extDeployment, app);

            List<String> classpath = app.computeCompileClasspath();
            assertThat(classpath)
                    .as("deployment module should not be on compile classpath")
                    .contains(runtimeJar.toString())
                    .doesNotContain(deploymentJar.toString());
        }

        @Test
        void deploymentDepTransitiveDepsDoNotLeak() throws Exception {
            StubModuleBuild reactiveLib = module("reactive-lib");
            reactiveLib.markBuilt();
            Path reactiveJar = createFakeJar(reactiveLib);

            StubModuleBuild extRuntime = module("ext-runtime")
                    .withOptionalModuleDeps("reactive-lib");
            extRuntime.markBuilt();
            createFakeJar(extRuntime);

            StubModuleBuild extDeployment = module("ext-deployment")
                    .withModuleDeps("ext-runtime", "reactive-lib");
            extDeployment.markBuilt();
            createFakeJar(extDeployment);

            StubModuleBuild app = module("app")
                    .withModuleDeps("ext-runtime")
                    .withQuarkusBuildPlugin()
                    .withDeploymentClasspath(installPath(extDeployment));

            wireDependencies(reactiveLib, extRuntime, extDeployment, app);

            List<String> classpath = app.computeCompileClasspath();
            assertThat(classpath)
                    .as("reactive-lib from deployment module should not leak onto app's compile classpath")
                    .doesNotContain(reactiveJar.toString());
        }

        @Test
        void externalClasspathFromDeploymentDepDoesNotLeak() throws Exception {
            StubModuleBuild extRuntime = module("ext-runtime");
            extRuntime.markBuilt();
            createFakeJar(extRuntime);

            StubModuleBuild extDeployment = module("ext-deployment")
                    .withModuleDeps("ext-runtime")
                    .withCompileClasspath("/some/deployment-only-lib.jar");
            extDeployment.markBuilt();
            createFakeJar(extDeployment);

            StubModuleBuild app = module("app")
                    .withModuleDeps("ext-runtime")
                    .withQuarkusBuildPlugin()
                    .withDeploymentClasspath(installPath(extDeployment));

            wireDependencies(extRuntime, extDeployment, app);

            List<String> classpath = app.computeCompileClasspath();
            assertThat(classpath)
                    .as("external classpath entries from deployment module should not leak")
                    .doesNotContain("/some/deployment-only-lib.jar");
        }

        @Test
        void optionalDepNotTransitiveToDownstream() throws Exception {
            StubModuleBuild optLib = module("optional-lib");
            optLib.markBuilt();
            Path optJar = createFakeJar(optLib);

            StubModuleBuild lib = module("lib")
                    .withOptionalModuleDeps("optional-lib");
            lib.markBuilt();
            createFakeJar(lib);

            StubModuleBuild app = module("app")
                    .withModuleDeps("lib");

            wireDependencies(optLib, lib, app);

            List<String> classpath = app.computeCompileClasspath();
            assertThat(classpath)
                    .as("optional dep of lib should not appear on app's classpath")
                    .doesNotContain(optJar.toString());
        }

        @Test
        void optionalDepTransitiveReactorDepsDoNotLeak() throws Exception {
            StubModuleBuild deepLib = module("deep-lib");
            deepLib.markBuilt();
            Path deepJar = createFakeJar(deepLib);

            StubModuleBuild optLib = module("optional-lib")
                    .withModuleDeps("deep-lib");
            optLib.markBuilt();
            createFakeJar(optLib);

            StubModuleBuild lib = module("lib")
                    .withOptionalModuleDeps("optional-lib");
            lib.markBuilt();
            createFakeJar(lib);

            StubModuleBuild app = module("app")
                    .withModuleDeps("lib");

            wireDependencies(deepLib, optLib, lib, app);

            List<String> classpath = app.computeCompileClasspath();
            assertThat(classpath)
                    .as("reactor deps of an optional dep should not leak downstream")
                    .doesNotContain(deepJar.toString());
        }

        @Test
        void optionalDepExternalClasspathDoesNotLeak() throws Exception {
            StubModuleBuild optLib = module("optional-lib")
                    .withCompileClasspath("/some/reactive-core.jar");
            optLib.markBuilt();
            createFakeJar(optLib);

            StubModuleBuild lib = module("lib")
                    .withOptionalModuleDeps("optional-lib");
            lib.markBuilt();
            createFakeJar(lib);

            StubModuleBuild app = module("app")
                    .withModuleDeps("lib");

            wireDependencies(optLib, lib, app);

            List<String> classpath = app.computeCompileClasspath();
            assertThat(classpath)
                    .as("external classpath of an optional dep should not leak downstream")
                    .doesNotContain("/some/reactive-core.jar");
        }
    }

    private Path createFakeJar(StubModuleBuild module) throws Exception {
        Path targetDir = projectRoot.resolve(module.artifactId()).resolve("target");
        Files.createDirectories(targetDir);
        Path jar = targetDir.resolve(module.artifactId() + "-" + module.version() + ".jar");
        Files.writeString(jar, "fake");
        return jar;
    }
}
