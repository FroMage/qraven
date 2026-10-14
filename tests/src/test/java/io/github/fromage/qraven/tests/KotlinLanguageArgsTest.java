package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.BuildRuntime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KotlinLanguageArgsTest {

    @Test
    void usesTheMajorAndMinorOfTheProjectsKotlinVersion() {
        assertThat(BuildRuntime.kotlinLanguageArgs("2.3.10", null, null))
                .containsExactly("-language-version", "2.3", "-api-version", "2.3");
    }

    @Test
    void anExplicitLanguageVersionWinsAndTheApiVersionFollowsIt() {
        assertThat(BuildRuntime.kotlinLanguageArgs("2.3.10", "2.2", null))
                .containsExactly("-language-version", "2.2", "-api-version", "2.2");
    }

    @Test
    void anExplicitApiVersionIsKept() {
        assertThat(BuildRuntime.kotlinLanguageArgs("2.3.10", "2.3", "2.2"))
                .containsExactly("-language-version", "2.3", "-api-version", "2.2");
    }

    @Test
    void nothingIsPassedWithoutAKotlinVersion() {
        assertThat(BuildRuntime.kotlinLanguageArgs(null, null, null)).isEmpty();
        assertThat(BuildRuntime.kotlinLanguageArgs(" ", "", " ")).isEmpty();
    }

    @Test
    void nothingIsPassedForAVersionTheCompilerDoesNotSupport() {
        assertThat(BuildRuntime.kotlinLanguageArgs("1.9.22", null, null)).isEmpty();
        assertThat(BuildRuntime.kotlinLanguageArgs("99.0.0", null, null)).isEmpty();
        assertThat(BuildRuntime.kotlinLanguageArgs("2.3.10", "99.0", null)).isEmpty();
    }
}
