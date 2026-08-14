package com.linjian.tongpin.data;

public final class RemoteCommand {
    public final String id;
    public final String type;
    public final Long positionMs;
    public final String query;
    public final String title;
    public final String artist;
    public final String targetCode;
    public final String targetSecret;

    public RemoteCommand(String id, String type, Long positionMs) {
        this(id, type, positionMs, "", "", "");
    }

    public RemoteCommand(
            String id,
            String type,
            Long positionMs,
            String query,
            String title,
            String artist
    ) {
        this(id, type, positionMs, query, title, artist, "", "");
    }

    public RemoteCommand(
            String id,
            String type,
            Long positionMs,
            String query,
            String title,
            String artist,
            String targetCode,
            String targetSecret
    ) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "" : type;
        this.positionMs = positionMs;
        this.query = query == null ? "" : query.trim();
        this.title = title == null ? "" : title.trim();
        this.artist = artist == null ? "" : artist.trim();
        this.targetCode = targetCode == null ? "" : targetCode.trim();
        this.targetSecret = targetSecret == null ? "" : targetSecret.trim();
    }
}
