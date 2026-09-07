package io.quarkiverse.qraven.hardcoded;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Resource;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class PomParser {

    public interface ProgressListener {
        void update(String phase, String detail, int current, int total);
    }

    private final Path projectRoot;
    private final DependencyResolver resolver;
    private final ModelBuilder modelBuilder;
    private final Path localRepoDir;
    private ProgressListener progressListener;

    public PomParser(Path projectRoot, DependencyResolver resolver) {
        this.projectRoot = projectRoot;
        this.resolver = resolver;
        this.modelBuilder = new DefaultModelBuilderFactory().newInstance();
        this.localRepoDir = resolver.getLocalRepoPath();
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    private void progress(String phase, String detail, int current, int total) {
        if (progressListener != null) {
            progressListener.update(phase, detail, current, total);
        }
    }

    public List<ModuleInfo> parseProject() {
        Map<String, Model> effectiveModels = new LinkedHashMap<>();
        List<ModuleInfo> modules = new ArrayList<>();

        discoverModules(projectRoot.resolve("pom.xml"), projectRoot, modules, effectiveModels);

        Set<String> reactorGAs = new LinkedHashSet<>();
        Map<String, String> gaToArtifactId = new LinkedHashMap<>();
        for (ModuleInfo m : modules) {
            String ga = m.getGroupId() + ":" + m.getArtifactId();
            reactorGAs.add(ga);
            gaToArtifactId.put(ga, m.getArtifactId());
        }

        Map<String, List<String>> skippedModuleReactorDeps = new LinkedHashMap<>();
        for (ModuleInfo m : modules) {
            if (shouldSkipModule(m, effectiveModels)) {
                Model model = effectiveModels.get(m.getGroupId() + ":" + m.getArtifactId());
                if (model != null && model.getDependencies() != null) {
                    List<String> deps = new ArrayList<>();
                    for (Dependency dep : model.getDependencies()) {
                        String scope = dep.getScope() != null ? dep.getScope() : "compile";
                        String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                        if (("compile".equals(scope) || "provided".equals(scope)) && reactorGAs.contains(ga)) {
                            deps.add(gaToArtifactId.get(ga));
                        }
                    }
                    skippedModuleReactorDeps.put(m.getArtifactId(), deps);
                }
            }
        }

        modules.removeIf(m -> shouldSkipModule(m, effectiveModels));

        int resolveIdx = 0;
        for (ModuleInfo info : modules) {
            resolveIdx++;
            Model model = effectiveModels.get(info.getGroupId() + ":" + info.getArtifactId());
            if (model == null) continue;

            extractCompilerConfig(model, info);

            if ("pom".equals(info.getPackaging())) {
                progress("resolve", info.getArtifactId(), resolveIdx, modules.size());
                continue;
            }

            progress("resolve", info.getArtifactId(), resolveIdx, modules.size());
            resolveDependencies(model, info, reactorGAs);

            List<String> extraDeps = new ArrayList<>();
            for (String depId : new ArrayList<>(info.getReactorDependencies())) {
                if (skippedModuleReactorDeps.containsKey(depId)) {
                    for (String transitiveDep : skippedModuleReactorDeps.get(depId)) {
                        if (!info.getReactorDependencies().contains(transitiveDep)
                                && !extraDeps.contains(transitiveDep)
                                && !skippedModuleReactorDeps.containsKey(transitiveDep)) {
                            extraDeps.add(transitiveDep);
                        }
                    }
                    info.getReactorDependencies().remove(depId);
                }
            }
            info.getReactorDependencies().addAll(extraDeps);
        }

        List<String> allReactorGAsList = new ArrayList<>(reactorGAs);
        for (ModuleInfo info : modules) {
            if (info.isHasExtensionPlugin()) {
                Model model = effectiveModels.get(info.getGroupId() + ":" + info.getArtifactId());
                if (model != null) {
                    collectExtensionMetadata(model, info, allReactorGAsList);
                }
            }
        }

        return modules;
    }

    private void collectExtensionMetadata(Model model, ModuleInfo info, List<String> reactorGAs) {
        info.getExtensionDescriptorProperties().put("groupId", info.getGroupId());
        info.getExtensionDescriptorProperties().put("artifactId", info.getArtifactId());
        info.getExtensionDescriptorProperties().put("version", info.getVersion());

        info.setExtensionProjectName(model.getName());
        info.setExtensionProjectDescription(model.getDescription());
        info.setExtensionReactorGAs(reactorGAs);

        String scmUrl = findScmUrl(model);
        info.setExtensionScmUrl(scmUrl);

        Properties props = model.getProperties();
        String release = props != null ? props.getProperty("maven.compiler.release") : null;
        info.setExtensionMinimumJavaVersion(release);

        List<String> modelDeps = new ArrayList<>();
        if (model.getDependencies() != null) {
            for (Dependency dep : model.getDependencies()) {
                String classifier = dep.getClassifier() != null ? dep.getClassifier() : "";
                String type = dep.getType() != null ? dep.getType() : "jar";
                String scope = dep.getScope() != null ? dep.getScope() : "compile";
                String version = dep.getVersion() != null ? dep.getVersion() : "";
                modelDeps.add(dep.getGroupId() + ":" + dep.getArtifactId() + ":"
                        + classifier + ":" + type + ":" + version + ":" + scope
                        + ":" + dep.isOptional());
            }
        }
        info.setExtensionModelDeps(modelDeps);
    }

    private String findScmUrl(Model model) {
        if (model.getScm() != null && model.getScm().getUrl() != null) {
            return model.getScm().getUrl();
        }
        return null;
    }

    private boolean shouldSkipModule(ModuleInfo info, Map<String, Model> effectiveModels) {
        if ("maven-plugin".equals(info.getPackaging())) {
            System.out.println("  Skipping maven-plugin module: " + info.getArtifactId());
            return true;
        }

        Model model = effectiveModels.get(info.getGroupId() + ":" + info.getArtifactId());
        if (model == null || model.getBuild() == null) return false;

        return false;
    }

    private void discoverModules(Path pomFile, Path baseDir, List<ModuleInfo> modules,
                                  Map<String, Model> effectiveModels) {
        Model model = buildEffectiveModel(pomFile);
        if (model == null) {
            System.err.println("WARNING: Failed to build effective model for " + pomFile);
            return;
        }

        ModuleInfo info = toModuleInfo(model, baseDir);
        modules.add(info);
        effectiveModels.put(info.getGroupId() + ":" + info.getArtifactId(), model);
        progress("scan", info.getArtifactId(), modules.size(), 0);

        for (String moduleName : model.getModules()) {
            Path moduleDir = baseDir.resolve(moduleName);
            Path modulePom = moduleDir.resolve("pom.xml");
            if (Files.exists(modulePom)) {
                discoverModules(modulePom, moduleDir, modules, effectiveModels);
            } else {
                System.err.println("WARNING: Module POM not found: " + modulePom);
            }
        }
    }

    private Model buildEffectiveModel(Path pomFile) {
        try {
            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
            request.setPomFile(pomFile.toFile());
            request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
            request.setProcessPlugins(true);
            request.setTwoPhaseBuilding(false);
            request.setSystemProperties(System.getProperties());
            request.setUserProperties(new Properties());
            request.setModelResolver(new LocalRepoModelResolver(localRepoDir, resolver));

            ModelBuildingResult result = modelBuilder.build(request);
            return result.getEffectiveModel();
        } catch (ModelBuildingException e) {
            System.err.println("WARNING: Model building problems for " + pomFile + ": " + e.getMessage());
            try {
                return e.getResult() != null ? e.getResult().getEffectiveModel() : null;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private ModuleInfo toModuleInfo(Model model, Path baseDir) {
        ModuleInfo info = new ModuleInfo();
        info.setGroupId(resolveGroupId(model));
        info.setArtifactId(model.getArtifactId());
        info.setVersion(resolveVersion(model));
        info.setPackaging(model.getPackaging() != null ? model.getPackaging() : "jar");
        info.setBaseDir(projectRoot.relativize(baseDir));
        info.setPomFile(baseDir.resolve("pom.xml").toAbsolutePath());

        Path srcMain = baseDir.resolve("src/main/java");
        info.setHasJavaSources(Files.isDirectory(srcMain) && hasJavaFiles(srcMain));

        Path kotlinSrcMain = baseDir.resolve("src/main/kotlin");
        info.setHasKotlinSources(Files.isDirectory(kotlinSrcMain) && hasKotlinFiles(kotlinSrcMain));

        extractResourceDirs(model, baseDir, info);
        extractFilterProperties(model, info);
        detectJandexPlugin(model, info);
        detectProtobufPlugin(model, baseDir, info);
        detectExtensionPlugin(model, info);
        extractManifestEntries(model, info);

        return info;
    }

    private void extractResourceDirs(Model model, Path baseDir, ModuleInfo info) {
        List<ModuleInfo.ResourceDir> resourceDirs = new ArrayList<>();

        if (model.getBuild() != null && model.getBuild().getResources() != null
                && !model.getBuild().getResources().isEmpty()) {
            for (Resource resource : model.getBuild().getResources()) {
                String dir = resource.getDirectory();
                Path dirPath = Path.of(dir);
                if (dirPath.isAbsolute()) {
                    dirPath = baseDir.toAbsolutePath().relativize(dirPath);
                }
                if (Files.isDirectory(baseDir.resolve(dirPath))) {
                    resourceDirs.add(new ModuleInfo.ResourceDir(
                            dirPath.toString(), resource.isFiltering()));
                }
            }
        } else {
            Path resMain = baseDir.resolve("src/main/resources");
            if (Files.isDirectory(resMain)) {
                resourceDirs.add(new ModuleInfo.ResourceDir("src/main/resources", false));
            }
        }

        info.setResourceDirs(resourceDirs);
    }

    private void extractFilterProperties(Model model, ModuleInfo info) {
        boolean hasFiltering = info.getResourceDirs().stream().anyMatch(ModuleInfo.ResourceDir::filtering);
        if (!hasFiltering) return;

        Map<String, String> props = new LinkedHashMap<>();
        props.put("project.groupId", info.getGroupId());
        props.put("project.artifactId", info.getArtifactId());
        props.put("project.version", info.getVersion());
        if (model.getName() != null) props.put("project.name", model.getName());
        if (model.getDescription() != null) props.put("project.description", model.getDescription());

        Properties modelProps = model.getProperties();
        if (modelProps != null) {
            for (String key : modelProps.stringPropertyNames()) {
                props.put(key, modelProps.getProperty(key));
            }
        }

        info.setFilterProperties(props);
    }

    private void detectJandexPlugin(Model model, ModuleInfo info) {
        if (model.getBuild() == null) return;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if ("jandex-maven-plugin".equals(plugin.getArtifactId())) {
                info.setNeedsJandexIndex(true);
                return;
            }
        }
    }

    private void detectProtobufPlugin(Model model, Path baseDir, ModuleInfo info) {
        if (model.getBuild() == null) return;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (!"protobuf-maven-plugin".equals(plugin.getArtifactId())) continue;

            boolean hasMainCompile = false;
            boolean hasCustom = false;
            for (PluginExecution exec : plugin.getExecutions()) {
                for (String goal : exec.getGoals()) {
                    if ("compile".equals(goal)) hasMainCompile = true;
                    if ("compile-custom".equals(goal)) hasCustom = true;
                }
            }

            if (!hasMainCompile) return;

            Path protoDir = baseDir.resolve("src/main/proto");
            if (!Files.isDirectory(protoDir)) return;
            try (var stream = Files.walk(protoDir)) {
                if (stream.noneMatch(p -> p.toString().endsWith(".proto"))) return;
            } catch (IOException e) {
                return;
            }

            info.setHasProtobufSources(true);

            Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
            if (config != null) {
                Xpp3Dom pluginArtifact = config.getChild("pluginArtifact");
                if (pluginArtifact != null && pluginArtifact.getValue() != null
                        && pluginArtifact.getValue().contains("grpc-java")) {
                    info.setProtobufUsesGrpc(hasCustom);
                }
                Xpp3Dom protocPlugins = config.getChild("protocPlugins");
                if (protocPlugins != null && hasCustom) {
                    for (Xpp3Dom protocPlugin : protocPlugins.getChildren("protocPlugin")) {
                        Xpp3Dom mainClass = protocPlugin.getChild("mainClass");
                        if (mainClass != null && mainClass.getValue() != null
                                && mainClass.getValue().contains("MutinyGrpcGenerator")) {
                            info.setProtobufUsesMutiny(true);
                        }
                    }
                }
            }
            return;
        }
    }

    private void detectExtensionPlugin(Model model, ModuleInfo info) {
        if (model.getBuild() == null) return;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (!"quarkus-extension-maven-plugin".equals(plugin.getArtifactId())) continue;

            boolean hasDescriptorGoal = false;
            for (PluginExecution exec : plugin.getExecutions()) {
                if (exec.getGoals().contains("extension-descriptor")) {
                    hasDescriptorGoal = true;

                    Xpp3Dom execConfig = (Xpp3Dom) exec.getConfiguration();
                    if (execConfig != null) {
                        extractExtensionConfig(execConfig, info);
                    }
                    break;
                }
            }
            if (!hasDescriptorGoal) continue;

            info.setHasExtensionPlugin(true);

            if (info.getExtensionDescriptorProperties().isEmpty()) {
                Xpp3Dom globalConfig = (Xpp3Dom) plugin.getConfiguration();
                if (globalConfig != null) {
                    extractExtensionConfig(globalConfig, info);
                }
            }

            if (!info.getExtensionDescriptorProperties().containsKey("deployment-artifact")) {
                String deployment = info.getGroupId() + ":" + info.getArtifactId() + "-deployment:" + info.getVersion();
                info.getExtensionDescriptorProperties().put("deployment-artifact", deployment);
            }
            return;
        }
    }

    private void extractExtensionConfig(Xpp3Dom config, ModuleInfo info) {
        Map<String, String> props = info.getExtensionDescriptorProperties();

        Xpp3Dom depNode = config.getChild("deployment");
        if (depNode != null && depNode.getValue() != null && !depNode.getValue().isBlank()) {
            props.put("deployment-artifact", depNode.getValue());
        }

        extractStringListProperty(config, "excludedArtifacts", "excluded-artifacts", props);
        extractStringListProperty(config, "parentFirstArtifacts", "parent-first-artifacts", props);
        extractStringListProperty(config, "runnerParentFirstArtifacts", "runner-parent-first-artifacts", props);
        extractStringListProperty(config, "lesserPriorityArtifacts", "lesser-priority-artifacts", props);
        extractStringListProperty(config, "conditionalDependencies", "conditional-dependencies", props);
        extractStringListProperty(config, "dependencyCondition", "dependency-condition", props);
    }

    private void extractStringListProperty(Xpp3Dom config, String xmlName, String propKey,
                                            Map<String, String> props) {
        Xpp3Dom node = config.getChild(xmlName);
        if (node == null) return;
        List<String> values = new ArrayList<>();
        for (Xpp3Dom child : node.getChildren()) {
            if (child.getValue() != null && !child.getValue().isBlank()) {
                values.add(child.getValue());
            }
        }
        if (!values.isEmpty()) {
            props.put(propKey, String.join(",", values));
        }
    }

    private void extractManifestEntries(Model model, ModuleInfo info) {
        if (model.getBuild() == null) return;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (!"maven-jar-plugin".equals(plugin.getArtifactId())) continue;
            Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
            if (config == null) continue;
            Xpp3Dom archive = config.getChild("archive");
            if (archive == null) continue;
            Xpp3Dom entries = archive.getChild("manifestEntries");
            if (entries == null) continue;
            Map<String, String> manifestEntries = new LinkedHashMap<>();
            for (Xpp3Dom entry : entries.getChildren()) {
                if (entry.getValue() != null) {
                    manifestEntries.put(entry.getName(), entry.getValue());
                }
            }
            info.setManifestEntries(manifestEntries);
        }
    }

    private boolean hasJavaFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasKotlinFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".kt"));
        } catch (Exception e) {
            return false;
        }
    }

    private void extractCompilerConfig(Model model, ModuleInfo info) {
        List<String> compilerArgs = new ArrayList<>();
        List<String> annotationProcessorPaths = new ArrayList<>();

        List<Dependency> managedDeps = model.getDependencyManagement() != null
                ? model.getDependencyManagement().getDependencies() : List.of();

        if (model.getBuild() != null && model.getBuild().getPluginManagement() != null) {
            extractCompilerConfigFromPlugins(
                    model.getBuild().getPluginManagement().getPlugins(),
                    compilerArgs, annotationProcessorPaths, managedDeps);
        }

        List<String> explicitArgs = new ArrayList<>();
        List<String> explicitAPPaths = new ArrayList<>();
        if (model.getBuild() != null) {
            extractCompilerConfigFromPlugins(model.getBuild().getPlugins(),
                    explicitArgs, explicitAPPaths, managedDeps);
        }
        if (!explicitArgs.isEmpty()) {
            compilerArgs = explicitArgs;
        }
        if (!explicitAPPaths.isEmpty()) {
            annotationProcessorPaths = explicitAPPaths;
        }

        Properties props = model.getProperties();
        String releaseProperty = props != null ? props.getProperty("maven.compiler.release") : null;
        boolean hasRelease = compilerArgs.contains("--release")
                || (releaseProperty != null && !releaseProperty.isBlank());
        if (hasRelease) {
            for (int i = compilerArgs.size() - 2; i >= 0; i--) {
                if (compilerArgs.get(i).equals("-source") || compilerArgs.get(i).equals("-target")) {
                    compilerArgs.remove(i + 1);
                    compilerArgs.remove(i);
                }
            }
            if (!compilerArgs.contains("--release")) {
                compilerArgs.add("--release");
                compilerArgs.add(releaseProperty);
            }
        } else {
            boolean hasVersionFlag = compilerArgs.stream()
                    .anyMatch(a -> a.equals("-source") || a.equals("-target"));
            if (!hasVersionFlag) {
                String source = props != null ? props.getProperty("maven.compiler.source") : null;
                String target = props != null ? props.getProperty("maven.compiler.target") : null;
                if (source != null && !source.isBlank()) {
                    compilerArgs.add("-source");
                    compilerArgs.add(source);
                }
                if (target != null && !target.isBlank()) {
                    compilerArgs.add("-target");
                    compilerArgs.add(target);
                }
            }
        }

        if (compilerArgs.isEmpty()) {
            compilerArgs.add("-parameters");
        }

        info.setCompilerArgs(compilerArgs);
        info.setAnnotationProcessorPaths(annotationProcessorPaths);
    }

    private void extractCompilerConfigFromPlugins(List<Plugin> plugins,
                                                   List<String> compilerArgs,
                                                   List<String> annotationProcessorPaths,
                                                   List<Dependency> managedDeps) {
        for (Plugin plugin : plugins) {
            if (!"maven-compiler-plugin".equals(plugin.getArtifactId())) {
                continue;
            }
            Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
            if (config == null) {
                continue;
            }

            Xpp3Dom releaseNode = config.getChild("release");
            if (releaseNode != null && releaseNode.getValue() != null && !releaseNode.getValue().isBlank()) {
                compilerArgs.add("--release");
                compilerArgs.add(releaseNode.getValue());
            } else {
                Xpp3Dom sourceNode = config.getChild("source");
                Xpp3Dom targetNode = config.getChild("target");
                if (sourceNode != null && sourceNode.getValue() != null && !sourceNode.getValue().isBlank()) {
                    compilerArgs.add("-source");
                    compilerArgs.add(sourceNode.getValue());
                }
                if (targetNode != null && targetNode.getValue() != null && !targetNode.getValue().isBlank()) {
                    compilerArgs.add("-target");
                    compilerArgs.add(targetNode.getValue());
                }
            }

            Xpp3Dom argsNode = config.getChild("compilerArgs");
            if (argsNode != null) {
                for (Xpp3Dom arg : argsNode.getChildren()) {
                    if (arg.getValue() != null && !arg.getValue().isBlank()) {
                        compilerArgs.add(arg.getValue());
                    }
                }
            }

            Xpp3Dom appNode = config.getChild("annotationProcessorPaths");
            if (appNode != null) {
                for (Xpp3Dom pathEntry : appNode.getChildren()) {
                    Xpp3Dom gidNode = pathEntry.getChild("groupId");
                    Xpp3Dom aidNode = pathEntry.getChild("artifactId");
                    Xpp3Dom verNode = pathEntry.getChild("version");
                    if (gidNode != null && aidNode != null && verNode != null) {
                        List<String> resolved = resolver.resolveAnnotationProcessorPath(
                                gidNode.getValue(), aidNode.getValue(), verNode.getValue(),
                                managedDeps);
                        annotationProcessorPaths.addAll(resolved);
                    }
                }
            }
        }
    }

    private void resolveDependencies(Model model, ModuleInfo info, Set<String> reactorGAs) {
        if (model.getDependencies() == null || model.getDependencies().isEmpty()) {
            return;
        }

        Map<String, String> managedVersions = new LinkedHashMap<>();
        if (model.getDependencyManagement() != null && model.getDependencyManagement().getDependencies() != null) {
            for (Dependency md : model.getDependencyManagement().getDependencies()) {
                if (md.getVersion() != null && !md.getVersion().isBlank()) {
                    managedVersions.put(md.getGroupId() + ":" + md.getArtifactId(), md.getVersion());
                }
            }
        }

        List<Dependency> externalDeps = new ArrayList<>();
        for (Dependency dep : model.getDependencies()) {
            String scope = dep.getScope() != null ? dep.getScope() : "compile";
            String ga = dep.getGroupId() + ":" + dep.getArtifactId();
            if (("compile".equals(scope) || "provided".equals(scope)) && reactorGAs.contains(ga)) {
                info.getReactorDependencies().add(dep.getArtifactId());
                continue;
            }
            if (!"test".equals(scope) && !reactorGAs.contains(ga)) {
                String version = dep.getVersion();
                if (version == null || version.isBlank()) {
                    version = managedVersions.get(ga);
                }
                if (version != null && !version.isBlank()) {
                    if (dep.getVersion() == null || dep.getVersion().isBlank()) {
                        dep.setVersion(version);
                    }
                    externalDeps.add(dep);
                }
            }
        }

        if (externalDeps.isEmpty()) {
            return;
        }

        List<Dependency> managedDeps = model.getDependencyManagement() != null
                ? model.getDependencyManagement().getDependencies() : List.of();

        List<DependencyResolver.ResolvedArtifact> resolved =
                resolver.resolveCompileClasspath(externalDeps, managedDeps);

        List<String> externalClasspath = new ArrayList<>();
        for (DependencyResolver.ResolvedArtifact art : resolved) {
            externalClasspath.add(art.filePath());
        }
        info.setCompileClasspath(externalClasspath);
    }

    private String resolveGroupId(Model model) {
        if (model.getGroupId() != null) {
            return model.getGroupId();
        }
        if (model.getParent() != null) {
            return model.getParent().getGroupId();
        }
        return "unknown";
    }

    private String resolveVersion(Model model) {
        if (model.getVersion() != null) {
            return model.getVersion();
        }
        if (model.getParent() != null) {
            return model.getParent().getVersion();
        }
        return "0.0.0";
    }
}
