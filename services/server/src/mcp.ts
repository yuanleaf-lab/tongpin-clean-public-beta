import type { Request, Response } from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import * as z from 'zod/v4';
import type { RoomStore } from './store.js';
import { toPublicRoom, type PublicRoom } from './types.js';

const textResult = (value: unknown) => ({
  content: [{ type: 'text' as const, text: JSON.stringify(value, null, 2) }]
});

const normalizeSongKey = (value: string): string => value.trim().toLocaleLowerCase();

const currentContextOf = (room: PublicRoom) => {
  const playback = room.playback;
  return playback ? {
    listeningDurationMs: room.listeningDurationMs,
    song: {
      title: playback.title,
      artist: playback.artist,
      album: playback.album,
      playerName: playback.playerName
    },
    playback: {
      playing: playback.playing,
      positionMs: playback.positionMs
    },
    lyrics: {
      current: playback.lyric,
      next: playback.nextLyric,
      synced: playback.lyricsSynced,
      source: playback.lyricsSource
    }
  } : {
    listeningDurationMs: room.listeningDurationMs,
    song: null,
    playback: null,
    lyrics: null
  };
};

const songMemoriesOf = (
  room: Pick<PublicRoom, 'notes'>,
  title: string,
  options: { limit?: number; newestFirst?: boolean } = {}
) => {
  const targetTitle = normalizeSongKey(title);
  const notes = room.notes.filter(note => normalizeSongKey(note.trackTitle) === targetTitle);
  const orderedNotes = options.newestFirst
    ? [...notes].sort((a, b) => b.createdAt - a.createdAt)
    : notes;
  const limitedNotes = options.limit === undefined ? orderedNotes : orderedNotes.slice(0, options.limit);
  return limitedNotes.map(note => ({
    id: note.id,
    text: note.text,
    positionMs: note.positionMs,
    createdAt: note.createdAt
  }));
};

const roomAuthError = 'Room not found or roomSecret is invalid.';

export function createMcpServer(store: RoomStore): McpServer {
  const server = new McpServer({ name: 'tongpin-clean', version: '1.3.1' });

  server.registerTool('create_room', {
    title: 'Create Tongpin room',
    description: 'Create a new shared listening room and return the room code and roomSecret.',
    inputSchema: {
      currentCode: z.string().optional(),
      currentRoomSecret: z.string().optional()
    }
  }, async ({ currentCode, currentRoomSecret }) => {
    const cleanCurrentCode = currentCode?.trim().toUpperCase() ?? '';
    const cleanCurrentRoomSecret = currentRoomSecret?.trim() ?? '';
    if ((cleanCurrentCode && !cleanCurrentRoomSecret) || (!cleanCurrentCode && cleanCurrentRoomSecret)) {
      return textResult({
        ok: false,
        error: 'currentCode and currentRoomSecret must be provided together; no new room was created.'
      });
    }

    const room = await store.create();
    if (!cleanCurrentCode && !cleanCurrentRoomSecret) {
      return textResult({
        ok: true,
        code: room.code,
        roomSecret: room.secret,
        switchQueued: false,
        switchMessage: 'New room created. The phone was not switched because no current room credentials were provided.'
      });
    }

    try {
      const switched = await store.setCommand(cleanCurrentCode, cleanCurrentRoomSecret, {
        type: 'switch_room',
        targetCode: room.code,
        targetSecret: room.secret
      });
      return textResult({
        ok: true,
        code: room.code,
        roomSecret: room.secret,
        switchQueued: true,
        switchCommandId: switched.pendingCommand?.id,
        switchMessage: 'New room created and a switch command was queued for the old room.'
      });
    } catch {
      return textResult({
        ok: true,
        code: room.code,
        roomSecret: room.secret,
        switchQueued: false,
        switchMessage: 'New room created, but the old room could not be authenticated so the phone was not switched.'
      });
    }
  });

  server.registerTool('get_room', {
    title: 'Read Tongpin room',
    description: 'Read full room state. Requires code and roomSecret.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10)
    }
  }, async ({ code, roomSecret }) => {
    try {
      return textResult({ ok: true, room: toPublicRoom(store.authenticate(code, roomSecret)) });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  server.registerTool('get_current_context', {
    title: 'Read current music context',
    description: 'Read current playback, lyrics, and listeningDurationMs. Requires code and roomSecret.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10)
    }
  }, async ({ code, roomSecret }) => {
    try {
      const room = toPublicRoom(store.authenticate(code, roomSecret));
      return textResult({ ok: true, context: currentContextOf(room) });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  server.registerTool('set_playback_command', {
    title: 'Control playback',
    description: 'Send play, pause, seek, next, or previous to the phone. Requires code and roomSecret.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      command: z.enum(['play', 'pause', 'seek', 'next', 'previous']),
      positionMs: z.number().int().nonnegative().optional()
    }
  }, async ({ code, roomSecret, command, positionMs }) => {
    if (command === 'seek' && positionMs === undefined) {
      return textResult({ ok: false, error: 'seek requires positionMs' });
    }
    try {
      const room = await store.setCommand(code, roomSecret, { type: command, positionMs });
      return textResult({ ok: true, command: room.pendingCommand, result: room.lastCommandResult });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  server.registerTool('search_and_play', {
    title: 'Search and play',
    description: 'Queue a strict title-and-artist search on the phone. The initial response only proves queuing; use get_command_status for the final device result.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      title: z.string().min(1).max(160),
      artist: z.string().min(1).max(160)
    }
  }, async ({ code, roomSecret, title, artist }) => {
    try {
      const cleanTitle = title.trim();
      const cleanArtist = artist.trim();
      const query = [cleanTitle, cleanArtist].filter(Boolean).join(' ');
      const room = await store.setCommand(code, roomSecret, {
        type: 'search_play',
        query,
        title: cleanTitle,
        artist: cleanArtist
      });
      return textResult({ ok: true, commandId: room.pendingCommand?.id, command: room.pendingCommand, result: room.lastCommandResult, note: 'queued only means the server accepted the command; query get_command_status for device execution.' });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  server.registerTool('get_command_status', {
    title: 'Read command execution status',
    description: 'Read the latest execution status for one command, including selected and confirmed playback metadata.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      commandId: z.string().min(1)
    }
  }, async ({ code, roomSecret, commandId }) => {
    try {
      const room = store.authenticate(code, roomSecret);
      const result = (room.commandResults ?? []).find(value => value.commandId === commandId);
      return textResult(result
        ? { ok: true, commandId, pending: room.pendingCommand?.id === commandId, result }
        : { ok: false, error: 'COMMAND_NOT_FOUND' });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  server.registerTool('add_listening_note', {
    title: 'Add listening note',
    description: 'Write a listening note into the room. Requires code and roomSecret.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      text: z.string().min(1).max(500),
      positionMs: z.number().int().nonnegative().optional()
    }
  }, async ({ code, roomSecret, text, positionMs }) => {
    try {
      const room = await store.addNote(code, roomSecret, text, positionMs);
      return textResult({ ok: true, note: room.notes.at(-1) });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  server.registerTool('delete_listening_note', {
    title: 'Delete listening note',
    description: 'Delete one listening note by noteId. Requires code and roomSecret.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      noteId: z.string().min(1).max(120)
    }
  }, async ({ code, roomSecret, noteId }) => {
    try {
      const result = await store.deleteNote(code, roomSecret, noteId);
      return textResult({ ok: true, noteId, deleted: result.deleted });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  server.registerTool('get_song_memory', {
    title: 'Read song memory',
    description: 'Read notes previously saved for a song title. Requires code and roomSecret.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      title: z.string().min(1).max(200),
      artist: z.string().min(1).max(200)
    }
  }, async ({ code, roomSecret, title, artist }) => {
    try {
      const cleanTitle = title.trim();
      const cleanArtist = artist.trim();
      const room = store.authenticate(code, roomSecret);
      const memories = songMemoriesOf(room, cleanTitle);
      return textResult({
        ok: true,
        song: {
          title: cleanTitle,
          artist: cleanArtist
        },
        memories
      });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  server.registerTool('get_song_context', {
    title: 'Read current song context and memory',
    description: 'Primary music-chat context tool. Returns current song, playback, lyrics, listeningDurationMs, and recent memories. Requires code and roomSecret.',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10)
    }
  }, async ({ code, roomSecret }) => {
    try {
      const room = toPublicRoom(store.authenticate(code, roomSecret));
      const context = currentContextOf(room);
      if (!context.song) {
        return textResult({
          ok: true,
          listeningDurationMs: room.listeningDurationMs,
          song: null,
          playback: null,
          lyrics: null,
          memories: []
        });
      }
      return textResult({
        ok: true,
        ...context,
        memories: songMemoriesOf(room, context.song.title, { limit: 5, newestFirst: true })
      });
    } catch {
      return textResult({ ok: false, error: roomAuthError });
    }
  });

  return server;
}

export async function handleMcpRequest(store: RoomStore, req: Request, res: Response): Promise<void> {
  const server = createMcpServer(store);
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    enableJsonResponse: true
  });
  let closed = false;
  const close = async () => {
    if (closed) return;
    closed = true;
    await transport.close();
    await server.close();
  };
  res.once('finish', () => void close());
  res.once('close', () => void close());
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error('MCP request failed', error);
    if (!res.headersSent) {
      res.status(500).json({ jsonrpc: '2.0', error: { code: -32603, message: 'Internal server error' }, id: null });
    }
  }
}
