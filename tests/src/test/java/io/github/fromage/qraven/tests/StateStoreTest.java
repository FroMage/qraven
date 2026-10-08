package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.ModuleInfo;
import io.github.fromage.qraven.hardcoded.StateStore;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StateStoreTest {

    private static final String VERSION = "1.2.3";
    private static final Path ROOT = Path.of("/projects/demo");
    private static final String KEY = "environment-1";

    // ---------------------------------------------------------------- fixtures

    private static ModuleInfo populated(String artifactId) throws Exception {
        return ModuleInfoFixtures.populated(artifactId);
    }

    private static List<Field> declaredFields() {
        return ModuleInfoFixtures.declaredFields();
    }

    private static String xml(Model model) throws Exception {
        StringWriter w = new StringWriter();
        new MavenXpp3Writer().write(w, model);
        return w.toString();
    }

    private static boolean same(Object a, Object b) throws Exception {
        if (a instanceof Model ma && b instanceof Model mb) {
            return xml(ma).equals(xml(mb));
        }
        if (a instanceof Path || b instanceof Path) {
            return Objects.equals(String.valueOf(a), String.valueOf(b));
        }
        return Objects.equals(a, b);
    }

    private static ModuleInfo simple(String artifactId, String... classpath) {
        ModuleInfo m = new ModuleInfo();
        m.setGroupId("com.test");
        m.setArtifactId(artifactId);
        m.setVersion("1.0");
        m.setCompileClasspath(new ArrayList<>(List.of(classpath)));
        return m;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void everyFieldSurvivesARoundTrip(@TempDir Path dir) throws Exception {
        ModuleInfo original = populated("everything");

        // the fixture must really populate every field, otherwise the round trip proves nothing
        ModuleInfo defaults = new ModuleInfo();
        for (Field f : declaredFields()) {
            f.setAccessible(true);
            assertThat(same(f.get(original), f.get(defaults)))
                    .as("fixture leaves %s at its default", f.getName()).isFalse();
        }

        Path file = dir.resolve("state.cbor");
        StateStore.write(file, VERSION, ROOT, KEY, List.of(original));
        ModuleInfo restored = StateStore.read(file, VERSION, ROOT, KEY).orElseThrow().module(0);

        for (Field f : declaredFields()) {
            f.setAccessible(true);
            assertThat(same(f.get(original), f.get(restored))).as("field %s", f.getName()).isTrue();
        }
        assertThat(StateStore.toJson(restored)).isEqualTo(StateStore.toJson(original));
    }

    @Test
    void emptyAndNullFieldsSurviveToo(@TempDir Path dir) throws Exception {
        ModuleInfo bare = new ModuleInfo();
        Path file = dir.resolve("state.cbor");
        StateStore.write(file, VERSION, ROOT, KEY, List.of(bare));

        ModuleInfo restored = StateStore.read(file, VERSION, ROOT, KEY).orElseThrow().module(0);

        assertThat(StateStore.toJson(restored)).isEqualTo(StateStore.toJson(bare));
        assertThat(restored.getGroupId()).isNull();
        assertThat(restored.getPackaging()).isEqualTo("jar");
        assertThat(restored.getCompileClasspath()).isEmpty();
    }

    @Test
    void modulesAreFoundByIdOrArtifactId(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.cbor");
        StateStore.write(file, VERSION, ROOT, KEY, List.of(simple("a"), simple("b"), simple("c")));

        StateStore.State state = StateStore.read(file, VERSION, ROOT, KEY).orElseThrow();

        assertThat(state.size()).isEqualTo(3);
        assertThat(state.moduleIds()).containsExactly("com.test:a", "com.test:b", "com.test:c");
        assertThat(state.find("com.test:b").orElseThrow().getArtifactId()).isEqualTo("b");
        assertThat(state.find("c").orElseThrow().getArtifactId()).isEqualTo("c");
        assertThat(state.find("missing")).isEmpty();
        assertThat(state.modules()).extracting(ModuleInfo::getArtifactId).containsExactly("a", "b", "c");
    }

    @Test
    void stringsAreSharedBetweenModules(@TempDir Path dir) throws Exception {
        String[] classpath = new String[200];
        for (int i = 0; i < classpath.length; i++) {
            classpath[i] = "$HOME/.m2/repository/org/example/lib" + i + "/1.0/lib" + i + "-1.0.jar";
        }
        Path one = dir.resolve("one.cbor");
        Path many = dir.resolve("many.cbor");
        StateStore.write(one, VERSION, ROOT, KEY, List.of(simple("m0", classpath)));
        List<ModuleInfo> modules = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            modules.add(simple("m" + i, classpath));
        }
        StateStore.write(many, VERSION, ROOT, KEY, modules);

        // 50 modules with the same 200 jars cost far less than 50 times one module
        assertThat(Files.size(many)).isLessThan(Files.size(one) * 3);
    }

    @Test
    void sameInputGivesIdenticalBytes(@TempDir Path dir) throws Exception {
        List<ModuleInfo> modules = List.of(populated("x"), simple("y", "a.jar", "b.jar"));
        Path first = dir.resolve("first.cbor");
        Path second = dir.resolve("second.cbor");

        StateStore.write(first, VERSION, ROOT, KEY, modules);
        StateStore.write(second, VERSION, ROOT, KEY, modules);

        assertThat(Files.readAllBytes(second)).isEqualTo(Files.readAllBytes(first));
    }

    @Test
    void writingReplacesThePreviousStateAndLeavesNoTempFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.cbor");
        StateStore.write(file, VERSION, ROOT, KEY, List.of(simple("old")));
        StateStore.write(file, VERSION, ROOT, KEY, List.of(simple("new")));

        assertThat(StateStore.read(file, VERSION, ROOT, KEY).orElseThrow().moduleIds()).containsExactly("com.test:new");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("state.cbor");
        }
    }

    @Test
    void stateFromAnotherVersionProjectOrEnvironmentIsIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.cbor");
        StateStore.write(file, VERSION, ROOT, KEY, List.of(simple("a")));

        assertThat(StateStore.read(file, VERSION, ROOT, KEY)).isPresent();
        assertThat(StateStore.read(file, "9.9.9", ROOT, KEY)).isEmpty();
        assertThat(StateStore.read(file, VERSION, Path.of("/projects/other"), KEY)).isEmpty();
        assertThat(StateStore.read(file, VERSION, ROOT, "another-environment")).isEmpty();
        // inspection skips those checks
        assertThat(StateStore.read(file, null, null, null)).isPresent();
    }

    @Test
    void unreadableStateIsTreatedAsAbsent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.cbor");
        StateStore.write(file, VERSION, ROOT, KEY, List.of(populated("a"), populated("b")));
        byte[] good = Files.readAllBytes(file);

        assertThat(StateStore.read(dir.resolve("missing.cbor"), VERSION, ROOT, KEY)).isEmpty();

        Files.write(file, new byte[0]);
        assertThat(StateStore.read(file, VERSION, ROOT, KEY)).isEmpty();

        Files.write(file, "not cbor at all".getBytes());
        assertThat(StateStore.read(file, VERSION, ROOT, KEY)).isEmpty();

        Files.write(file, Arrays.copyOf(good, good.length / 2));
        assertThat(StateStore.read(file, VERSION, ROOT, KEY)).as("truncated").isEmpty();

        for (int position : new int[] { 10, good.length / 2, good.length - 8 }) {
            byte[] corrupt = good.clone();
            corrupt[position] ^= 0x55;
            Files.write(file, corrupt);
            assertThat(StateStore.read(file, VERSION, ROOT, KEY)).as("byte %d flipped", position).isEmpty();
        }

        Files.write(file, good);
        assertThat(StateStore.read(file, VERSION, ROOT, KEY)).isPresent();
    }

    @Test
    void jsonDumpShowsFieldsAndValues() throws Exception {
        ModuleInfo module = simple("dumped", "one.jar");
        module.setHasKotlinSources(true);

        String json = StateStore.toJson(module);

        assertThat(json).contains("\"artifactId\": \"dumped\"")
                .contains("\"hasKotlinSources\": true")
                .contains("\"compileClasspath\": [\n    \"one.jar\"\n  ]")
                .contains("\"deploymentClasspath\": []");
    }
}
