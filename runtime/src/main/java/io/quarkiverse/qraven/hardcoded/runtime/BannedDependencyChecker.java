package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BannedDependencyChecker {

    private final Set<String> bannedExact = new HashSet<>();
    private final Set<String> bannedGroupAll = new HashSet<>();
    private final List<String[]> bannedPrefixes = new ArrayList<>();

    public BannedDependencyChecker(Path projectRoot) {
        Path rulesDir = projectRoot.resolve(
                "independent-projects/enforcer-rules/src/main/resources/enforcer-rules");
        loadRules(rulesDir.resolve("quarkus-banned-dependencies.xml"));
        loadRules(rulesDir.resolve("quarkus-banned-dependencies-okhttp.xml"));
    }

    private void loadRules(Path xmlFile) {
        if (!Files.exists(xmlFile)) return;
        try {
            String xml = Files.readString(xmlFile, StandardCharsets.UTF_8);
            Pattern pattern = Pattern.compile("<exclude>([^<]+)</exclude>");
            Matcher matcher = pattern.matcher(xml);
            while (matcher.find()) {
                String exclude = matcher.group(1).trim();
                int colon = exclude.indexOf(':');
                if (colon < 0) continue;
                String groupId = exclude.substring(0, colon);
                String artifactId = exclude.substring(colon + 1);
                if ("*".equals(artifactId)) {
                    bannedGroupAll.add(groupId);
                } else if (artifactId.endsWith("*")) {
                    bannedPrefixes.add(new String[] { groupId,
                            artifactId.substring(0, artifactId.length() - 1) });
                } else {
                    bannedExact.add(exclude);
                }
            }
        } catch (IOException e) {
            // skip
        }
    }

    public List<String> check(List<String> classpathEntries) {
        List<String> violations = new ArrayList<>();
        String m2 = System.getProperty("user.home") + "/.m2/repository/";
        for (String entry : classpathEntries) {
            if (!entry.startsWith(m2)) continue;
            String relative = entry.substring(m2.length());
            int lastSlash = relative.lastIndexOf('/');
            if (lastSlash < 0) continue;
            String beforeFile = relative.substring(0, lastSlash);
            int versionSlash = beforeFile.lastIndexOf('/');
            if (versionSlash < 0) continue;
            String beforeVersion = beforeFile.substring(0, versionSlash);
            int artifactSlash = beforeVersion.lastIndexOf('/');
            if (artifactSlash < 0) continue;
            String artifactId = beforeVersion.substring(artifactSlash + 1);
            String groupId = beforeVersion.substring(0, artifactSlash).replace('/', '.');

            if (isBanned(groupId, artifactId)) {
                violations.add(groupId + ":" + artifactId);
            }
        }
        return violations;
    }

    private boolean isBanned(String groupId, String artifactId) {
        if (bannedGroupAll.contains(groupId)) return true;
        if (bannedExact.contains(groupId + ":" + artifactId)) return true;
        for (String[] prefix : bannedPrefixes) {
            if (groupId.equals(prefix[0]) && artifactId.startsWith(prefix[1])) {
                return true;
            }
        }
        return false;
    }
}
