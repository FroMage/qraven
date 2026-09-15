package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeStyleHelper {

    private final Path projectRoot;
    private volatile FormatterState jdtFormatter;
    private volatile KtfmtState ktfmtFormatter;

    public CodeStyleHelper(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    // ============== Java Formatting (Eclipse JDT) ==============

    public int formatJavaFiles(Path... sourceDirs) {
        List<Path> javaFiles = collectFiles(sourceDirs, ".java");
        if (javaFiles.isEmpty()) return 0;

        FormatterState state = getJdtFormatter();
        if (state == null) return 0;

        int count = 0;
        for (Path file : javaFiles) {
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Object edit = state.formatMethod.invoke(state.formatter, state.formatKind,
                        source, 0, source.length(), 0, "\n");
                if (edit != null) {
                    Object doc = state.docCtor.newInstance(source);
                    state.applyMethod.invoke(edit, doc);
                    String formatted = (String) state.getMethod.invoke(doc);
                    if (!formatted.equals(source)) {
                        Files.writeString(file, formatted, StandardCharsets.UTF_8);
                        count++;
                    }
                }
            } catch (Exception e) {
                // skip file on error
            }
        }
        return count;
    }

    private FormatterState getJdtFormatter() {
        if (jdtFormatter != null) return jdtFormatter;
        synchronized (this) {
            if (jdtFormatter != null) return jdtFormatter;
            try {
                jdtFormatter = initJdtFormatter();
            } catch (Exception e) {
                System.err.println("WARN: Java formatter init failed: " + e.getMessage());
            }
            return jdtFormatter;
        }
    }

    private FormatterState initJdtFormatter() throws Exception {
        Path formatConfigFile = projectRoot.resolve(
                "independent-projects/ide-config/src/main/resources/eclipse-format.xml");
        if (!Files.exists(formatConfigFile)) return null;

        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        List<URL> urls = new ArrayList<>();
        addJarUrl(urls, m2, "org.eclipse.jdt", "org.eclipse.jdt.core");
        addJarUrl(urls, m2, "org.eclipse.jdt", "ecj");
        addJarUrl(urls, m2, "org.eclipse.platform", "org.eclipse.text");
        addJarUrl(urls, m2, "org.eclipse.platform", "org.eclipse.equinox.common");
        if (urls.size() < 4) {
            System.err.println("WARN: Eclipse JDT formatter jars not found in local Maven repository");
            return null;
        }

        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader());

        Map<String, String> options = parseEclipseFormatXml(formatConfigFile);
        options.putIfAbsent("org.eclipse.jdt.core.compiler.source", "17");
        options.putIfAbsent("org.eclipse.jdt.core.compiler.compliance", "17");
        options.putIfAbsent("org.eclipse.jdt.core.compiler.codegen.targetPlatform", "17");

        Class<?> toolFactoryClass = cl.loadClass("org.eclipse.jdt.core.ToolFactory");
        Method createFormatter = toolFactoryClass.getMethod("createCodeFormatter", Map.class);
        Object formatter = createFormatter.invoke(null, options);
        if (formatter == null) {
            cl.close();
            return null;
        }

        Class<?> codeFormatterClass = cl.loadClass("org.eclipse.jdt.core.formatter.CodeFormatter");
        int kind = codeFormatterClass.getField("K_COMPILATION_UNIT").getInt(null);
        try {
            kind |= codeFormatterClass.getField("F_INCLUDE_COMMENTS").getInt(null);
        } catch (NoSuchFieldException e) {
            // older JDT version
        }
        Method formatMethod = codeFormatterClass.getMethod("format",
                int.class, String.class, int.class, int.class, int.class, String.class);

        Class<?> documentClass = cl.loadClass("org.eclipse.jface.text.Document");
        Constructor<?> docCtor = documentClass.getConstructor(String.class);
        Class<?> iDocumentClass = cl.loadClass("org.eclipse.jface.text.IDocument");
        Class<?> textEditClass = cl.loadClass("org.eclipse.text.edits.TextEdit");
        Method applyMethod = textEditClass.getMethod("apply", iDocumentClass);
        Method getMethod = documentClass.getMethod("get");

        return new FormatterState(cl, formatter, formatMethod, kind, docCtor, applyMethod, getMethod);
    }

    private Map<String, String> parseEclipseFormatXml(Path xmlFile) throws IOException {
        Map<String, String> options = new HashMap<>();
        String xml = Files.readString(xmlFile, StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile("<setting\\s+id=\"([^\"]+)\"\\s+value=\"([^\"]*)\"/>");
        Matcher matcher = pattern.matcher(xml);
        while (matcher.find()) {
            options.put(matcher.group(1), matcher.group(2));
        }
        return options;
    }

    private record FormatterState(URLClassLoader classLoader, Object formatter,
            Method formatMethod, int formatKind,
            Constructor<?> docCtor, Method applyMethod, Method getMethod) {
    }

    // ============== Import Sorting ==============

    private static final String[] IMPORT_GROUPS = {"java.", "javax.", "jakarta.", "org.", "com."};

    public int sortImports(Path... sourceDirs) {
        List<Path> javaFiles = collectFiles(sourceDirs, ".java");
        if (javaFiles.isEmpty()) return 0;

        int count = 0;
        for (Path file : javaFiles) {
            try {
                if (sortFileImports(file)) count++;
            } catch (Exception e) {
                // skip file on error
            }
        }
        return count;
    }

    private boolean sortFileImports(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);

        int firstImport = -1;
        int lastImport = -1;
        List<String> regularImports = new ArrayList<>();
        List<String> staticImports = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import static ") && trimmed.endsWith(";")) {
                if (firstImport < 0) firstImport = i;
                lastImport = i;
                staticImports.add(trimmed);
            } else if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                if (firstImport < 0) firstImport = i;
                lastImport = i;
                regularImports.add(trimmed);
            }
        }

        if (firstImport < 0) return false;

        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String g : IMPORT_GROUPS) {
            groups.put(g, new ArrayList<>());
        }
        groups.put("", new ArrayList<>());

        for (String imp : regularImports) {
            String pkg = imp.substring("import ".length(), imp.length() - 1).trim();
            String matchedGroup = "";
            for (String g : IMPORT_GROUPS) {
                if (pkg.startsWith(g)) {
                    matchedGroup = g;
                    break;
                }
            }
            groups.get(matchedGroup).add(imp);
        }

        java.util.Comparator<String> regularComparator = (a, b) -> {
            return extractImportPath(a).compareTo(extractImportPath(b));
        };
        java.util.Comparator<String> staticComparator = (a, b) -> {
            String pa = extractImportPath(a);
            String pb = extractImportPath(b);
            int lastDotA = pa.lastIndexOf('.');
            int lastDotB = pb.lastIndexOf('.');
            String containerA = lastDotA >= 0 ? pa.substring(0, lastDotA) : "";
            String containerB = lastDotB >= 0 ? pb.substring(0, lastDotB) : "";
            int cmp = containerA.compareTo(containerB);
            if (cmp != 0) return cmp;
            String memberA = lastDotA >= 0 ? pa.substring(lastDotA + 1) : pa;
            String memberB = lastDotB >= 0 ? pb.substring(lastDotB + 1) : pb;
            return memberA.compareTo(memberB);
        };
        for (List<String> group : groups.values()) {
            group.sort(regularComparator);
        }
        staticImports.sort(staticComparator);

        StringBuilder newImports = new StringBuilder();
        boolean first = true;
        // Static imports come first (impsort staticAfter=false default)
        if (!staticImports.isEmpty()) {
            first = false;
            for (String imp : staticImports) {
                newImports.append(imp).append("\n");
            }
        }
        for (String g : IMPORT_GROUPS) {
            List<String> group = groups.get(g);
            if (!group.isEmpty()) {
                if (!first) newImports.append("\n");
                first = false;
                for (String imp : group) {
                    newImports.append(imp).append("\n");
                }
            }
        }
        List<String> otherGroup = groups.get("");
        if (!otherGroup.isEmpty()) {
            if (!first) newImports.append("\n");
            for (String imp : otherGroup) {
                newImports.append(imp).append("\n");
            }
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < firstImport; i++) {
            result.append(lines[i]).append("\n");
        }
        result.append(newImports);
        for (int i = lastImport + 1; i < lines.length; i++) {
            result.append(lines[i]);
            if (i < lines.length - 1) result.append("\n");
        }

        String newContent = result.toString();
        if (!newContent.equals(content)) {
            Files.writeString(file, newContent, StandardCharsets.UTF_8);
            return true;
        }
        return false;
    }

    private static String extractImportPath(String importStatement) {
        String s = importStatement.trim();
        if (s.startsWith("import static ")) {
            s = s.substring("import static ".length());
        } else if (s.startsWith("import ")) {
            s = s.substring("import ".length());
        }
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.trim();
    }

    // ============== Kotlin Formatting (ktfmt) ==============

    public int formatKotlinFiles(Path... sourceDirs) {
        List<Path> ktFiles = collectFiles(sourceDirs, ".kt");
        if (ktFiles.isEmpty()) return 0;

        KtfmtState state = getKtfmtFormatter();
        if (state == null) return 0;

        int count = 0;
        for (Path file : ktFiles) {
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String formatted = (String) state.formatMethod.invoke(null,
                        state.kotlinlangFormat, source);
                if (!formatted.equals(source)) {
                    Files.writeString(file, formatted, StandardCharsets.UTF_8);
                    count++;
                }
            } catch (Exception e) {
                // skip file on error
            }
        }
        return count;
    }

    private KtfmtState getKtfmtFormatter() {
        if (ktfmtFormatter != null) return ktfmtFormatter;
        synchronized (this) {
            if (ktfmtFormatter != null) return ktfmtFormatter;
            try {
                ktfmtFormatter = initKtfmt();
            } catch (Exception e) {
                System.err.println("WARN: ktfmt init failed: " + e.getMessage());
            }
            return ktfmtFormatter;
        }
    }

    private KtfmtState initKtfmt() throws Exception {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        List<URL> urls = new ArrayList<>();
        addJarUrl(urls, m2, "com.facebook", "ktfmt");
        addJarUrl(urls, m2, "com.google.googlejavaformat", "google-java-format");
        addJarUrl(urls, m2, "com.google.guava", "guava");
        addJarUrl(urls, m2, "com.google.guava", "failureaccess");
        addJarUrl(urls, m2, "com.google.errorprone", "error_prone_annotations");

        if (urls.isEmpty()) {
            System.err.println("WARN: ktfmt jars not found in local Maven repository");
            return null;
        }

        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]),
                getClass().getClassLoader());

        Class<?> formatterClass = cl.loadClass("com.facebook.ktfmt.format.Formatter");
        Class<?> formattingOptionsClass = cl.loadClass("com.facebook.ktfmt.format.FormattingOptions");

        Field kotlinlangField = formatterClass.getField("KOTLINLANG_FORMAT");
        Object kotlinlangFormat = kotlinlangField.get(null);

        Method formatMethod = formatterClass.getMethod("format",
                formattingOptionsClass, String.class);

        return new KtfmtState(cl, formatMethod, kotlinlangFormat);
    }

    private record KtfmtState(URLClassLoader classLoader, Method formatMethod,
            Object kotlinlangFormat) {
    }

    // ============== Helpers ==============

    private List<Path> collectFiles(Path[] dirs, String extension) {
        List<Path> files = new ArrayList<>();
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) continue;
            try (var stream = Files.walk(dir)) {
                stream.filter(p -> p.toString().endsWith(extension))
                        .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                        .forEach(files::add);
            } catch (IOException e) {
                // skip
            }
        }
        return files;
    }

    private void addJarUrl(List<URL> urls, Path m2, String groupId, String artifactId) {
        Path jar = findJarInM2(m2, groupId, artifactId);
        if (jar != null) {
            try {
                urls.add(jar.toUri().toURL());
            } catch (Exception e) {
                // skip
            }
        }
    }

    static Path findJarInM2(Path m2, String groupId, String artifactId) {
        Path artifactDir = m2.resolve(groupId.replace('.', '/')).resolve(artifactId);
        if (!Files.isDirectory(artifactDir)) return null;
        try (var versions = Files.list(artifactDir)) {
            return versions.filter(Files::isDirectory)
                    .map(v -> v.resolve(artifactId + "-" + v.getFileName() + ".jar"))
                    .filter(Files::exists)
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public void close() {
        if (jdtFormatter != null && jdtFormatter.classLoader != null) {
            try { jdtFormatter.classLoader.close(); } catch (IOException e) { /* ignore */ }
        }
        if (ktfmtFormatter != null && ktfmtFormatter.classLoader != null) {
            try { ktfmtFormatter.classLoader.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}
