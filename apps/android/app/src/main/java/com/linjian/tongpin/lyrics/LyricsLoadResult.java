package com.linjian.tongpin.lyrics;

final class LyricsLoadResult {
    final String source;
    final String syncedLyrics;
    final String status;
    final boolean synced;
    final boolean skipped;

    private LyricsLoadResult(String source, String syncedLyrics, String status, boolean synced, boolean skipped) {
        this.source = source == null ? "" : source;
        this.syncedLyrics = syncedLyrics == null ? "" : syncedLyrics;
        this.status = status == null ? "" : status;
        this.synced = synced;
        this.skipped = skipped;
    }

    static LyricsLoadResult synced(String source, String syncedLyrics) {
        return new LyricsLoadResult(source, syncedLyrics, source, true, false);
    }

    static LyricsLoadResult status(String source, String status) {
        return new LyricsLoadResult(source, "", status, false, false);
    }

    static LyricsLoadResult skipped(String source) {
        return new LyricsLoadResult(source, "", "", false, true);
    }
}
