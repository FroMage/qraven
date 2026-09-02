package io.quarkiverse.qraven.hardcoded;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;

import java.nio.file.Files;
import java.nio.file.Path;

public class LocalRepoModelResolver implements ModelResolver {

    private final Path localRepoDir;

    public LocalRepoModelResolver(Path localRepoDir) {
        this.localRepoDir = localRepoDir;
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        Path pomPath = localRepoDir
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
        if (Files.exists(pomPath)) {
            return new FileModelSource(pomPath.toFile());
        }
        throw new UnresolvableModelException(
                "Cannot find " + groupId + ":" + artifactId + ":" + version + " in " + localRepoDir,
                groupId, artifactId, version);
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
    }

    @Override
    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
    }

    @Override
    public ModelResolver newCopy() {
        return new LocalRepoModelResolver(localRepoDir);
    }
}
