package io.quarkiverse.qraven.hardcoded;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.codehaus.plexus.util.xml.Xpp3Dom;

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

    private final Path projectRoot;
    private final DependencyResolver resolver;
    private final ModelBuilder modelBuilder;
    private final Path localRepoDir;

    public PomParser(Path projectRoot, DependencyResolver resolver) {
        this.projectRoot = projectRoot;
        this.resolver = resolver;
        this.modelBuilder = new DefaultModelBuilderFactory().newInstance();
        this.localRepoDir = resolver.getLocalRepoPath();
    }

    public List<ModuleInfo> parseProject() {
        Map<String, Model> effectiveModels = new LinkedHashMap<>();
        List<ModuleInfo> modules = new ArrayList<>();

        discoverModules(projectRoot.resolve("pom.xml"), projectRoot, modules, effectiveModels);

        Set<String> reactorGAs = new LinkedHashSet<>();
        for (ModuleInfo m : modules) {
            reactorGAs.add(m.getGroupId() + ":" + m.getArtifactId());
        }

        for (ModuleInfo info : modules) {
            Model model = effectiveModels.get(info.getGroupId() + ":" + info.getArtifactId());
            if (model == null) continue;

            extractCompilerConfig(model, info);

            if ("pom".equals(info.getPackaging())) {
                continue;
            }

            resolveDependencies(model, info, reactorGAs);
        }

        return modules;
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
            request.setModelResolver(new LocalRepoModelResolver(localRepoDir));

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
        Path resMain = baseDir.resolve("src/main/resources");
        info.setHasJavaSources(Files.isDirectory(srcMain) && hasJavaFiles(srcMain));
        info.setHasResources(Files.isDirectory(resMain));

        return info;
    }

    private boolean hasJavaFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (Exception e) {
            return false;
        }
    }

    private void extractCompilerConfig(Model model, ModuleInfo info) {
        List<String> compilerArgs = new ArrayList<>();
        List<String> annotationProcessorPaths = new ArrayList<>();

        List<Dependency> managedDeps = model.getDependencyManagement() != null
                ? model.getDependencyManagement().getDependencies() : List.of();

        // Check pluginManagement first (inherited defaults)
        if (model.getBuild() != null && model.getBuild().getPluginManagement() != null) {
            extractCompilerConfigFromPlugins(
                    model.getBuild().getPluginManagement().getPlugins(),
                    compilerArgs, annotationProcessorPaths, managedDeps);
        }

        // Then overlay with explicit plugins (may add annotation processors)
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

        for (Dependency dep : model.getDependencies()) {
            String scope = dep.getScope() != null ? dep.getScope() : "compile";
            if ("compile".equals(scope) || "provided".equals(scope)) {
                String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                if (reactorGAs.contains(ga)) {
                    info.getReactorDependencies().add(dep.getArtifactId());
                }
            }
        }

        List<Dependency> managedDeps = model.getDependencyManagement() != null
                ? model.getDependencyManagement().getDependencies() : List.of();

        List<DependencyResolver.ResolvedArtifact> resolved =
                resolver.resolveCompileClasspath(model.getDependencies(), managedDeps);

        List<String> externalClasspath = new ArrayList<>();
        for (DependencyResolver.ResolvedArtifact art : resolved) {
            String ga = art.groupId() + ":" + art.artifactId();
            if (!reactorGAs.contains(ga)) {
                externalClasspath.add(art.filePath());
            }
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
