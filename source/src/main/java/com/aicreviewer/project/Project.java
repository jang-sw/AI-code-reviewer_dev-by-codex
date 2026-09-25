package com.aicreviewer.project;

import java.time.Instant;

public record Project(long id, String name, String repositoryUrl, String provider, String repositoryHost,
                      String repositoryPath, long ownerId, String ownerUsername, String status,
                      String reviewBranch, String lastReviewedSha, Instant approvedAt,
                      Instant createdAt, Instant updatedAt) {
    public long getId() { return id; }
    public String getName() { return name; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public String getProvider() { return provider; }
    public String getRepositoryHost() { return repositoryHost; }
    public String getRepositoryPath() { return repositoryPath; }
    public long getOwnerId() { return ownerId; }
    public String getOwnerUsername() { return ownerUsername; }
    public String getStatus() { return status; }
    public String getReviewBranch() { return reviewBranch; }
    public String getLastReviewedSha() { return lastReviewedSha; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean approved() { return "APPROVED".equals(status); }
    public boolean isApproved() { return approved(); }
}
