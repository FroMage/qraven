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
    }

    private Path createFakeJar(StubModuleBuild module) throws Exception {
        Path targetDir = projectRoot.resolve(module.artifactId()).resolve("target");
        Files.createDirectories(targetDir);
        Path jar = targetDir.resolve(module.artifactId() + "-" + module.version() + ".jar");
        Files.writeString(jar, "fake");
        return jar;
    }
}
