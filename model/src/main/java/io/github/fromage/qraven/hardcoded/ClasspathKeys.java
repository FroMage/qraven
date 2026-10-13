package io.github.fromage.qraven.hardcoded;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Identifies classpath entries independently of their version, so that merging the classpaths of
 * several modules keeps one version of each artifact, like Maven's nearest-wins mediation.
 */
public final class ClasspathKeys {

    private ClasspathKeys() {
    }

    /**
     * Returns a key shared by every version of the same artifact (same group, artifact id, classifier
     * and extension) for entries laid out as {@code <group>/<artifact>/<version>/<artifact>-<version>[-classifier].<ext>},
     * and the path itself for anything else (such as a reactor module's {@code target/} jar).
     */
    public static String of(String path) {
        int fileSlash = path.lastIndexOf('/');
        int versionSlash = fileSlash > 0 ? path.lastIndexOf('/', fileSlash - 1) : -1;
        int artifactSlash = versionSlash > 0 ? path.lastIndexOf('/', versionSlash - 1) : -1;
        if (artifactSlash < 0) return path;
        String file = path.substring(fileSlash + 1);
        String version = path.substring(versionSlash + 1, fileSlash);
        String artifact = path.substring(artifactSlash + 1, versionSlash);
        String prefix = artifact + "-" + version;
        if (!file.startsWith(prefix)) return path;
        return path.substring(0, versionSlash) + "|" + file.substring(prefix.length());
    }

    public static Set<String> keysOf(Collection<String> paths) {
        Set<String> keys = new HashSet<>();
        for (String p : paths) keys.add(of(p));
        return keys;
    }
}
