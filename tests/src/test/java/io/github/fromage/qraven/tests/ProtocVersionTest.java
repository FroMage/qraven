package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.BuildRuntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocVersionTest {

    /** A "protoc" that only records which one ran. */
    private static String fakeProtoc(Path dir, String name) throws Exception {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/sh\necho " + name + " >> \"" + dir.resolve("ran") + "\"\n");
        script.toFile().setExecutable(true);
        return script.toString();
    }

    private static List<String> compile(Path tmp, BuildRuntime runtime, String protocVersion) throws Exception {
        Path protos = Files.createDirectories(tmp.resolve("proto"));
        Files.writeString(protos.resolve("a.proto"), "syntax = \"proto3\";\n");
        Files.deleteIfExists(tmp.resolve("ran"));
        runtime.compileProtobuf(protos, tmp.resolve("out"), false, false, List.of(), protocVersion, null);
        return Files.readAllLines(tmp.resolve("ran"));
    }

    @Test
    void aModuleGetsTheProtocOfTheVersionItsPluginIsConfiguredWith(@TempDir Path tmp) throws Exception {
        BuildRuntime runtime = new BuildRuntime(tmp);
        runtime.setProtocPath(fakeProtoc(tmp, "default"));
        runtime.setProtocPath("3.25.5", fakeProtoc(tmp, "v3"));
        runtime.setProtocPath("4.33.2", fakeProtoc(tmp, "v4"));

        assertThat(compile(tmp, runtime, "3.25.5")).containsExactly("v3");
        assertThat(compile(tmp, runtime, "4.33.2")).containsExactly("v4");
    }

    @Test
    void aModuleWithoutAVersionOrWithAnUnresolvedOneGetsTheDefault(@TempDir Path tmp) throws Exception {
        BuildRuntime runtime = new BuildRuntime(tmp);
        runtime.setProtocPath(fakeProtoc(tmp, "default"));
        runtime.setProtocPath("3.25.5", fakeProtoc(tmp, "v3"));

        assertThat(compile(tmp, runtime, null)).containsExactly("default");
        assertThat(compile(tmp, runtime, "9.9.9")).containsExactly("default");
    }
}
