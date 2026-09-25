package com.aicreviewer.git;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GitRepositoryClient {
    private static final int PAGE_SIZE = 100;
    private final Set<String> allowedHosts;
    private final URI githubApi;
    private final String token;
    private final URI tokenOrigin;
    private final int maxPages;
    private final int maxDiffBytes;
    private final int operationTimeoutSeconds;
    private final ThreadLocal<Long> operationDeadline = new ThreadLocal<>();
    private final SafeHttpTransport http;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public GitRepositoryClient(@Value("${app.git.allowed-hosts:github.com}") Set<String> allowedHosts,
            @Value("${app.git.github-api-url:https://api.github.com}") String githubApi,
            @Value("${app.git.token:}") String token,
            @Value("${app.git.token-host:github.com}") String tokenHost,
            @Value("${app.git.token-origin:}") String tokenOrigin,
            @Value("${app.git.timeout-seconds:30}") int timeoutSeconds,
            @Value("${app.git.max-history-pages:1000}") int maxPages,
            @Value("${app.git.max-diff-bytes:262144}") int maxDiffBytes,
            @Value("${app.git.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${app.git.operation-timeout-seconds:300}") int operationTimeoutSeconds) {
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.githubApi = SafeHttpTransport.baseUri(githubApi);
        this.token = SafeHttpTransport.credential(token);
        this.tokenOrigin = credentialOrigin(tokenOrigin.isBlank()
                ? "https://" + tokenHost.strip().toLowerCase(java.util.Locale.ROOT) : tokenOrigin);
        if (maxPages < 1 || maxPages > 10000 || maxDiffBytes < 1024 || maxDiffBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid Git pagination or diff size limit");
        }
        this.maxPages = maxPages;
        this.maxDiffBytes = maxDiffBytes;
        if (operationTimeoutSeconds < 1 || operationTimeoutSeconds > 3600) throw new IllegalArgumentException("Invalid Git operation time limit");
        this.operationTimeoutSeconds = operationTimeoutSeconds;
        this.http = new SafeHttpTransport(Duration.ofSeconds(timeoutSeconds), maxResponseBytes);
    }

    public List<GitCommit> commits(RepositoryUrl repository, String branch, String lastReviewedSha, int limit) {
        operationDeadline.set(System.nanoTime() + Duration.ofSeconds(operationTimeoutSeconds).toNanos());
        try {
            return collectCommits(repository, branch, lastReviewedSha, limit);
        } finally {
            operationDeadline.remove();
        }
    }

    private List<GitCommit> collectCommits(RepositoryUrl repository, String branch, String lastReviewedSha, int limit) {
        RepositoryUrl checked = RepositoryUrl.parse(repository.normalizedUrl(), allowedHosts);
        if (!checked.equals(repository)) throw new IllegalArgumentException("Repository metadata does not match its URL");
        credentialAllowed(repository);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Commit batch limit must be between 1 and 1000");
        if (lastReviewedSha != null && !validSha(lastReviewedSha)) throw new IllegalArgumentException("Invalid review cursor");
        if (branch != null && (branch.isBlank() || branch.length() > 255 || branch.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid review branch");
        }
        String base = api(repository);
        boolean github = github(repository);
        String history = base + (github ? "/commits" : "/repository/commits");
        String refParam = github ? "sha" : "ref_name";
        JsonNode initial = get(repository, history + "?per_page=1" + (branch == null ? "" : "&" + refParam + "=" + encode(branch))).body();
        requireArray(initial);
        if (initial.isEmpty()) {
            if (lastReviewedSha != null) throw new IntegrationException("Review cursor is missing from repository history");
            return List.of();
        }
        String head = sha(initial.get(0), github ? "sha" : "id");
        Map<String, CommitMeta> graph = new LinkedHashMap<>();
        long metadataBytes = 0;
        for (int page = 1; ; page++) {
            if (page > maxPages) throw new IntegrationException("Git history exceeds configured pagination budget");
            Page response = get(repository, history + "?" + refParam + "=" + head + "&per_page=" + PAGE_SIZE
                    + "&page=" + page + (github ? "" : "&order=topo"));
            requireArray(response.body());
            for (JsonNode node : response.body()) {
                CommitMeta meta = metadata(node, github);
                metadataBytes += meta.message().getBytes(StandardCharsets.UTF_8).length + 128L + meta.parents().size() * 64L;
                if (metadataBytes > 32L * 1024 * 1024) throw new IntegrationException("Git history metadata exceeds the memory safety budget");
                if (graph.putIfAbsent(meta.sha(), meta) != null) throw new IntegrationException("Git returned duplicate commits across history pages");
            }
            if (!response.next() && response.body().size() < PAGE_SIZE) break;
        }
        List<CommitMeta> ordered = oldestFirst(graph, head);
        Set<String> checkpointShas = new HashSet<>();
        for (String current = head; current != null; ) {
            checkpointShas.add(current);
            CommitMeta meta = graph.get(current);
            current = meta.parents().isEmpty() ? null : meta.parents().getFirst();
        }
        int start = 0;
        if (lastReviewedSha != null) {
            if (!checkpointShas.contains(lastReviewedSha)) {
                throw new IntegrationException("Review cursor is no longer on the branch first-parent history; manual history reconciliation is required");
            }
            start = -1;
            for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).sha().equals(lastReviewedSha)) start = i + 1;
            if (start == -1) throw new IntegrationException("Review cursor is missing; repository history may have been rewritten");
        }
        List<GitCommit> result = new ArrayList<>();
        int end = Math.min(ordered.size(), start + limit);
        while (end > start && !checkpointShas.contains(ordered.get(end - 1).sha())) end--;
        if (end == start && start < ordered.size()) {
            throw new IntegrationException("Commit batch limit is too small to review the next complete merge group");
        }
        // Keep manifests only for this batch. No source files are written to disk.
        Map<String, Map<String, String>> trees = new LinkedHashMap<>(4, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) { return size() > 2; }
        };
        for (int i = start; i < end; i++) {
            CommitMeta meta = ordered.get(i);
            result.add(github ? githubCommit(repository, base, meta) : gitlabCommit(repository, base, meta, trees));
        }
        return List.copyOf(result);
    }

    private GitCommit githubCommit(RepositoryUrl repository, String base, CommitMeta meta) {
        StringBuilder diff = new StringBuilder();
        Set<String> filenames = new HashSet<>();
        long additions = 0, deletions = 0;
        int expectedAdds = -1, expectedDeletes = -1;
        for (int page = 1; ; page++) {
            if (page > Math.min(maxPages, 30)) throw new IntegrationException("GitHub commit diff exceeds pagination limit");
            Page response = get(repository, base + "/commits/" + meta.sha() + "?per_page=100&page=" + page);
            JsonNode body = response.body();
            if (!sha(body, "sha").equals(meta.sha())) throw new IntegrationException("Git returned an unexpected commit");
            expectedAdds = nonnegative(body.path("stats"), "additions");
            expectedDeletes = nonnegative(body.path("stats"), "deletions");
            JsonNode files = body.path("files");
            requireArray(files);
            for (JsonNode file : files) {
                String path = filePath(file, "filename");
                if (!filenames.add(path)) throw new IntegrationException("GitHub returned duplicate diff files");
                String patch = field(file, "patch", maxDiffBytes);
                int adds = nonnegative(file, "additions"), deletes = nonnegative(file, "deletions");
                verifyPatchCounts(patch, adds, deletes);
                additions += adds;
                deletions += deletes;
                String oldPath = file.hasNonNull("previous_filename") ? filePath(file, "previous_filename") : path;
                append(diff, "diff --git a/" + oldPath + " b/" + path + "\n" + patch + "\n");
            }
            if (!response.next() && files.size() < PAGE_SIZE) break;
            // GitHub caps its JSON file listing at 3,000; never accept reaching that ceiling.
            if (filenames.size() >= 3000) throw new IntegrationException("GitHub commit file limit reached; complete diff cannot be verified");
        }
        if (additions != expectedAdds || deletions != expectedDeletes) {
            throw new IntegrationException("GitHub diff is incomplete compared with commit statistics");
        }
        return new GitCommit(meta.sha(), meta.author(), meta.message(), diff.toString());
    }

    private GitCommit gitlabCommit(RepositoryUrl repository, String base, CommitMeta meta,
            Map<String, Map<String, String>> trees) {
        JsonNode details = get(repository, base + "/repository/commits/" + meta.sha() + "?stats=true").body();
        if (!sha(details, "id").equals(meta.sha())) throw new IntegrationException("Git returned an unexpected commit");
        int additions = nonnegative(details.path("stats"), "additions");
        int deletions = nonnegative(details.path("stats"), "deletions");
        Map<String, String> current = trees.computeIfAbsent(meta.sha(), key -> gitlabTree(repository, base, key));
        Map<String, String> parent = meta.parents().isEmpty() ? Map.of()
                : trees.computeIfAbsent(meta.parents().getFirst(), key -> gitlabTree(repository, base, key));
        Set<String> expected = new HashSet<>(current.keySet());
        expected.addAll(parent.keySet());
        expected.removeIf(path -> java.util.Objects.equals(current.get(path), parent.get(path)));
        Set<String> covered = new HashSet<>();
        Set<String> seenFiles = new HashSet<>();
        StringBuilder diff = new StringBuilder();
        long actualAdds = 0, actualDeletes = 0;
        for (int page = 1; ; page++) {
            if (page > maxPages) throw new IntegrationException("GitLab diff exceeds configured pagination budget");
            Page response = get(repository, base + "/repository/commits/" + meta.sha()
                    + "/diff?unidiff=true&per_page=100&page=" + page);
            requireArray(response.body());
            for (JsonNode file : response.body()) {
                if (file.path("collapsed").asBoolean(false) || file.path("too_large").asBoolean(false)) {
                    throw new IntegrationException("GitLab omitted a collapsed or oversized file diff");
                }
                String oldPath = filePath(file, "old_path"), newPath = filePath(file, "new_path");
                if (!seenFiles.add(oldPath + "\n" + newPath)) throw new IntegrationException("GitLab returned duplicate diff files");
                if (!expected.contains(oldPath) && !expected.contains(newPath)) throw new IntegrationException("GitLab diff does not match commit trees");
                covered.add(oldPath);
                covered.add(newPath);
                String patch = field(file, "diff", maxDiffBytes);
                if (patch.isBlank() || patch.lines().anyMatch(line -> line.equals("GIT binary patch")
                        || (line.startsWith("Binary files ") && line.endsWith(" differ")))) {
                    throw new IntegrationException("GitLab returned an unavailable or non-text file diff");
                }
                actualAdds += lineCount(patch, '+');
                actualDeletes += lineCount(patch, '-');
                append(diff, "diff --git a/" + oldPath + " b/" + newPath + "\n" + patch + "\n");
            }
            if (!response.next() && response.body().size() < PAGE_SIZE) break;
        }
        if (!covered.containsAll(expected) || actualAdds != additions || actualDeletes != deletions) {
            throw new IntegrationException("GitLab diff is incomplete compared with commit trees or statistics");
        }
        return new GitCommit(meta.sha(), null, meta.message(), diff.toString());
    }

    private Map<String, String> gitlabTree(RepositoryUrl repository, String base, String sha) {
        Map<String, String> tree = new HashMap<>();
        Set<String> seen = new HashSet<>();
        long treeBytes = 0;
        for (int page = 1; ; page++) {
            if (page > maxPages) throw new IntegrationException("GitLab tree exceeds configured pagination budget");
            Page response = get(repository, base + "/repository/tree?ref=" + sha + "&recursive=true&per_page=100&page=" + page);
            requireArray(response.body());
            for (JsonNode entry : response.body()) {
                String path = filePath(entry, "path");
                treeBytes += path.getBytes(StandardCharsets.UTF_8).length + 128L;
                if (treeBytes > 16L * 1024 * 1024) throw new IntegrationException("GitLab tree exceeds the memory safety budget");
                if (!seen.add(path)) throw new IntegrationException("GitLab returned duplicate tree entries");
                String type = field(entry, "type", 20);
                if (!type.equals("tree")) tree.put(path, sha(entry, "id") + ":" + field(entry, "mode", 10));
            }
            if (!response.next() && response.body().size() < PAGE_SIZE) break;
        }
        return Map.copyOf(tree);
    }

    private List<CommitMeta> oldestFirst(Map<String, CommitMeta> graph, String head) {
        List<CommitMeta> result = new ArrayList<>();
        Set<String> complete = new HashSet<>(), visiting = new HashSet<>();
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        pending.push(new Visit(head, false));
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            if (complete.contains(visit.sha())) continue;
            CommitMeta meta = graph.get(visit.sha());
            if (meta == null) throw new IntegrationException("Git history is incomplete: a parent commit is missing");
            if (visit.expanded()) {
                visiting.remove(meta.sha());
                complete.add(meta.sha());
                result.add(meta);
            } else {
                if (!visiting.add(meta.sha())) throw new IntegrationException("Git returned a cyclic commit history");
                pending.push(new Visit(meta.sha(), true));
                List<String> parents = new ArrayList<>(meta.parents());
                Collections.reverse(parents);
                for (String parent : parents) if (!complete.contains(parent)) pending.push(new Visit(parent, false));
            }
        }
        if (result.size() != graph.size()) throw new IntegrationException("Git returned commits outside the pinned history");
        return result;
    }

    private CommitMeta metadata(JsonNode node, boolean github) {
        String sha = sha(node, github ? "sha" : "id");
        JsonNode parents = node.path(github ? "parents" : "parent_ids");
        requireArray(parents);
        List<String> parentIds = new ArrayList<>();
        for (JsonNode parent : parents) {
            String id = github ? sha(parent, "sha") : parent.asText("");
            if (!validSha(id) || parentIds.contains(id)) throw new IntegrationException("Git returned invalid commit parents");
            parentIds.add(id);
        }
        String author = github && node.path("author").path("login").isString()
                ? field(node.path("author"), "login", 100) : null;
        String message = field(github ? node.path("commit") : node, "message", 32768);
        return new CommitMeta(sha, author, message, List.copyOf(parentIds));
    }

    private Page get(RepositoryUrl repository, String url) {
        if (System.nanoTime() >= operationDeadline.get()) throw new IntegrationException("Git operation exceeded its time budget");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET().header("Accept", "application/json");
        if (github(repository)) request.header("X-GitHub-Api-Version", "2022-11-28");
        if (credentialAllowed(repository)) {
            if (github(repository)) request.header("Authorization", "Bearer " + token);
            else request.header("PRIVATE-TOKEN", token);
        }
        var response = http.exchange(request, "Git", Duration.ofNanos(operationDeadline.get() - System.nanoTime()));
        try {
            JsonNode body = json.readTree(response.body());
            boolean next = response.headers().firstValue("Link").orElse("").matches("(?s).*rel=\"?next\"?.*")
                    || !response.headers().firstValue("X-Next-Page").orElse("").isBlank();
            return new Page(body, next);
        } catch (RuntimeException ex) {
            throw new IntegrationException("Git returned invalid JSON");
        }
    }

    private String api(RepositoryUrl repository) {
        if (github(repository)) return githubApi + "/repos/" + repository.path();
        URI source = URI.create(repository.normalizedUrl());
        return source.getScheme() + "://" + source.getRawAuthority() + "/api/v4/projects/" + encode(repository.path());
    }

    private boolean credentialAllowed(RepositoryUrl repository) {
        if (token.isBlank() || !repository.host().equalsIgnoreCase(tokenOrigin.getHost())) return false;
        URI actual = URI.create(repository.normalizedUrl());
        if (!actual.getScheme().equalsIgnoreCase(tokenOrigin.getScheme()) || effectivePort(actual) != effectivePort(tokenOrigin)) {
            throw new IntegrationException("Repository origin does not match the configured Git credential origin; configure its trusted scheme, host and port explicitly");
        }
        return true;
    }

    private static URI credentialOrigin(String value) {
        URI origin = SafeHttpTransport.baseUri(value);
        if (origin.getPath() != null && !origin.getPath().isEmpty()) {
            throw new IllegalArgumentException("Git credential origin must contain only scheme, host and optional port");
        }
        return origin;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80);
    }

    private void append(StringBuilder target, String value) {
        target.append(value);
        if (target.length() > maxDiffBytes || target.toString().getBytes(StandardCharsets.UTF_8).length > maxDiffBytes) {
            throw new IntegrationException("Commit diff exceeds configured size limit");
        }
    }

    private static void verifyPatchCounts(String patch, int additions, int deletions) {
        if (patch.isBlank() || lineCount(patch, '+') != additions || lineCount(patch, '-') != deletions) {
            throw new IntegrationException("Git returned a missing or truncated text patch");
        }
    }

    private static long lineCount(String patch, char prefix) {
        // API patch hunks may contain added source lines beginning with +++; count only inside hunks.
        boolean inHunk = false;
        long count = 0;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("@@ ")) { inHunk = true; continue; }
            if (inHunk && !line.isEmpty() && line.charAt(0) == prefix) count++;
        }
        return count;
    }

    private static String field(JsonNode node, String name, int maxLength) {
        JsonNode value = node.path(name);
        if (!value.isString() || value.asText().length() > maxLength) throw new IntegrationException("Git returned an invalid or missing " + name + " field");
        return value.asText();
    }

    private static String filePath(JsonNode node, String name) {
        String value = field(node, name, 1024);
        if (value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains("\"") || value.contains(" b/")
                || value.chars().anyMatch(Character::isISOControl)
                || List.of(value.split("/", -1)).stream().anyMatch(part -> part.equals("..") || part.isEmpty())) {
            throw new IntegrationException("Git returned an unsupported or unsafe file path");
        }
        return value;
    }

    private static int nonnegative(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) throw new IntegrationException("Git returned invalid commit statistics");
        return value.asInt();
    }

    private static String sha(JsonNode node, String field) {
        String value = field(node, field, 64);
        if (!validSha(value)) throw new IntegrationException("Git returned an invalid commit identifier");
        return value;
    }
    private static boolean validSha(String value) { return value.matches("[0-9a-f]{40}|[0-9a-f]{64}"); }
    private static void requireArray(JsonNode value) { if (value == null || !value.isArray()) throw new IntegrationException("Git returned an invalid list response"); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static boolean github(RepositoryUrl repository) { return repository.provider().equals("GITHUB"); }
    private record CommitMeta(String sha, String author, String message, List<String> parents) { }
    private record Visit(String sha, boolean expanded) { }
    private record Page(JsonNode body, boolean next) { }
}
