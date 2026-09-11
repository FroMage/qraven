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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public class BuildFileGenerator {

    private final Path projectRoot;
    private final Path outputDir;
    private final int threads;
    private boolean hasKotlinModules;
    private boolean hasProtobufModules;
    private boolean hasAntlrModules;
    private String protocPath;
    private String grpcJavaPluginPath;
    private String antlrToolClasspath;

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
        Path classesDir = outputDir.resolve("classes");
        deleteDirectory(srcDir);
        deleteDirectory(classesDir);
        Files.createDirectories(srcDir);

        hasKotlinModules = modules.stream().anyMatch(ModuleInfo::isHasKotlinSources);
        hasProtobufModules = modules.stream().anyMatch(ModuleInfo::isHasProtobufSources);
        if (hasProtobufModules) {
            resolveProtocPaths(modules);
        }
        hasAntlrModules = modules.stream().anyMatch(ModuleInfo::isHasAntlrSources);
        if (hasAntlrModules) {
            resolveAntlrToolClasspath(modules);
        }

        int total = modules.size() + 1;
        AtomicInteger progress = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(modules.size());
            for (ModuleInfo module : modules) {
                futures.add(executor.submit(() -> {
                    String className = sanitizeClassName(module.getArtifactId());
                    String source = generateModuleClass(module, className);
                    Path sourceFile = srcDir.resolve("Build_" + className + ".java");
                    try {
                        Files.writeString(sourceFile, source);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    int done = progress.incrementAndGet();
                    if (progressListener != null) {
                        progressListener.update(module.getArtifactId(), done, total);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    if (e.getCause() instanceof IOException ioe) throw ioe;
                    throw new RuntimeException(e);
                }
            }
        } finally {
            executor.shutdown();
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
        sb.append("    @Override public boolean hasKotlinSources() { return ").append(module.isHasKotlinSources()).append("; }\n");
        sb.append("    @Override public boolean hasProtobufSources() { return ").append(module.isHasProtobufSources()).append("; }\n");
        sb.append("    @Override public boolean protobufUsesGrpc() { return ").append(module.isProtobufUsesGrpc()).append("; }\n");
        sb.append("    @Override public boolean protobufUsesMutiny() { return ").append(module.isProtobufUsesMutiny()).append("; }\n");
        sb.append("    @Override public boolean hasAntlrSources() { return ").append(module.isHasAntlrSources()).append("; }\n");
        sb.append("    @Override public boolean antlrVisitor() { return ").append(module.isAntlrVisitor()).append("; }\n");
        sb.append("    @Override public boolean needsJandexIndex() { return ").append(module.isNeedsJandexIndex()).append("; }\n");
        sb.append("    @Override public boolean hasExtensionPlugin() { return ").append(module.isHasExtensionPlugin()).append("; }\n");
        sb.append("    @Override public String extensionValidationSkipWhen() { return ")
                .append(quoteOrNull(module.getExtensionValidationSkipWhen())).append("; }\n\n");

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
                        .append(quote(String.valueOf(rd.filtering()))).append(", ")
                        .append(quoteOrNull(rd.targetPath())).append("}");
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
        List<String> nonOptionalCp = new ArrayList<>(module.getCompileClasspath());
        nonOptionalCp.removeAll(module.getOptionalClasspathEntries());
        sb.append(formatStringList(makePortable(nonOptionalCp), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> optionalCompileClasspath() {\n");
        sb.append("        return List.of(\n");
        List<String> optionalCp = new ArrayList<>(module.getOptionalClasspathEntries());
        sb.append(formatStringList(makePortable(optionalCp), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> annotationProcessorPaths() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(makePortable(module.getAnnotationProcessorPaths()), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override public boolean isApCacheable() { return ")
                .append(module.isApCacheable()).append("; }\n\n");

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
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> optionalModuleDependencyIds() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(new java.util.ArrayList<>(module.getOptionalReactorDependencies()), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        // extensionDescriptorProperties
        Map<String, String> extProps = module.getExtensionDescriptorProperties();
        sb.append("    @Override\n");
        sb.append("    public Map<String, String> extensionDescriptorProperties() {\n");
        if (extProps.isEmpty()) {
            sb.append("        return Map.of();\n");
        } else {
            sb.append("        return Map.of(\n");
            List<Map.Entry<String, String>> extEntries = List.copyOf(extProps.entrySet());
            for (int i = 0; i < extEntries.size(); i++) {
                Map.Entry<String, String> entry = extEntries.get(i);
                sb.append("            ").append(quote(entry.getKey())).append(", ")
                        .append(quote(entry.getValue()));
                if (i < extEntries.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("        );\n");
        }
        sb.append("    }\n\n");

        // extension metadata methods
        sb.append("    @Override public String extensionProjectName() { return ")
                .append(quoteOrNull(module.getExtensionProjectName())).append("; }\n");
        sb.append("    @Override public String extensionProjectDescription() { return ")
                .append(quoteOrNull(module.getExtensionProjectDescription())).append("; }\n");
        sb.append("    @Override public String extensionScmUrl() { return ")
                .append(quoteOrNull(module.getExtensionScmUrl())).append("; }\n");
        sb.append("    @Override public String extensionMinimumJavaVersion() { return ")
                .append(quoteOrNull(module.getExtensionMinimumJavaVersion())).append("; }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> extensionModelDeps() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getExtensionModelDeps(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> extensionReactorGAs() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getExtensionReactorGAs(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> extensionParentFirstArtifacts() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getExtensionParentFirstArtifacts(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> extensionRunnerParentFirstArtifacts() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getExtensionRunnerParentFirstArtifacts(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> extensionExcludedArtifacts() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getExtensionExcludedArtifacts(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> extensionLesserPriorityArtifacts() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getExtensionLesserPriorityArtifacts(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> extensionProvidesCapabilities() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getExtensionProvidesCapabilities(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public List<String> extensionRequiresCapabilities() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getExtensionRequiresCapabilities(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        // quarkus build plugin
        sb.append("    @Override public boolean hasQuarkusBuildPlugin() { return ")
                .append(module.isHasQuarkusBuildPlugin()).append("; }\n");
        sb.append("    @Override public String quarkusBuildSkipWhen() { return ")
                .append(quoteOrNull(module.getQuarkusBuildSkipWhen())).append("; }\n");
        sb.append("    @Override public boolean hasGenerateCodeGoal() { return ")
                .append(module.isHasGenerateCodeGoal()).append("; }\n");
        sb.append("    @Override public boolean hasCodeGenProviders() { return ")
                .append(module.isHasCodeGenProviders()).append("; }\n");
        sb.append("    @Override public String generateCodeSkipWhen() { return ")
                .append(quoteOrNull(module.getGenerateCodeSkipWhen())).append("; }\n\n");

        // quarkusBuildProperties
        Map<String, String> qbProps = module.getQuarkusBuildProperties();
        if (qbProps.isEmpty()) {
            sb.append("    @Override\n");
            sb.append("    public Map<String, String> quarkusBuildProperties() { return Map.of(); }\n\n");
        } else {
            sb.append("    private static final String QUARKUS_BUILD_PROPS = \"\"\"\n");
            for (Map.Entry<String, String> entry : qbProps.entrySet()) {
                sb.append("            ").append(escapeTextBlock(entry.getKey()))
                        .append("=").append(escapeTextBlock(entry.getValue())).append("\n");
            }
            sb.append("            \"\"\";\n\n");
            sb.append("    @Override\n");
            sb.append("    public Map<String, String> quarkusBuildProperties() { return parseProps(QUARKUS_BUILD_PROPS); }\n\n");
        }

        // deploymentClasspath
        sb.append("    @Override\n");
        sb.append("    public List<String> deploymentClasspath() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(makePortable(module.getDeploymentClasspath()), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        // runtimeExtensionArtifacts
        sb.append("    @Override\n");
        sb.append("    public List<String> runtimeExtensionArtifacts() {\n");
        sb.append("        return List.of(\n");
        sb.append(formatStringList(module.getRuntimeExtensionArtifacts(), "            "));
        sb.append("        );\n");
        sb.append("    }\n\n");

        // extensionDevProperties
        Map<String, String> edProps = module.getExtensionDevProperties();
        if (edProps.isEmpty()) {
            sb.append("    @Override\n");
            sb.append("    public Map<String, String> extensionDevProperties() { return Map.of(); }\n");
        } else {
            sb.append("    private static final String EXT_DEV_PROPS = \"\"\"\n");
            for (Map.Entry<String, String> entry : edProps.entrySet()) {
                sb.append("            ").append(escapeTextBlock(entry.getKey()))
                        .append("=").append(escapeTextBlock(entry.getValue().replace("\n", "\\n"))).append("\n");
            }
            sb.append("            \"\"\";\n\n");
            sb.append("    @Override\n");
            sb.append("    public Map<String, String> extensionDevProperties() { return parseProps(EXT_DEV_PROPS); }\n");
        }

        sb.append("    @Override public String protocVersion() { return ")
                .append(quoteOrNull(module.getProtocVersion())).append("; }\n");
        sb.append("    @Override public String grpcVersion() { return ")
                .append(quoteOrNull(module.getGrpcVersion())).append("; }\n");
        sb.append("    @Override public String quarkusGrpcVersion() { return ")
                .append(quoteOrNull(module.getQuarkusGrpcVersion())).append("; }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private String quoteOrNull(String s) {
        return s != null ? quote(s) : "null";
    }

    private String generateMainClass(List<ModuleInfo> modules) {
        StringBuilder sb = new StringBuilder();
        sb.append("import io.quarkiverse.qraven.hardcoded.runtime.*;\n");
        sb.append("import java.util.*;\n\n");

        sb.append("public class Build {\n");
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        java.nio.file.Path projectRoot = java.nio.file.Path.of(\".\").toAbsolutePath().normalize();\n");
        sb.append("        int threads = Runtime.getRuntime().availableProcessors();\n");
        sb.append("        String projects = null;\n");
        sb.append("        boolean alsoMake = false;\n\n");

        sb.append("        for (int i = 0; i < args.length; i++) {\n");
        sb.append("            if (\"--help\".equals(args[i]) || \"-h\".equals(args[i])) {\n");
        sb.append("                System.out.println(\"Usage: java -jar build.jar [options]\");\n");
        sb.append("                System.out.println();\n");
        sb.append("                System.out.println(\"Options:\");\n");
        sb.append("                System.out.println(\"  -h, --help                Show this help message and exit\");\n");
        sb.append("                System.out.println(\"  -p, --project <path>      Project root directory (default: current directory)\");\n");
        sb.append("                System.out.println(\"  -t, --threads <n>         Thread count for parallel compilation (default: available CPUs)\");\n");
        sb.append("                System.out.println(\"  -pl, --projects <list>    Comma-separated list of module artifactIds to build\");\n");
        sb.append("                System.out.println(\"  -am, --also-make          Build dependencies of modules specified by -pl\");\n");
        sb.append("                System.out.println(\"  -D<key>=<value>           Set a system property\");\n");
        sb.append("                return;\n");
        sb.append("            } else if (args[i].startsWith(\"-D\")) {\n");
        sb.append("                String prop = args[i].substring(2);\n");
        sb.append("                int eq = prop.indexOf('=');\n");
        sb.append("                if (eq >= 0) {\n");
        sb.append("                    System.setProperty(prop.substring(0, eq), prop.substring(eq + 1));\n");
        sb.append("                } else {\n");
        sb.append("                    System.setProperty(prop, \"true\");\n");
        sb.append("                }\n");
        sb.append("            } else if ((\"--threads\".equals(args[i]) || \"-t\".equals(args[i])) && i + 1 < args.length) {\n");
        sb.append("                threads = Integer.parseInt(args[++i]);\n");
        sb.append("            } else if ((\"--project\".equals(args[i]) || \"-p\".equals(args[i])) && i + 1 < args.length) {\n");
        sb.append("                projectRoot = java.nio.file.Path.of(args[++i]).toAbsolutePath().normalize();\n");
        sb.append("            } else if ((\"--projects\".equals(args[i]) || \"-pl\".equals(args[i])) && i + 1 < args.length) {\n");
        sb.append("                projects = args[++i];\n");
        sb.append("            } else if (\"--also-make\".equals(args[i]) || \"-am\".equals(args[i])) {\n");
        sb.append("                alsoMake = true;\n");
        sb.append("            } else {\n");
        sb.append("                System.err.println(\"Unknown option: \" + args[i]);\n");
        sb.append("                System.err.println(\"Run with --help for usage information.\");\n");
        sb.append("                System.exit(1);\n");
        sb.append("            }\n");
        sb.append("        }\n\n");

        sb.append("        BuildRuntime runtime = new BuildRuntime(projectRoot);\n");
        if (protocPath != null) {
            sb.append("        runtime.setProtocPath(").append(quote(protocPath)).append(");\n");
        }
        if (grpcJavaPluginPath != null) {
            sb.append("        runtime.setGrpcJavaPluginPath(").append(quote(grpcJavaPluginPath)).append(");\n");
        }
        if (antlrToolClasspath != null) {
            sb.append("        runtime.setAntlrToolClasspath(").append(quote(antlrToolClasspath)).append(");\n");
        }
        sb.append("        List<ModuleBuild> modules = new ArrayList<>();\n");

        for (ModuleInfo module : modules) {
            String className = sanitizeClassName(module.getArtifactId());
            sb.append("        modules.add(new Build_").append(className).append("(runtime));\n");
        }

        sb.append("\n        new BuildOrchestrator(threads).buildAll(modules, projects, alsoMake);\n");
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

        List<Path> kotlinJars = hasKotlinModules ? findKotlinCompilerJars() : List.of();
        createBuildJar(classesDir, runtimeJar, jandexJar, kotlinJars, buildJar);
    }

    private List<Path> findKotlinCompilerJars() {
        java.util.Set<Path> seen = new java.util.LinkedHashSet<>();

        String[] classNames = {
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "kotlin.jvm.functions.Function0",
                "kotlinx.coroutines.CoroutineScope",
                "org.jetbrains.annotations.NotNull",
        };
        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className);
                URI location = cls.getProtectionDomain().getCodeSource().getLocation().toURI();
                Path jar = Path.of(location);
                if (Files.exists(jar) && !Files.isDirectory(jar)) {
                    seen.add(jar);
                }
            } catch (Exception e) {
                // class not on classpath — try fallback below
            }
        }

        if (seen.isEmpty()) {
            Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
            Path kotlinBase = m2.resolve("org/jetbrains/kotlin");
            Path compilerDir = kotlinBase.resolve("kotlin-compiler");
            String version = findFirstVersionDir(compilerDir, "kotlin-compiler");
            if (version != null) {
                String[] artifacts = {
                        "kotlin-compiler", "kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8",
                        "kotlin-reflect", "kotlin-script-runtime", "kotlin-build-tools-api"
                };
                for (String artifact : artifacts) {
                    Path jar = kotlinBase.resolve(artifact).resolve(version).resolve(artifact + "-" + version + ".jar");
                    if (Files.exists(jar)) seen.add(jar);
                }
                addFirstVersionJar(seen, m2.resolve("org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm"),
                        "kotlinx-coroutines-core-jvm");
                addFirstVersionJar(seen, m2.resolve("org/jetbrains/annotations"), "annotations");
            }
        }

        return new java.util.ArrayList<>(seen);
    }

    private String findFirstVersionDir(Path artifactDir, String artifactName) {
        if (!Files.isDirectory(artifactDir)) return null;
        try (var versions = Files.list(artifactDir)) {
            return versions.filter(Files::isDirectory)
                    .filter(v -> Files.exists(v.resolve(artifactName + "-" + v.getFileName() + ".jar")))
                    .map(v -> v.getFileName().toString())
                    .max(BuildFileGenerator::compareVersions).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            try {
                int cmp = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
                if (cmp != 0) return cmp;
            } catch (NumberFormatException e) {
                int cmp = sa.compareTo(sb);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private void addFirstVersionJar(java.util.Set<Path> jars, Path artifactDir, String artifactName) {
        String ver = findFirstVersionDir(artifactDir, artifactName);
        if (ver != null) {
            Path jar = artifactDir.resolve(ver).resolve(artifactName + "-" + ver + ".jar");
            if (Files.exists(jar)) jars.add(jar);
        }
    }

    private void resolveProtocPaths(List<ModuleInfo> modules) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "");
        String os;
        if (osName.contains("linux")) os = "linux";
        else if (osName.contains("mac") || osName.contains("darwin")) os = "osx";
        else if (osName.contains("win")) os = "windows";
        else os = osName;
        String arch;
        if ("amd64".equals(osArch) || "x86_64".equals(osArch)) arch = "x86_64";
        else if ("aarch64".equals(osArch)) arch = "aarch_64";
        else arch = osArch;
        String classifier = os + "-" + arch;

        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");

        Path protocBase = m2.resolve("com/google/protobuf/protoc");
        if (Files.isDirectory(protocBase)) {
            try (var versions = Files.list(protocBase)) {
                protocPath = versions.filter(Files::isDirectory)
                        .map(v -> {
                            String ver = v.getFileName().toString();
                            Path exe = v.resolve("protoc-" + ver + "-" + classifier + ".exe");
                            return Files.exists(exe) ? exe.toString() : null;
                        })
                        .filter(p -> p != null)
                        .findFirst().orElse(null);
            } catch (IOException e) {
                // ignore
            }
        }

        boolean needsGrpc = modules.stream().anyMatch(ModuleInfo::isProtobufUsesGrpc);
        if (needsGrpc) {
            Path grpcBase = m2.resolve("io/grpc/protoc-gen-grpc-java");
            if (Files.isDirectory(grpcBase)) {
                try (var versions = Files.list(grpcBase)) {
                    grpcJavaPluginPath = versions.filter(Files::isDirectory)
                            .map(v -> {
                                String ver = v.getFileName().toString();
                                Path exe = v.resolve("protoc-gen-grpc-java-" + ver + "-" + classifier + ".exe");
                                return Files.exists(exe) ? exe.toString() : null;
                            })
                            .filter(p -> p != null)
                            .findFirst().orElse(null);
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        if (protocPath != null) {
            System.out.println("Resolved protoc: " + protocPath);
        } else {
            System.err.println("WARNING: protoc binary not found in ~/.m2/repository");
        }
        if (needsGrpc && grpcJavaPluginPath != null) {
            System.out.println("Resolved protoc-gen-grpc-java: " + grpcJavaPluginPath);
        }
    }

    private void resolveAntlrToolClasspath(List<ModuleInfo> modules) {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        String antlrVersion = null;
        for (ModuleInfo module : modules) {
            if (!module.isHasAntlrSources()) continue;
            for (String cp : module.getCompileClasspath()) {
                if (cp.contains("/antlr4-runtime/")) {
                    String resolved = cp.startsWith("$HOME/")
                            ? System.getProperty("user.home") + cp.substring(5) : cp;
                    Path jar = Path.of(resolved);
                    String name = jar.getFileName().toString();
                    if (name.startsWith("antlr4-runtime-") && name.endsWith(".jar")) {
                        antlrVersion = name.substring("antlr4-runtime-".length(),
                                name.length() - ".jar".length());
                        break;
                    }
                }
            }
            if (antlrVersion != null) break;
        }
        if (antlrVersion == null) {
            System.err.println("WARNING: could not determine ANTLR4 version from classpath");
            return;
        }

        List<String> jars = new ArrayList<>();
        String[][] deps = {
            {"org/antlr/antlr4/" + antlrVersion, "antlr4-" + antlrVersion + ".jar"},
            {"org/antlr/antlr4-runtime/" + antlrVersion, "antlr4-runtime-" + antlrVersion + ".jar"},
        };
        boolean allFound = true;
        for (String[] dep : deps) {
            Path jar = m2.resolve(dep[0]).resolve(dep[1]);
            if (!Files.exists(jar)) {
                System.err.println("WARNING: ANTLR4 dependency not found: " + jar);
                allFound = false;
                continue;
            }
            jars.add(jar.toString());
        }
        // ANTLR4 tool transitive deps - find whatever version is available
        String[][] transitiveDeps = {
            {"org/antlr/antlr-runtime", "antlr-runtime"},
            {"org/antlr/ST4", "ST4"},
            {"org/abego/treelayout/org.abego.treelayout.core", "org.abego.treelayout.core"},
        };
        for (String[] dep : transitiveDeps) {
            Path depDir = m2.resolve(dep[0]);
            if (!Files.isDirectory(depDir)) {
                System.err.println("WARNING: ANTLR4 transitive dependency dir not found: " + depDir);
                allFound = false;
                continue;
            }
            try (var versions = Files.list(depDir)) {
                String found = versions.filter(Files::isDirectory)
                        .map(v -> {
                            String ver = v.getFileName().toString();
                            Path jar = v.resolve(dep[1] + "-" + ver + ".jar");
                            return Files.exists(jar) ? jar.toString() : null;
                        })
                        .filter(p -> p != null)
                        .findFirst().orElse(null);
                if (found != null) {
                    jars.add(found);
                } else {
                    System.err.println("WARNING: ANTLR4 transitive dependency jar not found in " + depDir);
                    allFound = false;
                }
            } catch (IOException e) {
                allFound = false;
            }
        }
        if (allFound && !jars.isEmpty()) {
            antlrToolClasspath = String.join(java.io.File.pathSeparator, jars);
            System.out.println("Resolved ANTLR4 tool classpath (" + antlrVersion + ")");
        }
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

    private void createBuildJar(Path classesDir, Path runtimeJar, String jandexJar,
                               List<Path> kotlinJars, Path buildJar) throws IOException {
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

            for (Path kotlinJar : kotlinJars) {
                addJarClassesToJar(kotlinJar, jos);
            }
        }
    }

    private void addJarClassesToJar(Path jarPath, JarOutputStream jos) throws IOException {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jarPath.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF")) {
                    continue;
                }
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
                        boolean isClass = entryName.endsWith(".class");
                        boolean isRequiredResource = entryName.equals("META-INF/quarkus-extension-schema.json");
                        if (!isClass && !isRequiredResource) {
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

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
