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

class ExtensionDescriptorConditionsTest {

    private static Properties generate(Path classesDir, List<String> conditionalDeps, List<String> conditionalDevDeps,
                                       List<String> dependencyCondition) throws Exception {
        Files.createDirectories(classesDir.resolve("META-INF"));
        ExtensionDescriptorHelper.generate(classesDir,
                Map.of("deployment-artifact", "com.test:ext-deployment:1.0", "groupId", "com.test",
                        "artifactId", "ext", "version", "1.0"),
                List.of(), List.of(), "Ext", null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                conditionalDeps, conditionalDevDeps, dependencyCondition, List.of(), List.of(),
                true, List.of(), Set.of(), Map.of(), Map.of());
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(classesDir.resolve("META-INF/quarkus-extension.properties"))) {
            props.load(in);
        }
        return props;
    }

    @Test
    void conditionsAreWrittenToTheDescriptor(@TempDir Path tmp) throws Exception {
        Properties props = generate(tmp, List.of("com.test:extra:1.0", "com.test:more:2.0"),
                List.of("com.test:dev-extra:1.0"), List.of("com.test:trigger", "com.test:other-trigger"));

        assertThat(props.getProperty("conditional-dependencies"))
                .isEqualTo("com.test:extra::jar:1.0 com.test:more::jar:2.0");
        assertThat(props.getProperty("conditional-dev-dependencies")).isEqualTo("com.test:dev-extra::jar:1.0");
        assertThat(props.getProperty("dependency-condition")).isEqualTo("com.test:trigger com.test:other-trigger");
    }

    @Test
    void noConditionsWritesNothing(@TempDir Path tmp) throws Exception {
        Properties props = generate(tmp, List.of(), List.of(), List.of());

        assertThat(props.stringPropertyNames()).noneMatch(n -> n.contains("condition") || n.contains("conditional"));
    }
}
