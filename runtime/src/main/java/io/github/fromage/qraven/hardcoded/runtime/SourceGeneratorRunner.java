package io.github.fromage.qraven.hardcoded.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the main method of a class in its own class loader: what exec-maven-plugin:java does, for
 * code generators that run in generate-sources.
 */
final class SourceGeneratorRunner {

    private SourceGeneratorRunner() {
    }

    static void run(String moduleId, String mainClass, String[] args, List<String> classpath) {
        List<URL> urls = new ArrayList<>(classpath.size());
        for (String entry : classpath) {
            Path path = Path.of(entry);
            if (!Files.exists(path)) continue;
            try {
                urls.add(path.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new RuntimeException("[" + moduleId + "] invalid classpath entry " + entry, e);
            }
        }

        Thread thread = Thread.currentThread();
        ClassLoader originalContextLoader = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            Class<?> main;
            try {
                main = Class.forName(mainClass, true, loader);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("[" + moduleId + "] source generator class " + mainClass
                        + " not found on its classpath (is the module that contains it built and installed?)", e);
            }
            Method method = main.getMethod("main", String[].class);
            method.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("[" + moduleId + "] source generator " + mainClass + " failed: " + cause, cause);
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new RuntimeException("[" + moduleId + "] failed to run source generator " + mainClass, e);
        } finally {
            thread.setContextClassLoader(originalContextLoader);
        }
    }
}
