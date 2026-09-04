package io.quarkiverse.qraven.hardcoded;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class QravenCli {

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(".").toAbsolutePath().normalize();
        int threads = Runtime.getRuntime().availableProcessors();
        Path outputDir = null;
        boolean buildNative = false;
        String graalvmHome = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
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

        System.out.println("Qraven Hardcoded Build Generator");
        System.out.println("================================");
        System.out.println("Project:  " + projectDir);
        System.out.println("Threads:  " + threads);
        System.out.println("Output:   " + outputDir);
        if (buildNative) {
            System.out.println("Native:   yes");
        }
        System.out.println();

        long totalStart = System.currentTimeMillis();

        System.out.println("Step 1: Parsing POM files and resolving dependencies...");
        long stepStart = System.currentTimeMillis();

        DependencyResolver resolver = new DependencyResolver();
        PomParser parser = new PomParser(projectDir, resolver);
        List<ModuleInfo> modules = parser.parseProject();

        System.out.println("Found " + modules.size() + " modules in " +
                (System.currentTimeMillis() - stepStart) + "ms");
        for (ModuleInfo m : modules) {
            System.out.println("  " + m + " (" + m.getCompileClasspath().size() + " deps, " +
                    m.getReactorDependencies().size() + " reactor deps)");
        }
        System.out.println();

        System.out.println("Step 2: Generating build files...");
        stepStart = System.currentTimeMillis();

        BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, threads);
        generator.generate(modules);

        System.out.println("Generated in " + (System.currentTimeMillis() - stepStart) + "ms");
        System.out.println();

        System.out.println("Step 3: Compiling and packaging build.jar...");
        stepStart = System.currentTimeMillis();

        generator.compileAndPackage();

        System.out.println("Packaged in " + (System.currentTimeMillis() - stepStart) + "ms");
        System.out.println();

        Path buildJar = outputDir.resolve("build.jar");

        if (buildNative) {
            System.out.println("Step 4: Compiling native image...");
            stepStart = System.currentTimeMillis();

            Path nativeImageBin = resolveNativeImage(graalvmHome);
            Path nativeBinary = outputDir.resolve("build");

            compileNativeImage(nativeImageBin, buildJar, nativeBinary);

            System.out.println("Native image compiled in " +
                    ((System.currentTimeMillis() - stepStart) / 1000) + "s");
            System.out.println("Binary: " + nativeBinary);
            System.out.println();
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        System.out.println("Total generation time: " + totalElapsed + "ms");
        System.out.println();
        if (buildNative) {
            System.out.println("To build the project, run:");
            System.out.println("  JAVA_HOME=$GRAALVM_HOME " + outputDir.resolve("build"));
        } else {
            System.out.println("To build the project, run:");
            System.out.println("  java -jar " + buildJar);
        }
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
        Path pathBin = Path.of("native-image");
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
