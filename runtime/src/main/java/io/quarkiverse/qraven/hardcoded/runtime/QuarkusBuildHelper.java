package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.CapabilityContract;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathCollection;
import io.quarkus.paths.PathList;

public class QuarkusBuildHelper {

    private static CuratedApplication bootstrap(ModuleBuild module, List<ModuleBuild> reactorDeps) throws Exception {
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

        addCodegenToolArtifacts(module, reactorDeps, deploymentCp, modelBuilder);

        var appModel = modelBuilder.build();

        Properties buildSystemProps = new Properties();
        for (Map.Entry<String, String> e : module.quarkusBuildProperties().entrySet()) {
            String val = e.getValue();
            if (val.contains("${")) {
                // Resolve Maven-style property expressions from system properties
                String resolved = val;
                int start;
                while ((start = resolved.indexOf("${")) >= 0) {
                    int end = resolved.indexOf("}", start);
                    if (end < 0) break;
                    String propName = resolved.substring(start + 2, end);
                    String propVal = System.getProperty(propName, "");
                    resolved = resolved.substring(0, start) + propVal + resolved.substring(end + 1);
                }
                if (!resolved.isEmpty()) {
                    buildSystemProps.put(e.getKey(), resolved);
                }
            } else {
                buildSystemProps.put(e.getKey(), val);
            }
        }

        return QuarkusBootstrap.builder()
                .setBaseClassLoader(QuarkusBuildHelper.class.getClassLoader())
                .setExistingModel(appModel)
                .setAppArtifact(appModel.getAppArtifact())
                .setProjectRoot(module.targetDir().getParent())
                .setTargetDirectory(module.targetDir())
                .setBaseName(module.artifactId() + "-" + module.version())
                .setBuildSystemProperties(buildSystemProps)
                .setLocalProjectDiscovery(false)
                .setIsolateDeployment(true)
                .build()
                .bootstrap();
    }

    static void run(ModuleBuild module, List<ModuleBuild> reactorDeps) throws Exception {
        try (CuratedApplication app = bootstrap(module, reactorDeps)) {
            app.createAugmentor().createProductionApplication();
        }
    }

    static Path generateCode(ModuleBuild module, List<ModuleBuild> reactorDeps) throws Exception {
        Path generatedSourcesDir = module.targetDir().resolve("generated-sources");
        ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
        long t0 = System.currentTimeMillis();
        try (CuratedApplication app = bootstrap(module, reactorDeps)) {
            long t1 = System.currentTimeMillis();
            QuarkusClassLoader deploymentCl = app.createDeploymentClassLoader();
            long t2 = System.currentTimeMillis();
            Thread.currentThread().setContextClassLoader(deploymentCl);
            try {
                Class<?> codeGenerator = deploymentCl.loadClass("io.quarkus.deployment.CodeGenerator");
                Method initAndRun = codeGenerator.getMethod("initAndRun",
                        QuarkusClassLoader.class, PathCollection.class,
                        Path.class, Path.class,
                        Consumer.class, ApplicationModel.class, Properties.class, String.class,
                        boolean.class);

                Path sourceParent = module.sourceDir().getParent();
                PathCollection sourceParentDirs = PathList.of(sourceParent);
                Properties buildProps = new Properties();
                buildProps.putAll(module.quarkusBuildProperties());

                long t3 = System.currentTimeMillis();
                initAndRun.invoke(null, deploymentCl, sourceParentDirs,
                        generatedSourcesDir, module.targetDir(),
                        (Consumer<Path>) p -> {}, app.getApplicationModel(), buildProps,
                        "NORMAL", false);
                long t4 = System.currentTimeMillis();
                System.err.println("[timing] [" + module.artifactId() + "] generateCode breakdown: "
                        + "bootstrap=" + (t1 - t0) + "ms, createDeploymentCL=" + (t2 - t1)
                        + "ms, initAndRun=" + (t4 - t3) + "ms, total=" + (t4 - t0) + "ms");
            } finally {
                Thread.currentThread().setContextClassLoader(originalTccl);
                deploymentCl.close();
            }
        }
        return generatedSourcesDir;
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

    private static void addCodegenToolArtifacts(ModuleBuild module, List<ModuleBuild> reactorDeps,
            List<String> deploymentCp, ApplicationModelBuilder modelBuilder) {
        String classifier = osClassifier();
        if (classifier == null) return;

        String m2 = System.getProperty("user.home") + "/.m2/repository/";
        String protocVersion = module.protocVersion();
        String grpcVersion = module.grpcVersion();
        String quarkusGrpcVersion = module.quarkusGrpcVersion();

        if (protocVersion != null) {
            Path protocPath = Path.of(m2, "com/google/protobuf/protoc/" + protocVersion
                    + "/protoc-" + protocVersion + "-" + classifier + ".exe");
            if (Files.exists(protocPath)) {
                addToolArtifact(modelBuilder, "com.google.protobuf", "protoc",
                        protocVersion, classifier, "exe", protocPath);
            }
        }
        if (grpcVersion != null) {
            Path grpcPluginPath = Path.of(m2, "io/grpc/protoc-gen-grpc-java/" + grpcVersion
                    + "/protoc-gen-grpc-java-" + grpcVersion + "-" + classifier + ".exe");
            if (Files.exists(grpcPluginPath)) {
                addToolArtifact(modelBuilder, "io.grpc", "protoc-gen-grpc-java",
                        grpcVersion, classifier, "exe", grpcPluginPath);
            }
        }
        if (quarkusGrpcVersion != null) {
            Path quarkusPluginPath = Path.of(m2, "io/quarkus/quarkus-grpc-protoc-plugin/"
                    + quarkusGrpcVersion + "/quarkus-grpc-protoc-plugin-" + quarkusGrpcVersion
                    + "-shaded.jar");
            if (Files.exists(quarkusPluginPath)) {
                addToolArtifact(modelBuilder, "io.quarkus", "quarkus-grpc-protoc-plugin",
                        quarkusGrpcVersion, "shaded", "jar", quarkusPluginPath);
            }
        }
    }

    private static void addToolArtifact(ApplicationModelBuilder modelBuilder,
            String groupId, String artifactId, String version,
            String classifier, String type, Path path) {
        ArtifactKey key = ArtifactKey.of(groupId, artifactId, classifier, type);
        if (modelBuilder.hasDependency(key)) return;
        modelBuilder.addDependency(ResolvedDependencyBuilder.newInstance()
                .setGroupId(groupId)
                .setArtifactId(artifactId)
                .setVersion(version)
                .setClassifier(classifier)
                .setType(type)
                .setResolvedPath(path)
                .setDeploymentCp());
    }

    private static String osClassifier() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String osName;
        if (os.contains("linux")) {
            osName = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osName = "osx";
        } else if (os.contains("win")) {
            osName = "windows";
        } else {
            return null;
        }
        String archName;
        if ("amd64".equals(arch) || "x86_64".equals(arch)) {
            archName = "x86_64";
        } else if ("aarch64".equals(arch) || "arm64".equals(arch)) {
            archName = "aarch_64";
        } else {
            return null;
        }
        return osName + "-" + archName;
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
