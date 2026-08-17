package com.linjian.tongpin.lyrics;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LyricsRepository {
    private static final String TAG = "LyricsRepository";
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final int MAX_CACHE_SIZE = 32;
    private static final long NEGATIVE_CACHE_MS = 30_000L;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ExecutorService requests = Executors.newFixedThreadPool(5);
    private final List<LyricsProvider> providers;
    private final Object lock = new Object();
    private final AtomicLong generation = new AtomicLong(0L);
    private final Map<String, CachedLyrics> cache = new LinkedHashMap<String, CachedLyrics>(40, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedLyrics> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private String activeKey = "";
    private String loadingKey = "";
    private List<Line> lines = Collections.emptyList();
    private String source = "";
    private String status = "";
    private boolean synced;
    private Future<?> activeTask;

    public LyricsRepository() {
        List<LyricsProvider> values = new ArrayList<>();
        values.add(new LrclibLyricsProvider(requests));
        values.add(new NeteaseLyricsProvider());
        providers = Collections.unmodifiableList(values);
    }

    public void ensure(
            String key,
            String title,
            String artist,
            String album,
            long durationMs,
            Runnable onChanged
    ) {
        if (key == null || key.isEmpty() || title == null || title.trim().isEmpty()) return;

        final long token;
        synchronized (lock) {
            activeKey = key;
            CachedLyrics cached = cache.get(key);
            if (cached != null && (cached.synced || System.currentTimeMillis() - cached.loadedAt < NEGATIVE_CACHE_MS)) {
                lines = cached.lines;
                source = cached.source;
                status = cached.status;
                synced = cached.synced;
                loadingKey = "";
                return;
            }
            if (key.equals(loadingKey)) return;

            if (activeTask != null) activeTask.cancel(true);
            token = generation.incrementAndGet();
            loadingKey = key;
            lines = Collections.emptyList();
            source = "";
            synced = false;
            status = "歌词加载中...";
        }
        if (onChanged != null) onChanged.run();

        activeTask = workers.submit(() -> {
            LoadResult result;
            try {
                result = loadLyrics(title, artist, album, durationMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                result = new LoadResult(Collections.emptyList(), "", "歌词服务暂不可用", false);
            }

            synchronized (lock) {
                if (token != generation.get() || !key.equals(activeKey)) return;
                CachedLyrics value = new CachedLyrics(result.lines, result.source, result.status, result.synced, System.currentTimeMillis());
                cache.put(key, value);
                loadingKey = "";
                lines = value.lines;
                source = value.source;
                status = value.status;
                synced = value.synced;
            }
            if (onChanged != null) onChanged.run();
        });
    }

    public void invalidate(String key) {
        synchronized (lock) {
            if (key == null || key.isEmpty()) return;
            cache.remove(key);
            if (key.equals(activeKey)) {
                generation.incrementAndGet();
                if (activeTask != null) activeTask.cancel(true);
                loadingKey = "";
                lines = Collections.emptyList();
                source = "";
                status = "准备重新匹配歌词";
                synced = false;
            }
        }
    }

    public Snapshot at(String key, long positionMs) {
        synchronized (lock) {
            if (key == null || !key.equals(activeKey)) return new Snapshot("", "", "", false);
            if (lines.isEmpty()) return new Snapshot("", "", status, synced);

            int current = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).timeMs <= positionMs + 150L) current = i;
                else break;
            }
            String lyric = current >= 0 ? lines.get(current).text : "";
            String next = current + 1 < lines.size() ? lines.get(current + 1).text : "";
            return new Snapshot(lyric, next, source.isEmpty() ? "LRCLIB" : source, true);
        }
    }

    public void shutdown() {
        generation.incrementAndGet();
        if (activeTask != null) activeTask.cancel(true);
        workers.shutdownNow();
        requests.shutdownNow();
    }

    private LoadResult loadLyrics(String rawTitle, String rawArtist, String album, long durationMs) throws Exception {
        String fallbackStatus = "";
        boolean requestFailed = false;

        for (LyricsProvider provider : providers) {
            LyricsLoadResult result;
            try {
                result = provider.load(rawTitle, rawArtist, album, durationMs);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Throwable ignored) {
                requestFailed = true;
                continue;
            }

            if (result == null || result.skipped) continue;
            if (!result.status.isEmpty()) fallbackStatus = result.status;
            if (!result.synced || result.syncedLyrics.trim().isEmpty()) continue;

            List<Line> parsed = parseLrc(result.syncedLyrics);
            if ("网易云".equals(result.source)) {
                Log.d(TAG, "netease parseLrc success=" + !parsed.isEmpty()
                        + " lineCount=" + parsed.size());
            }
            if (!parsed.isEmpty()) {
                return new LoadResult(parsed, result.source, result.source, true);
            }
            fallbackStatus = "暂未找到同步歌词";
        }

        if (!fallbackStatus.isEmpty()) {
            return new LoadResult(Collections.emptyList(), "", fallbackStatus, false);
        }
        return new LoadResult(
                Collections.emptyList(),
                "",
                requestFailed ? "歌词服务暂不可用" : "暂未找到同步歌词",
                false
        );
    }

    private static List<Line> parseLrc(String lrc) {
        if (lrc == null || lrc.trim().isEmpty()) return Collections.emptyList();
        List<Line> parsed = new ArrayList<>();
        String[] rows = lrc.replace("\r", "").split("\n");
        for (String row : rows) {
            Matcher matcher = TIMESTAMP.matcher(row);
            List<Long> times = new ArrayList<>();
            int textStart = 0;
            while (matcher.find()) {
                long minutes = parseLong(matcher.group(1));
                long seconds = parseLong(matcher.group(2));
                String fractionText = matcher.group(3);
                long fraction = 0L;
                if (fractionText != null && !fractionText.isEmpty()) {
                    long raw = parseLong(fractionText);
                    if (fractionText.length() == 1) fraction = raw * 100L;
                    else if (fractionText.length() == 2) fraction = raw * 10L;
                    else fraction = raw;
                }
                times.add(minutes * 60_000L + seconds * 1000L + fraction);
                textStart = matcher.end();
            }
            String text = row.substring(Math.min(textStart, row.length())).trim();
            if (text.isEmpty()) continue;
            for (Long time : times) parsed.add(new Line(time, text));
        }
        parsed.sort(Comparator.comparingLong(line -> line.timeMs));
        return Collections.unmodifiableList(parsed);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static final class Snapshot {
        public final String current;
        public final String next;
        public final String source;
        public final boolean synced;

        Snapshot(String current, String next, String source, boolean synced) {
            this.current = current == null ? "" : current;
            this.next = next == null ? "" : next;
            this.source = source == null ? "" : source;
            this.synced = synced;
        }
    }

    private static final class LoadResult {
        final List<Line> lines;
        final String source;
        final String status;
        final boolean synced;

        LoadResult(List<Line> lines, String source, String status, boolean synced) {
            this.lines = lines == null ? Collections.emptyList() : lines;
            this.source = source == null ? "" : source;
            this.status = status == null ? "" : status;
            this.synced = synced;
        }
    }

    private static final class CachedLyrics {
        final List<Line> lines;
        final String source;
        final String status;
        final boolean synced;
        final long loadedAt;

        CachedLyrics(List<Line> lines, String source, String status, boolean synced, long loadedAt) {
            this.lines = lines == null ? Collections.emptyList() : lines;
            this.source = source == null ? "" : source;
            this.status = status == null ? "" : status;
            this.synced = synced;
            this.loadedAt = loadedAt;
        }
    }

    private static final class Line {
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }
}
