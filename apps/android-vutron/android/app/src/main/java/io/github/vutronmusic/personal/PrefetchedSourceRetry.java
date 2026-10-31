package io.github.vutronmusic.personal;

/** Claims one fresh-resolution fallback per failed prefetched-player generation. */
final class PrefetchedSourceRetry {
    private int claimedGeneration = -1;
    boolean claim(int active, int failed, boolean prefetched, boolean online) {
        if (active != failed || claimedGeneration == failed || !prefetched || !online) return false;
        claimedGeneration = failed;
        return true;
    }
}
