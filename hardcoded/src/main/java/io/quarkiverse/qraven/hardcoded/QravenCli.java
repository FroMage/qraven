///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//JAVAC_OPTIONS --release 17
//DEPS org.apache.maven:maven-resolver-provider:3.9.6
//DEPS org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18
//DEPS org.apache.maven.resolver:maven-resolver-transport-file:1.9.18
//DEPS org.apache.maven.resolver:maven-resolver-transport-http:1.9.18
//DEPS io.smallrye:jandex:3.5.3
//DEPS org.jetbrains.kotlin:kotlin-compiler:2.4.10
//SOURCES BuildFileGenerator.java
//SOURCES DependencyResolver.java
//SOURCES LocalRepoModelResolver.java
//SOURCES ModuleInfo.java
//SOURCES PomParser.java
//SOURCES runtime/BuildOrchestrator.java
//SOURCES runtime/BuildRuntime.java
//SOURCES runtime/ModuleBuild.java
//SOURCES runtime/ProgressDisplay.java

package io.quarkiverse.qraven.hardcoded;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class QravenCli {

    private static final String ERASE_LINE = "\r\u001b[2K";

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(".").toAbsolutePath().normalize();
        int threads = Runtime.getRuntime().availableProcessors();
        Path outputDir = null;
        boolean buildNative = false;
        String graalvmHome = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> { printHelp(); return; }
                case "--project", "-p" -> projectDir = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--threads", "-t" -> threads = Integer.parseInt(args[++i]);
                case "--output", "-o" -> outputDir = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--native" -> buildNative = true;
                case "--graalvm-home" -> graalvmHome = args[++i];
            }
        }

        if (outputDir == null) {
            outputDir = projectDir.resolve("target/qraven");
        }

        Path nativeImageBin = null;
        if (buildNative) {
            nativeImageBin = resolveNativeImage(graalvmHome);
        }

        System.out.println("Qraven Build Generator");
        System.out.println("Project:  " + projectDir);
        System.out.println("Threads:  " + threads);
        System.out.println("Output:   " + outputDir);
        if (buildNative) {
            System.out.println("Native:   yes");
        }
        System.out.println();

        long totalStart = System.currentTimeMillis();
        long stepStart = System.currentTimeMillis();

        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        parser.setProgressListener((phase, detail, current, total) -> {
            String msg = switch (phase) {
                case "scan" -> "Scanning modules... " + current + " found (" + detail + ")";
                case "resolve" -> "Resolving " + current + "/" + total + " (" + detail + ")";
                default -> phase + ": " + detail;
            };
            System.err.print(ERASE_LINE + "  " + msg);
        });
        List<ModuleInfo> modules = parser.parseProject();
        System.err.print(ERASE_LINE);

        System.out.println("Parsed " + modules.size() + " modules in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        stepStart = System.currentTimeMillis();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, threads);
        generator.setProgressListener((detail, current, total) ->
                System.err.print(ERASE_LINE + "  Generating " + current + "/" + total + " (" + detail + ")"));
        generator.generate(modules);
        System.err.print(ERASE_LINE);

        System.out.println("Generated " + (modules.size() + 1) + " source files in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        stepStart = System.currentTimeMillis();
        System.err.print("  Compiling and packaging build.jar...");

        generator.compileAndPackage();

        System.err.print(ERASE_LINE);
        System.out.println("Packaged build.jar in " + (System.currentTimeMillis() - stepStart) + "ms");
        System.out.println();

        Path buildJar = outputDir.resolve("build.jar");

        if (buildNative) {
            stepStart = System.currentTimeMillis();

            Path nativeBinary = outputDir.resolve("build");

            compileNativeImage(nativeImageBin, buildJar, nativeBinary);

            System.out.println("Native image compiled in " +
                    ((System.currentTimeMillis() - stepStart) / 1000) + "s");
            System.out.println("Binary: " + nativeBinary);
            System.out.println();
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        System.out.println("Total time: " + totalElapsed + "ms");
        System.out.println();
        if (buildNative) {
            System.out.println("To build the project, run:");
            System.out.println("  JAVA_HOME=$GRAALVM_HOME " + outputDir.resolve("build"));
        } else {
            System.out.println("To build the project, run:");
            System.out.println("  java -jar " + buildJar);
        }
    }

    private static void printHelp() {
        System.out.println("""
                qraven - Fast build tool for Maven projects

                Parses pom.xml files, resolves dependencies, and generates a self-contained
                build.jar that compiles all modules using the javac API. The build.jar can
                optionally be compiled to a native binary via GraalVM for maximum speed.

                Usage: qraven [options]

                Options:
                  -h, --help                Show this help message and exit
                  -p, --project <path>      Project root directory (default: current directory)
                  -t, --threads <n>         Thread count for parallel compilation (default: available CPUs)
                  -o, --output <path>       Output directory for build.jar (default: <project>/target/qraven)
                      --native              Compile build.jar to a native binary after generation
                      --graalvm-home <path>  GraalVM installation path for native-image
                                            (also checks GRAALVM_HOME, JAVA_HOME, and PATH)

                Examples:
                  qraven                                    Generate build.jar for current directory
                  qraven -p /path/to/project -t 8           Use 8 threads
                  qraven --native --graalvm-home /opt/graalvm  Generate and compile to native binary

                Running the generated build:
                  java -jar target/qraven/build.jar          JVM mode
                  JAVA_HOME=/path/to/jdk target/qraven/build  Native mode (JAVA_HOME must match build JDK)
                """);
    }

    private static Path resolveNativeImage(String graalvmHome) {
        if (graalvmHome != null) {
            Path bin = Path.of(graalvmHome, "bin", "native-image");
            if (Files.isExecutable(bin)) return bin;
            throw new RuntimeException("native-image not found at " + bin);
        }
        String envGraalvm = System.getenv("GRAALVM_HOME");
        if (envGraalvm != null) {
            Path bin = Path.of(envGraalvm, "bin", "native-image");
            if (Files.isExecutable(bin)) return bin;
        }
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null) {
            Path bin = Path.of(envJavaHome, "bin", "native-image");
            if (Files.isExecutable(bin)) return bin;
        }
        try {
            Process p = new ProcessBuilder("which", "native-image").start();
            if (p.waitFor() == 0) {
                String path = new String(p.getInputStream().readAllBytes()).trim();
                if (!path.isEmpty()) return Path.of(path);
            }
        } catch (Exception e) {
            // fall through
        }
        throw new RuntimeException(
                "native-image not found. Set --graalvm-home, GRAALVM_HOME, or add native-image to PATH");
    }

    private static void compileNativeImage(Path nativeImageBin, Path buildJar, Path outputBinary) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(nativeImageBin.toString());
        cmd.add("-jar");
        cmd.add(buildJar.toString());
        cmd.add("-H:IncludeResourceBundles=com.sun.tools.javac.resources.compiler,com.sun.tools.javac.resources.javac,com.sun.tools.javac.resources.launcher");
        cmd.add("--initialize-at-build-time=com.sun.tools.javac");
        cmd.add("-H:+AllowJRTFileSystem");
        cmd.add("-march=native");
        cmd.add(outputBinary.toString());

        System.out.println("  " + String.join(" \\\n    ", cmd));
        System.out.println();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("  " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("native-image failed with exit code " + exitCode);
        }
    }
}
