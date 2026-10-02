package com.jinloes.prpilot.ui;

import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.StringUtils;

final class WorktreeCoordinator<T> {

    record WorktreeLease<T>(long epoch, String key, CompletableFuture<T> future, boolean owner) {}

    private long epoch;
    private String activeKey;
    private T activeValue;
    private String inFlightKey;
    private CompletableFuture<T> inFlight;

    synchronized WorktreeLease<T> acquire(String key) {
        if (activeValue != null && StringUtils.equals(activeKey, key)) {
            return new WorktreeLease<>(
                    epoch, key, CompletableFuture.completedFuture(activeValue), false);
        }
        if (inFlight != null && StringUtils.equals(inFlightKey, key)) {
            return new WorktreeLease<>(epoch, key, inFlight, false);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        inFlightKey = key;
        inFlight = future;
        return new WorktreeLease<>(epoch, key, future, true);
    }

    boolean install(WorktreeLease<T> lease, T value) {
        boolean accepted;
        synchronized (this) {
            accepted =
                    lease.epoch() == epoch
                            && inFlight == lease.future()
                            && StringUtils.equals(inFlightKey, lease.key());
            if (accepted) {
                activeKey = lease.key();
                activeValue = value;
                inFlightKey = null;
                inFlight = null;
            }
        }
        if (accepted) {
            lease.future().complete(value);
        }
        return accepted;
    }

    void fail(WorktreeLease<T> lease) {
        synchronized (this) {
            if (inFlight == lease.future()) {
                inFlightKey = null;
                inFlight = null;
            }
        }
        lease.future()
                .completeExceptionally(
                        new IllegalStateException(
                                "Unable to create an isolated pull request worktree."));
    }

    synchronized T activeValue() {
        return activeValue;
    }

    T clear() {
        T previous;
        CompletableFuture<T> detached;
        synchronized (this) {
            epoch++;
            previous = activeValue;
            detached = inFlight;
            activeKey = null;
            activeValue = null;
            inFlightKey = null;
            inFlight = null;
        }
        if (detached != null) {
            detached.completeExceptionally(
                    new IllegalStateException("Pull request worktree creation was cancelled."));
        }
        return previous;
    }
}
