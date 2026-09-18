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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

public class PomParser {

    public interface ProgressListener {
        void update(String phase, String detail, int current, int total);
    }

    private record PomEntry(Path pomFile, Path baseDir) {}
    private record RawGAV(String groupId, String artifactId, String version) {}

    private final Path projectRoot;
    private final DependencyResolver resolver;
    private final ThreadLocal<ModelBuilder> threadLocalModelBuilder =
            ThreadLocal.withInitial(() -> new DefaultModelBuilderFactory().newInstance());
    private final Path localRepoDir;
    private final ConcurrentLinkedQueue<String> warnings = new ConcurrentLinkedQueue<>();
    private ProgressListener progressListener;
    private int threads = Runtime.getRuntime().availableProcessors();
    private long scanTimeMs;
    private long resolveTimeMs;

    public PomParser(Path projectRoot, DependencyResolver resolver) {
        this.projectRoot = projectRoot;
        this.resolver = resolver;
        this.localRepoDir = resolver.getLocalRepoPath();
        resolver.setWarningConsumer(warnings::add);
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }

    public long getScanTimeMs() {
        return scanTimeMs;
    }

    public long getResolveTimeMs() {
        return resolveTimeMs;
    }

    private void progress(String phase, String detail, int current, int total) {
        if (progressListener != null) {
            progressListener.update(phase, detail, current, total);
        }
    }

    public List<ModuleInfo> parseProject() {
        // Phase 1: Quick discover all pom paths and build reactor GAV map
        long scanStart = System.currentTimeMillis();
        List<PomEntry> pomEntries = new ArrayList<>();
        Map<String, Path> reactorPoms = new LinkedHashMap<>();
        quickDiscoverPoms(projectRoot.resolve("pom.xml"), projectRoot, pomEntries, reactorPoms);

        // Phase 2: Build effective models in parallel
        int totalPoms = pomEntries.size();
        ModuleInfo[] moduleInfos = new ModuleInfo[totalPoms];
        Model[] modelArray = new Model[totalPoms];
        AtomicInteger scanCounter = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(totalPoms);
            for (int i = 0; i < totalPoms; i++) {
                final int idx = i;
                PomEntry entry = pomEntries.get(idx);
                futures.add(executor.submit(() -> {
                    Model model = buildEffectiveModel(entry.pomFile, reactorPoms);
                    if (model != null) {
                        ModuleInfo info = toModuleInfo(model, entry.baseDir);
                        moduleInfos[idx] = info;
                        modelArray[idx] = model;
                    }
                    int done = scanCounter.incrementAndGet();
                    String name = moduleInfos[idx] != null
                            ? moduleInfos[idx].getArtifactId()
                            : entry.baseDir.getFileName().toString();
                    progress("scan", name, done, totalPoms);
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { /* errors logged in buildEffectiveModel */ }
            }
        } finally {
            executor.shutdown();
        }

        // Assemble results preserving discovery order
        List<ModuleInfo> modules = new ArrayList<>();
        Map<String, Model> effectiveModels = new LinkedHashMap<>();
        for (int i = 0; i < totalPoms; i++) {
            if (moduleInfos[i] != null && modelArray[i] != null) {
                modules.add(moduleInfos[i]);
                effectiveModels.put(
                        moduleInfos[i].getGroupId() + ":" + moduleInfos[i].getArtifactId(),
                        modelArray[i]);
            }
        }

        scanTimeMs = System.currentTimeMillis() - scanStart;

        Set<String> reactorGAs = new LinkedHashSet<>();
        Map<String, String> gaToArtifactId = new LinkedHashMap<>();
        for (ModuleInfo m : modules) {
            String ga = m.getGroupId() + ":" + m.getArtifactId();
            reactorGAs.add(ga);
            gaToArtifactId.put(ga, m.getArtifactId());
        }

        // Identify skipped modules (check once, cache result)
        Set<String> skipSet = new LinkedHashSet<>();
        Map<String, List<String>> skippedModuleReactorDeps = new LinkedHashMap<>();
        for (ModuleInfo m : modules) {
            if (shouldSkipModule(m, effectiveModels)) {
                skipSet.add(m.getArtifactId());
                Model model = effectiveModels.get(m.getGroupId() + ":" + m.getArtifactId());
                if (model != null && model.getDependencies() != null) {
                    List<String> deps = new ArrayList<>();
                    for (Dependency dep : model.getDependencies()) {
                        String scope = dep.getScope() != null ? dep.getScope() : "compile";
                        String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                        if (("compile".equals(scope) || "provided".equals(scope))
                                && reactorGAs.contains(ga)) {
                            deps.add(gaToArtifactId.get(ga));
                        }
                    }
                    skippedModuleReactorDeps.put(m.getArtifactId(), deps);
                }
            }
        }

        modules.removeIf(m -> skipSet.contains(m.getArtifactId()));

        // Phase 3: Resolve dependencies in parallel
        long resolveStart = System.currentTimeMillis();
        AtomicInteger resolveCounter = new AtomicInteger();
        int totalModules = modules.size();

        executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(totalModules);
            for (ModuleInfo info : modules) {
                futures.add(executor.submit(() -> {
                    Model model = effectiveModels.get(
                            info.getGroupId() + ":" + info.getArtifactId());
                    if (model == null) return;

                    extractCompilerConfig(model, info);

                    if (!"pom".equals(info.getPackaging())) {
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

                    int done = resolveCounter.incrementAndGet();
                    progress("resolve", info.getArtifactId(), done, totalModules);
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { /* errors logged */ }
            }
        } finally {
            executor.shutdown();
        }

        resolveTimeMs = System.currentTimeMillis() - resolveStart;

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
            if (info.isHasQuarkusBuildPlugin() || info.isHasExtensionPlugin()) {
                Model model = effectiveModels.get(info.getGroupId() + ":" + info.getArtifactId());
                if (model != null) {
                    collectQuarkusBuildMetadata(model, info, reactorGAs, modules);
                }
            }
        }

        Map<String, String> allReactorExtDeployments = new LinkedHashMap<>();
        for (ModuleInfo m : modules) {
            if (!m.isHasExtensionPlugin()) continue;
            String deployArtifact = m.getExtensionDescriptorProperties().get("deployment-artifact");
            if (deployArtifact != null) {
                allReactorExtDeployments.put(m.getGroupId() + ":" + m.getArtifactId(), deployArtifact);
            }
        }
        for (ModuleInfo m : modules) {
            if (m.isHasExtensionPlugin()) {
                m.setAllReactorExtensionDeployments(allReactorExtDeployments);
            }
        }

        return modules;
    }

    private void quickDiscoverPoms(Path pomFile, Path baseDir, List<PomEntry> result,
                                    Map<String, Path> reactorPoms) {
        result.add(new PomEntry(pomFile, baseDir));
        RawGAV gav = quickParseGAV(pomFile);
        if (gav != null && gav.groupId != null && gav.artifactId != null) {
            reactorPoms.put(gav.groupId + ":" + gav.artifactId, pomFile);
        }
        List<String> moduleNames = readModuleNames(pomFile);
        for (String moduleName : moduleNames) {
            Path moduleDir = baseDir.resolve(moduleName);
            Path modulePom = moduleDir.resolve("pom.xml");
            if (Files.exists(modulePom)) {
                quickDiscoverPoms(modulePom, moduleDir, result, reactorPoms);
            } else {
                warnings.add("Module POM not found: " + modulePom);
            }
        }
    }

    private RawGAV quickParseGAV(Path pomFile) {
        try (InputStream is = Files.newInputStream(pomFile)) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(is);
            try {
                String groupId = null, artifactId = null, version = null;
                String parentGroupId = null, parentVersion = null;
                int depth = 0;
                boolean inParent = false;
                String currentElement = null;
                StringBuilder text = null;
                while (reader.hasNext()) {
                    int event = reader.next();
                    switch (event) {
                        case XMLStreamConstants.START_ELEMENT -> {
                            depth++;
                            String name = reader.getLocalName();
                            if (depth == 2 && "parent".equals(name)) {
                                inParent = true;
                            } else if (depth == 2 || (depth == 3 && inParent)) {
                                if ("groupId".equals(name) || "artifactId".equals(name) || "version".equals(name)) {
                                    currentElement = name;
                                    text = new StringBuilder();
                                }
                            }
                        }
                        case XMLStreamConstants.END_ELEMENT -> {
                            if (currentElement != null && text != null) {
                                String val = text.toString().trim();
                                if (inParent && depth == 3) {
                                    switch (currentElement) {
                                        case "groupId" -> parentGroupId = val;
                                        case "version" -> parentVersion = val;
                                    }
                                } else if (!inParent && depth == 2) {
                                    switch (currentElement) {
                                        case "groupId" -> groupId = val;
                                        case "artifactId" -> artifactId = val;
                                        case "version" -> version = val;
                                    }
                                }
                                currentElement = null;
                                text = null;
                            }
                            if (depth == 2 && "parent".equals(reader.getLocalName())) {
                                inParent = false;
                            }
                            depth--;
                            if (depth <= 0 && artifactId != null) break;
                        }
                        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                            if (text != null) text.append(reader.getText());
                        }
                    }
                }
                if (groupId == null) groupId = parentGroupId;
                if (version == null) version = parentVersion;
                return new RawGAV(groupId, artifactId, version);
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readModuleNames(Path pomFile) {
        List<String> modules = new ArrayList<>();
        try (InputStream is = Files.newInputStream(pomFile)) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(is);
            try {
                boolean inModules = false;
                boolean inModule = false;
                StringBuilder moduleText = null;
                while (reader.hasNext()) {
                    int event = reader.next();
                    switch (event) {
                        case XMLStreamConstants.START_ELEMENT -> {
                            String name = reader.getLocalName();
                            if ("modules".equals(name)) {
                                inModules = true;
                            } else if (inModules && "module".equals(name)) {
                                inModule = true;
                                moduleText = new StringBuilder();
                            }
                        }
                        case XMLStreamConstants.END_ELEMENT -> {
                            String name = reader.getLocalName();
                            if ("module".equals(name) && inModule) {
                                String text = moduleText.toString().trim();
                                if (!text.isEmpty() && !modules.contains(text)) {
                                    modules.add(text);
                                }
                                inModule = false;
                                moduleText = null;
                            } else if ("modules".equals(name)) {
                                inModules = false;
                            }
                        }
                        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                            if (inModule && moduleText != null) {
                                moduleText.append(reader.getText());
                            }
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            warnings.add("Failed to read modules from " + pomFile + ": " + e.getMessage());
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
        Model model = effectiveModels.get(info.getGroupId() + ":" + info.getArtifactId());
        if (model == null || model.getBuild() == null) return false;

        return false;
    }

    private Model buildEffectiveModel(Path pomFile, Map<String, Path> reactorPoms) {
        try {
            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
            request.setPomFile(pomFile.toFile());
            request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
            request.setProcessPlugins(true);
            request.setTwoPhaseBuilding(false);
            request.setSystemProperties(System.getProperties());
            request.setUserProperties(new Properties());
            request.setModelResolver(new LocalRepoModelResolver(localRepoDir, resolver, reactorPoms));

            ModelBuilder builder = threadLocalModelBuilder.get();
            ModelBuildingResult result = builder.build(request);
            return result.getEffectiveModel();
        } catch (ModelBuildingException e) {
            warnings.add("Model building problems for " + pomFile + ": " + e.getMessage());
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
        info.setProjectName(model.getName());
        info.setProjectDescription(model.getDescription());
        if (model.getParent() != null) {
            info.setParentGroupId(model.getParent().getGroupId());
            info.setParentArtifactId(model.getParent().getArtifactId());
        }
        info.setBaseDir(projectRoot.relativize(baseDir));
        info.setPomFile(baseDir.resolve("pom.xml").toAbsolutePath());

        Path srcMain = baseDir.resolve("src/main/java");
        info.setHasJavaSources(Files.isDirectory(srcMain) && hasJavaFiles(srcMain));

        Path kotlinSrcMain = baseDir.resolve("src/main/kotlin");
        info.setHasKotlinSources(Files.isDirectory(kotlinSrcMain) && hasKotlinFiles(kotlinSrcMain));

        Path testSrcMain = baseDir.resolve("src/test/java");
        info.setHasTestJavaSources(Files.isDirectory(testSrcMain) && hasJavaFiles(testSrcMain));

        Path testKotlinSrcMain = baseDir.resolve("src/test/kotlin");
        info.setHasTestKotlinSources(Files.isDirectory(testKotlinSrcMain) && hasKotlinFiles(testKotlinSrcMain));

        extractResourceDirs(model, baseDir, info);
        extractFilterProperties(model, info);
        detectJandexPlugin(model, info);
        detectSisuPlugin(model, info);
        detectProtobufPlugin(model, baseDir, info);
        detectAntlrPlugin(model, baseDir, info);
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
                            dirPath.toString(), resource.isFiltering(),
                            resource.getTargetPath()));
                }
            }
        } else {
            Path resMain = baseDir.resolve("src/main/resources");
            if (Files.isDirectory(resMain)) {
                resourceDirs.add(new ModuleInfo.ResourceDir("src/main/resources", false, null));
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

    private void detectSisuPlugin(Model model, ModuleInfo info) {
        if (model.getBuild() == null) return;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if ("sisu-maven-plugin".equals(plugin.getArtifactId())) {
                info.setHasSisuPlugin(true);
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

    private void detectAntlrPlugin(Model model, Path baseDir, ModuleInfo info) {
        if (model.getBuild() == null) return;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (!"antlr4-maven-plugin".equals(plugin.getArtifactId())) continue;

            Path antlrDir = baseDir.resolve("src/main/antlr4");
            if (!Files.isDirectory(antlrDir)) return;
            try (var stream = Files.walk(antlrDir)) {
                if (stream.noneMatch(p -> p.toString().endsWith(".g4"))) return;
            } catch (IOException e) {
                return;
            }

            info.setHasAntlrSources(true);

            boolean visitor = false;
            for (PluginExecution exec : plugin.getExecutions()) {
                Xpp3Dom execConfig = (Xpp3Dom) exec.getConfiguration();
                if (execConfig != null) {
                    Xpp3Dom visitorNode = execConfig.getChild("visitor");
                    if (visitorNode != null && "true".equals(visitorNode.getValue())) {
                        visitor = true;
                    }
                }
            }
            Xpp3Dom pluginConfig = (Xpp3Dom) plugin.getConfiguration();
            if (pluginConfig != null) {
                Xpp3Dom visitorNode = pluginConfig.getChild("visitor");
                if (visitorNode != null && "true".equals(visitorNode.getValue())) {
                    visitor = true;
                }
            }
            info.setAntlrVisitor(visitor);
            return;
        }
    }

    private void detectExtensionPlugin(Model model, ModuleInfo info) {
        if (model.getBuild() == null) return;
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (!"quarkus-extension-maven-plugin".equals(plugin.getArtifactId())) continue;

            Xpp3Dom pluginConfig = (Xpp3Dom) plugin.getConfiguration();
            boolean hasDescriptorGoal = false;
            for (PluginExecution exec : plugin.getExecutions()) {
                if (exec.getGoals().contains("extension-descriptor")) {
                    hasDescriptorGoal = true;

                    Xpp3Dom execConfig = (Xpp3Dom) exec.getConfiguration();
                    if (execConfig != null) {
                        extractExtensionConfig(execConfig, info);
                    }

                    String skip = extractConfigValue("skipExtensionValidation", execConfig, pluginConfig);
                    info.setExtensionValidationSkipWhen(skip != null ? skip : "${skipExtensionValidation}");
                    break;
                }
            }
            if (!hasDescriptorGoal) continue;

            info.setHasExtensionPlugin(true);

            if (info.getExtensionDescriptorProperties().isEmpty()) {
                if (pluginConfig != null) {
                    extractExtensionConfig(pluginConfig, info);
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

            Xpp3Dom pluginConfig = (Xpp3Dom) plugin.getConfiguration();

            for (PluginExecution exec : plugin.getExecutions()) {
                Xpp3Dom execConfig = (Xpp3Dom) exec.getConfiguration();

                if (exec.getGoals().contains("build")) {
                    info.setHasQuarkusBuildPlugin(true);
                    // BuildMojo field is "skip", property "quarkus.build.skip"
                    String skip = extractConfigValue("skip", execConfig, pluginConfig);
                    info.setQuarkusBuildSkipWhen(skip != null ? skip : "${quarkus.build.skip}");
                }
                if (exec.getGoals().contains("generate-code")) {
                    info.setHasGenerateCodeGoal(true);
                    // GenerateCodeMojo field is "skipSourceGeneration", property "quarkus.generate-code.skip"
                    String skip = extractConfigValue("skipSourceGeneration", execConfig, pluginConfig);
                    info.setGenerateCodeSkipWhen(skip != null ? skip : "${quarkus.generate-code.skip}");
                }
            }
            return;
        }
    }

    private String extractConfigValue(String elementName, Xpp3Dom execConfig, Xpp3Dom pluginConfig) {
        if (execConfig != null) {
            Xpp3Dom node = execConfig.getChild(elementName);
            if (node != null && node.getValue() != null && !node.getValue().isBlank()) {
                return node.getValue();
            }
        }
        if (pluginConfig != null) {
            Xpp3Dom node = pluginConfig.getChild(elementName);
            if (node != null && node.getValue() != null && !node.getValue().isBlank()) {
                return node.getValue();
            }
        }
        return null;
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
        // include optional transitive deps to match what addReactorJars adds at build time
        Set<String> reactorDepIds = new LinkedHashSet<>(info.getReactorDependencies());
        Set<String> excludedReactorDeps = new LinkedHashSet<>();
        collectTransitiveReactorDeps(reactorDepIds, allModules, excludedReactorDeps, true);
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

        // Also scan reactor deps' external classpath jars for extensions
        // At build time, addReactorJars() adds these to the full classpath
        for (String depId : reactorDepIds) {
            for (ModuleInfo m : allModules) {
                if (!m.getArtifactId().equals(depId)) continue;
                if ("pom".equals(m.getPackaging())) continue;
                for (String jarPath : m.getCompileClasspath()) {
                    scanExtensionJar(jarPath, extensionArtifacts, extensionDevProps, deploymentGAVs);
                }
                break;
            }
        }

        // Build excluded deployment artifact set from excluded reactor deps
        Set<String> excludedDeploymentGAs = new LinkedHashSet<>();
        for (String excludedId : excludedReactorDeps) {
            for (ModuleInfo m : allModules) {
                if (!m.getArtifactId().equals(excludedId)) continue;
                if (m.isHasExtensionPlugin()) {
                    String deployGA = m.getExtensionDescriptorProperties().get("deployment-artifact");
                    if (deployGA != null) {
                        String[] parts = deployGA.split(":");
                        if (parts.length >= 2) {
                            excludedDeploymentGAs.add(parts[0] + ":" + parts[1]);
                        }
                    }
                }
                break;
            }
        }

        info.setRuntimeExtensionArtifacts(extensionArtifacts);
        info.setExtensionDevProperties(extensionDevProps);

        List<String> deploymentClasspath = resolveDeploymentClasspath(deploymentGAVs, info,
                reactorGAs, allModules, excludedDeploymentGAs);
        info.setDeploymentClasspath(deploymentClasspath);
        info.setHasCodeGenProviders(
                checkHasCodeGenProviders(deploymentClasspath, deploymentGAVs, reactorGAs, allModules, info));

        scanCodegenToolVersions(info, deploymentClasspath, reactorDepIds, allModules);
    }

    private void scanCodegenToolVersions(ModuleInfo info, List<String> deploymentClasspath,
                                          Set<String> reactorDepIds, List<ModuleInfo> allModules) {
        String protocVersion = null;
        String grpcVersion = null;
        String quarkusGrpcVersion = null;

        for (String jarPath : deploymentClasspath) {
            String[] gav = parseGAVFromM2Path3(jarPath);
            if (gav == null) continue;
            if ("com.google.protobuf".equals(gav[0]) && "protobuf-java".equals(gav[1])) {
                protocVersion = gav[2];
            } else if ("io.grpc".equals(gav[0]) && "grpc-core".equals(gav[1])) {
                grpcVersion = gav[2];
            } else if ("io.quarkus".equals(gav[0]) && "quarkus-grpc-protoc-plugin".equals(gav[1])) {
                quarkusGrpcVersion = gav[2];
            }
        }

        for (String jarPath : info.getCompileClasspath()) {
            String[] gav = parseGAVFromM2Path3(jarPath);
            if (gav == null) continue;
            if (protocVersion == null && "com.google.protobuf".equals(gav[0]) && "protobuf-java".equals(gav[1])) {
                protocVersion = gav[2];
            }
            if (grpcVersion == null && "io.grpc".equals(gav[0]) && "grpc-core".equals(gav[1])) {
                grpcVersion = gav[2];
            }
            if (quarkusGrpcVersion == null && "io.quarkus".equals(gav[0]) && "quarkus-grpc-protoc-plugin".equals(gav[1])) {
                quarkusGrpcVersion = gav[2];
            }
        }

        for (String depId : reactorDepIds) {
            if (protocVersion != null && grpcVersion != null && quarkusGrpcVersion != null) break;
            for (ModuleInfo m : allModules) {
                if (!m.getArtifactId().equals(depId)) continue;
                if ("pom".equals(m.getPackaging())) continue;
                if (quarkusGrpcVersion == null && "quarkus-grpc-protoc-plugin".equals(m.getArtifactId())) {
                    quarkusGrpcVersion = m.getVersion();
                }
                for (String jarPath : m.getCompileClasspath()) {
                    String[] gav = parseGAVFromM2Path3(jarPath);
                    if (gav == null) continue;
                    if (protocVersion == null && "com.google.protobuf".equals(gav[0]) && "protobuf-java".equals(gav[1])) {
                        protocVersion = gav[2];
                    }
                    if (grpcVersion == null && "io.grpc".equals(gav[0]) && "grpc-core".equals(gav[1])) {
                        grpcVersion = gav[2];
                    }
                    if (quarkusGrpcVersion == null && "io.quarkus".equals(gav[0]) && "quarkus-grpc-protoc-plugin".equals(gav[1])) {
                        quarkusGrpcVersion = gav[2];
                    }
                }
                break;
            }
        }

        info.setProtocVersion(protocVersion);
        info.setGrpcVersion(grpcVersion);
        info.setQuarkusGrpcVersion(quarkusGrpcVersion);
    }

    private static String[] parseGAVFromM2Path3(String jarPath) {
        String m2 = System.getProperty("user.home") + "/.m2/repository/";
        if (!jarPath.startsWith(m2)) return null;
        String relative = jarPath.substring(m2.length());
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String beforeFile = relative.substring(0, lastSlash);
        int versionSlash = beforeFile.lastIndexOf('/');
        if (versionSlash < 0) return null;
        String version = beforeFile.substring(versionSlash + 1);
        String beforeVersion = beforeFile.substring(0, versionSlash);
        int artifactSlash = beforeVersion.lastIndexOf('/');
        if (artifactSlash < 0) return null;
        String artifactId = beforeVersion.substring(artifactSlash + 1);
        String groupId = beforeVersion.substring(0, artifactSlash).replace('/', '.');
        return new String[] { groupId, artifactId, version };
    }

    private static final String CODEGEN_SERVICE = "META-INF/services/io.quarkus.deployment.CodeGenProvider";

    private boolean checkHasCodeGenProviders(List<String> deploymentClasspath,
            Set<String> deploymentGAVs, Set<String> reactorGAs, List<ModuleInfo> allModules,
            ModuleInfo module) {
        // Check reactor deployment modules via their source trees
        for (String gav : deploymentGAVs) {
            String[] parts = gav.split(":");
            if (parts.length < 2) continue;
            String ga = parts[0] + ":" + parts[1];
            if (!reactorGAs.contains(ga)) continue;
            for (ModuleInfo m : allModules) {
                if (m.getArtifactId().equals(parts[1]) && m.getGroupId().equals(parts[0])) {
                    java.nio.file.Path resourcesService = m.getBaseDir().resolve("src/main/resources/" + CODEGEN_SERVICE);
                    if (java.nio.file.Files.exists(resourcesService)) return true;
                    break;
                }
            }
        }
        // Check module's reactor dependencies (e.g. quarkus-grpc-codegen)
        Set<String> reactorDepIds = new LinkedHashSet<>(module.getReactorDependencies());
        collectTransitiveReactorDeps(reactorDepIds, allModules, new LinkedHashSet<>());
        for (String depId : reactorDepIds) {
            for (ModuleInfo m : allModules) {
                if (!m.getArtifactId().equals(depId)) continue;
                java.nio.file.Path resourcesService = m.getBaseDir().resolve("src/main/resources/" + CODEGEN_SERVICE);
                if (java.nio.file.Files.exists(resourcesService)) return true;
                break;
            }
        }
        // Check external jars
        for (String jarPath : deploymentClasspath) {
            java.nio.file.Path p = java.nio.file.Path.of(jarPath);
            if (!java.nio.file.Files.exists(p) || java.nio.file.Files.isDirectory(p)) continue;
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(p.toFile())) {
                if (jar.getEntry(CODEGEN_SERVICE) != null) return true;
            } catch (Exception e) {
                // skip
            }
        }
        return false;
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

    private void collectTransitiveReactorDeps(Set<String> result, List<ModuleInfo> allModules,
                                                Set<String> excludedDeps) {
        collectTransitiveReactorDeps(result, allModules, excludedDeps, false);
    }

    private void collectTransitiveReactorDeps(Set<String> result, List<ModuleInfo> allModules,
                                                Set<String> excludedDeps, boolean includeOptional) {
        Map<String, ModuleInfo> modulesByArtifactId = new LinkedHashMap<>();
        for (ModuleInfo m : allModules) {
            modulesByArtifactId.put(m.getArtifactId(), m);
        }

        // Track exclusions that apply to each module in the result set
        Map<String, Set<String>> activeExclusions = new HashMap<>();
        Set<String> skippedByExclusion = new HashSet<>();
        Queue<String> queue = new LinkedList<>(result);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            ModuleInfo m = modulesByArtifactId.get(current);
            if (m == null) continue;

            Set<String> myExclusions = activeExclusions.getOrDefault(current, Set.of());
            Set<String> optionalDeps = m.getOptionalReactorDependencies();

            for (String dep : m.getReactorDependencies()) {
                if (!includeOptional && optionalDeps.contains(dep)) continue;
                if (myExclusions.contains(dep)) {
                    skippedByExclusion.add(dep);
                    continue;
                }

                if (result.add(dep)) {
                    // Propagate: parent's exclusions + this dep's declared exclusions
                    Set<String> depExclusions = new HashSet<>(myExclusions);
                    depExclusions.addAll(m.getReactorDependencyExclusionsFor(dep));
                    if (!depExclusions.isEmpty()) {
                        activeExclusions.put(dep, depExclusions);
                    }
                    queue.add(dep);
                }
            }
        }

        if (excludedDeps != null) {
            for (String s : skippedByExclusion) {
                if (!result.contains(s)) {
                    excludedDeps.add(s);
                }
            }
        }
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
                                                     Set<String> reactorGAs, List<ModuleInfo> allModules,
                                                     Set<String> excludedDeploymentGAs) {
        if (deploymentGAVs.isEmpty()) return List.of();

        List<org.apache.maven.model.Dependency> externalDeploymentDeps = new ArrayList<>();
        List<String> reactorDeploymentArtifactIds = new ArrayList<>();

        Set<String> excludedDeploymentArtifactIds = new LinkedHashSet<>();
        for (String ga : excludedDeploymentGAs) {
            String[] parts = ga.split(":");
            if (parts.length >= 2) {
                excludedDeploymentArtifactIds.add(parts[1]);
            }
        }

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
                for (String exclGA : excludedDeploymentGAs) {
                    String[] exclParts = exclGA.split(":");
                    if (exclParts.length >= 2) {
                        org.apache.maven.model.Exclusion exclusion = new org.apache.maven.model.Exclusion();
                        exclusion.setGroupId(exclParts[0]);
                        exclusion.setArtifactId(exclParts[1]);
                        dep.addExclusion(exclusion);
                    }
                }
                externalDeploymentDeps.add(dep);
            }
        }

        Set<String> runtimePaths = new LinkedHashSet<>(info.getCompileClasspath());
        String ownJar = resolver.resolveArtifactPath(info.getGroupId(), info.getArtifactId(), info.getVersion());
        if (ownJar != null) {
            runtimePaths.add(ownJar);
        }
        Set<String> deploymentArtifactIds = new LinkedHashSet<>();
        for (String gav : deploymentGAVs) {
            String[] dp = gav.split(":");
            if (dp.length >= 2) {
                deploymentArtifactIds.add(dp[1]);
            }
        }
        Set<String> runtimeReactorDeps = new LinkedHashSet<>();
        for (String depId : info.getReactorDependencies()) {
            if (!deploymentArtifactIds.contains(depId)) {
                runtimeReactorDeps.add(depId);
            }
        }
        collectTransitiveReactorDeps(runtimeReactorDeps, allModules, new LinkedHashSet<>(), true);
        for (String depId : runtimeReactorDeps) {
            for (ModuleInfo m : allModules) {
                if (!m.getArtifactId().equals(depId)) continue;
                if ("pom".equals(m.getPackaging())) continue;
                String jarPath = resolver.resolveArtifactPath(m.getGroupId(), m.getArtifactId(), m.getVersion());
                if (jarPath != null) {
                    runtimePaths.add(jarPath);
                }
                runtimePaths.addAll(m.getCompileClasspath());
                break;
            }
        }
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
            collectReactorModuleClasspath(artifactId, allModules, deploymentOnly, runtimePaths,
                    visited, excludedDeploymentArtifactIds);
        }

        return deploymentOnly;
    }

    private void collectReactorModuleClasspath(String artifactId, List<ModuleInfo> allModules,
                                                List<String> result, Set<String> exclude, Set<String> visited,
                                                Set<String> excludedArtifactIds) {
        if (!visited.add(artifactId)) return;
        if (excludedArtifactIds.contains(artifactId)) return;
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
                    Set<String> depExclusions = m.getReactorDependencyExclusionsFor(depId);
                    Set<String> mergedExclusions = excludedArtifactIds;
                    if (!depExclusions.isEmpty()) {
                        mergedExclusions = new HashSet<>(excludedArtifactIds);
                        mergedExclusions.addAll(depExclusions);
                    }
                    collectReactorModuleClasspath(depId, allModules, result, exclude, visited,
                            mergedExclusions);
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
        info.setApCacheable(checkApCacheable(annotationProcessorPaths));
    }

    private static boolean checkApCacheable(List<String> apPaths) {
        if (apPaths.isEmpty()) return false;
        boolean foundQuarkusProcessor = false;
        for (String path : apPaths) {
            String name = java.nio.file.Path.of(path).getFileName().toString().toLowerCase();
            if (name.startsWith("quarkus-extension-processor-")) {
                foundQuarkusProcessor = true;
                continue;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new java.io.File(path))) {
                if (jar.getEntry("META-INF/services/javax.annotation.processing.Processor") != null) {
                    return false;
                }
            } catch (Exception e) {
                // unreadable jar — treat as non-processor
            }
        }
        return foundQuarkusProcessor;
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
                if (gidNode != null && aidNode != null) {
                    String ver = verNode != null ? verNode.getValue() : null;
                    if (ver == null || ver.isBlank()) {
                        for (Dependency md : managedDeps) {
                            if (gidNode.getValue().equals(md.getGroupId())
                                    && aidNode.getValue().equals(md.getArtifactId())) {
                                ver = md.getVersion();
                                break;
                            }
                        }
                    }
                    if (ver != null && !ver.isBlank()) {
                        List<String> resolved = resolver.resolveAnnotationProcessorPath(
                                gidNode.getValue(), aidNode.getValue(), ver,
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
        List<Dependency> testExternalDeps = new ArrayList<>();
        boolean hasTestSources = info.isHasTestJavaSources() || info.isHasTestKotlinSources();
        for (Dependency dep : model.getDependencies()) {
            String scope = dep.getScope() != null ? dep.getScope() : "compile";
            String ga = dep.getGroupId() + ":" + dep.getArtifactId();
            if (("compile".equals(scope) || "provided".equals(scope)) && reactorGAs.contains(ga)) {
                info.getReactorDependencies().add(dep.getArtifactId());
                if ("true".equals(dep.getOptional())) {
                    info.getOptionalReactorDependencies().add(dep.getArtifactId());
                }
                if (dep.getExclusions() != null) {
                    for (org.apache.maven.model.Exclusion excl : dep.getExclusions()) {
                        String exclGA = excl.getGroupId() + ":" + excl.getArtifactId();
                        if (reactorGAs.contains(exclGA)) {
                            info.addReactorDependencyExclusion(dep.getArtifactId(), excl.getArtifactId());
                        }
                    }
                }
                continue;
            }
            if (reactorGAs.contains(ga)) {
                continue;
            }
            String version = dep.getVersion();
            if (version == null || version.isBlank()) {
                version = managedVersions.get(ga);
            }
            if (version != null && !version.isBlank()) {
                if (dep.getVersion() == null || dep.getVersion().isBlank()) {
                    dep.setVersion(version);
                }
                if (!"test".equals(scope)) {
                    externalDeps.add(dep);
                }
                if (hasTestSources) {
                    testExternalDeps.add(dep);
                }
            }
        }

        if (externalDeps.isEmpty() && testExternalDeps.isEmpty()) {
            return;
        }

        List<Dependency> managedDeps = model.getDependencyManagement() != null
                ? model.getDependencyManagement().getDependencies() : List.of();

        if (!externalDeps.isEmpty()) {
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

        if (hasTestSources && !testExternalDeps.isEmpty()) {
            List<DependencyResolver.ResolvedArtifact> testResolved =
                    resolver.resolveTestClasspath(testExternalDeps, managedDeps);
            List<String> testClasspath = new ArrayList<>();
            for (DependencyResolver.ResolvedArtifact art : testResolved) {
                testClasspath.add(art.filePath());
            }
            info.setTestCompileClasspath(testClasspath);
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
