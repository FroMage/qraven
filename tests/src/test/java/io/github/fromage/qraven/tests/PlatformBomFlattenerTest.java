package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.PlatformBomFlattener;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformBomFlattenerTest {

    private static Dependency dep(String g, String a, String version, String type, String classifier, String scope) {
        Dependency d = new Dependency();
        d.setGroupId(g);
        d.setArtifactId(a);
        d.setVersion(version);
        d.setType(type);
        d.setClassifier(classifier);
        d.setScope(scope);
        return d;
    }

    private static Model bom(Dependency... deps) {
        Model m = new Model();
        m.setGroupId("org.acme");
        m.setArtifactId("acme-bom");
        m.setVersion("1.0");
        m.setPackaging("pom");
        m.setName("Acme BOM");
        DependencyManagement dm = new DependencyManagement();
        for (Dependency d : deps) {
            dm.addDependency(d);
        }
        m.setDependencyManagement(dm);
        return m;
    }

    private static List<String> artifactIds(Model m) {
        return m.getDependencyManagement().getDependencies().stream().map(Dependency::getArtifactId).toList();
    }

    @Test
    void sortsAlphabeticallyWithPlatformArtifactsFirst() {
        Model flat = new PlatformBomFlattener(List.of(), List.of(), true).flatten(bom(
                dep("org.z", "zed", "1", "jar", null, null),
                dep("org.acme", "acme-bom-platform-properties", "1.0", "properties", null, null),
                dep("org.a", "alpha", "1", "jar", null, null),
                dep("org.acme", "acme-bom-platform-descriptor", "1.0", "json", "1.0", null)));

        assertThat(artifactIds(flat)).containsExactly(
                "acme-bom-platform-properties", "acme-bom-platform-descriptor", "alpha", "zed");
    }

    @Test
    void keepsOriginalOrderWhenNotAlphabetical() {
        Model flat = new PlatformBomFlattener(List.of(), List.of(), false).flatten(bom(
                dep("org.z", "zed", "1", "jar", null, null),
                dep("org.a", "alpha", "1", "jar", null, null)));

        assertThat(artifactIds(flat)).containsExactly("zed", "alpha");
    }

    @Test
    void excludesByScopeAndGlobPattern() {
        Model flat = new PlatformBomFlattener(List.of("com.squareup.okhttp3:*", "junit:junit"), List.of("test"), true)
                .flatten(bom(
                        dep("com.squareup.okhttp3", "okhttp", "4", "jar", null, null),
                        dep("junit", "junit", "4", "jar", null, null),
                        dep("org.a", "only-test", "1", "jar", null, "test"),
                        dep("org.a", "kept", "1", "jar", null, "compile")));

        assertThat(artifactIds(flat)).containsExactly("kept");
        // "compile" is the default scope and is not written out
        assertThat(flat.getDependencyManagement().getDependencies().get(0).getScope()).isNull();
    }

    @Test
    void testJarGetsImpliedClassifierAndAClassifierlessTwin() {
        Model flat = new PlatformBomFlattener(List.of(), List.of(), true).flatten(bom(
                dep("org.a", "lib", "1", "test-jar", null, null)));

        List<Dependency> deps = flat.getDependencyManagement().getDependencies();
        assertThat(deps).extracting(Dependency::getClassifier).containsExactlyInAnyOrder(null, "tests");
        assertThat(deps).extracting(Dependency::getType).containsOnly("test-jar");
    }

    @Test
    void keepsExclusionsAndProjectMetadata() {
        Dependency d = dep("org.a", "lib", "1", "jar", null, null);
        Exclusion e = new Exclusion();
        e.setGroupId("org.x");
        e.setArtifactId("*");
        d.addExclusion(e);

        Model flat = new PlatformBomFlattener(List.of(), List.of(), true).flatten(bom(d));

        assertThat(flat.getDependencyManagement().getDependencies().get(0).getExclusions()).hasSize(1);
        assertThat(flat.getArtifactId()).isEqualTo("acme-bom");
        assertThat(flat.getName()).isEqualTo("Acme BOM");
        assertThat(flat.getPackaging()).isEqualTo("pom");
    }
}
