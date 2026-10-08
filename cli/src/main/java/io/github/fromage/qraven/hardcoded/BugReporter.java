package io.github.fromage.qraven.hardcoded;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Interactive {@code --report-bug} mode: collects what a maintainer needs to reproduce a build
 * problem, shows the user exactly what will be in the zip, and only then creates it and opens an
 * issue on the qraven issue tracker.
 * <p>
 * Two modes, depending on whether the project is a git repository with a reachable upstream:
 * <ul>
 * <li><b>git</b>: remote URL and base commit (merge-base with the upstream branch), plus
 * optionally a patch of the user's changes (committed-but-unpushed, uncommitted and untracked);</li>
 * <li><b>fallback</b>: all {@code pom.xml} files and a listing of the project's files.</li>
 * </ul>
 * In both modes the logs, the generated build sources and the environment are included.
 * The project's sources are never included, except as the opt-in patch.
 */
public class BugReporter {

    static final String REPO = "FroMage/qraven";
    static final String ISSUES_URL = "https://github.com/" + REPO + "/issues/new";
    /** GitHub refuses attachments over 25 MB on free accounts. */
    static final long GITHUB_ATTACHMENT_LIMIT = 25L * 1024 * 1024;
    static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    static final int MAX_LISTING_ENTRIES = 50_000;
    static final int MAX_URL_BODY_CHARS = 6000;
    private static final int MAX_LOG_TAIL_LINES = 60;
    private static final int MAX_FILES_LISTED_PER_GROUP = 15;

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "node_modules", ".idea", ".gradle");

    /** File names that must never end up in a report, whatever the mode. */
    private static final List<String> SENSITIVE_GLOBS = List.of(
            ".env", ".env.*", "*.pem", "*.key", "*.jks", "*.p12", "*.pfx", "*.keystore",
            "id_rsa*", "id_ed25519*", "id_ecdsa*", "id_dsa*", "settings.xml", "settings-security.xml",
            ".netrc", ".npmrc", ".pypirc", "credentials*", "*.gpg", "*.asc");

    /** Where the issue gets created; replaceable so tests (and non-interactive callers) don't touch the network. */
    public interface Submitter {
        /** Submits the issue; returns a description of what was done, for the user. */
        String submit(String title, String body, Path zip) throws IOException, InterruptedException;
    }

    /** One file (or generated text) that will be in the zip. */
    record Entry(String group, String zipPath, Path file, byte[] content, long size) {
    }

    /** What would go in the zip, and what was left out. */
    static final class Contents {
        final List<Entry> entries = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        /** Human-readable description of how the project state is conveyed. */
        String mode;
        /** Set in git mode only. */
        GitInfo git;

        long totalSize() {
            return entries.stream().mapToLong(Entry::size).sum();
        }

        Map<String, List<Entry>> byGroup() {
            Map<String, List<Entry>> groups = new LinkedHashMap<>();
            for (Entry e : entries) {
                groups.computeIfAbsent(e.group(), k -> new ArrayList<>()).add(e);
            }
            return groups;
        }
    }

    record GitInfo(Path root, String remoteName, String remoteUrl, String upstream, String baseCommit,
            List<String> changedFiles, List<String> untrackedFiles, String patch) {
    }

    private final Path projectDir;
    private final Path outputDir;
    private final BufferedReader in;
    private final PrintStream out;
    private final Submitter submitter;
    private final String qravenVersion;
    private final List<String> commandLineArgs;

    public BugReporter(Path projectDir, Path outputDir, BufferedReader in, PrintStream out,
            Submitter submitter, String qravenVersion, List<String> commandLineArgs) {
        this.projectDir = projectDir;
        this.outputDir = outputDir;
        this.in = in;
        this.out = out;
        this.submitter = submitter;
        this.qravenVersion = qravenVersion;
        this.commandLineArgs = commandLineArgs;
    }

    /** Runs the interactive flow. Returns the process exit code. */
    public int run() throws IOException, InterruptedException {
        out.println("Qraven bug report");
        out.println();
        out.println("This will report a qraven issue (a broken build, a build that differs from Maven, ...)");
        out.println("on https://github.com/" + REPO + "/issues.");
        out.println();
        out.println("PRIVACY: the issue tracker is PUBLIC. A zip of diagnostic files from this project");
        out.println("(" + projectDir + ") will be created, and you will be asked to attach it to the issue.");
        out.println("You will see the exact list of files before anything is created, and you can cancel");
        out.println("at any point. Logs contain absolute paths on this machine (including your user name).");
        out.println();
        if (!confirm("Do you want to report a qraven issue?")) {
            out.println("Cancelled.");
            return 0;
        }

        out.println();
        out.println("Describe the problem: what you ran, what you expected, what happened.");
        out.println("End with an empty line.");
        String description = readDescription();
        if (description.isBlank()) {
            out.println("No description given, cancelled.");
            return 1;
        }

        out.println();
        out.println("Collecting files...");
        Contents contents = collect();

        if (contents.git != null && hasPatch(contents.git)) {
            out.println();
            out.println("Your tree differs from the upstream commit " + shortCommit(contents.git.baseCommit)
                    + " (" + contents.git.remoteUrl + "):");
            listSome(contents.git.changedFiles, "modified/committed");
            listSome(contents.git.untrackedFiles, "untracked");
            out.println("A patch of these changes lets a maintainer reproduce your exact tree. It contains");
            out.println("your actual code changes, and will be public if you attach it to the issue.");
            if (confirm("Include a patch of your changes?")) {
                addPatch(contents);
            } else {
                contents.notes.add("Your changes were NOT included (you declined). Only the upstream commit is recorded.");
            }
        }

        String envText = environmentText(contents);
        contents.entries.add(textEntry("Environment", "environment.txt", envText));
        contents.entries.add(textEntry("Description", "description.txt", description + "\n"));

        Path zip = outputDir.resolve("qraven-report-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
        while (true) {
            printContents(contents, zip);
            String answer = ask("Create this zip? [y]es / [N]o / [l]ist every file: ").toLowerCase();
            if (answer.startsWith("l")) {
                for (Entry e : contents.entries) {
                    out.println("  " + e.zipPath() + "  (" + size(e.size()) + ")");
                }
                out.println();
                continue;
            }
            if (!answer.startsWith("y")) {
                out.println("Cancelled, nothing was created.");
                return 0;
            }
            break;
        }

        writeZip(contents, zip);
        long zipSize = Files.size(zip);
        out.println("Created " + zip + " (" + size(zipSize) + ")");
        if (zipSize > GITHUB_ATTACHMENT_LIMIT) {
            out.println("WARNING: this is over GitHub's 25 MB attachment limit; you will need to trim it or share it another way.");
        }

        String title = title(description);
        String body = issueBody(description, envText, contents);
        out.println();
        out.println("Issue to be created on https://github.com/" + REPO + ":");
        out.println("  Title: " + title);
        out.println("  Body:");
        for (String line : body.split("\n", -1)) {
            out.println("    " + line);
        }
        out.println();
        if (!confirm("Create the issue? (The zip is NOT uploaded automatically.)")) {
            out.println("Not creating the issue. The zip is still at " + zip);
            return 0;
        }
        out.println(submitter.submit(title, body, zip));
        out.println();
        out.println("Please drag and drop this file into the issue (edit the issue on GitHub, or add a comment):");
        out.println("  " + zip);
        return 0;
    }

    // ---------------------------------------------------------------- collection

    Contents collect() throws IOException {
        Contents c = new Contents();
        collectLogs(c);
        collectGeneratedSources(c);
        collectState(c);
        GitInfo git = readGitInfo();
        if (git != null) {
            c.git = git;
            c.mode = "git: upstream " + git.upstream + " (" + git.remoteUrl + ") at " + git.baseCommit;
            c.entries.add(textEntry("Git", "git.txt", gitText(git)));
        } else {
            c.mode = "fallback: not a git repository, or no upstream branch could be found";
            c.notes.add("No git upstream found: including all pom.xml files and a file listing instead.");
            collectPoms(c);
            c.entries.add(textEntry("File listing", "file-listing.txt", fileListing()));
        }
        return c;
    }

    private void collectLogs(Contents c) throws IOException {
        if (!Files.isDirectory(outputDir)) {
            c.notes.add(outputDir + " does not exist: no build has been run here yet.");
            return;
        }
        try (var files = Files.list(outputDir)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".log"))::iterator) {
                addFile(c, "Logs", "logs/" + p.getFileName(), p);
            }
        }
        Path previous = outputDir.resolve("logs");
        if (Files.isDirectory(previous)) {
            try (var files = Files.list(previous)) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".log"))::iterator) {
                    addFile(c, "Previous logs", "logs/previous/" + p.getFileName(), p);
                }
            }
        }
    }

    /** What generation learned about each module, which the build reads; the closest thing to the build's input. */
    private void collectState(Contents c) throws IOException {
        Path state = outputDir.resolve("state.cbor");
        if (Files.isRegularFile(state)) {
            addFile(c, "Generation state (qraven --dump-module <artifactId> prints a module of it)",
                    "generated/state.cbor", state);
        }
    }

    private void collectGeneratedSources(Contents c) throws IOException {
        for (String name : List.of("build-src", "bootstrap-src")) {
            Path dir = outputDir.resolve(name);
            if (Files.isDirectory(dir)) {
                walk(dir, p -> {
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    addFile(c, "Generated build sources", "generated/" + name + "/" + rel, p);
                }, false);
            }
        }
    }

    private void collectPoms(Contents c) throws IOException {
        walk(projectDir, p -> {
            if (p.getFileName().toString().equals("pom.xml")) {
                String rel = projectDir.relativize(p).toString().replace('\\', '/');
                addFile(c, "Poms", "poms/" + rel, p);
            }
        }, true);
    }

    private String fileListing() throws IOException {
        StringBuilder sb = new StringBuilder();
        int[] count = { 0 };
        walk(projectDir, p -> {
            if (count[0]++ < MAX_LISTING_ENTRIES && !isSensitive(p)) {
                sb.append(projectDir.relativize(p).toString().replace('\\', '/')).append('\n');
            }
        }, true);
        if (count[0] > MAX_LISTING_ENTRIES) {
            sb.append("... truncated, ").append(count[0] - MAX_LISTING_ENTRIES).append(" more files\n");
        }
        return sb.toString();
    }

    private interface FileVisitor {
        void file(Path p) throws IOException;
    }

    private void walk(Path root, FileVisitor visitor, boolean skipBuildDirs) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (skipBuildDirs && !dir.equals(root) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    visitor.file(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void addFile(Contents c, String group, String zipPath, Path file) throws IOException {
        if (isSensitive(file)) {
            c.notes.add("Excluded (looks sensitive): " + file.getFileName());
            return;
        }
        long size = Files.size(file);
        if (size > MAX_FILE_SIZE) {
            c.notes.add("Excluded (over " + size(MAX_FILE_SIZE) + "): " + file.getFileName() + " (" + size(size) + ")");
            return;
        }
        c.entries.add(new Entry(group, zipPath, file, null, size));
    }

    static boolean isSensitive(Path file) {
        String name = file.getFileName().toString();
        for (String glob : SENSITIVE_GLOBS) {
            if (java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob)
                    .matches(Path.of(name))) {
                return true;
            }
        }
        return false;
    }

    private static Entry textEntry(String group, String zipPath, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new Entry(group, zipPath, null, bytes, bytes.length);
    }

    // ---------------------------------------------------------------- git

    GitInfo readGitInfo() {
        Optional<String> top = git(projectDir, "rev-parse", "--show-toplevel");
        if (top.isEmpty()) {
            return null;
        }
        Path root = Path.of(top.get());
        String upstream = git(root, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
                .orElse(null);
        if (upstream == null) {
            // no tracking branch: fall back to the default branch of origin
            upstream = git(root, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").orElse(null);
        }
        if (upstream == null || !upstream.contains("/")) {
            return null;
        }
        String remoteName = upstream.substring(0, upstream.indexOf('/'));
        Optional<String> url = git(root, "remote", "get-url", remoteName);
        Optional<String> base = git(root, "merge-base", "HEAD", upstream);
        if (url.isEmpty() || base.isEmpty()) {
            return null;
        }
        List<String> changed = lines(git(root, "diff", "--name-only", base.get(), "--", ".")
                .orElse(""));
        List<String> untracked = lines(git(root, "ls-files", "--others", "--exclude-standard").orElse(""));
        changed.removeIf(f -> isSensitive(Path.of(f)));
        untracked.removeIf(f -> isSensitive(Path.of(f)));
        return new GitInfo(root, remoteName, url.get(), upstream, base.get(), changed, untracked, null);
    }

    private static boolean hasPatch(GitInfo g) {
        return !g.changedFiles().isEmpty() || !g.untrackedFiles().isEmpty();
    }

    private void addPatch(Contents c) throws IOException {
        GitInfo g = c.git;
        List<String> args = new ArrayList<>(List.of("diff", "--no-color", g.baseCommit(), "--", "."));
        for (String glob : SENSITIVE_GLOBS) {
            args.add(":(exclude,glob)**/" + glob);
        }
        String patch = git(g.root(), args.toArray(String[]::new)).orElse("");
        if (!patch.isEmpty()) {
            Entry e = textEntry("Your changes (patch)", "changes/changes.patch", patch + "\n");
            if (e.size() > MAX_FILE_SIZE) {
                c.notes.add("Patch excluded (over " + size(MAX_FILE_SIZE) + "): " + size(e.size()));
            } else {
                c.entries.add(e);
            }
        }
        for (String f : g.untrackedFiles()) {
            addFile(c, "Your changes (untracked files)", "changes/untracked/" + f, g.root().resolve(f));
        }
    }

    private static String gitText(GitInfo g) {
        return "remote: " + g.remoteName() + " " + g.remoteUrl() + "\n"
                + "upstream: " + g.upstream() + "\n"
                + "base commit (merge-base of HEAD and upstream): " + g.baseCommit() + "\n"
                + "modified/committed files: " + g.changedFiles().size() + "\n"
                + "untracked files: " + g.untrackedFiles().size() + "\n";
    }

    private static Optional<String> git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return exec(dir, cmd).filter(s -> !s.isEmpty());
    }

    static Optional<String> exec(Path dir, List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            byte[] bytes = p.getInputStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            if (p.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(new String(bytes, StandardCharsets.UTF_8).stripTrailing());
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static List<String> lines(String s) {
        List<String> l = new ArrayList<>();
        for (String line : s.split("\n")) {
            if (!line.isBlank()) {
                l.add(line);
            }
        }
        return l;
    }

    // ---------------------------------------------------------------- output

    private String environmentText(Contents c) {
        StringBuilder sb = new StringBuilder();
        sb.append("qraven: ").append(qravenVersion).append('\n');
        sb.append("java: ").append(System.getProperty("java.version")).append(" (")
                .append(System.getProperty("java.vendor")).append(")\n");
        sb.append("os: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n');
        sb.append("command line: qraven ").append(String.join(" ", commandLineArgs)).append('\n');
        sb.append("project state: ").append(c.mode).append('\n');
        return sb.toString();
    }

    private void printContents(Contents c, Path zip) {
        out.println();
        out.println("The zip " + zip.getFileName() + " will contain:");
        for (Map.Entry<String, List<Entry>> group : c.byGroup().entrySet()) {
            List<Entry> es = group.getValue();
            long total = es.stream().mapToLong(Entry::size).sum();
            out.println("  " + group.getKey() + ": " + es.size() + " file" + (es.size() == 1 ? "" : "s")
                    + ", " + size(total));
            if (es.size() <= MAX_FILES_LISTED_PER_GROUP) {
                for (Entry e : es) {
                    out.println("      " + e.zipPath());
                }
            }
        }
        out.println("  Total: " + size(c.totalSize()) + " uncompressed (the zip will be smaller)");
        for (String note : c.notes) {
            out.println("  Note: " + note);
        }
        out.println();
        out.println("Your project's source code is NOT included" + (c.git != null
                ? " (other than the patch, if you chose to include it)." : "."));
    }

    private void listSome(List<String> files, String what) {
        if (files.isEmpty()) {
            return;
        }
        out.println("  " + files.size() + " " + what + " file" + (files.size() == 1 ? "" : "s") + ":");
        files.stream().limit(MAX_FILES_LISTED_PER_GROUP).forEach(f -> out.println("      " + f));
        if (files.size() > MAX_FILES_LISTED_PER_GROUP) {
            out.println("      ... and " + (files.size() - MAX_FILES_LISTED_PER_GROUP) + " more");
        }
    }

    private void writeZip(Contents c, Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os)) {
            for (Entry e : c.entries) {
                z.putNextEntry(new ZipEntry(e.zipPath()));
                if (e.content() != null) {
                    z.write(e.content());
                } else {
                    try (InputStream is = Files.newInputStream(e.file())) {
                        is.transferTo(z);
                    }
                }
                z.closeEntry();
            }
        }
    }

    static String title(String description) {
        String first = description.strip().split("\n", 2)[0].strip();
        if (first.length() > 80) {
            first = first.substring(0, 77) + "...";
        }
        return "Build problem: " + first;
    }

    String issueBody(String description, String envText, Contents c) {
        StringBuilder sb = new StringBuilder();
        sb.append(description.strip()).append("\n\n");
        sb.append("### Environment\n```\n").append(envText).append("```\n\n");
        if (c.git != null) {
            sb.append("### Git state\n```\n").append(gitText(c.git)).append("```\n\n");
        }
        String tail = failureTail();
        if (tail != null) {
            sb.append("### End of build.log\n```\n").append(tail).append("\n```\n\n");
        }
        sb.append("_Reported with `qraven --report-bug`. A zip of diagnostics is attached below._\n");
        return sb.toString();
    }

    /** The build failure summary if there is one, otherwise the last lines of build.log. */
    private String failureTail() {
        Path log = outputDir.resolve("build.log");
        if (!Files.isRegularFile(log)) {
            return null;
        }
        try {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            int summary = all.lastIndexOf("=== BUILD FAILURE SUMMARY ===");
            int from = summary >= 0 ? summary : Math.max(0, all.size() - MAX_LOG_TAIL_LINES);
            List<String> tail = all.subList(from, all.size());
            if (tail.size() > MAX_LOG_TAIL_LINES) {
                tail = tail.subList(0, MAX_LOG_TAIL_LINES);
            }
            return String.join("\n", tail);
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- interaction

    private String readDescription() throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    private String ask(String prompt) throws IOException {
        out.print(prompt);
        out.flush();
        String line = in.readLine();
        return line == null ? "" : line.strip();
    }

    private boolean confirm(String question) throws IOException {
        return ask(question + " [y/N] ").toLowerCase().startsWith("y");
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String shortCommit(String commit) {
        return commit.length() > 10 ? commit.substring(0, 10) : commit;
    }

    // ---------------------------------------------------------------- submitting the issue

    /** Uses {@code gh} if it is installed and logged in, otherwise opens a pre-filled issue form in the browser. */
    public static Submitter defaultSubmitter(PrintStream out) {
        return (title, body, zip) -> {
            if (exec(Path.of("."), List.of("gh", "auth", "status")).isPresent()) {
                Path bodyFile = Files.createTempFile("qraven-issue", ".md");
                try {
                    Files.writeString(bodyFile, body, StandardCharsets.UTF_8);
                    Optional<String> url = exec(Path.of("."), List.of("gh", "issue", "create", "-R", REPO,
                            "--title", title, "--body-file", bodyFile.toString()));
                    if (url.isPresent()) {
                        return "Created issue: " + url.get();
                    }
                    out.println("gh failed to create the issue, opening the browser instead.");
                } finally {
                    Files.deleteIfExists(bodyFile);
                }
            }
            String url = prefilledUrl(title, body);
            boolean opened = openBrowser(url);
            return (opened ? "Opened your browser on the issue form (review it and click \"Submit new issue\"):\n"
                    : "Could not open a browser. Open this URL to create the issue:\n") + url;
        };
    }

    public static String prefilledUrl(String title, String body) {
        String marker = "\n\n_(truncated, see the attached zip)_";
        String b = body;
        if (encode(b).length() > MAX_URL_BODY_CHARS) {
            while (encode(b + marker).length() > MAX_URL_BODY_CHARS && b.length() > 100) {
                b = b.substring(0, (int) (b.length() * 0.9));
            }
            b = b + marker;
        }
        return ISSUES_URL + "?title=" + encode(title) + "&body=" + encode(b);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> cmd = os.contains("mac") ? List.of("open", url)
                : os.contains("win") ? List.of("cmd", "/c", "start", "", url.replace("&", "^&"))
                        : List.of("xdg-open", url);
        try {
            // don't wait: some launchers stay alive as long as the browser does
            new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
