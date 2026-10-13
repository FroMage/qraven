package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.ClasspathKeys;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathKeysTest {

    private static final String M2 = "/home/u/.m2/repository/";

    @Test
    void versionsOfTheSameArtifactShareAKey() {
        assertThat(ClasspathKeys.of(M2 + "io/quarkus/quarkus-core/3.40.1/quarkus-core-3.40.1.jar"))
                .isEqualTo(ClasspathKeys.of(M2 + "io/quarkus/quarkus-core/4.0.0.Beta1/quarkus-core-4.0.0.Beta1.jar"));
    }

    @Test
    void differentArtifactsOrGroupsDoNotShareAKey() {
        assertThat(ClasspathKeys.of(M2 + "io/quarkus/quarkus-core/1/quarkus-core-1.jar"))
                .isNotEqualTo(ClasspathKeys.of(M2 + "io/quarkus/quarkus-core-deployment/1/quarkus-core-deployment-1.jar"))
                .isNotEqualTo(ClasspathKeys.of(M2 + "io/other/quarkus-core/1/quarkus-core-1.jar"));
    }

    @Test
    void classifiersAreKeptApart() {
        assertThat(ClasspathKeys.of(M2 + "a/b/1/b-1.jar"))
                .isNotEqualTo(ClasspathKeys.of(M2 + "a/b/1/b-1-tests.jar"))
                .isEqualTo(ClasspathKeys.of(M2 + "a/b/2/b-2.jar"));
        assertThat(ClasspathKeys.of(M2 + "a/b/1/b-1-tests.jar")).isEqualTo(ClasspathKeys.of(M2 + "a/b/2/b-2-tests.jar"));
    }

    @Test
    void otherPathsAreTheirOwnKey() {
        assertThat(ClasspathKeys.of("/work/mod/target/mod-1.0.jar")).isEqualTo("/work/mod/target/mod-1.0.jar");
        assertThat(ClasspathKeys.of("plain.jar")).isEqualTo("plain.jar");
    }
}
