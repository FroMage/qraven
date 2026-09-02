package io.quarkiverse.qraven.hardcoded;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class DependencyResolver {

    private final RepositorySystem repoSystem;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> remoteRepos;
    private final Path localRepoPath;

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
                        "https://repo.maven.apache.org/maven2/").build()
        );
    }

    public Path getLocalRepoPath() {
        return localRepoPath;
    }

    public record ResolvedArtifact(String groupId, String artifactId, String version, String filePath) {}

    public List<ResolvedArtifact> resolveCompileClasspath(
            List<org.apache.maven.model.Dependency> dependencies,
            List<org.apache.maven.model.Dependency> managedDependencies) {

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

        DependencyRequest depRequest = new DependencyRequest(collectRequest, null);

        try {
            DependencyResult result = repoSystem.resolveDependencies(session, depRequest);
            return result.getArtifactResults().stream()
                    .filter(ArtifactResult::isResolved)
                    .map(ar -> new ResolvedArtifact(
                            ar.getArtifact().getGroupId(),
                            ar.getArtifact().getArtifactId(),
                            ar.getArtifact().getVersion(),
                            ar.getArtifact().getFile().getAbsolutePath()))
                    .toList();
        } catch (DependencyResolutionException e) {
            System.err.println("WARNING: Dependency resolution incomplete: " + e.getMessage());
            DependencyResult result = e.getResult();
            if (result != null) {
                return result.getArtifactResults().stream()
                        .filter(ArtifactResult::isResolved)
                        .map(ar -> new ResolvedArtifact(
                                ar.getArtifact().getGroupId(),
                                ar.getArtifact().getArtifactId(),
                                ar.getArtifact().getVersion(),
                                ar.getArtifact().getFile().getAbsolutePath()))
                        .toList();
            }
            return List.of();
        }
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
            System.err.println("WARNING: Could not resolve annotation processor " +
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
        DefaultArtifact artifact = new DefaultArtifact(
                mavenDep.getGroupId(),
                mavenDep.getArtifactId(),
                mavenDep.getClassifier() != null ? mavenDep.getClassifier() : "",
                mavenDep.getType() != null ? mavenDep.getType() : "jar",
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
