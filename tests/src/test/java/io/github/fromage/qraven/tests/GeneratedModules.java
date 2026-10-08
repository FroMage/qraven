package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.BuildRuntime;
import io.github.fromage.qraven.hardcoded.runtime.ModuleBuild;
import io.github.fromage.qraven.hardcoded.runtime.ModuleStates;

import java.nio.file.Path;
import java.util.List;

/** What a generation left for the build to read: the modules of its state, as the build sees them. */
final class GeneratedModules {

    private GeneratedModules() {
    }

    static ModuleStates open(Path outputDir) {
        return ModuleStates.open(outputDir.resolve("state.cbor"));
    }

    static ModuleBuild module(Path outputDir, String artifactId) {
        return open(outputDir).createByArtifactId(artifactId, new BuildRuntime(Path.of(".").toAbsolutePath()));
    }

    static List<String> ids(Path outputDir) {
        return open(outputDir).ids();
    }
}
