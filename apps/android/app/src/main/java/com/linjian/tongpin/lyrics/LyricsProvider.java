package com.linjian.tongpin.lyrics;

interface LyricsProvider {
    LyricsLoadResult load(String title, String artist, String album, long durationMs) throws Exception;
}
