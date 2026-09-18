package io.quarkiverse.qraven.hardcoded;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModuleInfo {

    private String groupId;
    private String artifactId;
    private String version;
    private String packaging = "jar";
    private Path baseDir;
    private Path pomFile;
    private List<String> compileClasspath = new ArrayList<>();
    private List<String> annotationProcessorPaths = new ArrayList<>();
    private List<String> compilerArgs = new ArrayList<>();
    private List<String> reactorDependencies = new ArrayList<>();
    private boolean hasJavaSources;
    private boolean hasKotlinSources;
    private List<ResourceDir> resourceDirs = new ArrayList<>();
    private Map<String, String> filterProperties = new LinkedHashMap<>();
    private boolean needsJandexIndex;
    private Map<String, String> manifestEntries = new LinkedHashMap<>();
    private boolean hasProtobufSources;
    private boolean protobufUsesGrpc;
    private boolean protobufUsesMutiny;
    private boolean hasAntlrSources;
    private boolean antlrVisitor;
    private boolean hasExtensionPlugin;
    private String extensionValidationSkipWhen;
    private Map<String, String> extensionDescriptorProperties = new LinkedHashMap<>();
    private String extensionProjectName;
    private String extensionProjectDescription;
    private String extensionScmUrl;
    private String extensionMinimumJavaVersion;
    private List<String> extensionModelDeps = new ArrayList<>();
    private List<String> extensionReactorGAs = new ArrayList<>();
    private List<String> extensionParentFirstArtifacts = new ArrayList<>();
    private List<String> extensionRunnerParentFirstArtifacts = new ArrayList<>();
    private List<String> extensionExcludedArtifacts = new ArrayList<>();
    private List<String> extensionLesserPriorityArtifacts = new ArrayList<>();
    private List<String> extensionProvidesCapabilities = new ArrayList<>();
    private List<String> extensionRequiresCapabilities = new ArrayList<>();
    private boolean hasQuarkusBuildPlugin;
    private String quarkusBuildSkipWhen;
    private boolean hasGenerateCodeGoal;
    private String generateCodeSkipWhen;
    private Map<String, String> quarkusBuildProperties = new LinkedHashMap<>();
    private List<String> deploymentClasspath = new ArrayList<>();
    private List<String> runtimeExtensionArtifacts = new ArrayList<>();
    private Map<String, String> extensionDevProperties = new LinkedHashMap<>();
    private Set<String> optionalClasspathEntries = new LinkedHashSet<>();
    private Set<String> optionalReactorDependencies = new LinkedHashSet<>();
    private boolean apCacheable;
    private boolean hasCodeGenProviders;
    private boolean hasSisuPlugin;
    private String protocVersion;
    private String grpcVersion;
    private String quarkusGrpcVersion;
    private Map<String, String> allReactorExtensionDeployments = new LinkedHashMap<>();
    private Map<String, Set<String>> reactorDependencyExclusions = new LinkedHashMap<>();
    private String parentGroupId;
    private String parentArtifactId;
    private String projectName;
    private String projectDescription;

    public record ResourceDir(String directory, boolean filtering, String targetPath) {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getPackaging() { return packaging; }
    public void setPackaging(String packaging) { this.packaging = packaging; }

    public Path getBaseDir() { return baseDir; }
    public void setBaseDir(Path baseDir) { this.baseDir = baseDir; }

    public Path getPomFile() { return pomFile; }
    public void setPomFile(Path pomFile) { this.pomFile = pomFile; }

    public List<String> getCompileClasspath() { return compileClasspath; }
    public void setCompileClasspath(List<String> compileClasspath) { this.compileClasspath = compileClasspath; }

    public List<String> getAnnotationProcessorPaths() { return annotationProcessorPaths; }
    public void setAnnotationProcessorPaths(List<String> annotationProcessorPaths) { this.annotationProcessorPaths = annotationProcessorPaths; }

    public List<String> getCompilerArgs() { return compilerArgs; }
    public void setCompilerArgs(List<String> compilerArgs) { this.compilerArgs = compilerArgs; }

    public List<String> getReactorDependencies() { return reactorDependencies; }
    public void setReactorDependencies(List<String> reactorDependencies) { this.reactorDependencies = reactorDependencies; }

    public boolean isHasJavaSources() { return hasJavaSources; }
    public void setHasJavaSources(boolean hasJavaSources) { this.hasJavaSources = hasJavaSources; }

    public boolean isHasKotlinSources() { return hasKotlinSources; }
    public void setHasKotlinSources(boolean hasKotlinSources) { this.hasKotlinSources = hasKotlinSources; }

    public List<ResourceDir> getResourceDirs() { return resourceDirs; }
    public void setResourceDirs(List<ResourceDir> resourceDirs) { this.resourceDirs = resourceDirs; }

    public Map<String, String> getFilterProperties() { return filterProperties; }
    public void setFilterProperties(Map<String, String> filterProperties) { this.filterProperties = filterProperties; }

    public boolean isNeedsJandexIndex() { return needsJandexIndex; }
    public void setNeedsJandexIndex(boolean needsJandexIndex) { this.needsJandexIndex = needsJandexIndex; }

    public Map<String, String> getManifestEntries() { return manifestEntries; }
    public void setManifestEntries(Map<String, String> manifestEntries) { this.manifestEntries = manifestEntries; }

    public boolean isHasProtobufSources() { return hasProtobufSources; }
    public void setHasProtobufSources(boolean hasProtobufSources) { this.hasProtobufSources = hasProtobufSources; }

    public boolean isProtobufUsesGrpc() { return protobufUsesGrpc; }
    public void setProtobufUsesGrpc(boolean protobufUsesGrpc) { this.protobufUsesGrpc = protobufUsesGrpc; }

    public boolean isProtobufUsesMutiny() { return protobufUsesMutiny; }
    public void setProtobufUsesMutiny(boolean protobufUsesMutiny) { this.protobufUsesMutiny = protobufUsesMutiny; }

    public boolean isHasAntlrSources() { return hasAntlrSources; }
    public void setHasAntlrSources(boolean hasAntlrSources) { this.hasAntlrSources = hasAntlrSources; }

    public boolean isAntlrVisitor() { return antlrVisitor; }
    public void setAntlrVisitor(boolean antlrVisitor) { this.antlrVisitor = antlrVisitor; }

    public boolean isHasExtensionPlugin() { return hasExtensionPlugin; }
    public void setHasExtensionPlugin(boolean hasExtensionPlugin) { this.hasExtensionPlugin = hasExtensionPlugin; }

    public String getExtensionValidationSkipWhen() { return extensionValidationSkipWhen; }
    public void setExtensionValidationSkipWhen(String v) { this.extensionValidationSkipWhen = v; }

    public Map<String, String> getExtensionDescriptorProperties() { return extensionDescriptorProperties; }
    public void setExtensionDescriptorProperties(Map<String, String> extensionDescriptorProperties) { this.extensionDescriptorProperties = extensionDescriptorProperties; }

    public String getExtensionProjectName() { return extensionProjectName; }
    public void setExtensionProjectName(String extensionProjectName) { this.extensionProjectName = extensionProjectName; }

    public String getExtensionProjectDescription() { return extensionProjectDescription; }
    public void setExtensionProjectDescription(String extensionProjectDescription) { this.extensionProjectDescription = extensionProjectDescription; }

    public String getExtensionScmUrl() { return extensionScmUrl; }
    public void setExtensionScmUrl(String extensionScmUrl) { this.extensionScmUrl = extensionScmUrl; }

    public String getExtensionMinimumJavaVersion() { return extensionMinimumJavaVersion; }
    public void setExtensionMinimumJavaVersion(String extensionMinimumJavaVersion) { this.extensionMinimumJavaVersion = extensionMinimumJavaVersion; }

    public List<String> getExtensionModelDeps() { return extensionModelDeps; }
    public void setExtensionModelDeps(List<String> extensionModelDeps) { this.extensionModelDeps = extensionModelDeps; }

    public List<String> getExtensionReactorGAs() { return extensionReactorGAs; }
    public void setExtensionReactorGAs(List<String> extensionReactorGAs) { this.extensionReactorGAs = extensionReactorGAs; }

    public List<String> getExtensionParentFirstArtifacts() { return extensionParentFirstArtifacts; }
    public void setExtensionParentFirstArtifacts(List<String> v) { this.extensionParentFirstArtifacts = v; }

    public List<String> getExtensionRunnerParentFirstArtifacts() { return extensionRunnerParentFirstArtifacts; }
    public void setExtensionRunnerParentFirstArtifacts(List<String> v) { this.extensionRunnerParentFirstArtifacts = v; }

    public List<String> getExtensionExcludedArtifacts() { return extensionExcludedArtifacts; }
    public void setExtensionExcludedArtifacts(List<String> v) { this.extensionExcludedArtifacts = v; }

    public List<String> getExtensionLesserPriorityArtifacts() { return extensionLesserPriorityArtifacts; }
    public void setExtensionLesserPriorityArtifacts(List<String> v) { this.extensionLesserPriorityArtifacts = v; }

    public List<String> getExtensionProvidesCapabilities() { return extensionProvidesCapabilities; }
    public void setExtensionProvidesCapabilities(List<String> v) { this.extensionProvidesCapabilities = v; }

    public List<String> getExtensionRequiresCapabilities() { return extensionRequiresCapabilities; }
    public void setExtensionRequiresCapabilities(List<String> v) { this.extensionRequiresCapabilities = v; }

    public boolean isHasQuarkusBuildPlugin() { return hasQuarkusBuildPlugin; }
    public void setHasQuarkusBuildPlugin(boolean hasQuarkusBuildPlugin) { this.hasQuarkusBuildPlugin = hasQuarkusBuildPlugin; }

    public String getQuarkusBuildSkipWhen() { return quarkusBuildSkipWhen; }
    public void setQuarkusBuildSkipWhen(String quarkusBuildSkipWhen) { this.quarkusBuildSkipWhen = quarkusBuildSkipWhen; }

    public boolean isHasGenerateCodeGoal() { return hasGenerateCodeGoal; }
    public void setHasGenerateCodeGoal(boolean hasGenerateCodeGoal) { this.hasGenerateCodeGoal = hasGenerateCodeGoal; }

    public String getGenerateCodeSkipWhen() { return generateCodeSkipWhen; }
    public void setGenerateCodeSkipWhen(String generateCodeSkipWhen) { this.generateCodeSkipWhen = generateCodeSkipWhen; }

    public Map<String, String> getQuarkusBuildProperties() { return quarkusBuildProperties; }
    public void setQuarkusBuildProperties(Map<String, String> quarkusBuildProperties) { this.quarkusBuildProperties = quarkusBuildProperties; }

    public List<String> getDeploymentClasspath() { return deploymentClasspath; }
    public void setDeploymentClasspath(List<String> deploymentClasspath) { this.deploymentClasspath = deploymentClasspath; }

    public List<String> getRuntimeExtensionArtifacts() { return runtimeExtensionArtifacts; }
    public void setRuntimeExtensionArtifacts(List<String> runtimeExtensionArtifacts) { this.runtimeExtensionArtifacts = runtimeExtensionArtifacts; }

    public Map<String, String> getExtensionDevProperties() { return extensionDevProperties; }
    public void setExtensionDevProperties(Map<String, String> extensionDevProperties) { this.extensionDevProperties = extensionDevProperties; }

    public Set<String> getOptionalClasspathEntries() { return optionalClasspathEntries; }
    public void setOptionalClasspathEntries(Set<String> optionalClasspathEntries) { this.optionalClasspathEntries = optionalClasspathEntries; }

    public Set<String> getOptionalReactorDependencies() { return optionalReactorDependencies; }
    public void setOptionalReactorDependencies(Set<String> optionalReactorDependencies) { this.optionalReactorDependencies = optionalReactorDependencies; }

    public Map<String, Set<String>> getReactorDependencyExclusions() { return reactorDependencyExclusions; }
    public void addReactorDependencyExclusion(String depArtifactId, String excludedArtifactId) {
        reactorDependencyExclusions.computeIfAbsent(depArtifactId, k -> new LinkedHashSet<>()).add(excludedArtifactId);
    }
    public Set<String> getReactorDependencyExclusionsFor(String depArtifactId) {
        return reactorDependencyExclusions.getOrDefault(depArtifactId, Set.of());
    }

    public boolean isApCacheable() { return apCacheable; }
    public void setApCacheable(boolean apCacheable) { this.apCacheable = apCacheable; }

    public boolean isHasCodeGenProviders() { return hasCodeGenProviders; }
    public void setHasCodeGenProviders(boolean hasCodeGenProviders) { this.hasCodeGenProviders = hasCodeGenProviders; }
    public boolean isHasSisuPlugin() { return hasSisuPlugin; }
    public void setHasSisuPlugin(boolean hasSisuPlugin) { this.hasSisuPlugin = hasSisuPlugin; }

    public String getProtocVersion() { return protocVersion; }
    public void setProtocVersion(String protocVersion) { this.protocVersion = protocVersion; }

    public String getGrpcVersion() { return grpcVersion; }
    public void setGrpcVersion(String grpcVersion) { this.grpcVersion = grpcVersion; }

    public String getQuarkusGrpcVersion() { return quarkusGrpcVersion; }
    public void setQuarkusGrpcVersion(String quarkusGrpcVersion) { this.quarkusGrpcVersion = quarkusGrpcVersion; }

    public Map<String, String> getAllReactorExtensionDeployments() { return allReactorExtensionDeployments; }
    public void setAllReactorExtensionDeployments(Map<String, String> m) { this.allReactorExtensionDeployments = m; }

    public String getParentGroupId() { return parentGroupId; }
    public void setParentGroupId(String parentGroupId) { this.parentGroupId = parentGroupId; }

    public String getParentArtifactId() { return parentArtifactId; }
    public void setParentArtifactId(String parentArtifactId) { this.parentArtifactId = parentArtifactId; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getProjectDescription() { return projectDescription; }
    public void setProjectDescription(String projectDescription) { this.projectDescription = projectDescription; }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version + " [" + packaging + "]";
    }
}
