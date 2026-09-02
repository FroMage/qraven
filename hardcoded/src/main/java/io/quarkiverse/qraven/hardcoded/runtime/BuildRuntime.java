package io.quarkiverse.qraven.hardcoded.runtime;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class BuildRuntime {

    private final Path projectRoot;
    private final JavaCompiler compiler;
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

    public void compile(Path sourceDir, Path outputDir, List<String> classpath,
                        List<String> annotationProcessorPaths, List<String> compilerArgs) {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }

        List<Path> sourceFiles;
        try (var stream = Files.walk(sourceDir)) {
            sourceFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sources in " + sourceDir, e);
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

        List<String> fullClasspath = new ArrayList<>(classpath);
        if (!annotationProcessorPaths.isEmpty()) {
            for (String ap : annotationProcessorPaths) {
                if (!fullClasspath.contains(ap)) {
                    fullClasspath.add(ap);
                }
            }
        }

        if (!fullClasspath.isEmpty()) {
            options.add("-classpath");
            options.add(String.join(File.pathSeparator, fullClasspath));
        }

        options.addAll(compilerArgs);

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

    public void createJar(Path classesDir, Path jarFile) {
        if (!Files.isDirectory(classesDir)) {
            return;
        }

        try {
            Files.createDirectories(jarFile.getParent());
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

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
