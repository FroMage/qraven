package com.test.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MakeSources {

    /** Writes com/test/app/generated/Generated.java under the directory given as first argument. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]).resolve("com/test/app/generated");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Generated.java"), """
                package com.test.app.generated;

                public class Generated {
                    public static String greeting() {
                        return "%s";
                    }
                }
                """.formatted(args.length > 1 ? args[1] : "hello"));
    }
}
