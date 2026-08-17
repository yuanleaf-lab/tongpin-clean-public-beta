package com.linjian.tongpin.lyrics;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class NeteaseLyricsProvider implements LyricsProvider {
    private static final String TAG = "NeteaseLyricsProvider";
    private static final String SOURCE = "网易云";
    private static final String SEARCH = "https://music.163.com/api/search/get";
    private static final String LYRIC = "https://music.163.com/api/song/lyric";
    private static final Pattern TIMESTAMP = Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]");
    private static final int SEARCH_LIMIT = 8;
    private static final int MAX_LYRIC_CANDIDATES = 5;

    @Override
    public LyricsLoadResult load(String title, String artist, String album, long durationMs) throws Exception {
        Log.d(TAG, "search start title=" + safe(title)
                + " artist=" + safe(artist)
                + " durationMs=" + durationMs);
        List<Song> songs = search(title, artist, durationMs);
        Log.d(TAG, "search result title=" + safe(title)
                + " artist=" + safe(artist)
                + " candidates=" + songs.size());
        int checked = 0;
        for (Song song : songs) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (song.score < 125 && checked > 0) continue;
            Log.d(TAG, "candidate songId=" + song.id
                    + " title=" + safe(song.title)
                    + " artist=" + safe(song.artist)
                    + " durationMs=" + song.durationMs
                    + " score=" + song.score);
            String lrc = loadLyric(song.id);
            checked += 1;
            boolean hasLrc = lrc != null && !lrc.trim().isEmpty();
            boolean hasTimeline = hasSyncedLrc(lrc);
            Log.d(TAG, "lyric songId=" + song.id
                    + " hasLrc=" + hasLrc
                    + " hasTimeline=" + hasTimeline);
            if (hasTimeline) return LyricsLoadResult.synced(SOURCE, lrc);
            if (checked >= MAX_LYRIC_CANDIDATES) break;
        }
        Log.d(TAG, "search finished without synced LRC checked=" + checked);
        return LyricsLoadResult.status(SOURCE, "暂未找到同步歌词");
    }

    private static List<Song> search(String title, String artist, long durationMs) throws Exception {
        List<JSONObject> records = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        collectSearch(records, seen, title, artist);
        collectSearch(records, seen, title, "");

        List<Song> songs = new ArrayList<>();
        for (JSONObject item : records) {
            Song song = Song.from(item);
            if (song == null) continue;
            song.score = confidence(song, title, artist, durationMs);
            Log.d(TAG, "score title=" + safe(song.title)
                    + " artist=" + safe(song.artist)
                    + " durationMs=" + song.durationMs
                    + " score=" + song.score);
            if (song.score >= 80) songs.add(song);
        }
        songs.sort(Comparator.comparingInt((Song value) -> value.score).reversed());
        return songs;
    }

    private static void collectSearch(List<JSONObject> out, Set<Long> seen, String title, String artist) throws Exception {
        String query = ((title == null ? "" : title.trim()) + " " + (artist == null ? "" : artist.trim())).trim();
        if (query.isEmpty()) return;
        String url = SEARCH + "?csrf_token=&s=" + encode(query)
                + "&type=1&offset=0&total=true&limit=" + SEARCH_LIMIT;
        JSONObject root = getObject(url, "https://music.163.com/search/");
        JSONArray songs = root.optJSONObject("result") == null
                ? null
                : root.optJSONObject("result").optJSONArray("songs");
        if (songs == null) return;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            long id = song.optLong("id", 0L);
            if (id <= 0L || !seen.add(id)) continue;
            out.add(song);
        }
    }

    private static String loadLyric(long id) throws Exception {
        if (id <= 0L) return "";
        JSONObject root = getObject(
                LYRIC + "?id=" + id + "&lv=1&kv=1&tv=-1",
                "https://music.163.com/song?id=" + id
        );
        JSONObject lrc = root.optJSONObject("lrc");
        return lrc == null ? "" : lrc.optString("lyric", "");
    }

    private static int confidence(Song song, String rawTitle, String rawArtist, long durationMs) {
        String itemTitle = normalize(song.title);
        String itemArtist = normalize(song.artist);
        String expectedTitle = normalize(rawTitle);
        String expectedArtist = normalize(rawArtist);
        long expectedSeconds = Math.max(0L, Math.round(durationMs / 1000.0));
        long itemSeconds = Math.max(0L, Math.round(song.durationMs / 1000.0));

        int score = 0;
        if (itemTitle.equals(expectedTitle)) score += 100;
        else if (!expectedTitle.isEmpty() && (itemTitle.contains(expectedTitle) || expectedTitle.contains(itemTitle))) score += 45;
        else score -= 60;

        if (!expectedArtist.isEmpty() && itemArtist.equals(expectedArtist)) score += 70;
        else if (!expectedArtist.isEmpty() && (itemArtist.contains(expectedArtist) || expectedArtist.contains(itemArtist))) score += 34;
        else if (!expectedArtist.isEmpty() && !itemArtist.isEmpty()) score -= 55;

        if (expectedSeconds > 0L && itemSeconds > 0L) {
            long diff = Math.abs(itemSeconds - expectedSeconds);
            score += diff <= 2 ? 50 : diff <= 6 ? 28 : diff <= 12 ? 10 : diff <= 25 ? -8 : -45;
        }
        return score;
    }

    private static boolean hasSyncedLrc(String value) {
        return value != null && TIMESTAMP.matcher(value).find();
    }

    private static JSONObject getObject(String url, String referer) throws Exception {
        Response response = request(url, referer);
        if (response.code < 200 || response.code > 299) throw new IllegalStateException("HTTP " + response.code);
        return response.text.isEmpty() ? new JSONObject() : new JSONObject(response.text);
    }

    private static Response request(String value, String referer) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(1_800);
        connection.setReadTimeout(2_700);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("User-Agent", "TongpinClean/1.3.1-public (Android; NetEase lyrics fallback)");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code <= 299
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String text = "";
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        output.write(buffer, 0, read);
                    }
                    text = output.toString(StandardCharsets.UTF_8.name());
                }
            }
            return new Response(code, text);
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value.trim(), "UTF-8");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", "")
                .trim();
    }

    private static String safe(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 80 ? clean : clean.substring(0, 80);
    }

    private static final class Song {
        final long id;
        final String title;
        final String artist;
        final long durationMs;
        int score;

        Song(long id, String title, String artist, long durationMs) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.durationMs = Math.max(0L, durationMs);
        }

        static Song from(JSONObject value) {
            if (value == null) return null;
            long id = value.optLong("id", 0L);
            if (id <= 0L) return null;
            String title = value.optString("name", "");
            long duration = value.optLong("duration", value.optLong("dt", 0L));
            JSONArray artists = value.optJSONArray("artists");
            if (artists == null) artists = value.optJSONArray("ar");
            List<String> names = new ArrayList<>();
            if (artists != null) {
                for (int i = 0; i < artists.length(); i++) {
                    JSONObject artist = artists.optJSONObject(i);
                    if (artist == null) continue;
                    String name = artist.optString("name", "").trim();
                    if (!name.isEmpty()) names.add(name);
                }
            }
            return new Song(id, title, join(names), duration);
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private static final class Response {
        final int code;
        final String text;

        Response(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }
}
