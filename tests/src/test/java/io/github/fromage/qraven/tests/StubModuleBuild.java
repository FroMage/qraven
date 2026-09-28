package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.BuildRuntime;
import io.github.fromage.qraven.hardcoded.runtime.ModuleBuild;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class StubModuleBuild extends ModuleBuild {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private List<String> moduleDependencyIds = List.of();
    private List<String> optionalModuleDependencyIds = List.of();
    private List<String> testModuleDependencyIds = List.of();
    private List<String> deploymentClasspath = List.of();
    private boolean hasQuarkusBuildPlugin;
    private boolean hasExtensionPlugin;
    private List<String> compileClasspath = List.of();
    private List<String> optionalCompileClasspath = List.of();

    StubModuleBuild(BuildRuntime runtime, String groupId, String artifactId, String version) {
        super(runtime);
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    StubModuleBuild withModuleDeps(String... ids) {
        this.moduleDependencyIds = List.of(ids);
        return this;
    }

    StubModuleBuild withOptionalModuleDeps(String... ids) {
        this.optionalModuleDependencyIds = List.of(ids);
        return this;
    }

    StubModuleBuild withTestModuleDeps(String... ids) {
        this.testModuleDependencyIds = List.of(ids);
        return this;
    }

    StubModuleBuild withDeploymentClasspath(String... paths) {
        this.deploymentClasspath = List.of(paths);
        return this;
    }

    StubModuleBuild withQuarkusBuildPlugin() {
        this.hasQuarkusBuildPlugin = true;
        return this;
    }

    StubModuleBuild withExtensionPlugin() {
        this.hasExtensionPlugin = true;
        return this;
    }

    StubModuleBuild withCompileClasspath(String... entries) {
        this.compileClasspath = List.of(entries);
        return this;
    }

    StubModuleBuild withOptionalCompileClasspath(String... entries) {
        this.optionalCompileClasspath = List.of(entries);
        return this;
    }

    void markBuilt() {
        mainBuildSucceeded = true;
    }

    List<String> computeCompileClasspath() {
        List<String> classpath = new ArrayList<>(resolvedClasspath());
        Set<String> visited = new HashSet<>();
        addReactorJarsImpl(this, classpath, visited, true);
        return classpath;
    }

    @Override public String groupId() { return groupId; }
    @Override public String artifactId() { return artifactId; }
    @Override public String version() { return version; }
    @Override public String packaging() { return "jar"; }
    @Override public Path baseDir() { return Path.of(artifactId); }
    @Override public boolean hasJavaSources() { return false; }
    @Override public boolean hasKotlinSources() { return false; }
    @Override public boolean hasProtobufSources() { return false; }
    @Override public boolean protobufUsesGrpc() { return false; }
    @Override public boolean protobufUsesMutiny() { return false; }
    @Override public String[][] resourceDirs() { return new String[0][]; }
    @Override public Map<String, String> filterProperties() { return Map.of(); }
    @Override public boolean needsJandexIndex() { return false; }
    @Override public Map<String, String> manifestEntries() { return Map.of(); }
    @Override public List<String> compileClasspath() { return compileClasspath; }
    @Override public List<String> optionalCompileClasspath() { return optionalCompileClasspath; }
    @Override public List<String> annotationProcessorPaths() { return List.of(); }
    @Override public boolean isApCacheable() { return false; }
    @Override public List<String> compilerArgs() { return List.of(); }
    @Override public List<String> moduleDependencyIds() { return moduleDependencyIds; }
    @Override public List<String> optionalModuleDependencyIds() { return optionalModuleDependencyIds; }
    @Override public List<String> testModuleDependencyIds() { return testModuleDependencyIds; }
    @Override public boolean hasExtensionPlugin() { return hasExtensionPlugin; }
    @Override public String extensionValidationSkipWhen() { return null; }
    @Override public Map<String, String> extensionDescriptorProperties() { return Map.of(); }
    @Override public String extensionProjectName() { return null; }
    @Override public String extensionProjectDescription() { return null; }
    @Override public String extensionScmUrl() { return null; }
    @Override public String extensionMinimumJavaVersion() { return null; }
    @Override public List<String> extensionModelDeps() { return List.of(); }
    @Override public List<String> extensionReactorGAs() { return List.of(); }
    @Override public List<String> extensionParentFirstArtifacts() { return List.of(); }
    @Override public List<String> extensionRunnerParentFirstArtifacts() { return List.of(); }
    @Override public List<String> extensionExcludedArtifacts() { return List.of(); }
    @Override public List<String> extensionLesserPriorityArtifacts() { return List.of(); }
    @Override public List<String> extensionProvidesCapabilities() { return List.of(); }
    @Override public List<String> extensionRequiresCapabilities() { return List.of(); }
    @Override public boolean hasQuarkusBuildPlugin() { return hasQuarkusBuildPlugin; }
    @Override public String quarkusBuildSkipWhen() { return null; }
    @Override public boolean hasGenerateCodeGoal() { return false; }
    @Override public boolean hasCodeGenProviders() { return false; }
    @Override public boolean hasSisuPlugin() { return false; }
    @Override public String generateCodeSkipWhen() { return null; }
    @Override public Map<String, String> quarkusBuildProperties() { return Map.of(); }
    @Override public List<String> deploymentClasspath() { return deploymentClasspath; }
    @Override public List<String> runtimeExtensionArtifacts() { return List.of(); }
    @Override public Map<String, String> extensionDevProperties() { return Map.of(); }
    @Override public String protocVersion() { return null; }
    @Override public String grpcVersion() { return null; }
    @Override public String quarkusGrpcVersion() { return null; }
    @Override public Map<String, String> allReactorExtensionDeployments() { return Map.of(); }
    @Override public String pluginName() { return null; }
    @Override public String pluginDescription() { return null; }
}
