package io.quarkiverse.qraven.hardcoded.runtime;

import io.quarkus.maven.ExtensionDescriptorGenerator;
import io.quarkus.maven.capabilities.CapabilitiesConfig;
import io.quarkus.maven.capabilities.CapabilityConfig;
import io.quarkus.maven.dependency.ArtifactCoords;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExtensionDescriptorHelper {

    public static void generate(Path classesDir, Map<String, String> properties,
                                List<String> classpath, List<String> reactorModuleGAs,
                                String projectName, String projectDescription,
                                String scmUrl, String minimumJavaVersion,
                                List<String> modelDeps,
                                List<String> parentFirstArtifacts,
                                List<String> runnerParentFirstArtifacts,
                                List<String> excludedArtifacts,
                                List<String> lesserPriorityArtifacts,
                                List<String> providesCapabilities,
                                List<String> requiresCapabilities,
                                boolean skipExtensionValidation,
                                List<String> deploymentClasspath,
                                Set<String> extensionGAs,
                                Map<String, String> extensionProps,
                                Map<String, String> allReactorExtDeployments) {
        String deployment = properties.get("deployment-artifact");
        if (deployment == null) {
            throw new RuntimeException("Missing deployment-artifact in extension descriptor properties");
        }

        String groupId = properties.get("groupId");
        String artifactId = properties.get("artifactId");
        String version = properties.get("version");

        Path extensionFile = classesDir.resolve("META-INF").resolve("quarkus-extension.yaml");

        List<ExtensionDescriptorGenerator.ModelDependency> modelDependencies = parseModelDeps(modelDeps);

        ExtensionDescriptorGenerator.DependencyResolver resolver = createResolver(
                groupId, artifactId, version, classpath, reactorModuleGAs, deploymentClasspath,
                extensionGAs, extensionProps, allReactorExtDeployments);

        try {
            ExtensionDescriptorGenerator generator = new ExtensionDescriptorGenerator.Builder()
                    .groupId(groupId)
                    .artifactId(artifactId)
                    .version(version)
                    .projectName(projectName)
                    .projectDescription(projectDescription)
                    .deployment(deployment)
                    .capabilities(buildCapabilities(providesCapabilities, requiresCapabilities))
                    .outputDirectory(classesDir)
                    .extensionFile(Files.exists(extensionFile) ? extensionFile : null)
                    .scmUrl(scmUrl)
                    .minimumJavaVersion(minimumJavaVersion)
                    .parentFirstArtifacts(parentFirstArtifacts)
                    .runnerParentFirstArtifacts(runnerParentFirstArtifacts)
                    .excludedArtifacts(excludedArtifacts)
                    .lesserPriorityArtifacts(lesserPriorityArtifacts)
                    .skipExtensionValidation(skipExtensionValidation)
                    .ignoreNotDetectedQuarkusCoreVersion(true)
                    .skipCodestartValidation(true)
                    .modelDependencies(modelDependencies)
                    .resolver(resolver)
                    .logger(createLogger())
                    .build();

            generator.generate();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to generate extension descriptor for "
                    + groupId + ":" + artifactId + ": " + e.getMessage(), e);
        }
    }

    private static CapabilitiesConfig buildCapabilities(List<String> provides, List<String> requires) {
        CapabilitiesConfig config = new CapabilitiesConfig();
        if (provides != null) {
            for (String cap : provides) {
                CapabilityConfig cc = new CapabilityConfig();
                cc.set(cap);
                config.addProvides(cc);
            }
        }
        if (requires != null) {
            for (String cap : requires) {
                CapabilityConfig cc = new CapabilityConfig();
                cc.set(cap);
                config.addRequires(cc);
            }
        }
        return config;
    }

    private static ExtensionDescriptorGenerator.DependencyResolver createResolver(
            String groupId, String artifactId, String version,
            List<String> classpath, List<String> reactorModuleGAs,
            List<String> deploymentClasspath,
            Set<String> extensionGAs, Map<String, String> extensionProps,
            Map<String, String> allReactorExtDeployments) {

        Map<String, String> reactorArtifactToGroup = new java.util.HashMap<>();
        for (String ga : reactorModuleGAs) {
            int colon = ga.indexOf(':');
            if (colon > 0) {
                reactorArtifactToGroup.put(ga.substring(colon + 1), ga.substring(0, colon));
            }
        }

        List<ExtensionDescriptorGenerator.DepNode> children = new ArrayList<>();
        for (String jarPath : classpath) {
            GavFromPath gav = parseGavFromM2Path(jarPath);
            if (gav == null) {
                gav = parseGavFromFilename(jarPath, reactorArtifactToGroup);
            }
            if (gav != null) {
                children.add(new ExtensionDescriptorGenerator.DepNode(
                        gav.groupId, gav.artifactId, "", "jar",
                        gav.version, Path.of(jarPath), List.of()));
            }
        }

        ExtensionDescriptorGenerator.DepNode rootNode = new ExtensionDescriptorGenerator.DepNode(
                groupId, artifactId, "", "jar", version, null, children);

        List<ExtensionDescriptorGenerator.DepNode> deploymentChildren = new ArrayList<>();
        if (deploymentClasspath != null) {
            for (String jarPath : deploymentClasspath) {
                GavFromPath gav = parseGavFromM2Path(jarPath);
                if (gav == null) {
                    gav = parseGavFromFilename(jarPath, reactorArtifactToGroup);
                }
                if (gav != null) {
                    deploymentChildren.add(new ExtensionDescriptorGenerator.DepNode(
                            gav.groupId, gav.artifactId, "", "jar",
                            gav.version, Path.of(jarPath), List.of()));
                }
            }
        }

        discoverMissingDeploymentDeps(children, deploymentChildren, extensionGAs, extensionProps,
                allReactorExtDeployments);

        Set<String> deploymentGAs = new HashSet<>();
        for (ExtensionDescriptorGenerator.DepNode dc : deploymentChildren) {
            deploymentGAs.add(dc.getGroupId() + ":" + dc.getArtifactId());
        }
        children.removeIf(c -> deploymentGAs.contains(c.getGroupId() + ":" + c.getArtifactId()));

        return new ExtensionDescriptorGenerator.DependencyResolver() {
            @Override
            public ExtensionDescriptorGenerator.DepNode resolveRuntimeDependencies() {
                return rootNode;
            }

            @Override
            public ExtensionDescriptorGenerator.DepNode collectDeploymentDependencies(ArtifactCoords coords) {
                List<ExtensionDescriptorGenerator.DepNode> allDeps = new ArrayList<>();
                allDeps.add(new ExtensionDescriptorGenerator.DepNode(
                        groupId, artifactId, "", "jar", version, null, List.of()));
                allDeps.addAll(children);
                allDeps.addAll(deploymentChildren);
                return new ExtensionDescriptorGenerator.DepNode(
                        coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(),
                        coords.getType(), coords.getVersion(), null, allDeps);
            }

            @Override
            public Path resolveArtifact(String g, String a, String classifier,
                                        String type, String ver) {
                Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
                String ext = "jar".equals(type) ? "jar" : type;
                String fileName = classifier != null && !classifier.isEmpty()
                        ? a + "-" + ver + "-" + classifier + "." + ext
                        : a + "-" + ver + "." + ext;
                Path path = m2.resolve(g.replace('.', '/'))
                        .resolve(a).resolve(ver).resolve(fileName);
                return Files.exists(path) ? path : null;
            }

            @Override
            public boolean isInWorkspace(String g, String a) {
                return reactorModuleGAs.contains(g + ":" + a);
            }

            @Override
            public Path workspaceClassesDir(String g, String a) {
                return null;
            }

            @Override
            public boolean isParallelBuild() {
                return false;
            }

            @Override
            public boolean isAttachedArtifact(ArtifactCoords coords) {
                return false;
            }
        };
    }

    private static List<ExtensionDescriptorGenerator.ModelDependency> parseModelDeps(List<String> modelDeps) {
        List<ExtensionDescriptorGenerator.ModelDependency> result = new ArrayList<>();
        if (modelDeps == null) return result;
        for (String dep : modelDeps) {
            String[] parts = dep.split(":");
            if (parts.length >= 5) {
                result.add(new ExtensionDescriptorGenerator.ModelDependency(
                        parts[0], parts[1],
                        parts[2].isEmpty() ? null : parts[2],
                        parts[3], parts[4],
                        parts.length > 5 ? parts[5] : "compile",
                        parts.length > 6 && "true".equals(parts[6])));
            }
        }
        return result;
    }

    private static ExtensionDescriptorGenerator.Logger createLogger() {
        return new ExtensionDescriptorGenerator.Logger() {
            @Override
            public void debug(String msg) { }

            @Override
            public void warn(String msg) {
                System.err.println("WARN: " + msg);
            }

            @Override
            public void error(String msg) {
                System.err.println("ERROR: " + msg);
            }
        };
    }

    private static void discoverMissingDeploymentDeps(
            List<ExtensionDescriptorGenerator.DepNode> runtimeChildren,
            List<ExtensionDescriptorGenerator.DepNode> deploymentChildren,
            Set<String> extensionGAs, Map<String, String> extensionProps,
            Map<String, String> allReactorExtDeployments) {
        Set<String> deploymentKeys = new HashSet<>();
        for (ExtensionDescriptorGenerator.DepNode dc : deploymentChildren) {
            deploymentKeys.add(dc.getGroupId() + ":" + dc.getArtifactId());
        }

        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");

        for (ExtensionDescriptorGenerator.DepNode child : runtimeChildren) {
            String ga = child.getGroupId() + ":" + child.getArtifactId();

            String deploymentArtifact = null;

            if (extensionGAs.contains(ga)) {
                String packed = extensionProps.get(ga);
                if (packed != null) {
                    for (String line : packed.split("\\\\n|\\n")) {
                        if (line.startsWith("deployment-artifact=")) {
                            deploymentArtifact = line.substring("deployment-artifact=".length());
                            break;
                        }
                    }
                }
            }

            if (deploymentArtifact == null) {
                deploymentArtifact = allReactorExtDeployments.get(ga);
            }

            if (deploymentArtifact == null) continue;

            String[] parts = deploymentArtifact.split(":");
            if (parts.length < 3) continue;
            String dg = parts[0], da = parts[1], dv = parts[parts.length - 1];
            if (deploymentKeys.contains(dg + ":" + da)) continue;

            Path deployJar = m2.resolve(dg.replace('.', '/'))
                    .resolve(da).resolve(dv).resolve(da + "-" + dv + ".jar");
            if (Files.exists(deployJar)) {
                deploymentChildren.add(new ExtensionDescriptorGenerator.DepNode(
                        dg, da, "", "jar", dv, deployJar, List.of()));
                deploymentKeys.add(dg + ":" + da);
            }
        }
    }

    private record GavFromPath(String groupId, String artifactId, String version) {}

    private static GavFromPath parseGavFromM2Path(String jarPath) {
        String m2 = System.getProperty("user.home") + "/.m2/repository/";
        if (!jarPath.startsWith(m2)) return null;
        String relative = jarPath.substring(m2.length());
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String beforeLastSlash = relative.substring(0, lastSlash);

        int versionSlash = beforeLastSlash.lastIndexOf('/');
        if (versionSlash < 0) return null;
        String version = beforeLastSlash.substring(versionSlash + 1);
        String beforeVersion = beforeLastSlash.substring(0, versionSlash);

        int artifactSlash = beforeVersion.lastIndexOf('/');
        if (artifactSlash < 0) return null;
        String artifactId = beforeVersion.substring(artifactSlash + 1);
        String groupPath = beforeVersion.substring(0, artifactSlash);
        String groupId = groupPath.replace('/', '.');

        return new GavFromPath(groupId, artifactId, version);
    }

    private static GavFromPath parseGavFromFilename(String jarPath, Map<String, String> artifactToGroup) {
        String fileName = Path.of(jarPath).getFileName().toString();
        if (!fileName.endsWith(".jar")) return null;
        String baseName = fileName.substring(0, fileName.length() - 4);
        String bestAid = null;
        String bestGroup = null;
        for (Map.Entry<String, String> entry : artifactToGroup.entrySet()) {
            String aid = entry.getKey();
            if (baseName.startsWith(aid + "-")) {
                if (bestAid == null || aid.length() > bestAid.length()) {
                    bestAid = aid;
                    bestGroup = entry.getValue();
                }
            }
        }
        if (bestAid != null) {
            String ver = baseName.substring(bestAid.length() + 1);
            if (!ver.isEmpty()) {
                return new GavFromPath(bestGroup, bestAid, ver);
            }
        }
        return null;
    }

}
