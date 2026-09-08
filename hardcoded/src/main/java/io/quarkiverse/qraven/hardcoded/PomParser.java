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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

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

        for (ModuleInfo info : modules) {
            if (info.isHasQuarkusBuildPlugin()) {
                Model model = effectiveModels.get(info.getGroupId() + ":" + info.getArtifactId());
                if (model != null) {
                    collectQuarkusBuildMetadata(model, info, reactorGAs, modules);
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

        if (model.getBuild() != null) {
            for (Plugin plugin : model.getBuild().getPlugins()) {
                if (!"quarkus-extension-maven-plugin".equals(plugin.getArtifactId())) continue;
                Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
                if (config == null) continue;
                info.setExtensionParentFirstArtifacts(extractPluginListConfig(config, "parentFirstArtifacts", "parentFirstArtifact"));
                info.setExtensionRunnerParentFirstArtifacts(extractPluginListConfig(config, "runnerParentFirstArtifacts", "runnerParentFirstArtifact"));
                info.setExtensionExcludedArtifacts(extractPluginListConfig(config, "excludedArtifacts", "excludedArtifact"));
                info.setExtensionLesserPriorityArtifacts(extractPluginListConfig(config, "lesserPriorityArtifacts", "lesserPriorityArtifact"));
                extractCapabilities(config, info);
                break;
            }
        }
    }

    private void extractCapabilities(Xpp3Dom config, ModuleInfo info) {
        Xpp3Dom caps = config.getChild("capabilities");
        if (caps == null) return;
        List<String> provides = new ArrayList<>();
        List<String> requires = new ArrayList<>();
        for (Xpp3Dom child : caps.getChildren()) {
            String name = child.getName();
            String capStr = parseCapabilityElement(child);
            if (capStr == null) continue;
            if ("provides".equals(name)) {
                provides.add(capStr);
            } else if ("requires".equals(name)) {
                requires.add(capStr);
            }
        }
        info.setExtensionProvidesCapabilities(provides);
        info.setExtensionRequiresCapabilities(requires);
    }

    private String parseCapabilityElement(Xpp3Dom element) {
        if (element.getValue() != null && !element.getValue().isBlank()) {
            return element.getValue().trim();
        }
        Xpp3Dom nameNode = element.getChild("name");
        if (nameNode == null || nameNode.getValue() == null) return null;
        StringBuilder sb = new StringBuilder(nameNode.getValue().trim());
        Xpp3Dom onlyIf = element.getChild("onlyIf");
        if (onlyIf != null && onlyIf.getValue() != null) {
            sb.append("?").append(onlyIf.getValue().trim());
        }
        Xpp3Dom onlyIfNot = element.getChild("onlyIfNot");
        if (onlyIfNot != null && onlyIfNot.getValue() != null) {
            sb.append("?!").append(onlyIfNot.getValue().trim());
        }
        return sb.toString();
    }

    private List<String> extractPluginListConfig(Xpp3Dom config, String parentName, String childName) {
        Xpp3Dom parent = config.getChild(parentName);
        if (parent == null) return List.of();
        List<String> result = new ArrayList<>();
        for (Xpp3Dom child : parent.getChildren()) {
            if (child.getValue() != null && !child.getValue().isBlank()) {
                result.add(child.getValue());
            }
        }
        return result;
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
        detectQuarkusBuildPlugin(model, info);
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

    private void detectQuarkusBuildPlugin(Model model, ModuleInfo info) {
        if (model.getBuild() == null) return;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (!"quarkus-maven-plugin".equals(plugin.getArtifactId())) continue;
            for (PluginExecution exec : plugin.getExecutions()) {
                if (exec.getGoals().contains("build")) {
                    info.setHasQuarkusBuildPlugin(true);
                    return;
                }
            }
        }
    }

    private void collectQuarkusBuildMetadata(Model model, ModuleInfo info, Set<String> reactorGAs,
                                              List<ModuleInfo> allModules) {
        Map<String, String> buildProps = new LinkedHashMap<>();
        Properties modelProps = model.getProperties();
        if (modelProps != null) {
            for (String key : modelProps.stringPropertyNames()) {
                if (key.startsWith("quarkus.")) {
                    buildProps.put(key, modelProps.getProperty(key));
                }
            }
        }
        buildProps.putIfAbsent("quarkus.application.name", info.getArtifactId());
        buildProps.putIfAbsent("quarkus.application.version", info.getVersion());
        collectPlatformProperties(model, buildProps);
        info.setQuarkusBuildProperties(buildProps);

        List<String> extensionArtifacts = new ArrayList<>();
        Map<String, String> extensionDevProps = new LinkedHashMap<>();
        Set<String> deploymentGAVs = new LinkedHashSet<>();

        for (String jarPath : info.getCompileClasspath()) {
            scanExtensionJar(jarPath, extensionArtifacts, extensionDevProps, deploymentGAVs);
        }

        // also scan reactor dependencies for extension properties
        Set<String> reactorDepIds = new LinkedHashSet<>(info.getReactorDependencies());
        collectTransitiveReactorDeps(reactorDepIds, allModules);
        for (String depId : reactorDepIds) {
            for (ModuleInfo m : allModules) {
                if (!m.getArtifactId().equals(depId)) continue;
                if ("pom".equals(m.getPackaging())) continue;
                if (m.isHasExtensionPlugin()) {
                    // Build extension properties from POM data (avoids stale JAR data)
                    buildReactorExtensionProps(m, extensionArtifacts, extensionDevProps, deploymentGAVs);
                } else {
                    String jarPath = resolver.resolveArtifactPath(m.getGroupId(), m.getArtifactId(), m.getVersion());
                    if (jarPath != null) {
                        scanExtensionJar(jarPath, extensionArtifacts, extensionDevProps, deploymentGAVs);
                    }
                }
                break;
            }
        }

        info.setRuntimeExtensionArtifacts(extensionArtifacts);
        info.setExtensionDevProperties(extensionDevProps);

        List<String> deploymentClasspath = resolveDeploymentClasspath(deploymentGAVs, info, reactorGAs, allModules);
        info.setDeploymentClasspath(deploymentClasspath);
    }

    private void buildReactorExtensionProps(ModuleInfo m, List<String> extensionArtifacts,
                                               Map<String, String> extensionDevProps, Set<String> deploymentGAVs) {
        String ga = m.getGroupId() + ":" + m.getArtifactId();
        if (extensionArtifacts.contains(ga)) return;

        String deploymentArtifact = m.getExtensionDescriptorProperties().get("deployment-artifact");
        if (deploymentArtifact == null) return;

        extensionArtifacts.add(ga);
        deploymentGAVs.add(deploymentArtifact);

        StringBuilder packed = new StringBuilder();
        packed.append("deployment-artifact=").append(deploymentArtifact).append("\n");

        if (!m.getExtensionParentFirstArtifacts().isEmpty()) {
            packed.append("parent-first-artifacts=")
                    .append(String.join(",", m.getExtensionParentFirstArtifacts())).append("\n");
        }
        if (!m.getExtensionRunnerParentFirstArtifacts().isEmpty()) {
            packed.append("runner-parent-first-artifacts=")
                    .append(String.join(",", m.getExtensionRunnerParentFirstArtifacts())).append("\n");
        }
        if (!m.getExtensionExcludedArtifacts().isEmpty()) {
            packed.append("excluded-artifacts=")
                    .append(String.join(",", m.getExtensionExcludedArtifacts())).append("\n");
        }
        if (!m.getExtensionLesserPriorityArtifacts().isEmpty()) {
            packed.append("lesser-priority-artifacts=")
                    .append(String.join(",", m.getExtensionLesserPriorityArtifacts())).append("\n");
        }
        if (!m.getExtensionProvidesCapabilities().isEmpty()) {
            packed.append("provides-capabilities=")
                    .append(String.join(",", m.getExtensionProvidesCapabilities())).append("\n");
        }
        if (!m.getExtensionRequiresCapabilities().isEmpty()) {
            packed.append("requires-capabilities=")
                    .append(String.join(",", m.getExtensionRequiresCapabilities())).append("\n");
        }

        extensionDevProps.put(ga, packed.toString());
    }

    private void collectTransitiveReactorDeps(Set<String> result, List<ModuleInfo> allModules) {
        int prevSize;
        do {
            prevSize = result.size();
            for (ModuleInfo m : allModules) {
                if (result.contains(m.getArtifactId())) {
                    Set<String> optionalDeps = m.getOptionalReactorDependencies();
                    for (String dep : m.getReactorDependencies()) {
                        if (!optionalDeps.contains(dep)) {
                            result.add(dep);
                        }
                    }
                }
            }
        } while (result.size() > prevSize);
    }

    private void scanExtensionJar(String jarPath, List<String> extensionArtifacts,
                                   Map<String, String> extensionDevProps, Set<String> deploymentGAVs) {
        Properties extProps = readExtensionProperties(jarPath);
        if (extProps == null) return;

        String extKey = parseGAFromM2Path(jarPath);
        if (extKey == null) return;
        if (extensionArtifacts.contains(extKey)) return;
        extensionArtifacts.add(extKey);

        String deploymentArtifact = extProps.getProperty("deployment-artifact");
        if (deploymentArtifact != null) {
            deploymentGAVs.add(deploymentArtifact);
        }

        StringBuilder packed = new StringBuilder();
        for (String propName : extProps.stringPropertyNames()) {
            packed.append(propName).append("=").append(extProps.getProperty(propName)).append("\n");
        }
        extensionDevProps.put(extKey, packed.toString());
    }

    private void collectPlatformProperties(Model model, Map<String, String> buildProps) {
        if (model.getDependencyManagement() == null) return;
        for (Dependency dep : model.getDependencyManagement().getDependencies()) {
            if (!"properties".equals(dep.getType())) continue;
            if (!dep.getArtifactId().endsWith("-platform-properties")) continue;
            Path propsFile = localRepoDir
                    .resolve(dep.getGroupId().replace('.', '/'))
                    .resolve(dep.getArtifactId())
                    .resolve(dep.getVersion())
                    .resolve(dep.getArtifactId() + "-" + dep.getVersion() + ".properties");
            if (!Files.exists(propsFile)) continue;
            try (InputStream is = Files.newInputStream(propsFile)) {
                Properties props = new Properties();
                props.load(is);
                for (String key : props.stringPropertyNames()) {
                    if (key.startsWith("platform.")) {
                        buildProps.putIfAbsent(key, props.getProperty(key));
                    }
                }
            } catch (IOException e) {
                // skip unreadable platform properties
            }
        }
    }

    private String parseGAFromM2Path(String jarPath) {
        String m2 = System.getProperty("user.home") + "/.m2/repository/";
        if (!jarPath.startsWith(m2)) return null;
        String relative = jarPath.substring(m2.length());
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String beforeFile = relative.substring(0, lastSlash);
        int versionSlash = beforeFile.lastIndexOf('/');
        if (versionSlash < 0) return null;
        String beforeVersion = beforeFile.substring(0, versionSlash);
        int artifactSlash = beforeVersion.lastIndexOf('/');
        if (artifactSlash < 0) return null;
        String artifactId = beforeVersion.substring(artifactSlash + 1);
        String groupId = beforeVersion.substring(0, artifactSlash).replace('/', '.');
        return groupId + ":" + artifactId;
    }

    private Properties readExtensionProperties(String jarPath) {
        Path path = Path.of(jarPath);
        if (!Files.exists(path) || Files.isDirectory(path)) return null;
        try (JarFile jf = new JarFile(path.toFile())) {
            var entry = jf.getJarEntry("META-INF/quarkus-extension.properties");
            if (entry == null) return null;
            Properties props = new Properties();
            try (InputStream is = jf.getInputStream(entry)) {
                props.load(is);
            }
            return props;
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> resolveDeploymentClasspath(Set<String> deploymentGAVs, ModuleInfo info,
                                                     Set<String> reactorGAs, List<ModuleInfo> allModules) {
        if (deploymentGAVs.isEmpty()) return List.of();

        List<org.apache.maven.model.Dependency> externalDeploymentDeps = new ArrayList<>();
        List<String> reactorDeploymentArtifactIds = new ArrayList<>();

        for (String gav : deploymentGAVs) {
            String[] parts = gav.split(":");
            if (parts.length < 3) continue;
            String ga = parts[0] + ":" + parts[1];
            if (reactorGAs.contains(ga)) {
                reactorDeploymentArtifactIds.add(parts[1]);
            } else {
                org.apache.maven.model.Dependency dep = new org.apache.maven.model.Dependency();
                dep.setGroupId(parts[0]);
                dep.setArtifactId(parts[1]);
                dep.setVersion(parts[2]);
                dep.setScope("compile");
                externalDeploymentDeps.add(dep);
            }
        }

        Set<String> runtimePaths = new LinkedHashSet<>(info.getCompileClasspath());
        List<String> deploymentOnly = new ArrayList<>();

        // Resolve external deployment deps via Aether
        if (!externalDeploymentDeps.isEmpty()) {
            List<DependencyResolver.ResolvedArtifact> resolved =
                    resolver.resolveCompileClasspath(externalDeploymentDeps, List.of());
            for (DependencyResolver.ResolvedArtifact art : resolved) {
                if (!runtimePaths.contains(art.filePath())) {
                    deploymentOnly.add(art.filePath());
                }
            }
        }

        // For reactor deployment modules, collect their classpaths directly
        Set<String> visited = new LinkedHashSet<>();
        for (String artifactId : reactorDeploymentArtifactIds) {
            collectReactorModuleClasspath(artifactId, allModules, deploymentOnly, runtimePaths, visited);
        }

        return deploymentOnly;
    }

    private void collectReactorModuleClasspath(String artifactId, List<ModuleInfo> allModules,
                                                List<String> result, Set<String> exclude, Set<String> visited) {
        if (!visited.add(artifactId)) return;
        for (ModuleInfo m : allModules) {
            if (!m.getArtifactId().equals(artifactId)) continue;
            if ("pom".equals(m.getPackaging())) continue;

            String jarPath = resolver.resolveArtifactPath(m.getGroupId(), m.getArtifactId(), m.getVersion());
            if (jarPath != null && !exclude.contains(jarPath) && !result.contains(jarPath)) {
                result.add(jarPath);
            }

            Set<String> optionalEntries = m.getOptionalClasspathEntries();
            for (String cp : m.getCompileClasspath()) {
                if (!exclude.contains(cp) && !result.contains(cp)
                        && !optionalEntries.contains(cp)) {
                    result.add(cp);
                }
            }

            Set<String> optionalReactorDeps = m.getOptionalReactorDependencies();
            for (String depId : m.getReactorDependencies()) {
                if (!optionalReactorDeps.contains(depId)) {
                    collectReactorModuleClasspath(depId, allModules, result, exclude, visited);
                }
            }
            break;
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

        if (model.getBuild() != null) {
            extractCompilerConfigFromPlugins(model.getBuild().getPlugins(),
                    compilerArgs, annotationProcessorPaths, managedDeps);
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
            if (config != null) {
                extractCompilerConfig(config, compilerArgs, annotationProcessorPaths, managedDeps);
            }

            if (plugin.getExecutions() != null) {
                for (PluginExecution exec : plugin.getExecutions()) {
                    Xpp3Dom execConfig = (Xpp3Dom) exec.getConfiguration();
                    if (execConfig != null) {
                        extractCompilerConfig(execConfig, compilerArgs, annotationProcessorPaths, managedDeps);
                    }
                }
            }
        }
    }

    private void extractCompilerConfig(Xpp3Dom config,
                                       List<String> compilerArgs,
                                       List<String> annotationProcessorPaths,
                                       List<Dependency> managedDeps) {
        Xpp3Dom releaseNode = config.getChild("release");
        if (releaseNode != null && releaseNode.getValue() != null && !releaseNode.getValue().isBlank()) {
            if (!compilerArgs.contains("--release")) {
                compilerArgs.add("--release");
                compilerArgs.add(releaseNode.getValue());
            }
        } else {
            Xpp3Dom sourceNode = config.getChild("source");
            Xpp3Dom targetNode = config.getChild("target");
            if (sourceNode != null && sourceNode.getValue() != null && !sourceNode.getValue().isBlank()) {
                if (!compilerArgs.contains("-source")) {
                    compilerArgs.add("-source");
                    compilerArgs.add(sourceNode.getValue());
                }
            }
            if (targetNode != null && targetNode.getValue() != null && !targetNode.getValue().isBlank()) {
                if (!compilerArgs.contains("-target")) {
                    compilerArgs.add("-target");
                    compilerArgs.add(targetNode.getValue());
                }
            }
        }

        Xpp3Dom parametersNode = config.getChild("parameters");
        if (parametersNode != null && "true".equals(parametersNode.getValue())) {
            if (!compilerArgs.contains("-parameters")) {
                compilerArgs.add("-parameters");
            }
        }

        Xpp3Dom argsNode = config.getChild("compilerArgs");
        if (argsNode != null) {
            for (Xpp3Dom arg : argsNode.getChildren()) {
                if (arg.getValue() != null && !arg.getValue().isBlank()) {
                    if (!compilerArgs.contains(arg.getValue())) {
                        compilerArgs.add(arg.getValue());
                    }
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
                if ("true".equals(dep.getOptional())) {
                    info.getOptionalReactorDependencies().add(dep.getArtifactId());
                }
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

        boolean hasOptional = externalDeps.stream().anyMatch(d -> "true".equals(d.getOptional()));
        if (hasOptional) {
            List<Dependency> requiredDeps = externalDeps.stream()
                    .filter(d -> !"true".equals(d.getOptional()))
                    .toList();
            Set<String> requiredPaths;
            if (requiredDeps.isEmpty()) {
                requiredPaths = Set.of();
            } else {
                requiredPaths = resolver.resolveCompileClasspath(requiredDeps, managedDeps).stream()
                        .map(DependencyResolver.ResolvedArtifact::filePath)
                        .collect(Collectors.toSet());
            }
            Set<String> optionalPaths = new LinkedHashSet<>();
            for (String path : externalClasspath) {
                if (!requiredPaths.contains(path)) {
                    optionalPaths.add(path);
                }
            }
            info.setOptionalClasspathEntries(optionalPaths);
        }
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
