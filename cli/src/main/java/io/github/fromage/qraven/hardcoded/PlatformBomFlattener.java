package io.github.fromage.qraven.hardcoded;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;

/**
 * Native implementation of the {@code flatten-platform-bom} goal of
 * {@code io.quarkus:quarkus-platform-bom-maven-plugin}: builds a BOM model that only
 * contains the (already import-expanded) managed dependencies of the effective model,
 * minus the excluded ones and sorted alphabetically.
 */
public final class PlatformBomFlattener {

    private static final String DESCRIPTOR_SUFFIX = "-platform-descriptor";
    private static final String PROPERTIES_SUFFIX = "-platform-properties";

    private final List<ArtifactPattern> excludePatterns;
    private final List<String> excludeScopes;
    private final boolean alphabetically;

    public PlatformBomFlattener(List<String> excludeArtifactKeys, List<String> excludeScopes, boolean alphabetically) {
        List<ArtifactPattern> patterns = new ArrayList<>(excludeArtifactKeys.size());
        for (String key : excludeArtifactKeys) {
            patterns.add(ArtifactPattern.of(key));
        }
        this.excludePatterns = patterns;
        this.excludeScopes = excludeScopes;
        this.alphabetically = alphabetically;
    }

    public Model flatten(Model effective) {
        DependencyManagement dm = new DependencyManagement();
        Map<String, Dependency> sorted = new TreeMap<>();

        if (effective.getDependencyManagement() != null) {
            for (Dependency source : effective.getDependencyManagement().getDependencies()) {
                Dependency dep = normalize(source);
                String scope = dep.getScope();
                if (scope != null && excludeScopes.contains(scope)) {
                    continue;
                }
                if (isExcluded(dep)) {
                    continue;
                }
                if (dep.getArtifactId().endsWith(DESCRIPTOR_SUFFIX) || dep.getArtifactId().endsWith(PROPERTIES_SUFFIX)) {
                    // platform descriptor and properties go first, in their original order
                    dm.addDependency(dep);
                    continue;
                }
                add(dm, sorted, dep);
                if ("tests".equals(dep.getClassifier()) && "test-jar".equals(dep.getType())) {
                    // The classifier is often omitted for test-jar constraints in BOMs, in which case
                    // it is filled in implicitly: keep the constraint without it too.
                    Dependency noClassifier = dep.clone();
                    noClassifier.setClassifier(null);
                    add(dm, sorted, noClassifier);
                }
            }
        }
        for (Dependency dep : sorted.values()) {
            dm.addDependency(dep);
        }

        Model flat = new Model();
        flat.setModelVersion("4.0.0");
        flat.setGroupId(effective.getGroupId());
        flat.setArtifactId(effective.getArtifactId());
        flat.setVersion(effective.getVersion());
        flat.setPackaging(effective.getPackaging());
        flat.setName(effective.getName());
        flat.setDescription(effective.getDescription());
        flat.setUrl(effective.getUrl());
        flat.setLicenses(effective.getLicenses());
        flat.setDevelopers(effective.getDevelopers());
        flat.setScm(effective.getScm());
        flat.setIssueManagement(effective.getIssueManagement());
        flat.setDistributionManagement(effective.getDistributionManagement());
        flat.setDependencyManagement(dm);
        return flat;
    }

    private void add(DependencyManagement dm, Map<String, Dependency> sorted, Dependency dep) {
        if (alphabetically) {
            sorted.put(key(dep), dep);
        } else {
            dm.addDependency(dep);
        }
    }

    private static String key(Dependency d) {
        return d.getGroupId() + ":" + d.getArtifactId() + ":" + (d.getClassifier() == null ? "" : d.getClassifier())
                + ":" + d.getType();
    }

    /** Applies what the Maven resolver does when it reads a managed dependency as an artifact. */
    private static Dependency normalize(Dependency source) {
        Dependency dep = source.clone();
        if (dep.getClassifier() == null || dep.getClassifier().isEmpty()) {
            String implied = switch (dep.getType()) {
                case "test-jar" -> "tests";
                case "javadoc" -> "javadoc";
                case "java-source" -> "sources";
                case "ejb-client" -> "client";
                default -> null;
            };
            dep.setClassifier(implied);
        }
        if ("compile".equals(dep.getScope()) || (dep.getScope() != null && dep.getScope().isEmpty())) {
            dep.setScope(null);
        }
        if (!dep.isOptional()) {
            dep.setOptional((String) null);
        }
        List<Exclusion> exclusions = new ArrayList<>(dep.getExclusions().size());
        for (Exclusion e : dep.getExclusions()) {
            Exclusion copy = new Exclusion();
            copy.setGroupId(e.getGroupId());
            copy.setArtifactId(e.getArtifactId());
            exclusions.add(copy);
        }
        dep.setExclusions(exclusions);
        dep.setSystemPath(null);
        return dep;
    }

    private boolean isExcluded(Dependency d) {
        for (ArtifactPattern p : excludePatterns) {
            if (p.matches(d.getGroupId(), d.getArtifactId(), d.getClassifier(), d.getType(), d.getVersion())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code groupId:artifactId[:classifier[:type[:version]]]}, each element optionally a glob
     * ({@code *} and {@code ?}); missing trailing elements match anything.
     */
    private record ArtifactPattern(List<Pattern> elements) {

        static ArtifactPattern of(String spec) {
            String[] parts = spec.split(":", -1);
            if (parts.length < 2 || parts.length > 5) {
                throw new IllegalArgumentException("Unsupported artifact key pattern '" + spec + "'");
            }
            List<Pattern> elements = new ArrayList<>(parts.length);
            for (String part : parts) {
                elements.add(glob(part));
            }
            return new ArtifactPattern(elements);
        }

        boolean matches(String groupId, String artifactId, String classifier, String type, String version) {
            String[] values = {groupId, artifactId, classifier == null ? "" : classifier, type, version};
            for (int i = 0; i < elements.size(); i++) {
                if (!elements.get(i).matcher(values[i] == null ? "" : values[i]).matches()) {
                    return false;
                }
            }
            return true;
        }

        private static Pattern glob(String glob) {
            StringBuilder regex = new StringBuilder();
            for (char c : glob.toCharArray()) {
                switch (c) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    default -> regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return Pattern.compile(regex.toString());
        }
    }
}
