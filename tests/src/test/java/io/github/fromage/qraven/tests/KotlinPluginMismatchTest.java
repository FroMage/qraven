package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.BuildRuntime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KotlinPluginMismatchTest {

    @Test
    void sameVersion_noWarning() {
        assertThat(BuildRuntime.kotlinPluginMismatch(
                "kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar", "2.4.20")).isNull();
    }

    @Test
    void compilerVersionWithSuffix_isComparedOnItsNumbers() {
        assertThat(BuildRuntime.kotlinPluginMismatch(
                "kotlin-allopen-compiler-plugin-embeddable-2.4.20.jar", "2.4.20-release-123")).isNull();
        assertThat(BuildRuntime.kotlinPluginMismatch(
                "kotlin-allopen-compiler-plugin-embeddable-2.4.20-RC.jar", "2.4.20")).isNull();
    }

    @Test
    void differentVersion_warnsWithBothVersions() {
        String warning = BuildRuntime.kotlinPluginMismatch(
                "kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar", "2.4.10");
        assertThat(warning).contains("2.4.20").contains("2.4.10").contains("NoSuchMethodError");
    }

    @Test
    void unrelatedJars_areIgnored() {
        assertThat(BuildRuntime.kotlinPluginMismatch("some-library-1.0.0.jar", "2.4.20")).isNull();
        assertThat(BuildRuntime.kotlinPluginMismatch("kotlin-stdlib-2.0.0.jar", "2.4.20")).isNull();
    }
}
