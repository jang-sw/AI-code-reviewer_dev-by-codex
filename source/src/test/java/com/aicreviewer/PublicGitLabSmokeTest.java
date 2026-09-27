package com.aicreviewer;

import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.ManualReviewFile;
import com.aicreviewer.git.RepositoryUrl;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in public GitLab-owned fixture; immutable refs, explicit blank credentials, no AI or blob downloads. */
@EnabledIfEnvironmentVariable(named = "RUN_GITLAB_SMOKE", matches = "true")
class PublicGitLabSmokeTest {
    private static final String FIXTURE_HEAD = "ddd0f15ae83993f5cb66a927a28673882e99100b";
    private static final String ROOT = "1a0b36b3cdad1d2ee32457c102a8c0b7056fa863";
    private static final String IMAGE_ADDED = "33f3729a45c02fc67d00adb1b8bca394b0e761d9";
    private static final String IMAGE_MODIFIED = "2f63565e7aac07bcdadb654e253078b727143ec4";
    private static final String TEXT_AFTER_IMAGE = "874797c3a73b60d2187ed6e2fcabd289ff75171e";
    private static final String BEFORE_EMPTY_FILE = "c7fbe50c7c7419d9701eebe64b1fdacc3df5b9dd";
    private static final String EMPTY_FILE_ADDED = "9a944d90955aaf45f6d0c88f30e27f8d2c41cec0";

    @Test
    void readsPinnedPublicHistoryAndRootDiffThenFinalizesAndResumesWithoutNewDiffs() {
        GitRepositoryClient client = new GitRepositoryClient(Set.of("gitlab.com"), "https://api.github.com", "",
                "github.com", "", 20, 10, 262144, 2097152, 90);
        RepositoryUrl repository = RepositoryUrl.parse("https://gitlab.com/gitlab-org/gitlab-test.git", Set.of("gitlab.com"));
        var first = client.batch(repository, FIXTURE_HEAD, null, Set.of(), 1);
        assertThat(first.commits()).hasSize(1);
        assertThat(first.commits().getFirst().sha()).isEqualTo(ROOT);
        assertThat(first.commits().getFirst().coverageType()).isEqualTo("FULL");
        assertThat(first.commits().getFirst().diff()).contains("diff --git ", "@@ ");
        assertThat(first.checkpointSha()).isEqualTo(ROOT);

        var finalized = client.batch(repository, ROOT, null, Set.of(ROOT), 1);
        assertThat(finalized.commits()).isEmpty();
        assertThat(finalized.checkpointSha()).isEqualTo(ROOT);
        var resumed = client.batch(repository, ROOT, ROOT, Set.of(ROOT), 1);
        assertThat(resumed.commits()).isEmpty();
        assertThat(resumed.checkpointSha()).isNull();
    }

    @Test
    @Timeout(180)
    void provesBinaryModificationThenResumesAtTheFollowingTextCommit() {
        GitRepositoryClient client = coverageClient();
        RepositoryUrl repository = publicRepository();
        // This supplied parent cursor scopes the probe; it does not claim that this test reviewed earlier history.
        var binaryBatch = client.batch(repository, TEXT_AFTER_IMAGE, IMAGE_ADDED, Set.of(), 1);
        assertThat(binaryBatch.commits()).hasSize(1);
        var binary = binaryBatch.commits().getFirst();
        assertThat(binary.sha()).isEqualTo(IMAGE_MODIFIED);
        assertThat(binary.coverageType()).isEqualTo("MANUAL_ONLY");
        assertThat(binary.diff()).isEmpty();
        assertThat(binary.coverageDetails()).contains("AI 본문 검토 없음", "추가·삭제 행수는 검증할 수 없습니다");
        assertThat(binary.manualFiles()).containsExactly(new ManualReviewFile("files/images/6049019_460s.jpg",
                "18079e308ff9b3a5e304941020747e5c39b46c88", "08cf843fd8fe1c50757df0a13fcc44661996b4df",
                "100644", "100644", "SOURCE_DIFF_UNAVAILABLE"));
        assertThat(binaryBatch.checkpointSha()).isEqualTo(IMAGE_MODIFIED);

        var textBatch = client.batch(repository, TEXT_AFTER_IMAGE, binaryBatch.checkpointSha(), Set.of(binary.sha()), 1);
        assertThat(textBatch.commits()).hasSize(1);
        var text = textBatch.commits().getFirst();
        assertThat(text.sha()).isEqualTo(TEXT_AFTER_IMAGE);
        assertThat(text.coverageType()).isEqualTo("FULL");
        assertThat(text.manualFiles()).isEmpty();
        assertThat(text.diff()).contains("diff --git a/files/ruby/popen.rb b/files/ruby/popen.rb\n",
                "diff --git a/files/ruby/version_info.rb b/files/ruby/version_info.rb\n", "@@ ");
        assertThat(textBatch.checkpointSha()).isEqualTo(TEXT_AFTER_IMAGE);

        var resumed = client.batch(repository, TEXT_AFTER_IMAGE, textBatch.checkpointSha(),
                Set.of(binary.sha(), text.sha()), 1);
        assertThat(resumed.commits()).isEmpty();
        assertThat(resumed.checkpointSha()).isNull();
    }

    @Test
    @Timeout(180)
    void provesCanonicalEmptyFileMetadataThenReprovesItForManualAssignment() {
        GitRepositoryClient client = coverageClient();
        RepositoryUrl repository = publicRepository();
        var batch = client.batch(repository, EMPTY_FILE_ADDED, BEFORE_EMPTY_FILE, Set.of(), 1);
        assertThat(batch.commits()).hasSize(1);
        var metadata = batch.commits().getFirst();
        assertThat(metadata.sha()).isEqualTo(EMPTY_FILE_ADDED);
        assertThat(metadata.coverageType()).isEqualTo("METADATA_ONLY");
        assertThat(metadata.manualFiles()).isEmpty();
        assertThat(metadata.diff()).contains("diff --git a/files/empty b/files/empty\n", "new file mode 100644");
        assertThat(metadata.diff()).doesNotContain("@@ ");
        assertThat(batch.checkpointSha()).isEqualTo(EMPTY_FILE_ADDED);

        var manual = client.manualMetadataFallback(repository, metadata);
        assertThat(manual.sha()).isEqualTo(metadata.sha());
        assertThat(manual.message()).isEqualTo(metadata.message());
        assertThat(manual.coverageType()).isEqualTo("MANUAL_ONLY");
        assertThat(manual.diff()).isEmpty();
        assertThat(manual.coverageDetails()).contains("AI 본문 검토 없음", "빈 파일 생성·삭제");
        assertThat(manual.manualFiles()).containsExactly(new ManualReviewFile("files/empty", null,
                "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", null, "100644", "METADATA_CHANGE"));
    }

    private static GitRepositoryClient coverageClient() {
        return new GitRepositoryClient(Set.of("gitlab.com"), "https://api.github.com", "",
                "github.com", "", 20, 2, 262144, 2097152, 90);
    }

    private static RepositoryUrl publicRepository() {
        return RepositoryUrl.parse("https://gitlab.com/gitlab-org/gitlab-test.git", Set.of("gitlab.com"));
    }
}
