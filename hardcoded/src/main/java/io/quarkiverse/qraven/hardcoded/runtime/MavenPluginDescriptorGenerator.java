package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.Type;

public class MavenPluginDescriptorGenerator {

    private static final DotName MOJO = DotName.createSimple("org.apache.maven.plugins.annotations.Mojo");
    private static final DotName PARAMETER = DotName.createSimple("org.apache.maven.plugins.annotations.Parameter");
    private static final DotName COMPONENT = DotName.createSimple("org.apache.maven.plugins.annotations.Component");
    private static final DotName EXECUTE = DotName.createSimple("org.apache.maven.plugins.annotations.Execute");

    private static final Pattern M2_PATH_PATTERN = Pattern.compile(
            ".*/([^/]+)/([^/]+)/([^/]+)/[^/]+\\.jar$");

    public static void generate(Path classesDir, String groupId, String artifactId, String version,
            String projectName, String projectDescription,
            List<String> classpath) throws IOException {
        Index index = buildIndex(classesDir);

        Collection<AnnotationInstance> mojos = index.getAnnotations(MOJO);
        if (mojos.isEmpty()) {
            return;
        }

        String goalPrefix = deriveGoalPrefix(artifactId);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\n");
        xml.append("<plugin>\n");
        appendElement(xml, 2, "name", projectName != null ? projectName : artifactId);
        appendElement(xml, 2, "description", projectDescription != null ? projectDescription : "");
        appendElement(xml, 2, "groupId", groupId);
        appendElement(xml, 2, "artifactId", artifactId);
        appendElement(xml, 2, "version", version);
        appendElement(xml, 2, "goalPrefix", goalPrefix);
        appendElement(xml, 2, "isolatedRealm", "false");
        appendElement(xml, 2, "inheritedByDefault", "true");
        xml.append("  <mojos>\n");

        for (AnnotationInstance mojoAnn : mojos) {
            if (mojoAnn.target().kind() != org.jboss.jandex.AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo mojoClass = mojoAnn.target().asClass();
            generateMojo(xml, index, mojoClass, mojoAnn);
        }

        xml.append("  </mojos>\n");

        generateDependencies(xml, classpath);

        xml.append("</plugin>\n");

        Path pluginXml = classesDir.resolve("META-INF").resolve("maven").resolve("plugin.xml");
        Files.createDirectories(pluginXml.getParent());
        try (OutputStream os = Files.newOutputStream(pluginXml)) {
            os.write(xml.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Index buildIndex(Path classesDir) throws IOException {
        Indexer indexer = new Indexer();
        try (var stream = Files.walk(classesDir)) {
            for (Path classFile : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                try (InputStream is = Files.newInputStream(classFile)) {
                    indexer.index(is);
                }
            }
        }
        return indexer.complete();
    }

    private static void generateMojo(StringBuilder xml, Index index, ClassInfo mojoClass,
            AnnotationInstance mojoAnn) {
        String goal = stringValue(mojoAnn, "name", "");
        String phase = enumValue(mojoAnn, "defaultPhase", "NONE");
        String depResolution = enumValue(mojoAnn, "requiresDependencyResolution", "NONE");
        String instantiation = enumValue(mojoAnn, "instantiationStrategy", "PER_LOOKUP");
        String executionStrategy = stringValue(mojoAnn, "executionStrategy", "once-per-session");
        boolean requiresProject = boolValue(mojoAnn, "requiresProject", true);
        boolean requiresReports = boolValue(mojoAnn, "requiresReports", false);
        boolean aggregator = boolValue(mojoAnn, "aggregator", false);
        boolean requiresDirectInvocation = boolValue(mojoAnn, "requiresDirectInvocation", false);
        boolean requiresOnline = boolValue(mojoAnn, "requiresOnline", false);
        boolean inheritByDefault = boolValue(mojoAnn, "inheritByDefault", true);
        boolean threadSafe = boolValue(mojoAnn, "threadSafe", false);

        xml.append("    <mojo>\n");
        appendElement(xml, 6, "goal", goal);

        String javadoc = "";
        appendElement(xml, 6, "description", javadoc);

        if (!"NONE".equals(depResolution)) {
            appendElement(xml, 6, "requiresDependencyResolution", resolutionScopeToString(depResolution));
        }

        appendElement(xml, 6, "requiresDirectInvocation", String.valueOf(requiresDirectInvocation));
        appendElement(xml, 6, "requiresProject", String.valueOf(requiresProject));
        appendElement(xml, 6, "requiresReports", String.valueOf(requiresReports));
        appendElement(xml, 6, "aggregator", String.valueOf(aggregator));
        appendElement(xml, 6, "requiresOnline", String.valueOf(requiresOnline));
        appendElement(xml, 6, "inheritedByDefault", String.valueOf(inheritByDefault));

        if (!"NONE".equals(phase)) {
            appendElement(xml, 6, "phase", phaseToString(phase));
        }

        appendElement(xml, 6, "implementation", mojoClass.name().toString());
        appendElement(xml, 6, "language", "java");
        appendElement(xml, 6, "instantiationStrategy", instantiationToString(instantiation));
        appendElement(xml, 6, "executionStrategy", executionStrategy);
        appendElement(xml, 6, "threadSafe", String.valueOf(threadSafe));

        Map<String, ParameterInfo> parameters = new LinkedHashMap<>();
        List<ComponentInfo> components = new ArrayList<>();
        collectFieldsFromHierarchy(index, mojoClass, parameters, components);

        if (!parameters.isEmpty()) {
            xml.append("      <parameters>\n");
            for (Map.Entry<String, ParameterInfo> entry : parameters.entrySet()) {
                ParameterInfo p = entry.getValue();
                xml.append("        <parameter>\n");
                appendElement(xml, 10, "name", entry.getKey());
                appendElement(xml, 10, "type", p.type);
                appendElement(xml, 10, "required", String.valueOf(p.required));
                appendElement(xml, 10, "editable", String.valueOf(!p.readonly));
                appendElement(xml, 10, "description", "");
                xml.append("        </parameter>\n");
            }
            xml.append("      </parameters>\n");

            xml.append("      <configuration>\n");
            for (Map.Entry<String, ParameterInfo> entry : parameters.entrySet()) {
                ParameterInfo p = entry.getValue();
                String name = entry.getKey();
                xml.append("        <").append(name);
                xml.append(" implementation=\"").append(escapeXml(p.type)).append("\"");
                if (p.defaultValue != null && !p.defaultValue.isEmpty()) {
                    xml.append(" default-value=\"").append(escapeXml(p.defaultValue)).append("\"");
                }
                xml.append(">");
                if (p.property != null && !p.property.isEmpty()) {
                    xml.append("${").append(escapeXml(p.property)).append("}");
                }
                xml.append("</").append(name).append(">\n");
            }
            xml.append("      </configuration>\n");
        }

        if (!components.isEmpty()) {
            xml.append("      <requirements>\n");
            for (ComponentInfo c : components) {
                xml.append("        <requirement>\n");
                appendElement(xml, 10, "role", c.role);
                if (c.hint != null && !c.hint.isEmpty()) {
                    appendElement(xml, 10, "role-hint", c.hint);
                }
                appendElement(xml, 10, "field-name", c.fieldName);
                xml.append("        </requirement>\n");
            }
            xml.append("      </requirements>\n");
        }

        xml.append("    </mojo>\n");
    }

    private static void collectFieldsFromHierarchy(Index index, ClassInfo classInfo,
            Map<String, ParameterInfo> parameters, List<ComponentInfo> components) {
        if (classInfo == null) return;

        DotName superName = classInfo.superName();
        if (superName != null && !superName.toString().equals("java.lang.Object")) {
            ClassInfo superClass = index.getClassByName(superName);
            if (superClass != null) {
                collectFieldsFromHierarchy(index, superClass, parameters, components);
            }
        }

        for (FieldInfo field : classInfo.fields()) {
            AnnotationInstance paramAnn = field.annotation(PARAMETER);
            if (paramAnn != null) {
                ParameterInfo info = new ParameterInfo();
                info.type = fieldTypeName(field);
                info.required = boolValue(paramAnn, "required", false);
                info.readonly = boolValue(paramAnn, "readonly", false);
                info.defaultValue = stringValue(paramAnn, "defaultValue", "");
                info.property = stringValue(paramAnn, "property", "");
                String name = stringValue(paramAnn, "name", "");
                if (name.isEmpty()) {
                    name = field.name();
                }
                info.alias = stringValue(paramAnn, "alias", "");
                parameters.put(name, info);
                continue;
            }

            AnnotationInstance compAnn = field.annotation(COMPONENT);
            if (compAnn != null) {
                ComponentInfo info = new ComponentInfo();
                AnnotationValue roleValue = compAnn.value("role");
                if (roleValue != null) {
                    info.role = roleValue.asClass().name().toString();
                } else {
                    info.role = fieldTypeName(field);
                }
                info.hint = stringValue(compAnn, "hint", "");
                info.fieldName = field.name();
                components.add(info);
            }
        }
    }

    private static String fieldTypeName(FieldInfo field) {
        Type type = field.type();
        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            return type.asParameterizedType().name().toString();
        }
        return type.name().toString();
    }

    private static void generateDependencies(StringBuilder xml, List<String> classpath) {
        if (classpath == null || classpath.isEmpty()) return;

        List<GAV> deps = new ArrayList<>();
        for (String path : classpath) {
            GAV gav = parseGAVFromPath(path);
            if (gav != null) {
                deps.add(gav);
            }
        }

        if (deps.isEmpty()) return;

        xml.append("  <dependencies>\n");
        for (GAV gav : deps) {
            xml.append("    <dependency>\n");
            appendElement(xml, 6, "groupId", gav.groupId);
            appendElement(xml, 6, "artifactId", gav.artifactId);
            appendElement(xml, 6, "type", "jar");
            appendElement(xml, 6, "version", gav.version);
            xml.append("    </dependency>\n");
        }
        xml.append("  </dependencies>\n");
    }

    static GAV parseGAVFromPath(String path) {
        String normalized = path.replace('\\', '/');
        Matcher m = M2_PATH_PATTERN.matcher(normalized);
        if (!m.matches()) return null;
        String artifactId = m.group(1);
        String version = m.group(2);
        int artifactIdx = normalized.lastIndexOf("/" + artifactId + "/" + version + "/");
        if (artifactIdx < 0) return null;
        String prefix = normalized.substring(0, artifactIdx);
        int repoIdx = prefix.lastIndexOf("/repository/");
        String groupPath;
        if (repoIdx >= 0) {
            groupPath = prefix.substring(repoIdx + "/repository/".length());
        } else {
            groupPath = prefix;
        }
        String groupId = groupPath.replace('/', '.');
        return new GAV(groupId, artifactId, version);
    }

    private static String deriveGoalPrefix(String artifactId) {
        if (artifactId.endsWith("-maven-plugin")) {
            return artifactId.substring(0, artifactId.length() - "-maven-plugin".length());
        }
        if (artifactId.startsWith("maven-") && artifactId.endsWith("-plugin")) {
            return artifactId.substring("maven-".length(), artifactId.length() - "-plugin".length());
        }
        return artifactId;
    }

    private static String resolutionScopeToString(String enumName) {
        return switch (enumName) {
            case "COMPILE" -> "compile";
            case "RUNTIME" -> "runtime";
            case "TEST" -> "test";
            case "COMPILE_PLUS_RUNTIME" -> "compile+runtime";
            case "RUNTIME_PLUS_SYSTEM" -> "runtime+system";
            default -> "";
        };
    }

    private static String phaseToString(String enumName) {
        return enumName.toLowerCase().replace('_', '-');
    }

    private static String instantiationToString(String enumName) {
        return switch (enumName) {
            case "PER_LOOKUP" -> "per-lookup";
            case "SINGLETON" -> "singleton";
            case "KEEP_ALIVE" -> "keep-alive";
            default -> "per-lookup";
        };
    }

    private static String stringValue(AnnotationInstance ann, String name, String defaultVal) {
        AnnotationValue v = ann.value(name);
        return v != null ? v.asString() : defaultVal;
    }

    private static boolean boolValue(AnnotationInstance ann, String name, boolean defaultVal) {
        AnnotationValue v = ann.value(name);
        return v != null ? v.asBoolean() : defaultVal;
    }

    private static String enumValue(AnnotationInstance ann, String name, String defaultVal) {
        AnnotationValue v = ann.value(name);
        return v != null ? v.asEnum() : defaultVal;
    }

    private static void appendElement(StringBuilder sb, int indent, String element, String value) {
        sb.append(" ".repeat(indent))
                .append("<").append(element).append(">")
                .append(escapeXml(value))
                .append("</").append(element).append(">\n");
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static class ParameterInfo {
        String type;
        boolean required;
        boolean readonly;
        String defaultValue;
        String property;
        String alias;
    }

    private static class ComponentInfo {
        String role;
        String hint;
        String fieldName;
    }

    record GAV(String groupId, String artifactId, String version) {}
}
