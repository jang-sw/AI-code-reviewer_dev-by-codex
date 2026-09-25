package com.aicreviewer.review;

import java.util.Optional;

/** A lease must cover external I/O and all commit transactions in a review run. */
public interface ProjectReviewLock {
    Optional<Lease> tryAcquire(long projectId);

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
