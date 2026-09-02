package io.quarkiverse.qraven.hardcoded;

import java.nio.file.Path;
import java.util.List;

public class QravenCli {

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(".").toAbsolutePath().normalize();
        int threads = Runtime.getRuntime().availableProcessors();
        Path outputDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--project", "-p" -> projectDir = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--threads", "-t" -> threads = Integer.parseInt(args[++i]);
                case "--output", "-o" -> outputDir = Path.of(args[++i]).toAbsolutePath().normalize();
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

        long totalElapsed = System.currentTimeMillis() - totalStart;
        System.out.println("Total generation time: " + totalElapsed + "ms");
        System.out.println();
        System.out.println("To build the project, run:");
        System.out.println("  java -jar " + outputDir.resolve("build.jar"));
    }
}
