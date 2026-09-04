package io.quarkiverse.qraven.hardcoded;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private List<ResourceDir> resourceDirs = new ArrayList<>();
    private Map<String, String> filterProperties = new LinkedHashMap<>();
    private boolean needsJandexIndex;
    private Map<String, String> manifestEntries = new LinkedHashMap<>();

    public record ResourceDir(String directory, boolean filtering) {}

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

    public List<ResourceDir> getResourceDirs() { return resourceDirs; }
    public void setResourceDirs(List<ResourceDir> resourceDirs) { this.resourceDirs = resourceDirs; }

    public Map<String, String> getFilterProperties() { return filterProperties; }
    public void setFilterProperties(Map<String, String> filterProperties) { this.filterProperties = filterProperties; }

    public boolean isNeedsJandexIndex() { return needsJandexIndex; }
    public void setNeedsJandexIndex(boolean needsJandexIndex) { this.needsJandexIndex = needsJandexIndex; }

    public Map<String, String> getManifestEntries() { return manifestEntries; }
    public void setManifestEntries(Map<String, String> manifestEntries) { this.manifestEntries = manifestEntries; }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version + " [" + packaging + "]";
    }
}
