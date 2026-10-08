package io.github.fromage.qraven.hardcoded.runtime;

import io.github.fromage.qraven.hardcoded.StateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** The modules of a generation state, ready to be turned into {@link ModuleBuild}s by id. */
public final class ModuleStates {

    private final StateStore.State state;
    private final Map<String, Integer> indexById = new HashMap<>();
    private final Path flattenedPomsDir;

    private ModuleStates(StateStore.State state, Path flattenedPomsDir) {
        this.state = state;
        this.flattenedPomsDir = flattenedPomsDir;
        for (int i = 0; i < state.size(); i++) {
            indexById.putIfAbsent(state.moduleIds().get(i), i);
        }
    }

    /**
     * Opens the state written by generation. It is read as it is: whether it matches this project and environment
     * was checked when it was written.
     */
    public static ModuleStates open(Path stateFile) {
        if (!Files.exists(stateFile)) {
            throw new IllegalStateException("The generation state " + stateFile
                    + " does not exist. Run qraven to generate it.");
        }
        StateStore.State state = StateStore.read(stateFile, null, null, null).orElseThrow(() ->
                new IllegalStateException("The generation state " + stateFile
                        + " cannot be read, or was written by an incompatible qraven. Run qraven to generate it again."));
        return new ModuleStates(state, stateFile.toAbsolutePath().getParent().resolve("flattened-poms"));
    }

    /** The {@code groupId:artifactId} of every module, in the order they were written. */
    public java.util.List<String> ids() {
        return state.moduleIds();
    }

    /** Creates the build of the module with this artifactId (the first one if several modules share it). */
    public ModuleBuild createByArtifactId(String artifactId, BuildRuntime runtime) {
        for (String id : state.moduleIds()) {
            if (id.endsWith(":" + artifactId)) {
                return create(id, runtime);
            }
        }
        throw new IllegalStateException("No module " + artifactId + " in the generation state");
    }

    /** Creates the build of the module with this {@code groupId:artifactId}. */
    public ModuleBuild create(String id, BuildRuntime runtime) {
        Integer index = indexById.get(id);
        if (index == null) {
            throw new IllegalStateException("Module " + id + " is not in the generation state. "
                    + "Run qraven to generate it again.");
        }
        return new DataModuleBuild(runtime, state.module(index), flattenedPomsDir);
    }
}
