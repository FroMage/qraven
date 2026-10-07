package io.github.fromage.qraven.hardcoded;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A digest of the environment the effective models are built in. Maven interpolates and activates profiles from the
 * JVM's system properties (JDK version, OS, anything passed with -D), so a generation state is only reusable when
 * they are the same.
 * <p>
 * Only properties that are part of that environment are digested: the standard ones a pom can plausibly refer to,
 * and every property outside the JDK's own namespaces, which is how a user's {@code -D} shows up. The JDK and
 * libraries add and change other properties while running (the time zone is set lazily, for one), and those must
 * not make the key differ between two runs of the same environment.
 */
final class GenerationEnvironment {

    private static final Set<String> STANDARD = Set.of(
            "java.version", "java.specification.version", "java.runtime.version", "java.vendor", "java.home",
            "java.io.tmpdir", "os.name", "os.arch", "os.version", "user.home", "user.name", "user.dir",
            "file.separator", "path.separator", "line.separator");

    private static final List<String> JDK_NAMESPACES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "os.", "user.", "file.", "path.", "line.", "native.",
            "stdout.", "stderr.", "awt.", "surefire.", "basedir");

    private GenerationEnvironment() {
    }

    static boolean relevant(String property) {
        return STANDARD.contains(property) || JDK_NAMESPACES.stream().noneMatch(property::startsWith);
    }

    static String key() {
        Map<String, String> relevant = new TreeMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (relevant(name)) {
                relevant.put(name, System.getProperty(name));
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            relevant.forEach((k, v) -> {
                digest.update(k.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(v.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
