package io.quarkiverse.qraven.hardcoded.runtime;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.StandardLocation;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.jandex.Index;
import org.jboss.jandex.IndexWriter;
import org.jboss.jandex.Indexer;

public class BuildRuntime {

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "ico", "tiff", "tif", "wbmp", "jp2", "webp",
            "ttf", "otf", "eot", "woff", "woff2",
            "pdf", "zip", "gz", "tar", "jar", "war", "ear", "rar", "7z",
            "class", "so", "dll", "dylib", "o", "a",
            "ser", "idx", "jks", "p12", "pfx", "keystore", "truststore",
            "db", "sqlite", "bin", "dat",
            "mp3", "mp4", "wav", "ogg", "flac", "avi", "mkv", "mov",
            "psd", "ai", "eps", "raw", "cr2", "nef",
            "exe", "msi", "dmg", "deb", "rpm"
    );

    private final Path projectRoot;
    private final JavaCompiler compiler;
    private final boolean jrtUnavailable;
    private final List<File> platformClasspath;
    private final ConcurrentHashMap<Long, StandardJavaFileManager> fileManagers = new ConcurrentHashMap<>();

    public BuildRuntime(Path projectRoot) {
        this.projectRoot = projectRoot;
        if (System.getProperty("java.home") == null) {
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null) {
                System.setProperty("java.home", javaHome);
            }
        }
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (this.compiler == null) {
            throw new RuntimeException("No Java compiler available - ensure you are running on a JDK (set JAVA_HOME)");
        }
        this.jrtUnavailable = !isJrtAvailable();
        if (jrtUnavailable) {
            this.platformClasspath = buildPlatformClasspath();
        } else {
            this.platformClasspath = List.of();
        }
    }

    private static boolean isJrtAvailable() {
        try {
            java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<File> buildPlatformClasspath() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null) return List.of();
        Path jmodsDir = Path.of(javaHome, "jmods");
        if (!Files.isDirectory(jmodsDir)) return List.of();

        long start = System.currentTimeMillis();
        try {
            Path moduleJarsDir = Files.createTempDirectory("platform-modules");
            moduleJarsDir.toFile().deleteOnExit();
            int count = 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(jmodsDir, "*.jmod")) {
                for (Path jmod : ds) {
                    String modName = jmod.getFileName().toString();
                    modName = modName.substring(0, modName.length() - ".jmod".length());
                    Path moduleJar = moduleJarsDir.resolve(modName + ".jar");
                    moduleJar.toFile().deleteOnExit();
                    try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jmod.toFile());
                         OutputStream fos = Files.newOutputStream(moduleJar);
                         JarOutputStream jos = new JarOutputStream(fos)) {
                        var entries = zf.entries();
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.startsWith("classes/") && !entry.isDirectory()) {
                                String classEntry = name.substring("classes/".length());
                                jos.putNextEntry(new JarEntry(classEntry));
                                try (InputStream is = zf.getInputStream(entry)) {
                                    is.transferTo(jos);
                                }
                                jos.closeEntry();
                            }
                        }
                    }
                    count++;
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("Platform modules extracted in " + elapsed + "ms (" + count + " modules)");
            return List.of(moduleJarsDir.toFile());
        } catch (IOException e) {
            System.err.println("Warning: failed to extract platform classes: " + e.getMessage());
            return List.of();
        }
    }

    public void warmupClasspath(Set<String> allJars) {
        long start = System.currentTimeMillis();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        try {
            List<File> jarFiles = allJars.stream()
                    .map(File::new)
                    .filter(File::exists)
                    .toList();
            fm.setLocation(StandardLocation.CLASS_PATH, jarFiles);
            fm.list(StandardLocation.CLASS_PATH, "", java.util.EnumSet.of(JavaFileObject.Kind.CLASS), false);
        } catch (IOException e) {
            // warmup is best-effort
        } finally {
            try { fm.close(); } catch (IOException e) { /* ignore */ }
        }
        long classpathTime = System.currentTimeMillis() - start;

        long ctSymStart = System.currentTimeMillis();
        Path ctSymFile = Path.of(System.getProperty("java.home"), "lib", "ct.sym");
        if (Files.exists(ctSymFile)) {
            try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(ctSymFile, (ClassLoader) null);
                 DirectoryStream<Path> dir =
                         Files.newDirectoryStream(fs.getRootDirectories().iterator().next())) {
                for (Path section : dir) {
                    try (DirectoryStream<Path> modules = Files.newDirectoryStream(section)) {
                        for (Path module : modules) {
                            // walk entries to prime page cache
                        }
                    } catch (IOException e) {
                        // some entries aren't directories
                    }
                }
            } catch (IOException e) {
                // ct.sym warmup is best-effort
            }
        }
        long ctSymTime = System.currentTimeMillis() - ctSymStart;

        System.out.println("Classpath warmup: " + classpathTime + "ms (" + allJars.size() + " jars), ct.sym: " + ctSymTime + "ms");
    }

    public void close() {
        for (StandardJavaFileManager fm : fileManagers.values()) {
            try { fm.close(); } catch (IOException e) { /* ignore */ }
        }
        fileManagers.clear();
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public void clean(Path targetDir) {
        if (!Files.exists(targetDir)) {
            return;
        }
        try {
            Files.walkFileTree(targetDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to clean " + targetDir, e);
        }
    }

    public void copyResources(Path resourceDir, Path outputDir) {
        if (!Files.isDirectory(resourceDir)) {
            return;
        }
        try {
            Files.walkFileTree(resourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path target = outputDir.resolve(resourceDir.relativize(dir));
                    Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path target = outputDir.resolve(resourceDir.relativize(file));
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy resources from " + resourceDir, e);
        }
    }

    public void copyResourcesFiltered(Path resourceDir, Path outputDir, Map<String, String> properties) {
        if (!Files.isDirectory(resourceDir)) {
            return;
        }
        try {
            Files.walkFileTree(resourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(outputDir.resolve(resourceDir.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path target = outputDir.resolve(resourceDir.relativize(file));
                    if (isBinaryFile(file)) {
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        try {
                            String content = Files.readString(file);
                            String filtered = interpolateProperties(content, properties);
                            Files.writeString(target, filtered);
                        } catch (java.nio.charset.MalformedInputException e) {
                            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy filtered resources from " + resourceDir, e);
        }
    }

    private boolean isBinaryFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private String interpolateProperties(String content, Map<String, String> properties) {
        if (content.indexOf("${") < 0) return content;
        StringBuilder sb = new StringBuilder(content.length());
        int i = 0;
        while (i < content.length()) {
            if (i < content.length() - 2 && content.charAt(i) == '$' && content.charAt(i + 1) == '{') {
                int end = content.indexOf('}', i + 2);
                if (end > 0) {
                    String key = content.substring(i + 2, end);
                    String value = properties.get(key);
                    if (value != null) {
                        sb.append(value);
                    } else {
                        sb.append("${").append(key).append('}');
                    }
                    i = end + 1;
                    continue;
                }
            }
            sb.append(content.charAt(i));
            i++;
        }
        return sb.toString();
    }

    public void compile(Path sourceDir, Path outputDir, List<String> classpath,
                        List<String> annotationProcessorPaths, List<String> compilerArgs,
                        Path... extraSourceDirs) {
        List<Path> sourceFiles = new ArrayList<>();
        if (Files.isDirectory(sourceDir)) {
            try (var stream = Files.walk(sourceDir)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                        .forEach(sourceFiles::add);
            } catch (IOException e) {
                throw new RuntimeException("Failed to list sources in " + sourceDir, e);
            }
        }
        for (Path extra : extraSourceDirs) {
            if (Files.isDirectory(extra)) {
                try (var stream = Files.walk(extra)) {
                    stream.filter(p -> p.toString().endsWith(".java"))
                            .forEach(sourceFiles::add);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to list sources in " + extra, e);
                }
            }
        }

        if (sourceFiles.isEmpty()) {
            return;
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output dir " + outputDir, e);
        }

        StandardJavaFileManager fileManager = fileManagers.computeIfAbsent(
                Thread.currentThread().getId(),
                k -> compiler.getStandardFileManager(null, null, null));

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Iterable<? extends JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjectsFromPaths(sourceFiles);

        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(outputDir.toString());

        if (!classpath.isEmpty()) {
            options.add("-classpath");
            options.add(String.join(File.pathSeparator, classpath));
        }

        if (!annotationProcessorPaths.isEmpty()) {
            options.add("-processorpath");
            options.add(String.join(File.pathSeparator, annotationProcessorPaths));
        } else {
            options.add("-proc:none");
        }

        for (int i = 0; i < compilerArgs.size(); i++) {
            if ("--release".equals(compilerArgs.get(i)) && i + 1 < compilerArgs.size()) {
                String ver = compilerArgs.get(++i);
                if (jrtUnavailable) {
                    options.add("-source");
                    options.add(ver);
                    options.add("-target");
                    options.add(ver);
                } else {
                    options.add("--release");
                    options.add(ver);
                }
            } else {
                options.add(compilerArgs.get(i));
            }
        }

        if (jrtUnavailable && !platformClasspath.isEmpty()) {
            options.add("--system");
            options.add("none");
            options.add("--module-path");
            options.add(platformClasspath.get(0).getAbsolutePath());
            options.add("--add-modules");
            options.add("ALL-MODULE-PATH");
        }

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, compilationUnits);

        boolean success = task.call();
        if (!success) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                if (diag.getKind() == Diagnostic.Kind.ERROR) {
                    sb.append("  ").append(diag).append("\n");
                }
            }
            throw new RuntimeException(sb.toString());
        }
    }

    private volatile org.jetbrains.kotlin.cli.jvm.K2JVMCompiler kotlinCompiler;

    public void warmupKotlin() {
        long start = System.currentTimeMillis();
        kotlinCompiler = new org.jetbrains.kotlin.cli.jvm.K2JVMCompiler();
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Kotlin compiler warmup: " + elapsed + "ms");
    }

    public void compileKotlin(Path kotlinSourceDir, Path javaSourceDir, Path outputDir, List<String> classpath) {
        if (!Files.isDirectory(kotlinSourceDir)) return;

        List<Path> kotlinFiles;
        try (var stream = Files.walk(kotlinSourceDir)) {
            kotlinFiles = stream.filter(p -> p.toString().endsWith(".kt")).toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list Kotlin sources in " + kotlinSourceDir, e);
        }
        if (kotlinFiles.isEmpty()) return;

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output dir " + outputDir, e);
        }

        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler compiler =
                kotlinCompiler != null ? kotlinCompiler : new org.jetbrains.kotlin.cli.jvm.K2JVMCompiler();

        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(outputDir.toString());
        args.add("-no-stdlib");
        args.add("-jvm-target");
        args.add("21");

        String cp = String.join(File.pathSeparator, classpath);
        if (!cp.isEmpty()) {
            args.add("-classpath");
            args.add(cp);
        }

        List<String> javaRoots = new ArrayList<>();
        if (Files.isDirectory(javaSourceDir)) {
            javaRoots.add(javaSourceDir.toString());
        }
        javaRoots.add(kotlinSourceDir.toString());
        args.add("-Xjava-source-roots=" + String.join(File.pathSeparator, javaRoots));

        for (Path ktFile : kotlinFiles) {
            args.add(ktFile.toString());
        }

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(errBuf);
        org.jetbrains.kotlin.cli.common.ExitCode exitCode =
                compiler.exec(errStream, args.toArray(new String[0]));
        if (exitCode != org.jetbrains.kotlin.cli.common.ExitCode.OK) {
            throw new RuntimeException("Kotlin compilation failed:\n" + errBuf);
        }
    }

    private String protocPath;
    private String grpcJavaPluginPath;
    private volatile Path mutinyPluginScript;

    public void setProtocPath(String path) { this.protocPath = path; }
    public void setGrpcJavaPluginPath(String path) { this.grpcJavaPluginPath = path; }

    public void compileProtobuf(Path protoSourceDir, Path outputDir,
                                boolean useGrpc, boolean useMutiny, List<String> classpath) {
        if (protocPath == null) {
            throw new RuntimeException("protoc binary path not set — cannot compile .proto files");
        }

        List<Path> protoFiles;
        try (var stream = Files.walk(protoSourceDir)) {
            protoFiles = stream.filter(p -> p.toString().endsWith(".proto")).toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list .proto files in " + protoSourceDir, e);
        }
        if (protoFiles.isEmpty()) return;

        Path javaOut = outputDir.resolve("java");
        try {
            Files.createDirectories(javaOut);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create protobuf output dir " + javaOut, e);
        }

        new File(protocPath).setExecutable(true);

        List<String> cmd = new ArrayList<>();
        cmd.add(protocPath);
        cmd.add("--java_out=" + javaOut);
        cmd.add("-I" + protoSourceDir);

        if (useGrpc && grpcJavaPluginPath != null) {
            new File(grpcJavaPluginPath).setExecutable(true);
            Path grpcOut = outputDir.resolve("grpc-java");
            try { Files.createDirectories(grpcOut); } catch (IOException e) {
                throw new RuntimeException("Failed to create grpc output dir", e);
            }
            cmd.add("--plugin=protoc-gen-grpc-java=" + grpcJavaPluginPath);
            cmd.add("--grpc-java_out=" + grpcOut);
        }

        if (useMutiny) {
            Path mutinyOut = outputDir.resolve("quarkus-grpc");
            try { Files.createDirectories(mutinyOut); } catch (IOException e) {
                throw new RuntimeException("Failed to create mutiny output dir", e);
            }
            Path script = getOrCreateMutinyPluginScript(classpath);
            if (script != null) {
                cmd.add("--plugin=protoc-gen-quarkus-grpc=" + script);
                cmd.add("--quarkus-grpc_out=" + mutinyOut);
            }
        }

        for (Path protoFile : protoFiles) {
            cmd.add(protoFile.toString());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("protoc failed (exit " + exitCode + "):\n" + output);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to run protoc", e);
        }
    }

    private Path getOrCreateMutinyPluginScript(List<String> classpath) {
        if (mutinyPluginScript != null) return mutinyPluginScript;
        synchronized (this) {
            if (mutinyPluginScript != null) return mutinyPluginScript;

            Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
            Path pluginBase = m2.resolve("io/quarkus/quarkus-grpc-protoc-plugin");
            if (!Files.isDirectory(pluginBase)) return null;

            try (var versions = Files.list(pluginBase)) {
                Path versionDir = versions.filter(Files::isDirectory).findFirst().orElse(null);
                if (versionDir == null) return null;
                String ver = versionDir.getFileName().toString();
                Path shadedJar = versionDir.resolve("quarkus-grpc-protoc-plugin-" + ver + "-shaded.jar");
                Path pluginJar = Files.exists(shadedJar) ? shadedJar
                        : versionDir.resolve("quarkus-grpc-protoc-plugin-" + ver + ".jar");
                if (!Files.exists(pluginJar)) return null;

                List<String> pluginCp = new ArrayList<>();
                pluginCp.add(pluginJar.toString());

                if (!pluginJar.equals(shadedJar)) {
                    Path jprotocBase = m2.resolve("com/salesforce/servicelibs/jprotoc");
                    if (Files.isDirectory(jprotocBase)) {
                        try (var jVers = Files.list(jprotocBase)) {
                            jVers.filter(Files::isDirectory).findFirst().ifPresent(jv -> {
                                String jver = jv.getFileName().toString();
                                Path jjar = jv.resolve("jprotoc-" + jver + ".jar");
                                if (Files.exists(jjar)) pluginCp.add(jjar.toString());
                            });
                        }
                    }

                    for (String cp : classpath) {
                        if (cp.contains("protobuf-java") && !cp.contains("util")) {
                            pluginCp.add(cp);
                        }
                        if (cp.contains("smallrye-common-annotation")) {
                            pluginCp.add(cp);
                        }
                        if (cp.contains("guava")) {
                            pluginCp.add(cp);
                        }
                    }
                }

                Path script = Files.createTempFile("protoc-gen-quarkus-grpc", ".sh");
                script.toFile().deleteOnExit();
                Files.writeString(script,
                        "#!/bin/sh\nexec java -cp " + String.join(":", pluginCp)
                                + " io.quarkus.grpc.protoc.plugin.MutinyGrpcGenerator\n");
                script.toFile().setExecutable(true);
                mutinyPluginScript = script;
                return script;
            } catch (IOException e) {
                return null;
            }
        }
    }

    public void generateJandexIndex(Path classesDir) {
        if (!Files.isDirectory(classesDir)) return;
        try {
            Indexer indexer = new Indexer();
            try (var stream = Files.walk(classesDir)) {
                List<Path> classFiles = stream
                        .filter(p -> p.toString().endsWith(".class"))
                        .toList();
                for (Path classFile : classFiles) {
                    try (InputStream is = Files.newInputStream(classFile)) {
                        indexer.index(is);
                    }
                }
            }
            Index index = indexer.complete();
            Path indexFile = classesDir.resolve("META-INF").resolve("jandex.idx");
            Files.createDirectories(indexFile.getParent());
            try (OutputStream os = Files.newOutputStream(indexFile)) {
                new IndexWriter(os).write(index);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Jandex index in " + classesDir, e);
        }
    }

    public void createJar(Path classesDir, Path jarFile) {
        createJar(classesDir, jarFile, Map.of());
    }

    public void createJar(Path classesDir, Path jarFile, Map<String, String> manifestEntries) {
        if (!Files.isDirectory(classesDir)) {
            return;
        }

        try {
            Files.createDirectories(jarFile.getParent());
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            for (Map.Entry<String, String> entry : manifestEntries.entrySet()) {
                manifest.getMainAttributes().putValue(entry.getKey(), entry.getValue());
            }

            try (OutputStream fos = Files.newOutputStream(jarFile);
                 JarOutputStream jos = new JarOutputStream(fos, manifest)) {
                Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                        jos.putNextEntry(new JarEntry(entryName));
                        Files.copy(file, jos);
                        jos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create JAR " + jarFile, e);
        }
    }

    public void install(Path jarFile, Path pomFile, String groupId, String artifactId,
                        String version, String packaging) {
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Path artifactDir = localRepo
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version);

        try {
            Files.createDirectories(artifactDir);

            Files.copy(pomFile, artifactDir.resolve(artifactId + "-" + version + ".pom"),
                    StandardCopyOption.REPLACE_EXISTING);

            if (jarFile != null && Files.exists(jarFile)) {
                Files.copy(jarFile, artifactDir.resolve(artifactId + "-" + version + ".jar"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to install " + groupId + ":" + artifactId + ":" + version, e);
        }
    }
}
