package com.aicreviewer.review;

import com.aicreviewer.git.RepositoryUrl;

public record ReviewProject(long id, String name, String repositoryUrl, String provider,
                            String repositoryHost, String repositoryPath, long ownerId,
                            String status, String branch, String lastReviewedSha) {
    RepositoryUrl repository() {
        return new RepositoryUrl(repositoryUrl, provider, repositoryHost, repositoryPath);
    }
}
