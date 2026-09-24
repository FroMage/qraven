package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.CodeStyleHelper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodeStyleHelperTest {

    static CodeStyleHelper helper;

    @BeforeAll
    static void init() {
        helper = new CodeStyleHelper(Path.of("."));
    }

    @AfterAll
    static void cleanup() {
        if (helper != null) {
            helper.close();
        }
    }

    private Path writeJavaFile(Path dir, String content) throws IOException {
        Path file = dir.resolve("Test.java");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void sortImports_groupsCorrectly(@TempDir Path dir) throws IOException {
        writeJavaFile(dir,
                "package com.test;\n" +
                "\n" +
                "import org.foo.Bar;\n" +
                "import java.util.List;\n" +
                "import com.example.Baz;\n" +
                "import javax.sql.DataSource;\n" +
                "\n" +
                "public class Test {}\n");

        int count = helper.sortImports(dir);
        assertThat(count).isEqualTo(1);

        String result = Files.readString(dir.resolve("Test.java"));
        int javaPos = result.indexOf("import java.util.List");
        int javaxPos = result.indexOf("import javax.sql.DataSource");
        int orgPos = result.indexOf("import org.foo.Bar");
        int comPos = result.indexOf("import com.example.Baz");
        assertThat(javaPos).isLessThan(javaxPos);
        assertThat(javaxPos).isLessThan(orgPos);
        assertThat(orgPos).isLessThan(comPos);
    }

    @Test
    void sortImports_staticImportsFirst(@TempDir Path dir) throws IOException {
        writeJavaFile(dir,
                "package com.test;\n" +
                "\n" +
                "import java.util.List;\n" +
                "import static java.util.Collections.emptyList;\n" +
                "\n" +
                "public class Test {}\n");

        helper.sortImports(dir);

        String result = Files.readString(dir.resolve("Test.java"));
        int staticPos = result.indexOf("import static");
        int regularPos = result.indexOf("import java.util.List");
        assertThat(staticPos).isLessThan(regularPos);
    }

    @Test
    void sortImports_separatesGroupsWithBlankLines(@TempDir Path dir) throws IOException {
        writeJavaFile(dir,
                "package com.test;\n" +
                "\n" +
                "import org.foo.Bar;\n" +
                "import java.util.List;\n" +
                "\n" +
                "public class Test {}\n");

        helper.sortImports(dir);

        String result = Files.readString(dir.resolve("Test.java"));
        assertThat(result).contains("import java.util.List;\n\nimport org.foo.Bar;");
    }

    @Test
    void sortImports_preservesCodeOutsideImports(@TempDir Path dir) throws IOException {
        writeJavaFile(dir,
                "package com.test;\n" +
                "\n" +
                "import java.util.List;\n" +
                "\n" +
                "public class Test {\n" +
                "    // important comment\n" +
                "    void method() {}\n" +
                "}\n");

        helper.sortImports(dir);

        String result = Files.readString(dir.resolve("Test.java"));
        assertThat(result).contains("// important comment");
        assertThat(result).contains("void method()");
    }

    @Test
    void sortImports_alreadySorted_noChange(@TempDir Path dir) throws IOException {
        String original =
                "package com.test;\n" +
                "\n" +
                "import java.util.List;\n" +
                "\n" +
                "import org.foo.Bar;\n" +
                "\n" +
                "public class Test {}\n";
        writeJavaFile(dir, original);

        int count = helper.sortImports(dir);
        assertThat(count).isEqualTo(0);

        String result = Files.readString(dir.resolve("Test.java"));
        assertThat(result).isEqualTo(original);
    }
}
