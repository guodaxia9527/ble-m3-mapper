package com.guodaxia.blem3mapper;

/**
 * Resolves center clicks using input arrival times, independent of slow player/UI actions.
 * The owner must consume queued input before expiring a single click.
 */
final class CenterClickTracker<T> {
    static final class Click<T> {
        final T context;
        final boolean doubleClick;

        Click(T context, boolean doubleClick) {
            this.context = context;
            this.doubleClick = doubleClick;
        }
    }

    private final long intervalMs;
    private Object source;
    private T pending;
    private long receivedAt;

    CenterClickTracker(long intervalMs) {
        if (intervalMs < 1) throw new IllegalArgumentException("Click interval must be positive");
        this.intervalMs = intervalMs;
    }

    boolean hasPending() { return pending != null; }

    Click<T> press(Object source, T context, long receivedAt) {
        if (context == null) throw new IllegalArgumentException("Click context is required");
        if (pending != null && this.source == source && receivedAt >= this.receivedAt
                && receivedAt - this.receivedAt <= intervalMs) {
            Click<T> result = new Click<>(pending, true);
            cancel();
            return result;
        }
        Click<T> previous = takeSingle();
        this.source = source;
        this.pending = context;
        this.receivedAt = receivedAt;
        return previous;
    }

    Click<T> expire(long now, boolean inputQueued) {
        // A second press may already be queued behind native diagnostics while the worker
        // was reading window state. Never turn its first press into a premature toggle.
        if (!inputQueued && pending != null && now - receivedAt > intervalMs) return takeSingle();
        return null;
    }

    long pollMillis(long now, long maximum) {
        return pending == null ? maximum
                : Math.max(1, Math.min(maximum, receivedAt + intervalMs + 1 - now));
    }

    Click<T> takeSingle() {
        if (pending == null) return null;
        Click<T> result = new Click<>(pending, false);
        cancel();
        return result;
    }

    void cancel() {
        source = null;
        pending = null;
    }
}
