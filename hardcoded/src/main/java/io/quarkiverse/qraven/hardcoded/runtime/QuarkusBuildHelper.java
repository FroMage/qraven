package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.StringReader;
import java.net.URL;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.CapabilityContract;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;

public class QuarkusBuildHelper {

    static void run(ModuleBuild module, List<ModuleBuild> reactorDeps) throws Exception {
        ApplicationModelBuilder modelBuilder = new ApplicationModelBuilder();

        ResolvedDependencyBuilder appArtifact = ResolvedDependencyBuilder.newInstance()
                .setGroupId(module.groupId())
                .setArtifactId(module.artifactId())
                .setVersion(module.version())
                .setResolvedPaths(PathList.of(module.classesDir()))
                .setRuntimeCp()
                .setDeploymentCp();
        modelBuilder.setAppArtifact(appArtifact);

        Set<String> extensionGAs = new HashSet<>(module.runtimeExtensionArtifacts());
        Map<String, String> extensionProps = module.extensionDevProperties();

        for (String jarPath : module.resolvedClasspath()) {
            addRuntimeDep(jarPath, extensionGAs, extensionProps, modelBuilder);
        }

        // reactor dependencies are not in the resolved classpath - add them
        addReactorDeps(module, reactorDeps, extensionGAs, extensionProps, modelBuilder, new HashSet<>());

        List<String> deploymentCp = ModuleBuild.resolvePaths(module.deploymentClasspath());
        for (String jarPath : deploymentCp) {
            GAV gav = parseGAVFromM2Path(jarPath);
            if (gav == null) continue;

            ArtifactKey key = ArtifactKey.of(gav.groupId, gav.artifactId, "", "jar");
            if (modelBuilder.hasDependency(key)) continue;

            ResolvedDependencyBuilder dep = ResolvedDependencyBuilder.newInstance()
                    .setGroupId(gav.groupId)
                    .setArtifactId(gav.artifactId)
                    .setVersion(gav.version)
                    .setResolvedPath(Path.of(jarPath))
                    .setDeploymentCp();

            modelBuilder.addDependency(dep);
        }

        var appModel = modelBuilder.build();

        Properties buildSystemProps = new Properties();
        buildSystemProps.putAll(module.quarkusBuildProperties());

        try (CuratedApplication app = QuarkusBootstrap.builder()
                .setBaseClassLoader(QuarkusBuildHelper.class.getClassLoader())
                .setExistingModel(appModel)
                .setProjectRoot(module.targetDir().getParent())
                .setTargetDirectory(module.targetDir())
                .setBaseName(module.artifactId() + "-" + module.version())
                .setBuildSystemProperties(buildSystemProps)
                .setAppArtifact(appModel.getAppArtifact())
                .setLocalProjectDiscovery(false)
                .setIsolateDeployment(true)
                .build()
                .bootstrap()) {
            // Dump build-steps.list entries visible to the augment classloader
            ClassLoader augmentCl = app.getOrCreateAugmentClassLoader();
            try {
                Enumeration<URL> stepLists = augmentCl.getResources("META-INF/quarkus-build-steps.list");
                int count = 0;
                boolean foundArc = false;
                while (stepLists.hasMoreElements()) {
                    URL url = stepLists.nextElement();
                    try (var is = url.openStream();
                         var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty() && !line.startsWith("#")) {
                                count++;
                                if (line.contains("ArcProcessor")) {
                                    foundArc = true;
                                }
                            }
                        }
                    }
                }
                if (!foundArc) {
                    System.err.println("[QRAVEN-DIAG] WARNING: ArcProcessor NOT found in build-steps.list! "
                        + count + " total steps found for " + module.artifactId());
                }
            } catch (Exception diag) {
                System.err.println("[QRAVEN-DIAG] Failed to enumerate build-steps: " + diag);
            }

            try {
                app.createAugmentor().createProductionApplication();
            } catch (Exception e) {
                System.err.println("[QRAVEN-DIAG] Build failed for " + module.artifactId() + ": " + e.getClass().getName() + ": " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    System.err.println("[QRAVEN-DIAG]   Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
                System.err.flush();
                throw e;
            }
        } catch (Exception e) {
            if (!e.getClass().getName().contains("QRAVEN")) {
                System.err.println("[QRAVEN-DIAG] Outer exception for " + module.artifactId() + ": " + e.getClass().getName() + ": " + e.getMessage());
                System.err.flush();
            }
            throw e;
        }
    }

    private static void addRuntimeDep(String jarPath, Set<String> extensionGAs,
            Map<String, String> extensionProps, ApplicationModelBuilder modelBuilder) throws Exception {
        GAV gav = parseGAVFromM2Path(jarPath);
        if (gav == null) return;

        ArtifactKey key = ArtifactKey.of(gav.groupId, gav.artifactId, "", "jar");
        if (modelBuilder.hasDependency(key)) return;

        ResolvedDependencyBuilder dep = ResolvedDependencyBuilder.newInstance()
                .setGroupId(gav.groupId)
                .setArtifactId(gav.artifactId)
                .setVersion(gav.version)
                .setResolvedPath(Path.of(jarPath))
                .setRuntimeCp()
                .setDeploymentCp();

        String ga = gav.groupId + ":" + gav.artifactId;
        if (extensionGAs.contains(ga)) {
            dep.setRuntimeExtensionArtifact();
            String packed = extensionProps.get(ga);
            if (packed != null) {
                Properties props = new Properties();
                props.load(new StringReader(packed.replace("\\n", "\n")));
                modelBuilder.handleExtensionProperties(props, dep.getKey());
                registerCapabilities(modelBuilder, dep, props);
            }
        }

        modelBuilder.addDependency(dep);
    }

    private static void registerCapabilities(ApplicationModelBuilder modelBuilder,
            ResolvedDependencyBuilder dep, Properties props) {
        String provides = props.getProperty("provides-capabilities");
        String requires = props.getProperty("requires-capabilities");
        if (provides != null || requires != null) {
            String coords = dep.getGroupId() + ":" + dep.getArtifactId() + ":"
                    + dep.getVersion();
            modelBuilder.addExtensionCapabilities(
                    CapabilityContract.of(coords, provides, requires));
        }
    }

    private static void addReactorDeps(ModuleBuild module, List<ModuleBuild> reactorDeps,
            Set<String> extensionGAs, Map<String, String> extensionProps,
            ApplicationModelBuilder modelBuilder, Set<String> visited) throws Exception {
        List<String> optionalDepIds = module.optionalModuleDependencyIds();
        for (ModuleBuild dep : reactorDeps) {
            if (!visited.add(dep.artifactId())) continue;
            if (!"jar".equals(dep.packaging())) continue;
            if (!dep.didSucceed()) continue;
            if (optionalDepIds.contains(dep.artifactId())) continue;

            ArtifactKey key = ArtifactKey.of(dep.groupId(), dep.artifactId(), "", "jar");
            if (!modelBuilder.hasDependency(key)) {
                ResolvedDependencyBuilder depBuilder = ResolvedDependencyBuilder.newInstance()
                        .setGroupId(dep.groupId())
                        .setArtifactId(dep.artifactId())
                        .setVersion(dep.version())
                        .setResolvedPath(dep.jarFile())
                        .setRuntimeCp()
                        .setDeploymentCp();

                String ga = dep.groupId() + ":" + dep.artifactId();
                if (extensionGAs.contains(ga)) {
                    depBuilder.setRuntimeExtensionArtifact();
                    String packed = extensionProps.get(ga);
                    if (packed != null) {
                        Properties props = new Properties();
                        props.load(new StringReader(packed.replace("\\n", "\n")));
                        modelBuilder.handleExtensionProperties(props, depBuilder.getKey());
                        registerCapabilities(modelBuilder, depBuilder, props);
                    }
                }

                modelBuilder.addDependency(depBuilder);
            }

            // also add transitive deps from reactor modules (excluding optional)
            Set<String> optional = dep.resolvedOptionalClasspathEntries();
            for (String cp : dep.resolvedClasspath()) {
                if (!optional.contains(cp)) {
                    addRuntimeDep(cp, extensionGAs, extensionProps, modelBuilder);
                }
            }

            addReactorDeps(dep, dep.getDependencies(), extensionGAs, extensionProps, modelBuilder, visited);
        }
    }

    private record GAV(String groupId, String artifactId, String version) {}

    private static GAV parseGAVFromM2Path(String jarPath) {
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
        return new GAV(groupId, artifactId, version);
    }
}
