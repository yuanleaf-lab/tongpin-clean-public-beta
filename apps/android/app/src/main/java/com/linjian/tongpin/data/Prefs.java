package com.linjian.tongpin.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public final class Prefs {
    private static final String NAME = "tongpin_clean";
    private static final String DEFAULT_SERVER = "https://your-service.onrender.com";

    private Prefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String normalizeServer(String value) {
        String server = value == null ? "" : value.trim();
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        if (server.endsWith("/mcp")) server = server.substring(0, server.length() - 4);
        if (server.endsWith("/api")) server = server.substring(0, server.length() - 4);
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        return server.isEmpty() ? DEFAULT_SERVER : server;
    }

    public static String server(Context context) {
        return prefs(context).getString("server", DEFAULT_SERVER);
    }

    public static void saveServer(Context context, String value) {
        prefs(context).edit().putString("server", normalizeServer(value)).apply();
    }


    public static String theme(Context context) {
        return prefs(context).getString("theme", "cream");
    }

    public static void saveTheme(Context context, String value) {
        String clean = value == null ? "cream" : value.trim();
        prefs(context).edit().putString("theme", clean.isEmpty() ? "cream" : clean).apply();
    }

    public static RoomCredentials room(Context context) {
        return new RoomCredentials(
                prefs(context).getString("room_code", ""),
                prefs(context).getString("room_secret", "")
        );
    }

    public static void saveRoom(Context context, RoomCredentials room) {
        prefs(context).edit()
                .putString("room_code", room.code.toUpperCase(Locale.ROOT))
                .putString("room_secret", room.secret)
                .apply();
    }

    public static void clearRoom(Context context) {
        prefs(context).edit()
                .remove("room_code")
                .remove("room_secret")
                .remove("last_command_id")
                .remove("last_command_status")
                .apply();
    }


    public static JSONArray roomNotes(Context context) {
        String raw = prefs(context).getString("room_notes", "[]");
        try {
            JSONArray notes = withStableNoteIds(new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw));
            Set<String> deletedIds = deletedNoteIdSet(context);
            JSONArray visible = new JSONArray();
            for (int i = 0; i < notes.length(); i++) {
                JSONObject note = notes.optJSONObject(i);
                if (note == null) continue;
                if (!deletedIds.contains(stableNoteId(note))) visible.put(note);
            }
            return visible;
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    public static void saveRoomNotes(Context context, String rawJsonArray) {
        String value = rawJsonArray == null || rawJsonArray.trim().isEmpty() ? "[]" : rawJsonArray.trim();
        prefs(context).edit().putString("room_notes", value).apply();
    }

    public static JSONArray deletedNoteIds(Context context) {
        String raw = prefs(context).getString("deleted_note_ids", "[]");
        try {
            return new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    public static void mergeDeletedNoteIds(Context context, JSONArray incomingIds) {
        if (incomingIds == null) return;
        LinkedHashSet<String> ids = deletedNoteIdSet(context);
        for (int i = 0; i < incomingIds.length(); i++) {
            String id = incomingIds.optString(i, "").trim();
            if (!id.isEmpty()) ids.add(id);
        }
        saveDeletedNoteIds(context, ids);
        removeDeletedNotes(context);
    }

    public static void deleteRoomNote(Context context, String noteId) {
        String id = noteId == null ? "" : noteId.trim();
        if (id.isEmpty()) return;
        LinkedHashSet<String> ids = deletedNoteIdSet(context);
        ids.add(id);
        saveDeletedNoteIds(context, ids);
        removeDeletedNotes(context);
    }

    public static void mergeRoomNotes(Context context, JSONArray incomingNotes) {
        if (incomingNotes == null) return;
        Set<String> deletedIds = deletedNoteIdSet(context);
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
        addNotesToMap(merged, roomNotes(context), deletedIds);
        addNotesToMap(merged, incomingNotes, deletedIds);
        List<JSONObject> notes = new ArrayList<>(merged.values());
        notes.sort(Comparator.comparingLong(note -> note.optLong("createdAt", 0L)));
        JSONArray output = new JSONArray();
        int start = Math.max(0, notes.size() - 200);
        for (int i = start; i < notes.size(); i++) {
            output.put(notes.get(i));
        }
        saveRoomNotes(context, output.toString());
    }

    private static void removeDeletedNotes(Context context) {
        Set<String> deletedIds = deletedNoteIdSet(context);
        JSONArray notes = roomNotes(context);
        JSONArray output = new JSONArray();
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            String id = stableNoteId(note);
            if (!deletedIds.contains(id)) output.put(note);
        }
        saveRoomNotes(context, output.toString());
    }

    private static LinkedHashSet<String> deletedNoteIdSet(Context context) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        JSONArray array = deletedNoteIds(context);
        for (int i = 0; i < array.length(); i++) {
            String id = array.optString(i, "").trim();
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private static void saveDeletedNoteIds(Context context, LinkedHashSet<String> ids) {
        JSONArray array = new JSONArray();
        for (String id : ids) array.put(id);
        prefs(context).edit().putString("deleted_note_ids", array.toString()).apply();
    }

    private static JSONArray withStableNoteIds(JSONArray notes) {
        JSONArray output = new JSONArray();
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            stableNoteId(note);
            output.put(note);
        }
        return output;
    }

    public static String stableNoteId(JSONObject note) {
        String id = note.optString("id", "").trim();
        if (!id.isEmpty()) return id;
        String key = note.optString("trackTitle", "")
                + "|" + note.optLong("positionMs", 0L)
                + "|" + note.optLong("createdAt", 0L)
                + "|" + note.optString("text", "");
        id = "legacy_" + Integer.toHexString(key.hashCode());
        try {
            note.put("id", id);
        } catch (Throwable ignored) {
        }
        return id;
    }

    private static void addNotesToMap(LinkedHashMap<String, JSONObject> target, JSONArray notes, Set<String> deletedIds) {
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            String text = note.optString("text", "").trim();
            if (text.isEmpty()) continue;
            String id = stableNoteId(note);
            if (deletedIds.contains(id)) continue;
            target.put(id, note);
        }
    }


    public static boolean backgroundSyncEnabled(Context context) {
        return prefs(context).getBoolean("background_sync_enabled", true);
    }

    public static void saveBackgroundSyncEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("background_sync_enabled", enabled).apply();
    }


    public static boolean qqLyricsEnabled(Context context) {
        return prefs(context).getBoolean("qq_lyrics_enabled", false);
    }

    public static void saveQqLyricsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("qq_lyrics_enabled", enabled).apply();
    }

    public static boolean ocrLyricsEnabled(Context context) {
        return prefs(context).getBoolean("ocr_lyrics_enabled", false);
    }

    public static void saveOcrLyricsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("ocr_lyrics_enabled", enabled).apply();
    }

    public static LiveLyricsSnapshot liveLyrics(Context context) {
        SharedPreferences p = prefs(context);
        return new LiveLyricsSnapshot(
                p.getString("live_lyrics_track_key", ""),
                p.getString("live_lyrics_current", ""),
                p.getString("live_lyrics_next", ""),
                p.getString("live_lyrics_source", ""),
                p.getLong("live_lyrics_observed", 0L)
        );
    }

    public static void saveLiveLyrics(
            Context context,
            String trackKey,
            String current,
            String next,
            String source,
            long observedAt
    ) {
        prefs(context).edit()
                .putString("live_lyrics_track_key", trackKey == null ? "" : trackKey)
                .putString("live_lyrics_current", current == null ? "" : current)
                .putString("live_lyrics_next", next == null ? "" : next)
                .putString("live_lyrics_source", source == null ? "" : source)
                .putLong("live_lyrics_observed", observedAt)
                .apply();
    }

    public static void clearLiveLyrics(Context context) {
        prefs(context).edit()
                .remove("live_lyrics_track_key")
                .remove("live_lyrics_current")
                .remove("live_lyrics_next")
                .remove("live_lyrics_source")
                .remove("live_lyrics_observed")
                .apply();
    }


    private static String manualLyricsStorageKey(String trackKey) {
        String value = trackKey == null ? "" : trackKey;
        return "manual_lyrics_" + Integer.toHexString(value.hashCode());
    }

    public static boolean hasManualLyrics(Context context, String trackKey) {
        return !manualLyrics(context, trackKey).trim().isEmpty();
    }

    public static String manualLyrics(Context context, String trackKey) {
        if (trackKey == null || trackKey.isEmpty()) return "";
        return prefs(context).getString(manualLyricsStorageKey(trackKey), "");
    }

    public static void saveManualLyrics(Context context, String trackKey, String value) {
        if (trackKey == null || trackKey.isEmpty()) return;
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            clearManualLyrics(context, trackKey);
            return;
        }
        prefs(context).edit().putString(manualLyricsStorageKey(trackKey), clean).apply();
    }

    public static void clearManualLyrics(Context context, String trackKey) {
        if (trackKey == null || trackKey.isEmpty()) return;
        prefs(context).edit().remove(manualLyricsStorageKey(trackKey)).apply();
    }

    public static String sourceUrl(Context context) {
        return prefs(context).getString("source_url", "");
    }

    public static void saveSourceUrl(Context context, String value) {
        String clean = value == null ? "" : value.trim();
        SharedPreferences.Editor editor = prefs(context).edit().putString("source_url", clean);
        if (clean.isEmpty()) editor.remove("source_track_key");
        editor.apply();
    }

    public static String sourceTrackKey(Context context) {
        return prefs(context).getString("source_track_key", "");
    }

    public static void bindSourceToCurrentTrack(Context context) {
        PlaybackSnapshot playback = playback(context);
        prefs(context).edit().putString("source_track_key", playback.sourceKey()).apply();
    }

    public static String sourceUrlForTrack(Context context, String sourceKey) {
        String url = sourceUrl(context);
        if (url.isEmpty()) return "";
        String bound = sourceTrackKey(context);
        return !bound.isEmpty() && bound.equals(sourceKey) ? url : "";
    }

    public static String status(Context context) {
        return prefs(context).getString("status", "尚未连接");
    }

    public static void saveStatus(Context context, String value) {
        prefs(context).edit().putString("status", value).apply();
    }

    public static long lastSync(Context context) {
        return prefs(context).getLong("last_sync", 0L);
    }

    public static void saveLastSync(Context context, long value) {
        prefs(context).edit().putLong("last_sync", value).apply();
    }

    public static long lastPlaybackPublish(Context context) {
        return prefs(context).getLong("last_playback_publish", 0L);
    }

    public static void saveLastPlaybackPublish(Context context, long value) {
        prefs(context).edit().putLong("last_playback_publish", value).apply();
    }

    public static long listeningDurationMs(Context context) {
        return prefs(context).getLong("listening_duration_ms", 0L);
    }

    public static void saveListeningDurationMs(Context context, long value) {
        prefs(context).edit().putLong("listening_duration_ms", Math.max(0L, value)).apply();
    }

    public static String lastCommandId(Context context) {
        return prefs(context).getString("last_command_id", "");
    }

    public static void saveLastCommandId(Context context, String value) {
        prefs(context).edit().putString("last_command_id", value).apply();
    }

    public static String lastCommandStatus(Context context) {
        return prefs(context).getString("last_command_status", "");
    }

    public static void saveLastCommandStatus(Context context, String value) {
        prefs(context).edit().putString("last_command_status", value).apply();
    }

    public static String lastCommandResult(Context context) {
        return prefs(context).getString("last_command_result", "尚无命令");
    }

    public static void saveLastCommandResult(Context context, String value) {
        prefs(context).edit().putString("last_command_result", value).apply();
    }

    public static PlaybackSnapshot playback(Context context) {
        SharedPreferences p = prefs(context);
        return new PlaybackSnapshot(
                p.getString("pb_title", "等待播放器"),
                p.getString("pb_artist", "请先在 QQ 音乐、酷狗音乐或网易云音乐播放一首歌"),
                p.getString("pb_album", ""),
                p.getLong("pb_duration", 0L),
                p.getLong("pb_position", 0L),
                p.getBoolean("pb_playing", false),
                p.getString("pb_package", ""),
                p.getString("pb_source", ""),
                p.getLong("pb_observed", 0L),
                p.getString("pb_lyric", ""),
                p.getString("pb_next_lyric", ""),
                p.getString("pb_lyrics_source", ""),
                p.getBoolean("pb_lyrics_synced", false)
        );
    }

    public static void savePlayback(Context context, PlaybackSnapshot value) {
        prefs(context).edit()
                .putString("pb_title", value.title)
                .putString("pb_artist", value.artist)
                .putString("pb_album", value.album)
                .putLong("pb_duration", value.durationMs)
                .putLong("pb_position", value.positionMs)
                .putBoolean("pb_playing", value.playing)
                .putString("pb_package", value.packageName)
                .putString("pb_source", value.sourceUrl)
                .putLong("pb_observed", value.observedAt)
                .putString("pb_lyric", value.lyric)
                .putString("pb_next_lyric", value.nextLyric)
                .putString("pb_lyrics_source", value.lyricsSource)
                .putBoolean("pb_lyrics_synced", value.lyricsSynced)
                .apply();
    }
}
