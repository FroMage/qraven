package io.quarkiverse.qraven.hardcoded;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class DependencyResolver {

    private final RepositorySystem repoSystem;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> remoteRepos;
    private final Path localRepoPath;
    private Consumer<String> warningConsumer;

    private final ConcurrentHashMap<String, List<ResolvedArtifact>> resolutionCache = new ConcurrentHashMap<>();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger cacheMisses = new AtomicInteger();

    public DependencyResolver() {
        this.localRepoPath = Path.of(System.getProperty("user.home"), ".m2", "repository");

        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        this.repoSystem = locator.getService(RepositorySystem.class);

        this.session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(localRepoPath.toFile());
        session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session, localRepo));

        for (String key : System.getProperties().stringPropertyNames()) {
            session.setSystemProperty(key, System.getProperty(key));
        }

        this.remoteRepos = List.of(
                new RemoteRepository.Builder("central", "default",
                        "https://repo.maven.apache.org/maven2/").build(),
                new RemoteRepository.Builder("gradle", "default",
                        "https://repo.gradle.org/gradle/libs-releases/").build(),
                new RemoteRepository.Builder("confluent", "default",
                        "https://packages.confluent.io/maven/").build()
        );
    }

    public Path getLocalRepoPath() {
        return localRepoPath;
    }

    public void setWarningConsumer(Consumer<String> consumer) {
        this.warningConsumer = consumer;
    }

    private void warn(String message) {
        if (warningConsumer != null) {
            warningConsumer.accept(message);
        } else {
            System.err.println(message);
        }
    }

    public record ResolvedArtifact(String groupId, String artifactId, String version, String filePath) {}

    public String resolutionCacheStats() {
        return "Resolution cache: " + cacheHits.get() + " hits, "
                + cacheMisses.get() + " misses, "
                + resolutionCache.size() + " distinct keys";
    }

    private String computeResolutionKey(String scope,
            List<org.apache.maven.model.Dependency> dependencies,
            List<org.apache.maven.model.Dependency> managedDependencies) {
        List<String> depKeys = new ArrayList<>(dependencies.size());
        for (org.apache.maven.model.Dependency dep : dependencies) {
            StringBuilder dk = new StringBuilder();
            dk.append(dep.getGroupId()).append(':').append(dep.getArtifactId())
                    .append(':').append(dep.getVersion()).append(':').append(dep.getScope());
            if (dep.getClassifier() != null && !dep.getClassifier().isEmpty()) {
                dk.append(':').append(dep.getClassifier());
            }
            if (dep.getType() != null && !"jar".equals(dep.getType())) {
                dk.append(':').append(dep.getType());
            }
            if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
                for (var excl : dep.getExclusions()) {
                    dk.append('!').append(excl.getGroupId()).append(':').append(excl.getArtifactId());
                }
            }
            depKeys.add(dk.toString());
        }
        Collections.sort(depKeys);
        int managedFp = managedDependencies != null ? managedDependencies.size() : 0;
        if (managedDependencies != null && !managedDependencies.isEmpty()) {
            var first = managedDependencies.get(0);
            var last = managedDependencies.get(managedDependencies.size() - 1);
            managedFp = managedFp * 31
                    + (first.getGroupId() + ":" + first.getArtifactId()).hashCode();
            managedFp = managedFp * 31
                    + (last.getGroupId() + ":" + last.getArtifactId()).hashCode();
        }
        return scope + "#" + managedFp + "|" + String.join("|", depKeys);
    }

    public List<ResolvedArtifact> resolveCompileClasspath(
            List<org.apache.maven.model.Dependency> dependencies,
            List<org.apache.maven.model.Dependency> managedDependencies) {

        String cacheKey = computeResolutionKey("compile", dependencies, managedDependencies);
        List<ResolvedArtifact> cached = resolutionCache.get(cacheKey);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }

        CollectRequest collectRequest = new CollectRequest();

        for (org.apache.maven.model.Dependency dep : dependencies) {
            String scope = dep.getScope() != null ? dep.getScope() : "compile";
            if (!"test".equals(scope)) {
                collectRequest.addDependency(toAetherDep(dep));
            }
        }

        if (managedDependencies != null) {
            for (org.apache.maven.model.Dependency dep : managedDependencies) {
                if (!"import".equals(dep.getScope())) {
                    collectRequest.addManagedDependency(toAetherDep(dep));
                }
            }
        }

        collectRequest.setRepositories(remoteRepos);

        DependencyRequest depRequest = new DependencyRequest(collectRequest,
                DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME));

        try {
            DependencyResult result = repoSystem.resolveDependencies(session, depRequest);
            List<ResolvedArtifact> resolved = toResolvedArtifacts(result);
            resolutionCache.put(cacheKey, resolved);
            cacheMisses.incrementAndGet();
            return resolved;
        } catch (DependencyResolutionException e) {
            warn("WARNING: Dependency resolution incomplete: " + e.getMessage());
            DependencyResult result = e.getResult();
            if (result != null) {
                return toResolvedArtifacts(result);
            }
            return List.of();
        }
    }

    public List<ResolvedArtifact> resolveTestClasspath(
            List<org.apache.maven.model.Dependency> dependencies,
            List<org.apache.maven.model.Dependency> managedDependencies) {

        String cacheKey = computeResolutionKey("test", dependencies, managedDependencies);
        List<ResolvedArtifact> cached = resolutionCache.get(cacheKey);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }

        CollectRequest collectRequest = new CollectRequest();

        for (org.apache.maven.model.Dependency dep : dependencies) {
            collectRequest.addDependency(toAetherDep(dep));
        }

        if (managedDependencies != null) {
            for (org.apache.maven.model.Dependency dep : managedDependencies) {
                if (!"import".equals(dep.getScope())) {
                    collectRequest.addManagedDependency(toAetherDep(dep));
                }
            }
        }

        collectRequest.setRepositories(remoteRepos);

        DependencyRequest depRequest = new DependencyRequest(collectRequest,
                DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME, JavaScopes.TEST));

        try {
            DependencyResult result = repoSystem.resolveDependencies(session, depRequest);
            List<ResolvedArtifact> resolved = toResolvedArtifacts(result);
            resolutionCache.put(cacheKey, resolved);
            cacheMisses.incrementAndGet();
            return resolved;
        } catch (DependencyResolutionException e) {
            warn("WARNING: Test dependency resolution incomplete: " + e.getMessage());
            DependencyResult result = e.getResult();
            if (result != null) {
                return toResolvedArtifacts(result);
            }
            return List.of();
        }
    }

    private List<ResolvedArtifact> toResolvedArtifacts(DependencyResult result) {
        return result.getArtifactResults().stream()
                .filter(ArtifactResult::isResolved)
                .filter(ar -> "jar".equals(ar.getArtifact().getExtension()))
                .map(ar -> new ResolvedArtifact(
                        ar.getArtifact().getGroupId(),
                        ar.getArtifact().getArtifactId(),
                        ar.getArtifact().getVersion(),
                        ar.getArtifact().getFile().getAbsolutePath()))
                .toList();
    }

    public List<String> resolveAnnotationProcessorPath(String groupId, String artifactId, String version,
                                                        List<org.apache.maven.model.Dependency> managedDependencies) {
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(new Dependency(
                new DefaultArtifact(groupId, artifactId, "jar", version), "compile"));
        collectRequest.setRepositories(remoteRepos);

        if (managedDependencies != null) {
            for (org.apache.maven.model.Dependency dep : managedDependencies) {
                if (!"import".equals(dep.getScope()) && dep.getVersion() != null) {
                    collectRequest.addManagedDependency(toAetherDep(dep));
                }
            }
        }

        DependencyRequest depRequest = new DependencyRequest(collectRequest, null);

        try {
            DependencyResult result = repoSystem.resolveDependencies(session, depRequest);
            return result.getArtifactResults().stream()
                    .filter(ArtifactResult::isResolved)
                    .map(ar -> ar.getArtifact().getFile().getAbsolutePath())
                    .toList();
        } catch (DependencyResolutionException e) {
            warn("WARNING: Could not resolve annotation processor " +
                    groupId + ":" + artifactId + ":" + version + ": " + e.getMessage());
            DependencyResult result = e.getResult();
            if (result != null) {
                return result.getArtifactResults().stream()
                        .filter(ArtifactResult::isResolved)
                        .map(ar -> ar.getArtifact().getFile().getAbsolutePath())
                        .toList();
            }
            return List.of();
        }
    }

    public Path resolvePom(String groupId, String artifactId, String version) {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "pom", version);
        ArtifactRequest request = new ArtifactRequest(artifact, remoteRepos, null);
        try {
            ArtifactResult result = repoSystem.resolveArtifact(session, request);
            if (result.isResolved()) {
                return result.getArtifact().getFile().toPath();
            }
        } catch (ArtifactResolutionException e) {
            // fall through
        }
        return null;
    }

    public String resolveArtifactPath(String groupId, String artifactId, String version) {
        Path jarPath = localRepoPath
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".jar");
        if (jarPath.toFile().exists()) {
            return jarPath.toAbsolutePath().toString();
        }
        return null;
    }

    private Dependency toAetherDep(org.apache.maven.model.Dependency mavenDep) {
        String version = mavenDep.getVersion() != null ? mavenDep.getVersion() : "";
        String classifier = mavenDep.getClassifier() != null ? mavenDep.getClassifier() : "";
        String extension = mavenDep.getType() != null ? mavenDep.getType() : "jar";
        if ("test-jar".equals(extension)) {
            extension = "jar";
            if (classifier.isEmpty()) {
                classifier = "tests";
            }
        }
        DefaultArtifact artifact = new DefaultArtifact(
                mavenDep.getGroupId(),
                mavenDep.getArtifactId(),
                classifier,
                extension,
                version);

        String scope = mavenDep.getScope() != null ? mavenDep.getScope() : "compile";
        Dependency dep = new Dependency(artifact, scope);

        if (mavenDep.getExclusions() != null && !mavenDep.getExclusions().isEmpty()) {
            Collection<Exclusion> exclusions = mavenDep.getExclusions().stream()
                    .map(e -> new Exclusion(
                            e.getGroupId() != null ? e.getGroupId() : "*",
                            e.getArtifactId() != null ? e.getArtifactId() : "*",
                            "*", "*"))
                    .toList();
            dep = dep.setExclusions(exclusions);
        }

        return dep;
    }
}
