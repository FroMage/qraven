package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.ExtensionDescriptorHelper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionRequiresQuarkusCoreTest {

    /** The supported Quarkus version range written for an extension built against the given Quarkus core. */
    private static String requiredQuarkusVersion(Path classesDir, String coreVersion) throws Exception {
        Files.createDirectories(classesDir.resolve("META-INF"));
        String core = System.getProperty("user.home") + "/.m2/repository/io/quarkus/quarkus-core/" + coreVersion
                + "/quarkus-core-" + coreVersion + ".jar";
        ExtensionDescriptorHelper.generate(classesDir,
                Map.of("deployment-artifact", "com.test:ext-deployment:1.0", "groupId", "com.test",
                        "artifactId", "ext", "version", "1.0"),
                List.of(core), List.of(), "Ext", null, null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                true, List.of(), Set.of(), Map.of(), Map.of());
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(classesDir.resolve("META-INF/quarkus-extension.properties"))) {
            props.load(in);
        }
        return props.getProperty("requires-quarkus-version");
    }

    @Test
    void aReleaseRequiresItsMajorAndMinor(@TempDir Path tmp) throws Exception {
        assertThat(requiredQuarkusVersion(tmp, "3.33.1")).isEqualTo("[3.33,)");
    }

    @Test
    void aSnapshotRequiresItsMajorAndMinor(@TempDir Path tmp) throws Exception {
        assertThat(requiredQuarkusVersion(tmp, "3.33.999-SNAPSHOT")).isEqualTo("[3.33,)");
    }

    @Test
    void aPreReleaseRequiresItselfBecauseItSortsBeforeItsMajorAndMinor(@TempDir Path tmp) throws Exception {
        // [4.0,) would exclude 4.0.0.Beta1 itself, as Maven orders 4.0.0.Beta1 before 4.0
        assertThat(requiredQuarkusVersion(tmp, "4.0.0.Beta1")).isEqualTo("[4.0.0.Beta1,)");
    }
}
