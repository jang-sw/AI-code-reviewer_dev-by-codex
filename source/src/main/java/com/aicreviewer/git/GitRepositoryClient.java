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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GitRepositoryClient {
    private static final int PAGE_SIZE = 100;
    // Git hashes the object header plus bytes; these hash the seven bytes "blob 0\0", not an empty byte array.
    private static final String EMPTY_BLOB_SHA1 = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391";
    private static final String EMPTY_BLOB_SHA256 = "473a0f4c3be8a93681a267e3b1e9a7dcda1185436fe141f7749120a303721813";
    private static final java.util.regex.Pattern COMPLETE_TEXT_HUNK = java.util.regex.Pattern.compile("^@@ -([0-9]+)(?:,([0-9]+))? \\+([0-9]+)(?:,([0-9]+))? @@.*$");
    /** Bounded durable progress input; repositories must stop reading before exceeding this count. */
    public static final int MAX_REVIEWED_SHAS = 131072;
    private final Set<String> allowedHosts;
    private final URI githubApi;
    private final GitCredentialRegistry credentials;
    private final int maxPages;
    private final int maxDiffBytes;
    private final int operationTimeoutSeconds;
    private final ThreadLocal<Long> operationDeadline = new ThreadLocal<>();
    private final SafeHttpTransport http;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    @Autowired
    public GitRepositoryClient(@Value("${app.git.allowed-hosts:github.com}") Set<String> allowedHosts,
            @Value("${app.git.github-api-url:https://api.github.com}") String githubApi,
            @Value("${app.git.token:}") String token,
            @Value("${app.git.token-host:github.com}") String tokenHost,
            @Value("${app.git.token-origin:}") String tokenOrigin,
            @Value("${app.git.timeout-seconds:30}") int timeoutSeconds,
            @Value("${app.git.max-history-pages:1000}") int maxPages,
            @Value("${app.git.max-diff-bytes:262144}") int maxDiffBytes,
            @Value("${app.git.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${app.git.operation-timeout-seconds:300}") int operationTimeoutSeconds,
            GitCredentialProperties credentialProperties) {
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.githubApi = SafeHttpTransport.baseUri(githubApi);
        this.credentials = new GitCredentialRegistry(this.allowedHosts, token, tokenHost, tokenOrigin, credentialProperties);
        if (maxPages < 1 || maxPages > 10000 || maxDiffBytes < 1024 || maxDiffBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid Git pagination or diff size limit");
        }
        this.maxPages = maxPages;
        this.maxDiffBytes = maxDiffBytes;
        if (operationTimeoutSeconds < 1 || operationTimeoutSeconds > 3600) throw new IllegalArgumentException("Invalid Git operation time limit");
        this.operationTimeoutSeconds = operationTimeoutSeconds;
        this.http = new SafeHttpTransport(Duration.ofSeconds(timeoutSeconds), maxResponseBytes);
    }

    public GitRepositoryClient(Set<String> allowedHosts, String githubApi, String token, String tokenHost,
            String tokenOrigin, int timeoutSeconds, int maxPages, int maxDiffBytes, int maxResponseBytes,
            int operationTimeoutSeconds) {
        this(allowedHosts, githubApi, token, tokenHost, tokenOrigin, timeoutSeconds, maxPages, maxDiffBytes,
                maxResponseBytes, operationTimeoutSeconds, new GitCredentialProperties());
    }

    public List<GitCommit> commits(RepositoryUrl repository, String branch, String lastReviewedSha, int limit) {
        return collectWithDeadline(repository, branch, lastReviewedSha, Set.of(), limit, true).commits();
    }

    public GitReviewBatch batch(RepositoryUrl repository, String branch, String lastReviewedSha,
            Set<String> reviewedShas, int limit) {
        if (reviewedShas == null || reviewedShas.size() > MAX_REVIEWED_SHAS) {
            throw new IntegrationException("Durable review progress exceeds the configured memory safety budget");
        }
        if (reviewedShas.stream().anyMatch(value -> value == null || !validSha(value))) {
            throw new IntegrationException("Durable review progress contains an invalid commit identifier");
        }
        return collectWithDeadline(repository, branch, lastReviewedSha, Set.copyOf(reviewedShas), limit, false);
    }

    /** Re-proves the entire pinned change before converting an AI preflight limit into manual work. */
    public GitCommit manualFallback(RepositoryUrl repository, GitCommit original) {
        if (original == null || original.sha() == null || !validSha(original.sha()) || !"FULL".equals(original.coverageType())) {
            throw new IllegalArgumentException("Manual AI fallback requires a complete pinned commit");
        }
        RepositoryUrl checked = RepositoryUrl.parse(repository.normalizedUrl(), allowedHosts);
        if (!checked.equals(repository)) throw new IllegalArgumentException("Repository metadata does not match its URL");
        credentials.tokenFor(repository);
        operationDeadline.set(System.nanoTime() + Duration.ofSeconds(operationTimeoutSeconds).toNanos());
        try {
            String base = api(repository);
            JsonNode details = get(repository, base + (github(repository) ? "/commits/" : "/repository/commits/")
                    + original.sha() + (github(repository) ? "?per_page=100&page=1" : "?stats=true")).body();
            CommitMeta meta = metadata(details, github(repository));
            if (!meta.sha().equals(original.sha()) || !meta.message().equals(original.message())) {
                throw new IntegrationException("Git returned inconsistent pinned commit metadata");
            }
            return manualCommit(repository, base, new CommitMeta(meta.sha(), original.authorLogin(), original.authorEmail(),
                    original.message(), meta.parents()), "AI_INPUT_LIMIT");
        } finally {
            operationDeadline.remove();
        }
    }

    private GitCommit manualCommit(RepositoryUrl repository, String base, CommitMeta meta, String reason) {
        return github(repository) ? githubManualCommit(repository, base, meta, reason) : gitlabManualCommit(repository, base, meta, reason);
    }

    private GitCommit githubManualCommit(RepositoryUrl repository, String base, CommitMeta meta, String reason) {
        Set<String> filenames = new HashSet<>(), covered = new HashSet<>();
        ManualTrees manifest = null;
        JsonNode first = null;
        long actualAdds = 0, actualDeletes = 0;
        int additions = -1, deletions = -1;
        for (int page = 1; ; page++) {
            if (page > Math.min(maxPages, 30)) throw new IntegrationException("GitHub commit diff exceeds pagination limit");
            Page response = get(repository, base + "/commits/" + meta.sha() + "?per_page=100&page=" + page);
            JsonNode details = response.body();
            if (!sha(details, "sha").equals(meta.sha())) throw new IntegrationException("Git returned an unexpected commit");
            int pageAdds = nonnegative(details.path("stats"), "additions"), pageDeletes = nonnegative(details.path("stats"), "deletions");
            if (first == null) {
                first = details;
                additions = pageAdds;
                deletions = pageDeletes;
                manifest = githubManualTrees(repository, base, meta, details);
            } else if (additions != pageAdds || deletions != pageDeletes
                    || !first.path("commit").path("tree").equals(details.path("commit").path("tree"))
                    || !first.path("parents").equals(details.path("parents"))) {
                throw new IntegrationException("GitHub returned inconsistent paginated commit metadata");
            }
            JsonNode files = details.path("files");
            requireArray(files);
            for (JsonNode file : files) {
                String path = filePath(file, "filename");
                if (!filenames.add(path)) throw new IntegrationException("GitHub returned duplicate diff files");
                String oldPath = file.hasNonNull("previous_filename") ? filePath(file, "previous_filename") : path;
                String status = field(file, "status", 20);
                int adds = nonnegative(file, "additions"), deletes = nonnegative(file, "deletions");
                actualAdds += adds;
                actualDeletes += deletes;
                String before = manifest.parent().get(oldPath), after = manifest.current().get(path);
                validateManualChange(oldPath, path, status, before, after, manifest);
                if ((status.equals("added") && deletes != 0) || (status.equals("removed") && adds != 0)) {
                    throw new IntegrationException("GitHub file statistics contradict its create or delete shape");
                }
                rejectInvalidEmptyObject(before, after, meta.sha().length());
                coverManualPaths(oldPath, path, covered, manifest.changed());
                if (!sha(file, "sha").equals(blob(after == null ? before : after))) {
                    throw new IntegrationException("GitHub diff blob disagrees with immutable commit tree");
                }
                String patch = optionalManualPatch(file, "patch");
                boolean bodyless = manualBodyless(before, after, oldPath, path, meta.sha().length());
                if (bodyless) validateBodylessPatch(patch, adds, deletes);
                else if (patch != null && !patch.isBlank() && !binaryPatch(patch)) {
                    // A large complete patch may be unsuitable for AI, but its reported statistics must still agree.
                    verifyManualPatch(patch, adds, deletes);
                }
            }
            if (!response.next() && files.size() < PAGE_SIZE) break;
            if (filenames.size() >= 3000) throw new IntegrationException("GitHub commit file limit reached; complete diff cannot be verified");
        }
        if (actualAdds != additions || actualDeletes != deletions || !covered.equals(manifest.changed())) {
            throw new IntegrationException("GitHub manual file evidence is incomplete compared with commit trees or statistics");
        }
        return manualResult(meta, manifest, reason, false);
    }

    private ManualTrees githubManualTrees(RepositoryUrl repository, String base, CommitMeta meta, JsonNode details) {
        requireParents(details, "parents", true, meta.parents());
        Map<String, String> current = githubTree(repository, base, sha(details.path("commit").path("tree"), "sha"));
        Map<String, String> parent = Map.of();
        if (!meta.parents().isEmpty()) {
            String parentSha = meta.parents().getFirst();
            JsonNode parentDetails = get(repository, base + "/git/commits/" + parentSha).body();
            if (!sha(parentDetails, "sha").equals(parentSha)) throw new IntegrationException("GitHub returned an unexpected parent commit");
            parent = githubTree(repository, base, sha(parentDetails.path("tree"), "sha"));
        }
        return manualTrees(parent, current);
    }

    private GitCommit gitlabManualCommit(RepositoryUrl repository, String base, CommitMeta meta, String reason) {
        JsonNode details = get(repository, base + "/repository/commits/" + meta.sha() + "?stats=true").body();
        if (!sha(details, "id").equals(meta.sha())) throw new IntegrationException("Git returned an unexpected commit");
        requireParents(details, "parent_ids", false, meta.parents());
        int additions = nonnegative(details.path("stats"), "additions"), deletions = nonnegative(details.path("stats"), "deletions");
        Map<String, String> current = gitlabTree(repository, base, meta.sha());
        Map<String, String> parent = meta.parents().isEmpty() ? Map.of() : gitlabTree(repository, base, meta.parents().getFirst());
        ManualTrees manifest = manualTrees(parent, current);
        Set<String> covered = new HashSet<>(), seen = new HashSet<>();
        long knownAdds = 0, knownDeletes = 0;
        boolean unknownCounts = false;
        for (int page = 1; ; page++) {
            if (page > maxPages) throw new IntegrationException("GitLab diff exceeds configured pagination budget");
            Page response = get(repository, base + "/repository/commits/" + meta.sha() + "/diff?unidiff=true&per_page=100&page=" + page);
            requireArray(response.body());
            for (JsonNode file : response.body()) {
                String oldPath = filePath(file, "old_path"), path = filePath(file, "new_path");
                if (!seen.add(oldPath + "\n" + path)) throw new IntegrationException("GitLab returned duplicate diff files");
                boolean created = requiredBoolean(file, "new_file"), deleted = requiredBoolean(file, "deleted_file"), renamed = requiredBoolean(file, "renamed_file");
                String status = created ? "added" : deleted ? "removed" : renamed ? "renamed" : "modified";
                if ((created && deleted) || (renamed && (created || deleted))) throw new IntegrationException("GitLab returned inconsistent file change flags");
                String before = parent.get(oldPath), after = current.get(path);
                validateManualChange(oldPath, path, status, before, after, manifest);
                rejectInvalidEmptyObject(before, after, meta.sha().length());
                coverManualPaths(oldPath, path, covered, manifest.changed());
                validateOptionalMode(file, "a_mode", before);
                validateOptionalMode(file, "b_mode", after);
                boolean collapsed = optionalBoolean(file, "collapsed"), tooLarge = optionalBoolean(file, "too_large");
                boolean unavailable = collapsed || tooLarge;
                String patch = optionalManualPatch(file, "diff");
                boolean bodyless = manualBodyless(before, after, oldPath, path, meta.sha().length());
                if (bodyless) {
                    if (unavailable) throw new IntegrationException("GitLab unavailable diff contradicts unchanged blob or empty-file proof");
                    validateBodylessPatch(patch, 0, 0);
                } else if (patch == null || patch.isBlank() || binaryPatch(patch)) {
                    // GitLab omits per-file line statistics for unavailable diffs. Keep this gap explicit.
                    unknownCounts = true;
                } else {
                    validateCompleteTextPatch(patch);
                    long adds = lineCount(patch, '+'), deletes = lineCount(patch, '-');
                    if (adds + deletes == 0) throw new IntegrationException("Git returned a missing or truncated text patch");
                    if ((created && deletes != 0) || (deleted && adds != 0)) {
                        throw new IntegrationException("GitLab patch statistics contradict its create or delete shape");
                    }
                    knownAdds += adds;
                    knownDeletes += deletes;
                    unknownCounts |= unavailable;
                }
            }
            if (!response.next() && response.body().size() < PAGE_SIZE) break;
        }
        if (!covered.equals(manifest.changed()) || knownAdds > additions || knownDeletes > deletions
                || (!unknownCounts && (knownAdds != additions || knownDeletes != deletions))) {
            throw new IntegrationException("GitLab manual file evidence is incomplete compared with commit trees or statistics");
        }
        return manualResult(meta, manifest, reason, unknownCounts);
    }

    private static ManualTrees manualTrees(Map<String, String> parent, Map<String, String> current) {
        Set<String> changed = new HashSet<>(parent.keySet());
        changed.addAll(current.keySet());
        changed.removeIf(path -> java.util.Objects.equals(parent.get(path), current.get(path)));
        if (changed.isEmpty() || changed.size() > ManualReviewFile.MAX_FILES) {
            throw new IntegrationException("Manual review changed-file evidence is empty or exceeds its safety limit");
        }
        return new ManualTrees(parent, current, Set.copyOf(changed));
    }

    private static void validateManualChange(String oldPath, String path, String status, String before, String after, ManualTrees trees) {
        boolean samePath = oldPath.equals(path);
        boolean shape = switch (status) {
            case "added" -> samePath && before == null && after != null;
            case "removed" -> samePath && before != null && after == null;
            case "modified", "changed" -> samePath && before != null && after != null && !before.equals(after);
            case "renamed" -> !samePath && before != null && after != null
                    && !trees.current().containsKey(oldPath) && !trees.parent().containsKey(path);
            default -> false;
        };
        if (!shape) throw new IntegrationException("Git manual file change does not match immutable trees");
    }

    private static void coverManualPaths(String oldPath, String path, Set<String> covered, Set<String> expected) {
        for (String value : oldPath.equals(path) ? Set.of(path) : Set.of(oldPath, path)) {
            if (!expected.contains(value) || !covered.add(value)) throw new IntegrationException("Git manual file paths do not match complete commit trees");
        }
    }

    private static boolean manualBodyless(String before, String after, String oldPath, String path, int shaLength) {
        return (before != null && after != null && blob(before).equals(blob(after)) && blobMode(mode(before)) && blobMode(mode(after)))
                || emptyFileChange(before, after, oldPath, path, shaLength);
    }

    private static void rejectInvalidEmptyObject(String before, String after, int shaLength) {
        for (String entry : new String[] { before, after }) {
            if (entry != null && emptyBlobId(blob(entry), shaLength) && !Set.of("100644", "100755").contains(mode(entry))) {
                throw new IntegrationException("Canonical empty blob cannot prove a symlink or submodule object");
            }
        }
    }

    private static String optionalManualPatch(JsonNode file, String name) {
        if (!file.hasNonNull(name)) return null;
        if (!file.path(name).isString()) throw new IntegrationException("Git returned an invalid patch field");
        return file.path(name).asText();
    }

    private static void validateBodylessPatch(String patch, int adds, int deletes) {
        if (adds != 0 || deletes != 0 || (patch != null && (binaryPatch(patch) || patch.lines().anyMatch(line -> line.startsWith("@@")
                || (line.startsWith("+") && !line.startsWith("+++ ")) || (line.startsWith("-") && !line.startsWith("--- ")))))) {
            throw new IntegrationException("Git diff contradicts unchanged blob or empty-file proof");
        }
    }

    private void verifyManualPatch(String patch, int adds, int deletes) {
        validateCompleteTextPatch(patch);
        if ((long) adds + deletes == 0 || lineCount(patch, '+') != adds || lineCount(patch, '-') != deletes) {
            throw new IntegrationException("Git returned a missing or truncated text patch");
        }
    }

    private void validateCompleteTextPatch(String patch) {
        long oldRemaining = 0, newRemaining = 0;
        boolean seenHunk = false;
        for (String rawLine : patch.split("\n", -1)) {
            if (System.nanoTime() >= operationDeadline.get()) throw new IntegrationException("Git operation exceeded its time budget");
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.startsWith("@@")) {
                if (oldRemaining != 0 || newRemaining != 0) throw incompleteTextPatch();
                var match = COMPLETE_TEXT_HUNK.matcher(line);
                if (!match.matches()) throw incompleteTextPatch();
                try {
                    long oldStart = Long.parseLong(match.group(1)), newStart = Long.parseLong(match.group(3));
                    oldRemaining = match.group(2) == null ? 1 : Long.parseLong(match.group(2));
                    newRemaining = match.group(4) == null ? 1 : Long.parseLong(match.group(4));
                    if (oldStart > Integer.MAX_VALUE || oldRemaining > Integer.MAX_VALUE - oldStart
                            || newStart > Integer.MAX_VALUE || newRemaining > Integer.MAX_VALUE - newStart) throw incompleteTextPatch();
                } catch (NumberFormatException ex) { throw incompleteTextPatch(); }
                seenHunk = true;
            } else if (seenHunk && line.equals("\\ No newline at end of file")) {
                // No old/new lines are added by this marker.
            } else if (oldRemaining != 0 || newRemaining != 0) {
                if (line.startsWith(" ")) { oldRemaining--; newRemaining--; }
                else if (line.startsWith("-")) oldRemaining--;
                else if (line.startsWith("+")) newRemaining--;
                else throw incompleteTextPatch();
                if (oldRemaining < 0 || newRemaining < 0) throw incompleteTextPatch();
            } else if (!line.isEmpty() && (seenHunk || !(line.startsWith("diff --git ") || line.startsWith("index ")
                    || line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("old mode ") || line.startsWith("new mode ")
                    || line.startsWith("new file mode ") || line.startsWith("deleted file mode ") || line.startsWith("similarity index ")
                    || line.startsWith("dissimilarity index ") || line.startsWith("rename from ") || line.startsWith("rename to ")))) {
                throw incompleteTextPatch();
            }
        }
        if (!seenHunk || oldRemaining != 0 || newRemaining != 0) throw incompleteTextPatch();
    }

    private static IntegrationException incompleteTextPatch() {
        return new IntegrationException("Git returned a missing or truncated text patch with incomplete hunks");
    }

    private static boolean requiredBoolean(JsonNode node, String name) {
        if (!node.path(name).isBoolean()) throw new IntegrationException("Git returned an invalid file change flag");
        return node.path(name).asBoolean();
    }

    private static boolean optionalBoolean(JsonNode node, String name) {
        return node.has(name) && requiredBoolean(node, name);
    }

    private static void validateOptionalMode(JsonNode file, String field, String entry) {
        if (!file.hasNonNull(field)) return;
        String expected = entry == null ? "0" : mode(entry);
        String actual = field(file, field, 10);
        if (!actual.equals(expected) && !(entry == null && actual.equals("000000"))) {
            throw new IntegrationException("GitLab diff mode disagrees with immutable commit tree");
        }
    }

    private static void requireParents(JsonNode details, String field, boolean github, List<String> expected) {
        JsonNode parents = details.path(field);
        if (!parents.isArray()) throw new IntegrationException("Git commit parent metadata is missing or invalid");
        List<String> actual = new ArrayList<>();
        for (JsonNode parent : parents) actual.add(github ? sha(parent, "sha") : parent.asText(""));
        if (!actual.equals(expected)) throw new IntegrationException("Git commit parents disagree with pinned history");
    }

    private GitCommit manualResult(CommitMeta meta, ManualTrees trees, String reason, boolean unknownLineCounts) {
        List<ManualReviewFile> files = trees.changed().stream().sorted().map(path -> {
            String before = trees.parent().get(path), after = trees.current().get(path);
            if ((before != null && blob(before).length() != meta.sha().length())
                    || (after != null && blob(after).length() != meta.sha().length())) {
                throw new IntegrationException("Git manual tree object format disagrees with the pinned commit");
            }
            return new ManualReviewFile(path, before == null ? null : blob(before), after == null ? null : blob(after),
                    before == null ? null : mode(before), after == null ? null : mode(after), reason);
        }).toList();
        String details = "AI 본문 검토 없음. 고정 커밋과 첫 부모의 전체 트리로 변경 경로 " + files.size()
                + "개를 확인했습니다. 일부 파일의 본문 또는 입력 한도 때문에 지원 가능한 파일도 포함하여 커밋 전체를 수동 확인해야 합니다."
                + (unknownLineCounts ? " GitLab이 제공하지 않은 본문의 추가·삭제 행수는 검증할 수 없습니다. 제공된 본문의 통계 모순은 검사했습니다." : "");
        if (System.nanoTime() >= operationDeadline.get()) throw new IntegrationException("Git operation exceeded its time budget");
        return new GitCommit(meta.sha(), meta.author(), meta.authorEmail(), meta.message(), "", "MANUAL_ONLY", details, files);
    }

    private record ManualTrees(Map<String, String> parent, Map<String, String> current, Set<String> changed) { }

    private GitReviewBatch collectWithDeadline(RepositoryUrl repository, String branch, String lastReviewedSha,
            Set<String> reviewedShas, int limit, boolean requireClosedBatch) {
        operationDeadline.set(System.nanoTime() + Duration.ofSeconds(operationTimeoutSeconds).toNanos());
        try {
            return collectCommits(repository, branch, lastReviewedSha, reviewedShas, limit, requireClosedBatch);
        } finally {
            operationDeadline.remove();
        }
    }

    private GitReviewBatch collectCommits(RepositoryUrl repository, String branch, String lastReviewedSha,
            Set<String> reviewedShas, int limit, boolean requireClosedBatch) {
        RepositoryUrl checked = RepositoryUrl.parse(repository.normalizedUrl(), allowedHosts);
        if (!checked.equals(repository)) throw new IllegalArgumentException("Repository metadata does not match its URL");
        credentials.tokenFor(repository);
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
            return new GitReviewBatch(List.of(), null);
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
                if (meta.authorEmail() != null) metadataBytes += meta.authorEmail().getBytes(StandardCharsets.UTF_8).length;
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
        List<CommitMeta> selected = new ArrayList<>();
        String checkpoint = null;
        if (requireClosedBatch) {
            // Compatibility API: callers historically checkpointed the final returned SHA.
            int end = Math.min(ordered.size(), start + limit);
            while (end > start && !checkpointShas.contains(ordered.get(end - 1).sha())) end--;
            if (end == start && start < ordered.size()) {
                throw new IntegrationException("Commit batch limit is too small to review the next complete merge group");
            }
            selected.addAll(ordered.subList(start, end));
            if (!selected.isEmpty()) checkpoint = selected.getLast().sha();
        } else {
            // The first-parent-first DFS above gives the cursor an immutable, closed prefix.
            // Reviewed orphan SHAs never participate: only nodes in this pinned graph are visited.
            for (int i = start; i < ordered.size(); i++) {
                CommitMeta meta = ordered.get(i);
                if (!reviewedShas.contains(meta.sha())) {
                    if (selected.size() == limit) break;
                    selected.add(meta);
                }
                // Keep walking an already-reviewed tail even when the selected quota is full.
                if (checkpointShas.contains(meta.sha())) checkpoint = meta.sha();
            }
        }
        // Keep manifests only for this batch. No source files are written to disk.
        Map<String, Map<String, String>> trees = new LinkedHashMap<>(4, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) { return size() > 2; }
        };
        List<GitCommit> result = new ArrayList<>();
        for (CommitMeta meta : selected) {
            try {
                result.add(github ? githubCommit(repository, base, meta, trees) : gitlabCommit(repository, base, meta, trees));
            } catch (ManualCandidate candidate) {
                // Re-read and validate the entire listing; an early unsupported file never hides later corruption.
                result.add(manualCommit(repository, base, meta, candidate.reason));
            }
        }
        return new GitReviewBatch(List.copyOf(result), checkpoint);
    }

    private GitCommit githubCommit(RepositoryUrl repository, String base, CommitMeta meta,
            Map<String, Map<String, String>> trees) {
        Set<String> filenames = new HashSet<>();
        List<GithubFile> collected = new ArrayList<>();
        long additions = 0, deletions = 0;
        long collectedBytes = 0;
        int expectedAdds = -1, expectedDeletes = -1;
        boolean needsManifest = false;
        JsonNode firstDetails = null;
        for (int page = 1; ; page++) {
            if (page > Math.min(maxPages, 30)) throw new IntegrationException("GitHub commit diff exceeds pagination limit");
            Page response = get(repository, base + "/commits/" + meta.sha() + "?per_page=100&page=" + page);
            JsonNode body = response.body();
            if (!sha(body, "sha").equals(meta.sha())) throw new IntegrationException("Git returned an unexpected commit");
            int pageAdds = nonnegative(body.path("stats"), "additions"), pageDeletes = nonnegative(body.path("stats"), "deletions");
            if (firstDetails == null) {
                firstDetails = body;
                expectedAdds = pageAdds;
                expectedDeletes = pageDeletes;
            } else if (expectedAdds != pageAdds || expectedDeletes != pageDeletes
                    || !firstDetails.path("commit").path("tree").equals(body.path("commit").path("tree"))
                    || !firstDetails.path("parents").equals(body.path("parents"))) {
                throw new IntegrationException("GitHub returned inconsistent paginated commit metadata");
            }
            JsonNode files = body.path("files");
            requireArray(files);
            for (JsonNode file : files) {
                String path = filePath(file, "filename");
                if (!filenames.add(path)) throw new IntegrationException("GitHub returned duplicate diff files");
                int adds = nonnegative(file, "additions"), deletes = nonnegative(file, "deletions");
                String oldPath = file.hasNonNull("previous_filename") ? filePath(file, "previous_filename") : path;
                String status = file.hasNonNull("status") ? field(file, "status", 20) : "";
                String blob = file.hasNonNull("sha") ? field(file, "sha", 64) : "";
                // A canonical empty blob is only a candidate; immutable trees must still prove create/delete shape.
                boolean metadataCandidate = !oldPath.equals(path) || status.equals("renamed")
                        || ((long) adds + deletes == 0 && Set.of("modified", "changed").contains(status))
                        || (Set.of("added", "removed").contains(status) && emptyBlobId(blob, meta.sha().length()));
                String patch = patchField(file, "patch", metadataCandidate);
                if (!metadataCandidate && binaryPatch(patch)) throw new ManualCandidate("SOURCE_DIFF_UNAVAILABLE");
                if (!metadataCandidate) verifyPatchCounts(patch, adds, deletes);
                needsManifest |= metadataCandidate;
                additions += adds;
                deletions += deletes;
                collectedBytes += oldPath.getBytes(StandardCharsets.UTF_8).length + path.getBytes(StandardCharsets.UTF_8).length
                        + patch.getBytes(StandardCharsets.UTF_8).length + 18L; // Exact canonical file header and two newlines.
                if (collectedBytes > maxDiffBytes) throw new ManualCandidate("GIT_DIFF_BUDGET");
                collected.add(new GithubFile(oldPath, path, status, blob, patch, adds, deletes));
            }
            if (!response.next() && files.size() < PAGE_SIZE) break;
            // GitHub caps its JSON file listing at 3,000; never accept reaching that ceiling.
            if (filenames.size() >= 3000) throw new IntegrationException("GitHub commit file limit reached; complete diff cannot be verified");
        }
        if (additions != expectedAdds || deletions != expectedDeletes) {
            throw new IntegrationException("GitHub diff is incomplete compared with commit statistics");
        }
        if (filenames.isEmpty()) {
            return new GitCommit(meta.sha(), meta.author(), meta.authorEmail(), meta.message(), "", "EMPTY",
                    "변경 파일 0개, 추가/삭제 0행을 확인했습니다. AI 본문 검토 없음.");
        }
        if (needsManifest) return githubMetadataCommit(repository, base, meta, firstDetails, collected, trees);
        StringBuilder diff = new StringBuilder();
        for (GithubFile file : collected) append(diff, "diff --git a/" + file.oldPath() + " b/" + file.path() + "\n" + file.patch() + "\n");
        return new GitCommit(meta.sha(), meta.author(), meta.authorEmail(), meta.message(), diff.toString());
    }

    private GitCommit githubMetadataCommit(RepositoryUrl repository, String base, CommitMeta meta, JsonNode details,
            List<GithubFile> files, Map<String, Map<String, String>> trees) {
        JsonNode parents = details.path("parents");
        requireArray(parents);
        List<String> detailParents = new ArrayList<>();
        for (JsonNode parent : parents) detailParents.add(sha(parent, "sha"));
        if (!detailParents.equals(meta.parents())) throw new IntegrationException("GitHub commit parents disagree with pinned history");
        String currentTree = sha(details.path("commit").path("tree"), "sha");
        Map<String, String> current = trees.computeIfAbsent(currentTree, key -> githubTree(repository, base, key));
        Map<String, String> parent = Map.of();
        if (!meta.parents().isEmpty()) {
            String parentSha = meta.parents().getFirst();
            JsonNode parentDetails = get(repository, base + "/git/commits/" + parentSha).body();
            if (!sha(parentDetails, "sha").equals(parentSha)) throw new IntegrationException("GitHub returned an unexpected parent commit");
            String parentTree = sha(parentDetails.path("tree"), "sha");
            parent = trees.computeIfAbsent(parentTree, key -> githubTree(repository, base, key));
        }
        Set<String> expected = new HashSet<>(current.keySet());
        expected.addAll(parent.keySet());
        Map<String, String> previous = parent;
        expected.removeIf(path -> java.util.Objects.equals(current.get(path), previous.get(path)));
        Set<String> covered = new HashSet<>();
        StringBuilder diff = new StringBuilder(), metadataDetails = new StringBuilder();
        int bodyFiles = 0;
        for (GithubFile file : files) {
            String before = parent.get(file.oldPath()), after = current.get(file.path());
            validateGithubTreeChange(file, before, after, parent, current);
            for (String path : file.oldPath().equals(file.path()) ? Set.of(file.path()) : Set.of(file.oldPath(), file.path())) {
                if (!expected.contains(path) || !covered.add(path)) throw new IntegrationException("GitHub diff does not match complete commit trees");
            }
            String expectedBlob = after != null ? blob(after) : blob(before);
            if (!validSha(file.blob()) || !file.blob().equals(expectedBlob)) {
                throw new IntegrationException("GitHub diff blob disagrees with immutable commit tree");
            }
            boolean metadataChange = before != null && after != null
                    && (!file.oldPath().equals(file.path()) || !mode(before).equals(mode(after)));
            boolean unchangedBlob = metadataChange && blob(before).equals(blob(after));
            boolean emptyFileChange = emptyFileChange(before, after, file.oldPath(), file.path(), meta.sha().length());
            boolean bodyless = unchangedBlob || emptyFileChange;
            String patch = file.patch();
            if (binaryPatch(patch)) {
                if (bodyless) throw new IntegrationException("GitHub diff contradicts unchanged blob or empty-file proof");
                throw new ManualCandidate("SOURCE_DIFF_UNAVAILABLE");
            }
            if (bodyless) {
                if (file.additions() != 0 || file.deletions() != 0 || patch.lines().anyMatch(line ->
                        line.startsWith("@@") || (line.startsWith("+") && !line.startsWith("+++ "))
                                || (line.startsWith("-") && !line.startsWith("--- ")))) {
                    throw new IntegrationException("GitHub diff contradicts unchanged blob or empty-file proof");
                }
            } else {
                verifyPatchCounts(patch, file.additions(), file.deletions());
                bodyFiles++;
            }
            append(diff, "diff --git a/" + file.oldPath() + " b/" + file.path() + "\n");
            if (metadataChange) {
                appendCoverage(metadataDetails, "이전 경로: " + file.oldPath() + ", 모드: " + mode(before)
                        + " → 새 경로: " + file.path() + ", 모드: " + mode(after)
                        + (unchangedBlob ? " (동일 blob; 본문 변경 없음)\n" : " (본문 변경 포함)\n"));
                if (!file.oldPath().equals(file.path())) append(diff, "rename from " + file.oldPath() + "\nrename to " + file.path() + "\n");
                append(diff, "old mode " + mode(before) + "\nnew mode " + mode(after) + "\n");
            }
            if (emptyFileChange) appendEmptyFileCoverage(diff, metadataDetails, file.path(), before, after);
            append(diff, (bodyless ? "" : patch) + "\n");
        }
        if (!covered.equals(expected)) throw new IntegrationException("GitHub diff is incomplete compared with commit trees");
        return new GitCommit(meta.sha(), meta.author(), meta.authorEmail(), meta.message(), diff.toString(),
                bodyFiles == 0 ? "METADATA_ONLY" : "FULL", coverage(bodyFiles == 0
                        ? "AI 본문 검토 없음. 경로·권한·파일 유형 변경은 수동 확인이 필요합니다.\n"
                        : "본문 diff와 함께 다음 경로·권한·파일 유형 변경을 전달했습니다.\n", metadataDetails));
    }

    private static void validateGithubTreeChange(GithubFile file, String before, String after,
            Map<String, String> parent, Map<String, String> current) {
        boolean samePath = file.oldPath().equals(file.path());
        boolean shapeMatches = switch (file.status()) {
            case "added" -> samePath && before == null && after != null;
            case "removed" -> samePath && before != null && after == null;
            case "modified", "changed" -> samePath && before != null && after != null;
            case "renamed" -> !samePath && before != null && after != null
                    && !current.containsKey(file.oldPath()) && !parent.containsKey(file.path());
            default -> false;
        };
        if (!shapeMatches || (before != null && !blobMode(mode(before))) || (after != null && !blobMode(mode(after)))) {
            throw new IntegrationException("GitHub diff change kind or file type does not match immutable trees");
        }
    }

    private Map<String, String> githubTree(RepositoryUrl repository, String base, String treeSha) {
        Page response = get(repository, base + "/git/trees/" + treeSha + "?recursive=1");
        JsonNode body = response.body();
        if (!sha(body, "sha").equals(treeSha)) throw new IntegrationException("GitHub returned an unexpected tree");
        if (!body.path("truncated").isBoolean() || body.path("truncated").asBoolean() || response.next()) {
            throw new IntegrationException("GitHub tree is incomplete or truncated");
        }
        JsonNode entries = body.path("tree");
        requireArray(entries);
        Map<String, String> tree = new HashMap<>();
        Set<String> seen = new HashSet<>();
        long bytes = 0;
        for (JsonNode entry : entries) {
            String path = filePath(entry, "path"), type = field(entry, "type", 20), mode = field(entry, "mode", 10);
            bytes += path.getBytes(StandardCharsets.UTF_8).length + 128L;
            if (bytes > 16L * 1024 * 1024 || seen.size() >= 100000) throw new IntegrationException("GitHub tree exceeds the memory safety budget");
            if (!seen.add(path)) throw new IntegrationException("GitHub returned duplicate tree entries");
            String objectSha = sha(entry, "sha");
            if (type.equals("tree") && mode.equals("040000")) continue;
            if (!(type.equals("blob") && blobMode(mode)) && !(type.equals("commit") && mode.equals("160000"))) {
                throw new IntegrationException("GitHub returned inconsistent tree type or mode");
            }
            tree.put(path, objectSha + ":" + mode);
        }
        return Map.copyOf(tree);
    }

    private record GithubFile(String oldPath, String path, String status, String blob, String patch, int additions, int deletions) { }

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
        StringBuilder metadataDetails = new StringBuilder();
        int bodyFiles = 0;
        long actualAdds = 0, actualDeletes = 0;
        for (int page = 1; ; page++) {
            if (page > maxPages) throw new IntegrationException("GitLab diff exceeds configured pagination budget");
            Page response = get(repository, base + "/repository/commits/" + meta.sha()
                    + "/diff?unidiff=true&per_page=100&page=" + page);
            requireArray(response.body());
            for (JsonNode file : response.body()) {
                if (file.path("collapsed").asBoolean(false) || file.path("too_large").asBoolean(false)) {
                    throw new ManualCandidate(file.path("too_large").asBoolean(false) ? "GIT_DIFF_BUDGET" : "SOURCE_DIFF_UNAVAILABLE");
                }
                String oldPath = filePath(file, "old_path"), newPath = filePath(file, "new_path");
                if (!seenFiles.add(oldPath + "\n" + newPath)) throw new IntegrationException("GitLab returned duplicate diff files");
                if (!expected.contains(oldPath) && !expected.contains(newPath)) throw new IntegrationException("GitLab diff does not match commit trees");
                covered.add(oldPath);
                covered.add(newPath);
                String before = parent.get(oldPath), after = current.get(newPath);
                boolean metadataChange = before != null && after != null
                        && (!oldPath.equals(newPath) || !mode(before).equals(mode(after)));
                boolean unchangedBlob = metadataChange && blob(before).equals(blob(after))
                        && blobMode(mode(before)) && blobMode(mode(after))
                        && (oldPath.equals(newPath) || (!current.containsKey(oldPath) && !parent.containsKey(newPath)));
                boolean emptyFileChange = emptyFileChange(before, after, oldPath, newPath, meta.sha().length());
                if (emptyFileChange && (!file.path("new_file").isBoolean() || !file.path("deleted_file").isBoolean()
                        || file.path("new_file").asBoolean() != (before == null) || file.path("deleted_file").asBoolean() != (after == null)
                        || (file.hasNonNull("renamed_file") && (!file.path("renamed_file").isBoolean() || file.path("renamed_file").asBoolean())))) {
                    throw new IntegrationException("GitLab empty-file change flags disagree with immutable trees");
                }
                boolean bodyless = unchangedBlob || emptyFileChange;
                String patch = patchField(file, "diff", bodyless);
                if (binaryPatch(patch)) {
                    if (bodyless) throw new IntegrationException("GitLab diff contradicts unchanged blob or empty-file proof");
                    throw new ManualCandidate("SOURCE_DIFF_UNAVAILABLE");
                }
                long fileAdds = lineCount(patch, '+'), fileDeletes = lineCount(patch, '-');
                if (bodyless && (fileAdds != 0 || fileDeletes != 0 || patch.lines().anyMatch(line ->
                        line.startsWith("@@") || (line.startsWith("+") && !line.startsWith("+++ "))
                                || (line.startsWith("-") && !line.startsWith("--- "))))) {
                    throw new IntegrationException("GitLab diff contradicts unchanged blob or empty-file proof");
                }
                if (!bodyless && patch.isBlank()) throw new ManualCandidate("SOURCE_DIFF_UNAVAILABLE");
                if (!bodyless && fileAdds + fileDeletes == 0) {
                    throw new IntegrationException("GitLab returned an unavailable or non-text file diff");
                }
                actualAdds += fileAdds;
                actualDeletes += fileDeletes;
                append(diff, "diff --git a/" + oldPath + " b/" + newPath + "\n");
                if (metadataChange) {
                    appendCoverage(metadataDetails, "이전 경로: " + oldPath + ", 모드: " + mode(before)
                            + " → 새 경로: " + newPath + ", 모드: " + mode(after)
                            + (unchangedBlob ? " (동일 blob; 본문 변경 없음)\n" : " (본문 변경 포함)\n"));
                    if (!oldPath.equals(newPath)) append(diff, "rename from " + oldPath + "\nrename to " + newPath + "\n");
                    append(diff, "old mode " + mode(before) + "\nnew mode " + mode(after) + "\n");
                }
                if (emptyFileChange) appendEmptyFileCoverage(diff, metadataDetails, newPath, before, after);
                if (!bodyless) bodyFiles++;
                append(diff, (bodyless ? "" : patch) + "\n");
            }
            if (!response.next() && response.body().size() < PAGE_SIZE) break;
        }
        if (!covered.containsAll(expected) || actualAdds != additions || actualDeletes != deletions) {
            throw new IntegrationException("GitLab diff is incomplete compared with commit trees or statistics");
        }
        if (expected.isEmpty()) {
            return new GitCommit(meta.sha(), null, meta.authorEmail(), meta.message(), "", "EMPTY",
                    "변경 파일 0개, 추가/삭제 0행과 동일한 커밋 트리를 확인했습니다. AI 본문 검토 없음.");
        }
        if (bodyFiles == 0) {
            return new GitCommit(meta.sha(), null, meta.authorEmail(), meta.message(), diff.toString(), "METADATA_ONLY",
                    coverage("AI 본문 검토 없음. 경로·권한·파일 유형 변경은 수동 확인이 필요합니다.\n", metadataDetails));
        }
        return new GitCommit(meta.sha(), null, meta.authorEmail(), meta.message(), diff.toString(), "FULL",
                metadataDetails.isEmpty() ? "" : coverage("본문 diff와 함께 다음 경로·권한·파일 유형 변경을 전달했습니다.\n", metadataDetails));
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
                String mode = field(entry, "mode", 10);
                if (!mode.matches("[0-7]{6}")) throw new IntegrationException("GitLab returned an invalid tree mode");
                if (type.equals("tree")) {
                    if (!mode.equals("040000")) throw new IntegrationException("GitLab returned inconsistent tree metadata");
                    sha(entry, "id");
                } else {
                    if (!type.equals("blob") && !type.equals("commit")) throw new IntegrationException("GitLab returned an invalid tree type");
                    if ((type.equals("blob") && !blobMode(mode)) || (type.equals("commit") && !mode.equals("160000"))) {
                        throw new IntegrationException("GitLab returned inconsistent tree metadata");
                    }
                    tree.put(path, sha(entry, "id") + ":" + mode);
                }
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
        JsonNode email = github ? node.path("commit").path("author").path("email") : node.path("author_email");
        return new CommitMeta(sha, author, authorEmail(email), message, List.copyOf(parentIds));
    }

    private Page get(RepositoryUrl repository, String url) {
        if (System.nanoTime() >= operationDeadline.get()) throw new IntegrationException("Git operation exceeded its time budget");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET().header("Accept", "application/json");
        if (github(repository)) request.header("X-GitHub-Api-Version", "2022-11-28");
        String token = credentials.tokenFor(repository);
        if (token != null) {
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

    private static String authorEmail(JsonNode value) {
        if (!value.isString()) return null;
        String email = value.asText().strip().toLowerCase(java.util.Locale.ROOT);
        if (email.isBlank() || email.length() > 320 || email.codePoints().anyMatch(Character::isISOControl)) return null;
        return email;
    }

    private void append(StringBuilder target, String value) {
        target.append(value);
        if (target.length() > maxDiffBytes || target.toString().getBytes(StandardCharsets.UTF_8).length > maxDiffBytes) {
            throw new ManualCandidate("GIT_DIFF_BUDGET");
        }
    }

    private static String blob(String entry) { return entry.substring(0, entry.indexOf(':')); }
    private static String mode(String entry) { return entry.substring(entry.indexOf(':') + 1); }
    private static boolean blobMode(String mode) { return Set.of("100644", "100755", "120000").contains(mode); }

    private static boolean emptyBlobId(String sha, int objectIdLength) {
        return objectIdLength == 40 ? EMPTY_BLOB_SHA1.equals(sha) : objectIdLength == 64 && EMPTY_BLOB_SHA256.equals(sha);
    }

    private static boolean emptyFileChange(String before, String after, String oldPath, String newPath, int objectIdLength) {
        if (!oldPath.equals(newPath) || (before == null) == (after == null)) return false;
        String entry = before == null ? after : before;
        return Set.of("100644", "100755").contains(mode(entry)) && emptyBlobId(blob(entry), objectIdLength);
    }

    private void appendEmptyFileCoverage(StringBuilder diff, StringBuilder details, String path, String before, String after) {
        boolean created = before == null;
        String entry = created ? after : before;
        appendCoverage(details, "빈 파일 " + (created ? "생성" : "삭제") + ": " + path + ", 모드: " + mode(entry)
                + " (Git 빈 blob 확인; 본문 없음; 경로·권한 수동 확인 필요)\n");
        append(diff, (created ? "new file mode " : "deleted file mode ") + mode(entry) + "\n");
    }

    private static void appendCoverage(StringBuilder target, String value) {
        if (target.length() + value.length() > 16000) throw new IntegrationException("Commit coverage details exceed the size limit");
        target.append(value);
    }

    private static String coverage(String prefix, StringBuilder details) {
        StringBuilder result = new StringBuilder();
        appendCoverage(result, prefix);
        appendCoverage(result, details.toString());
        return result.toString();
    }

    private static void verifyPatchCounts(String patch, int additions, int deletions) {
        if (patch.isBlank()) throw new ManualCandidate("SOURCE_DIFF_UNAVAILABLE");
        if ((long) additions + deletions == 0
                || lineCount(patch, '+') != additions || lineCount(patch, '-') != deletions) {
            throw new IntegrationException("Git returned a missing or truncated text patch");
        }
    }

    private String patchField(JsonNode file, String name, boolean allowMissing) {
        if (!file.hasNonNull(name)) {
            if (allowMissing) return "";
            throw new ManualCandidate("SOURCE_DIFF_UNAVAILABLE");
        }
        if (!file.path(name).isString()) throw new IntegrationException("Git returned an invalid patch field");
        String patch = file.path(name).asText();
        if (patch.length() > maxDiffBytes || patch.getBytes(StandardCharsets.UTF_8).length > maxDiffBytes) {
            throw new ManualCandidate("GIT_DIFF_BUDGET");
        }
        return patch;
    }

    private static boolean binaryPatch(String patch) {
        boolean binary = patch.lines().anyMatch(line -> line.equals("GIT binary patch")
                || (line.startsWith("Binary files ") && line.endsWith(" differ")));
        if (binary && patch.lines().anyMatch(line -> line.startsWith("@@")
                || (line.startsWith("+") && !line.startsWith("+++ ")) || (line.startsWith("-") && !line.startsWith("--- ")))) {
            throw new IntegrationException("Git returned contradictory binary and text patch content");
        }
        return binary;
    }

    private static final class ManualCandidate extends IntegrationException {
        private final String reason;
        private ManualCandidate(String reason) { super("Git source diff requires complete manual-review evidence"); this.reason = reason; }
    }

    private static long lineCount(String patch, char prefix) {
        // API patch hunks may contain added source lines beginning with +++; count only inside hunks.
        boolean inHunk = false;
        long count = 0;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("@@")) {
                if (!line.matches("@@ -[0-9]+(?:,[0-9]+)? \\+[0-9]+(?:,[0-9]+)? @@.*")) {
                    throw new IntegrationException("Git returned an invalid patch hunk");
                }
                inHunk = true;
                continue;
            }
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
    private record CommitMeta(String sha, String author, String authorEmail, String message, List<String> parents) { }
    private record Visit(String sha, boolean expanded) { }
    private record Page(JsonNode body, boolean next) { }
}
