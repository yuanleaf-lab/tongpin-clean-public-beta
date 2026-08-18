package com.linjian.tongpin.data;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RoomApi {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private RoomApi() {}

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(Throwable error);
    }

    private static JSONObject request(String method, String url, String secret, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "application/json");
        if (secret != null && !secret.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + secret);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

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
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    text = output.toString(StandardCharsets.UTF_8.name());
                }
            }
            if (code < 200 || code > 299) {
                throw new IOException("HTTP " + code + (text.isEmpty() ? "" : ": " + text));
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }

    public static void createRoom(Context context, String serverInput, Callback<RoomCredentials> callback) {
        EXECUTOR.execute(() -> {
            try {
                String server = Prefs.normalizeServer(serverInput);
                JSONObject json = request("POST", server + "/api/rooms", null, new JSONObject());
                RoomCredentials room = new RoomCredentials(json.getString("code"), json.getString("roomSecret"));
                Prefs.saveServer(context, server);
                Prefs.saveRoom(context, room);
                Prefs.saveStatus(context, "房间已创建，等待媒体服务连接");
                callback.onSuccess(room);
            } catch (Throwable error) {
                callback.onError(error);
            }
        });
    }

    public static void health(String serverInput, Callback<String> callback) {
        EXECUTOR.execute(() -> {
            try {
                String server = Prefs.normalizeServer(serverInput);
                JSONObject json = request("GET", server + "/health", null, null);
                callback.onSuccess(json.optString("version", "ok"));
            } catch (Throwable error) {
                callback.onError(error);
            }
        });
    }

    public static void sendChat(Context context, String message, Callback<String> callback) {
        EXECUTOR.execute(() -> {
            try {
                String cleanMessage = message == null ? "" : message.trim();
                if (cleanMessage.isEmpty()) throw new IOException("消息不能为空");
                String server = Prefs.server(context);
                RoomCredentials room = Prefs.room(context);
                if (room.code.isEmpty() || room.secret.isEmpty()) throw new IOException("尚未创建房间");
                JSONObject body = new JSONObject().put("message", cleanMessage);
                JSONObject json = request("POST", server + "/api/rooms/" + room.code + "/chat", room.secret, body);
                callback.onSuccess(json.optString("reply", ""));
            } catch (Throwable error) {
                callback.onError(error);
            }
        });
    }

    public static JSONObject getRoomSync(Context context) throws Exception {
        String server = Prefs.server(context);
        RoomCredentials room = Prefs.room(context);
        if (room.code.isEmpty() || room.secret.isEmpty()) throw new IOException("尚未创建房间");
        return request("GET", server + "/api/rooms/" + room.code, room.secret, null);
    }

    public static void publishPlaybackSync(Context context, PlaybackSnapshot snapshot) throws Exception {
        String server = Prefs.server(context);
        RoomCredentials room = Prefs.room(context);
        if (room.code.isEmpty() || room.secret.isEmpty()) return;
        JSONObject body = new JSONObject()
                .put("title", snapshot.title)
                .put("artist", snapshot.artist)
                .put("album", snapshot.album)
                .put("durationMs", snapshot.durationMs)
                .put("positionMs", snapshot.positionMs)
                .put("playing", snapshot.playing)
                .put("packageName", snapshot.packageName)
                .put("sourceUrl", snapshot.sourceUrl)
                .put("observedAt", snapshot.observedAt)
                .put("lyric", snapshot.lyric)
                .put("nextLyric", snapshot.nextLyric)
                .put("lyricsSource", snapshot.lyricsSource)
                .put("lyricsSynced", snapshot.lyricsSynced)
                .put("listeningDurationMs", Prefs.listeningDurationMs(context))
                .put("notes", Prefs.roomNotes(context))
                .put("deletedNoteIds", Prefs.deletedNoteIds(context));
        request("POST", server + "/api/rooms/" + room.code + "/playback", room.secret, body);
    }

    public static void deleteNote(Context context, String noteId, Callback<Boolean> callback) {
        EXECUTOR.execute(() -> {
            try {
                String cleanNoteId = noteId == null ? "" : noteId.trim();
                if (cleanNoteId.isEmpty()) throw new IOException("笔记 ID 为空");
                String server = Prefs.server(context);
                RoomCredentials room = Prefs.room(context);
                if (room.code.isEmpty() || room.secret.isEmpty()) throw new IOException("尚未创建房间");
                JSONObject json = request(
                        "DELETE",
                        server + "/api/rooms/" + room.code + "/notes/" + cleanNoteId,
                        room.secret,
                        null
                );
                if (json.optJSONArray("deletedNoteIds") != null) {
                    Prefs.mergeDeletedNoteIds(context, json.optJSONArray("deletedNoteIds"));
                } else if (json.optJSONObject("room") != null && json.optJSONObject("room").optJSONArray("deletedNoteIds") != null) {
                    Prefs.mergeDeletedNoteIds(context, json.optJSONObject("room").optJSONArray("deletedNoteIds"));
                }
                if (json.optJSONObject("room") != null && json.optJSONObject("room").optJSONArray("notes") != null) {
                    Prefs.mergeRoomNotes(context, json.optJSONObject("room").optJSONArray("notes"));
                }
                callback.onSuccess(json.optBoolean("deleted", true));
            } catch (Throwable error) {
                callback.onError(error);
            }
        });
    }

    public static void acknowledgeCommandSync(Context context, String commandId, String status, String message) throws Exception {
        String server = Prefs.server(context);
        RoomCredentials room = Prefs.room(context);
        if (room.code.isEmpty() || room.secret.isEmpty()) return;
        acknowledgeCommandSync(server, room.code, room.secret, commandId, status, message);
    }

    public static void acknowledgeCommandSync(
            String server,
            String roomCode,
            String roomSecret,
            String commandId,
            String status,
            String message
    ) throws Exception {
        if (server == null || server.isEmpty() || roomCode == null || roomCode.isEmpty() || roomSecret == null || roomSecret.isEmpty()) {
            return;
        }
        JSONObject body = new JSONObject().put("status", status).put("message", message);
        request(
                "POST",
                server + "/api/rooms/" + roomCode + "/commands/" + commandId + "/ack",
                roomSecret,
                body
        );
    }
}
