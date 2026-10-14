package io.github.fromage.qraven.hardcoded.runtime;

import io.github.fromage.qraven.hardcoded.ModuleInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A {@link ModuleBuild} whose data is a {@link ModuleInfo} read from the generation state, instead of a class
 * generated for the module. The build only ever reads these accessors, so this is all a module is: data.
 * <p>
 * The accessors are what the generated classes used to return: classpaths use {@code $HOME} for the home directory
 * (the build resolves it back), the optional classpath entries are kept apart from the others, and the
 * extension dev properties keep the {@code \n} escapes their consumers expect.
 */
public final class DataModuleBuild extends ModuleBuild {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String packaging;
    private final Path baseDir;
    private final boolean hasJavaSources;
    private final boolean hasKotlinSources;
    private final boolean hasTestJavaSources;
    private final boolean hasTestKotlinSources;
    private final boolean hasProtobufSources;
    private final boolean hasTestProtobufSources;
    private final boolean protobufUsesGrpc;
    private final boolean protobufUsesMutiny;
    private final boolean hasAntlrSources;
    private final boolean antlrVisitor;
    private final boolean needsJandexIndex;
    private final String platformPropertiesFileName;
    private final String flattenedPomFile;
    private final boolean hasExtensionPlugin;
    private final boolean hasImpsortPlugin;
    private final boolean hasSpotlessPlugin;
    private final boolean hasEnforcerPlugin;
    private final String extensionValidationSkipWhen;
    private final String[][] resourceDirs;
    private final Map<String, String> filterProperties;
    private final Map<String, String> manifestEntries;
    private final List<String> compileClasspath;
    private final List<String> optionalCompileClasspath;
    private final List<String> testCompileClasspath;
    private final List<String> annotationProcessorPaths;
    private final boolean apCacheable;
    private final List<String> compilerArgs;
    private final List<String> testCompilerArgs;
    private final List<String> moduleDependencyIds;
    private final List<String> optionalModuleDependencyIds;
    private final List<String> testModuleDependencyIds;
    private final List<String> testJarModuleDependencyIds;
    private final Map<String, String> extensionDescriptorProperties;
    private final String extensionProjectName;
    private final String extensionProjectDescription;
    private final String extensionScmUrl;
    private final String extensionMinimumJavaVersion;
    private final List<String> extensionModelDeps;
    private final List<String> extensionReactorGAs;
    private final List<String> extensionParentFirstArtifacts;
    private final List<String> extensionRunnerParentFirstArtifacts;
    private final List<String> extensionExcludedArtifacts;
    private final List<String> extensionLesserPriorityArtifacts;
    private final List<String> extensionConditionalDependencies;
    private final List<String> extensionConditionalDevDependencies;
    private final List<String> extensionDependencyCondition;
    private final List<String> extensionProvidesCapabilities;
    private final List<String> extensionRequiresCapabilities;
    private final boolean hasQuarkusBuildPlugin;
    private final String quarkusBuildSkipWhen;
    private final boolean hasGenerateCodeGoal;
    private final boolean hasGenerateCodeTestsGoal;
    private final boolean hasCodeGenProviders;
    private final boolean hasSisuPlugin;
    private final String generateCodeSkipWhen;
    private final Map<String, String> quarkusBuildProperties;
    private final List<String> deploymentClasspath;
    private final List<String> runtimeExtensionArtifacts;
    private final Map<String, String> extensionDevProperties;
    private final String protocVersion;
    private final String grpcVersion;
    private final String quarkusGrpcVersion;
    private final String protobufProtocVersion;
    private final String protobufGrpcPluginVersion;
    private final Map<String, String> allReactorExtensionDeployments;
    private final String pluginName;
    private final String pluginDescription;
    private final List<String> kotlinCompilerPlugins;
    private final String kotlinVersion;
    private final String kotlinLanguageVersion;
    private final String kotlinApiVersion;
    private final List<String> kotlinPluginOptions;
    private final List<ShadeExecution> shadeExecutions;
    private final List<SourceGenerator> sourceGenerators;
    private final List<String> generatorClasspath;
    private final List<String> addedSourceDirs;

    private volatile String fingerprint;

    /**
     * @param flattenedPomsDir where generation wrote the flattened poms of the platform BOMs
     */
    public DataModuleBuild(BuildRuntime runtime, ModuleInfo m, Path flattenedPomsDir) {
        super(runtime);
        groupId = m.getGroupId();
        artifactId = m.getArtifactId();
        version = m.getVersion();
        packaging = m.getPackaging();
        baseDir = Path.of(m.getBaseDir().toString());
        hasJavaSources = m.isHasJavaSources();
        hasKotlinSources = m.isHasKotlinSources();
        hasTestJavaSources = m.isHasTestJavaSources();
        hasTestKotlinSources = m.isHasTestKotlinSources();
        hasProtobufSources = m.isHasProtobufSources();
        hasTestProtobufSources = m.isHasTestProtobufSources();
        protobufUsesGrpc = m.isProtobufUsesGrpc();
        protobufUsesMutiny = m.isProtobufUsesMutiny();
        hasAntlrSources = m.isHasAntlrSources();
        antlrVisitor = m.isAntlrVisitor();
        needsJandexIndex = m.isNeedsJandexIndex();
        platformPropertiesFileName = m.getPlatformPropertiesFileName();
        flattenedPomFile = m.getFlattenedBomModel() == null ? null
                : flattenedPomsDir.resolve(m.getArtifactId() + "-" + m.getVersion() + ".pom").toString();
        hasExtensionPlugin = m.isHasExtensionPlugin();
        hasImpsortPlugin = m.isHasImpsortPlugin();
        hasSpotlessPlugin = m.isHasSpotlessPlugin();
        hasEnforcerPlugin = m.isHasEnforcerPlugin();
        extensionValidationSkipWhen = m.getExtensionValidationSkipWhen();

        resourceDirs = new String[m.getResourceDirs().size()][];
        for (int i = 0; i < resourceDirs.length; i++) {
            ModuleInfo.ResourceDir rd = m.getResourceDirs().get(i);
            resourceDirs[i] = new String[] { rd.directory(), String.valueOf(rd.filtering()), rd.targetPath(),
                    String.join("\n", rd.includes()), String.join("\n", rd.excludes()) };
        }
        filterProperties = copy(m.getFilterProperties());
        manifestEntries = copy(m.getManifestEntries());

        List<String> nonOptionalClasspath = new ArrayList<>(m.getCompileClasspath());
        nonOptionalClasspath.removeAll(m.getOptionalClasspathEntries());
        compileClasspath = portable(nonOptionalClasspath);
        optionalCompileClasspath = portable(m.getOptionalClasspathEntries());
        testCompileClasspath = portable(m.getTestCompileClasspath());
        annotationProcessorPaths = portable(m.getAnnotationProcessorPaths());
        apCacheable = m.isApCacheable();
        compilerArgs = List.copyOf(m.getCompilerArgs());
        // generation state written before the test arguments were recorded has none; it never is empty otherwise
        testCompilerArgs = m.getTestCompilerArgs().isEmpty() ? compilerArgs : List.copyOf(m.getTestCompilerArgs());

        moduleDependencyIds = List.copyOf(m.getReactorDependencies());
        optionalModuleDependencyIds = List.copyOf(m.getOptionalReactorDependencies());
        testModuleDependencyIds = List.copyOf(m.getTestReactorDependencies());
        testJarModuleDependencyIds = List.copyOf(m.getTestJarReactorDependencies());

        extensionDescriptorProperties = copy(m.getExtensionDescriptorProperties());
        extensionProjectName = m.getExtensionProjectName();
        extensionProjectDescription = m.getExtensionProjectDescription();
        extensionScmUrl = m.getExtensionScmUrl();
        extensionMinimumJavaVersion = m.getExtensionMinimumJavaVersion();
        extensionModelDeps = List.copyOf(m.getExtensionModelDeps());
        extensionReactorGAs = List.copyOf(m.getExtensionReactorGAs());
        extensionParentFirstArtifacts = List.copyOf(m.getExtensionParentFirstArtifacts());
        extensionRunnerParentFirstArtifacts = List.copyOf(m.getExtensionRunnerParentFirstArtifacts());
        extensionExcludedArtifacts = List.copyOf(m.getExtensionExcludedArtifacts());
        extensionLesserPriorityArtifacts = List.copyOf(m.getExtensionLesserPriorityArtifacts());
        extensionConditionalDependencies = List.copyOf(m.getExtensionConditionalDependencies());
        extensionConditionalDevDependencies = List.copyOf(m.getExtensionConditionalDevDependencies());
        extensionDependencyCondition = List.copyOf(m.getExtensionDependencyCondition());
        extensionProvidesCapabilities = List.copyOf(m.getExtensionProvidesCapabilities());
        extensionRequiresCapabilities = List.copyOf(m.getExtensionRequiresCapabilities());

        hasQuarkusBuildPlugin = m.isHasQuarkusBuildPlugin();
        quarkusBuildSkipWhen = m.getQuarkusBuildSkipWhen();
        hasGenerateCodeGoal = m.isHasGenerateCodeGoal();
        hasGenerateCodeTestsGoal = m.isHasGenerateCodeTestsGoal();
        hasCodeGenProviders = m.isHasCodeGenProviders();
        hasSisuPlugin = m.isHasSisuPlugin();
        generateCodeSkipWhen = m.getGenerateCodeSkipWhen();
        quarkusBuildProperties = copy(m.getQuarkusBuildProperties());
        deploymentClasspath = portable(m.getDeploymentClasspath());
        runtimeExtensionArtifacts = List.copyOf(m.getRuntimeExtensionArtifacts());
        // the consumers split these packed property texts on a literal backslash-n
        Map<String, String> devProperties = new LinkedHashMap<>();
        m.getExtensionDevProperties().forEach((k, v) -> devProperties.put(k, v.replace("\n", "\\n")));
        extensionDevProperties = Collections.unmodifiableMap(devProperties);
        protocVersion = m.getProtocVersion();
        grpcVersion = m.getGrpcVersion();
        quarkusGrpcVersion = m.getQuarkusGrpcVersion();
        protobufProtocVersion = m.getProtobufProtocVersion();
        protobufGrpcPluginVersion = m.getProtobufGrpcPluginVersion();
        allReactorExtensionDeployments = copy(m.getAllReactorExtensionDeployments());
        pluginName = m.getProjectName();
        pluginDescription = m.getProjectDescription();
        kotlinCompilerPlugins = List.copyOf(m.getKotlinCompilerPlugins());
        kotlinVersion = m.getKotlinVersion();
        kotlinLanguageVersion = m.getKotlinLanguageVersion();
        kotlinApiVersion = m.getKotlinApiVersion();
        kotlinPluginOptions = List.copyOf(m.getKotlinPluginOptions());

        List<ShadeExecution> shades = new ArrayList<>();
        for (ModuleInfo.ShadeExecution e : m.getShadeExecutions()) {
            List<ShadeFilter> filters = new ArrayList<>();
            for (ModuleInfo.ShadeFilter f : e.filters()) {
                filters.add(new ShadeFilter(f.artifact(), List.copyOf(f.excludes())));
            }
            shades.add(new ShadeExecution(e.id(), e.attached(), e.classifier(), List.copyOf(e.includeArtifacts()),
                    List.copyOf(filters), e.mainClass()));
        }
        shadeExecutions = List.copyOf(shades);
        List<SourceGenerator> generators = new ArrayList<>();
        for (ModuleInfo.SourceGenerator g : m.getSourceGenerators()) {
            generators.add(new SourceGenerator(g.mainClass(), List.copyOf(g.arguments()), g.skipWhen()));
        }
        sourceGenerators = List.copyOf(generators);
        // only meaningful with source generators
        generatorClasspath = m.getSourceGenerators().isEmpty() ? List.of() : portable(m.getGeneratorClasspath());
        addedSourceDirs = List.copyOf(m.getAddedSourceDirs());
    }

    /** The paths below the home directory are written with $HOME, which the build resolves again. */
    private static List<String> portable(Collection<String> paths) {
        String home = System.getProperty("user.home");
        List<String> result = new ArrayList<>(paths.size());
        for (String p : paths) {
            result.add(p.startsWith(home + "/") ? "$HOME" + p.substring(home.length()) : p);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> copy(Map<String, String> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    @Override public String groupId() { return groupId; }
    @Override public String artifactId() { return artifactId; }
    @Override public String version() { return version; }
    @Override public String packaging() { return packaging; }
    @Override public Path baseDir() { return baseDir; }
    @Override public boolean hasJavaSources() { return hasJavaSources; }
    @Override public boolean hasKotlinSources() { return hasKotlinSources; }
    @Override public boolean hasTestJavaSources() { return hasTestJavaSources; }
    @Override public boolean hasTestKotlinSources() { return hasTestKotlinSources; }
    @Override public boolean hasProtobufSources() { return hasProtobufSources; }
    @Override public boolean hasTestProtobufSources() { return hasTestProtobufSources; }
    @Override public boolean protobufUsesGrpc() { return protobufUsesGrpc; }
    @Override public boolean protobufUsesMutiny() { return protobufUsesMutiny; }
    @Override public boolean hasAntlrSources() { return hasAntlrSources; }
    @Override public boolean antlrVisitor() { return antlrVisitor; }
    @Override public boolean needsJandexIndex() { return needsJandexIndex; }
    @Override public String platformPropertiesFileName() { return platformPropertiesFileName; }
    @Override public String flattenedPomFile() { return flattenedPomFile; }
    @Override public boolean hasExtensionPlugin() { return hasExtensionPlugin; }
    @Override public boolean hasImpsortPlugin() { return hasImpsortPlugin; }
    @Override public boolean hasSpotlessPlugin() { return hasSpotlessPlugin; }
    @Override public boolean hasEnforcerPlugin() { return hasEnforcerPlugin; }
    @Override public String extensionValidationSkipWhen() { return extensionValidationSkipWhen; }
    @Override public String[][] resourceDirs() { return resourceDirs; }
    @Override public Map<String, String> filterProperties() { return filterProperties; }
    @Override public Map<String, String> manifestEntries() { return manifestEntries; }
    @Override public List<String> compileClasspath() { return compileClasspath; }
    @Override public List<String> optionalCompileClasspath() { return optionalCompileClasspath; }
    @Override public List<String> testCompileClasspath() { return testCompileClasspath; }
    @Override public List<String> annotationProcessorPaths() { return annotationProcessorPaths; }
    @Override public boolean isApCacheable() { return apCacheable; }
    @Override public List<String> compilerArgs() { return compilerArgs; }
    @Override public List<String> testCompilerArgs() { return testCompilerArgs; }
    @Override public List<String> moduleDependencyIds() { return moduleDependencyIds; }
    @Override public List<String> optionalModuleDependencyIds() { return optionalModuleDependencyIds; }
    @Override public List<String> testModuleDependencyIds() { return testModuleDependencyIds; }
    @Override public List<String> testJarModuleDependencyIds() { return testJarModuleDependencyIds; }
    @Override public Map<String, String> extensionDescriptorProperties() { return extensionDescriptorProperties; }
    @Override public String extensionProjectName() { return extensionProjectName; }
    @Override public String extensionProjectDescription() { return extensionProjectDescription; }
    @Override public String extensionScmUrl() { return extensionScmUrl; }
    @Override public String extensionMinimumJavaVersion() { return extensionMinimumJavaVersion; }
    @Override public List<String> extensionModelDeps() { return extensionModelDeps; }
    @Override public List<String> extensionReactorGAs() { return extensionReactorGAs; }
    @Override public List<String> extensionParentFirstArtifacts() { return extensionParentFirstArtifacts; }
    @Override public List<String> extensionRunnerParentFirstArtifacts() { return extensionRunnerParentFirstArtifacts; }
    @Override public List<String> extensionExcludedArtifacts() { return extensionExcludedArtifacts; }
    @Override public List<String> extensionLesserPriorityArtifacts() { return extensionLesserPriorityArtifacts; }
    @Override public List<String> extensionConditionalDependencies() { return extensionConditionalDependencies; }
    @Override public List<String> extensionConditionalDevDependencies() { return extensionConditionalDevDependencies; }
    @Override public List<String> extensionDependencyCondition() { return extensionDependencyCondition; }
    @Override public List<String> extensionProvidesCapabilities() { return extensionProvidesCapabilities; }
    @Override public List<String> extensionRequiresCapabilities() { return extensionRequiresCapabilities; }
    @Override public boolean hasQuarkusBuildPlugin() { return hasQuarkusBuildPlugin; }
    @Override public String quarkusBuildSkipWhen() { return quarkusBuildSkipWhen; }
    @Override public boolean hasGenerateCodeGoal() { return hasGenerateCodeGoal; }
    @Override public boolean hasGenerateCodeTestsGoal() { return hasGenerateCodeTestsGoal; }
    @Override public boolean hasCodeGenProviders() { return hasCodeGenProviders; }
    @Override public boolean hasSisuPlugin() { return hasSisuPlugin; }
    @Override public String generateCodeSkipWhen() { return generateCodeSkipWhen; }
    @Override public Map<String, String> quarkusBuildProperties() { return quarkusBuildProperties; }
    @Override public List<String> deploymentClasspath() { return deploymentClasspath; }
    @Override public List<String> runtimeExtensionArtifacts() { return runtimeExtensionArtifacts; }
    @Override public Map<String, String> extensionDevProperties() { return extensionDevProperties; }
    @Override public String protocVersion() { return protocVersion; }
    @Override public String grpcVersion() { return grpcVersion; }
    @Override public String quarkusGrpcVersion() { return quarkusGrpcVersion; }
    @Override public String protobufProtocVersion() { return protobufProtocVersion; }
    @Override public String protobufGrpcPluginVersion() { return protobufGrpcPluginVersion; }
    @Override public Map<String, String> allReactorExtensionDeployments() { return allReactorExtensionDeployments; }
    @Override public String pluginName() { return pluginName; }
    @Override public String pluginDescription() { return pluginDescription; }
    @Override public List<String> kotlinCompilerPlugins() { return kotlinCompilerPlugins; }
    @Override public List<String> kotlinPluginOptions() { return kotlinPluginOptions; }
    @Override public String kotlinVersion() { return kotlinVersion; }
    @Override public String kotlinLanguageVersion() { return kotlinLanguageVersion; }
    @Override public String kotlinApiVersion() { return kotlinApiVersion; }
    @Override public List<ShadeExecution> shadeExecutions() { return shadeExecutions; }
    @Override public List<SourceGenerator> sourceGenerators() { return sourceGenerators; }
    @Override public List<String> generatorClasspath() { return generatorClasspath; }
    @Override public List<String> addedSourceDirs() { return addedSourceDirs; }

    /**
     * A fingerprint of everything above, which is everything the build knows about the module. It changes when
     * a classpath, a compiler argument or any other setting that goes into building the module changes, and not
     * when generation-only bookkeeping does.
     */
    @Override
    public String buildDataFingerprint() {
        String result = fingerprint;
        if (result == null) {
            result = computeFingerprint();
            fingerprint = result;
        }
        return result;
    }

    private String computeFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Object[] values = {
                groupId, artifactId, version, packaging, baseDir.toString(),
                hasJavaSources, hasKotlinSources, hasTestJavaSources, hasTestKotlinSources, hasProtobufSources,
                hasTestProtobufSources, protobufUsesGrpc, protobufUsesMutiny, hasAntlrSources, antlrVisitor,
                needsJandexIndex, platformPropertiesFileName, flattenedPomFile, hasExtensionPlugin,
                hasImpsortPlugin, hasSpotlessPlugin, hasEnforcerPlugin,
                extensionValidationSkipWhen, resourceDirs, filterProperties, manifestEntries, compileClasspath,
                optionalCompileClasspath, testCompileClasspath, annotationProcessorPaths, apCacheable, compilerArgs, testCompilerArgs,
                moduleDependencyIds, optionalModuleDependencyIds, testModuleDependencyIds, testJarModuleDependencyIds,
                extensionDescriptorProperties, extensionProjectName, extensionProjectDescription, extensionScmUrl,
                extensionMinimumJavaVersion, extensionModelDeps, extensionReactorGAs, extensionParentFirstArtifacts,
                extensionRunnerParentFirstArtifacts, extensionExcludedArtifacts, extensionLesserPriorityArtifacts,
                extensionConditionalDependencies, extensionConditionalDevDependencies, extensionDependencyCondition,
                extensionProvidesCapabilities, extensionRequiresCapabilities, hasQuarkusBuildPlugin,
                quarkusBuildSkipWhen, hasGenerateCodeGoal, hasGenerateCodeTestsGoal, hasCodeGenProviders,
                hasSisuPlugin, generateCodeSkipWhen, quarkusBuildProperties, deploymentClasspath,
                runtimeExtensionArtifacts, extensionDevProperties, protocVersion, grpcVersion, quarkusGrpcVersion, protobufProtocVersion, protobufGrpcPluginVersion,
                allReactorExtensionDeployments, pluginName, pluginDescription, kotlinCompilerPlugins,
                kotlinPluginOptions, kotlinVersion, kotlinLanguageVersion, kotlinApiVersion, shadeExecutions, sourceGenerators, generatorClasspath, addedSourceDirs
            };
            for (Object value : values) {
                hash(digest, value);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Feeds a value to the digest so that different values can't produce the same bytes. */
    private static void hash(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update((byte) 'n');
        } else if (value instanceof String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) 's');
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        } else if (value instanceof Boolean b) {
            digest.update(b ? (byte) 't' : (byte) 'f');
        } else if (value instanceof Object[] array) {
            digest.update((byte) '[');
            digest.update(Integer.toString(array.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            for (Object o : array) {
                hash(digest, o);
            }
        } else if (value instanceof Collection<?> c) {
            digest.update((byte) '[');
            digest.update(Integer.toString(c.size()).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            for (Object o : c) {
                hash(digest, o);
            }
        } else if (value instanceof Map<?, ?> map) {
            // sorted, so that the order entries were stored in doesn't matter
            digest.update((byte) '{');
            digest.update(Integer.toString(map.size()).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            new TreeMap<>(map).forEach((k, v) -> {
                hash(digest, k);
                hash(digest, v);
            });
        } else if (value instanceof ShadeExecution e) {
            hash(digest, new Object[] { e.id(), e.attached(), e.classifier(), e.includeArtifacts(), e.filters(),
                    e.mainClass() });
        } else if (value instanceof ShadeFilter f) {
            hash(digest, new Object[] { f.artifact(), f.excludes() });
        } else if (value instanceof SourceGenerator g) {
            hash(digest, new Object[] { g.mainClass(), g.arguments(), g.skipWhen() });
        } else {
            throw new IllegalArgumentException("cannot fingerprint " + value.getClass());
        }
    }
}
