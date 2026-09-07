package io.quarkiverse.qraven.hardcoded;

import io.quarkiverse.qraven.hardcoded.runtime.BuildRuntime;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public class BuildFileGenerator {

    private final Path projectRoot;
    private final Path outputDir;
    private final int threads;

    public BuildFileGenerator(Path projectRoot, Path outputDir, int threads) {
        this.projectRoot = projectRoot;
        this.outputDir = outputDir;
        this.threads = threads;
    }

    public interface ProgressListener {
        void update(String detail, int current, int total);
    }

    private ProgressListener progressListener;

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    public void generate(List<ModuleInfo> modules) throws IOException {
        Path srcDir = outputDir.resolve("src");
        Files.createDirectories(srcDir);

        int total = modules.size() + 1;
        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo module = modules.get(i);
            String className = sanitizeClassName(module.getArtifactId());
            Path sourceFile = srcDir.resolve("Build_" + className + ".java");
            Files.writeString(sourceFile, generateModuleClass(module, className));
            if (progressListener != null) {
                progressListener.update(module.getArtifactId(), i + 1, total);
            }
        }

        Path mainFile = srcDir.resolve("Build.java");
        Files.writeString(mainFile, generateMainClass(modules));
        if (progressListener != null) {
            progressListener.update("Build.java", total, total);
        }
    }

    private String generateModuleClass(ModuleInfo module, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("import io.quarkiverse.qraven.hardcoded.runtime.ModuleBuild;\n");
        sb.append("import io.quarkiverse.qraven.hardcoded.runtime.BuildRuntime;\n");
        sb.append("import java.nio.file.Path;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n\n");

        sb.append("public class Build_").append(className).append(" extends ModuleBuild {\n\n");

        sb.append("    public Build_").append(className).append("(BuildRuntime runtime) { super(runtime); }\n\n");

        sb.append("    @Override public String groupId() { return ").append(quote(module.getGroupId())).append("; }\n");
        sb.append("    @Override public String artifactId() { return ").append(quote(module.getArtifactId())).append("; }\n");
        sb.append("    @Override public String version() { return ").append(quote(module.getVersion())).append("; }\n");
        sb.append("    @Override public String packaging() { return ").append(quote(module.getPackaging())).append("; }\n");
        sb.append("    @Override public Path baseDir() { return Path.of(").append(quote(module.getBaseDir().toString())).append("); }\n");
        sb.append("    @Override public boolean hasJavaSources() { return ").append(module.isHasJavaSources()).append("; }\n");
        sb.append("    @Override public boolean needsJandexIndex() { return ").append(module.isNeedsJandexIndex()).append("; }\n\n");

        // resourceDirs
        sb.append("    @Override\n");
        sb.append("    public String[][] resourceDirs() {\n");
        List<ModuleInfo.ResourceDir> rds = module.getResourceDirs();
        if (rds.isEmpty()) {
            sb.append("        return new String[0][];\n");
        } else {
            sb.append("        return new String[][] {\n");
            for (int i = 0; i < rds.size(); i++) {
                ModuleInfo.ResourceDir rd = rds.get(i);
                sb.append("            {").append(quote(rd.directory())).append(", ")
                        .append(quote(String.valueOf(rd.filtering()))).append("}");
                if (i < rds.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("        };\n");
        }
        sb.append("    }\n\n");

        // filterProperties
        Map<String, String> props = module.getFilterProperties();
        if (props.isEmpty()) {
            sb.append("    @Override\n");
            sb.append("    public Map<String, String> filterProperties() { return Map.of(); }\n\n");
        } else {
            sb.append("    private static final String FILTER_PROPS = \"\"\"\n");
            for (Map.Entry<String, String> entry : props.entrySet()) {
                sb.append("            ").append(escapeTextBlock(entry.getKey()))
                        .append("=").append(escapeTextBlock(entry.getValue())).append("\n");
            }
            sb.append("            \"\"\";\n\n");
            sb.append("    @Override\n");
            sb.append("    public Map<String, String> filterProperties() { return parseProps(FILTER_PROPS); }\n\n");
        }

        // manifestEntries
        sb.append("    @Override\n");
        sb.append("    public Map<String, String> manifestEntries() {\n");
        Map<String, String> manifest = module.getManifestEntries();
        if (manifest.isEmpty()) {
            sb.append("        return Map.of();\n");
        } else {
            sb.append("        return Map.of(\n");
            List<Map.Entry<String, String>> mentries = List.copyOf(manifest.entrySet());
            for (int i = 0; i < mentries.size(); i++) {
                Map.Entry<String, String> entry = mentries.get(i);
                sb.append("            ").append(quote(entry.getKey())).append(", ")
                        .append(quote(entry.getValue()));
                if (i < mentries.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("        );\n");
        }
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> compileClasspath() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(makePortable(module.getCompileClasspath()), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> annotationProcessorPaths() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(makePortable(module.getAnnotationProcessorPaths()), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> compilerArgs() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getCompilerArgs(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> moduleDependencyIds() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getReactorDependencies(), "            "));
        sb.append("        );\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private String generateMainClass(List<ModuleInfo> modules) {
        StringBuilder sb = new StringBuilder();
        sb.append("import io.quarkiverse.qraven.hardcoded.runtime.*;\n");
        sb.append("import java.util.*;\n\n");

        sb.append("public class Build {\n");
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        java.nio.file.Path projectRoot = java.nio.file.Path.of(\".\").toAbsolutePath().normalize();\n");
        sb.append("        int threads = Runtime.getRuntime().availableProcessors();\n\n");

        sb.append("        for (int i = 0; i < args.length; i++) {\n");
        sb.append("            if ((\"--threads\".equals(args[i]) || \"-t\".equals(args[i])) && i + 1 < args.length) {\n");
        sb.append("                threads = Integer.parseInt(args[++i]);\n");
        sb.append("            } else if ((\"--project\".equals(args[i]) || \"-p\".equals(args[i])) && i + 1 < args.length) {\n");
        sb.append("                projectRoot = java.nio.file.Path.of(args[++i]).toAbsolutePath().normalize();\n");
        sb.append("            }\n");
        sb.append("        }\n\n");

        sb.append("        BuildRuntime runtime = new BuildRuntime(projectRoot);\n");
        sb.append("        List<ModuleBuild> modules = new ArrayList<>();\n");

        for (ModuleInfo module : modules) {
            String className = sanitizeClassName(module.getArtifactId());
            sb.append("        modules.add(new Build_").append(className).append("(runtime));\n");
        }

        sb.append("\n        new BuildOrchestrator(threads).buildAll(modules);\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    public void compileAndPackage() throws IOException {
        Path srcDir = outputDir.resolve("src");
        Path classesDir = outputDir.resolve("classes");
        Path buildJar = outputDir.resolve("build.jar");
        Files.createDirectories(classesDir);

        Path runtimeJar = findRuntimeJar();

        String jandexJar = findJandexJar(runtimeJar);

        List<Path> sourceFiles;
        try (var stream = Files.walk(srcDir)) {
            sourceFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("No Java compiler available");
        }

        String classpath = runtimeJar.toString();
        if (jandexJar != null) {
            classpath += File.pathSeparator + jandexJar;
        }

        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(sourceFiles);

            List<String> options = List.of(
                    "-d", classesDir.toString(),
                    "-classpath", classpath,
                    "--release", "17"
            );

            var task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
            if (!task.call()) {
                StringBuilder sb = new StringBuilder("Compilation of generated sources failed:\n");
                diagnostics.getDiagnostics().forEach(d -> sb.append("  ").append(d).append("\n"));
                throw new RuntimeException(sb.toString());
            }
        }

        createBuildJar(classesDir, runtimeJar, jandexJar, buildJar);
    }

    private String findJandexJar(Path runtimeJar) {
        if (!Files.isDirectory(runtimeJar)) {
            return null;
        }
        try {
            URI jandexLocation = org.jboss.jandex.Indexer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path jandexPath = Path.of(jandexLocation);
            if (Files.exists(jandexPath) && !Files.isDirectory(jandexPath)) {
                return jandexPath.toString();
            }
        } catch (Exception e) {
            // fall through to manual search
        }
        Path jandex = Path.of(System.getProperty("user.home"), ".m2", "repository",
                "io", "smallrye", "jandex");
        if (Files.isDirectory(jandex)) {
            try (var versions = Files.list(jandex)) {
                return versions.filter(Files::isDirectory)
                        .findFirst()
                        .map(v -> {
                            String ver = v.getFileName().toString();
                            Path jar = v.resolve("jandex-" + ver + ".jar");
                            return Files.exists(jar) ? jar.toString() : null;
                        })
                        .orElse(null);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private void createBuildJar(Path classesDir, Path runtimeJar, String jandexJar, Path buildJar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Build");

        try (OutputStream fos = Files.newOutputStream(buildJar);
             JarOutputStream jos = new JarOutputStream(fos, manifest)) {

            // Add generated compiled classes
            addDirectoryToJar(classesDir, classesDir, jos);

            // Add runtime classes from our JAR
            addRuntimeClassesToJar(runtimeJar, jos);

            // Add jandex classes if not already included
            if (jandexJar != null) {
                addJarClassesToJar(Path.of(jandexJar), jos);
            }
        }
    }

    private void addJarClassesToJar(Path jarPath, JarOutputStream jos) throws IOException {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jarPath.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !entry.isDirectory()) {
                    try {
                        jos.putNextEntry(new JarEntry(name));
                        try (var is = jf.getInputStream(entry)) {
                            is.transferTo(jos);
                        }
                        jos.closeEntry();
                    } catch (java.util.zip.ZipException e) {
                        // duplicate entry — skip
                    }
                }
            }
        }
    }

    private void addDirectoryToJar(Path baseDir, Path root, JarOutputStream jos) throws IOException {
        Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String entryName = root.relativize(file).toString().replace('\\', '/');
                if (entryName.equals("META-INF/MANIFEST.MF")) {
                    return FileVisitResult.CONTINUE;
                }
                jos.putNextEntry(new JarEntry(entryName));
                Files.copy(file, jos);
                jos.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void addRuntimeClassesToJar(Path runtimeJar, JarOutputStream jos) throws IOException {
        if (Files.isDirectory(runtimeJar)) {
            // Running from exploded classes (development mode)
            Path runtimePkg = runtimeJar.resolve("io/quarkiverse/qraven/hardcoded/runtime");
            if (Files.isDirectory(runtimePkg)) {
                Files.walkFileTree(runtimePkg, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String entryName = runtimeJar.relativize(file).toString().replace('\\', '/');
                        jos.putNextEntry(new JarEntry(entryName));
                        Files.copy(file, jos);
                        jos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } else {
            // Running from a JAR - copy all classes (includes shaded Jandex)
            URI jarUri = URI.create("jar:" + runtimeJar.toUri());
            try (FileSystem zipFs = FileSystems.newFileSystem(jarUri, java.util.Map.of())) {
                Path root = zipFs.getPath("/");
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String entryName = file.toString();
                        if (entryName.startsWith("/")) {
                            entryName = entryName.substring(1);
                        }
                        if (!entryName.endsWith(".class")) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (entryName.startsWith("META-INF/")) {
                            return FileVisitResult.CONTINUE;
                        }
                        try {
                            jos.putNextEntry(new JarEntry(entryName));
                            Files.copy(file, jos);
                            jos.closeEntry();
                        } catch (java.util.zip.ZipException e) {
                            // duplicate entry - skip
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    private Path findRuntimeJar() {
        try {
            URI location = BuildRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(location);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Cannot determine runtime JAR location", e);
        }
    }

    private String formatStringList(List<String> items, String indent) {
        if (items.isEmpty()) {
            return "";
        }
        return items.stream()
                .map(s -> indent + quote(s))
                .collect(Collectors.joining(",\n", "", "\n"));
    }

    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private String escapeTextBlock(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "");
    }

    private List<String> makePortable(List<String> paths) {
        String home = System.getProperty("user.home");
        return paths.stream()
                .map(p -> p.startsWith(home + "/") ? "$HOME" + p.substring(home.length()) : p)
                .toList();
    }

    private String sanitizeClassName(String artifactId) {
        return artifactId.replace('-', '_').replace('.', '_');
    }
}
