package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.DependencyResolver;

import org.apache.maven.model.Dependency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyResolverTest {

    private static Dependency dep(String groupId, String artifactId, String version) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        d.setScope("compile");
        return d;
    }

    private static List<String> artifactIds(List<DependencyResolver.ResolvedArtifact> resolved) {
        return resolved.stream().map(DependencyResolver.ResolvedArtifact::artifactId).toList();
    }

    @Test
    void sameDependenciesInAnotherOrder_getTheirOwnClasspathOrder() {
        // both are in ~/.m2 because the tests themselves use them
        Dependency opentest4j = dep("org.opentest4j", "opentest4j", "1.3.0");
        Dependency assertj = dep("org.assertj", "assertj-core", "3.25.3");
        DependencyResolver resolver = new DependencyResolver();

        List<String> a = artifactIds(resolver.resolveCompileClasspath(List.of(opentest4j, assertj), List.of()));
        List<String> b = artifactIds(resolver.resolveCompileClasspath(List.of(assertj, opentest4j), List.of()));

        assertThat(a).containsExactlyInAnyOrderElementsOf(b);
        assertThat(a.indexOf("opentest4j")).isLessThan(a.indexOf("assertj-core"));
        assertThat(b.indexOf("assertj-core")).isLessThan(b.indexOf("opentest4j"));
    }

    @Test
    void sameDependenciesInTheSameOrder_areServedFromTheCache() {
        Dependency opentest4j = dep("org.opentest4j", "opentest4j", "1.3.0");
        DependencyResolver resolver = new DependencyResolver();

        resolver.resolveCompileClasspath(List.of(opentest4j), List.of());
        resolver.resolveCompileClasspath(List.of(opentest4j), List.of());

        assertThat(resolver.resolutionCacheStats()).contains("1 hits, 1 misses");
    }
}
