import type { Request, Response } from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import * as z from 'zod/v4';
import type { RoomStore } from './store.js';
import { toPublicRoom, type PlaybackCommand, type PublicRoom, type Room } from './types.js';

type RoomArgs = {
  code?: string;
  roomSecret?: string;
};

type McpAccess = {
  canUseActiveRoom: boolean;
};

const optionalRoomCode = z.string().min(6).optional();
const optionalRoomSecret = z.string().min(10).optional();

const textResult = (value: unknown) => ({
  content: [{ type: 'text' as const, text: JSON.stringify(value, null, 2) }]
});

const normalizeSongKey = (value: string): string => value.trim().toLocaleLowerCase();

const sanitizeCommand = (command: PlaybackCommand | null): Omit<PlaybackCommand, 'targetSecret'> | null => {
  if (!command) return null;
  const { targetSecret: _targetSecret, ...safeCommand } = command;
  return safeCommand;
};

const toMcpRoom = (room: Room): PublicRoom => {
  const publicRoom = toPublicRoom(room);
  return {
    ...publicRoom,
    pendingCommand: sanitizeCommand(publicRoom.pendingCommand)
  } as PublicRoom;
};

const resolveRoom = (store: RoomStore, access: McpAccess, args: RoomArgs): Room => {
  const code = args.code?.trim().toUpperCase() ?? '';
  const roomSecret = args.roomSecret?.trim() ?? '';
  if ((code && !roomSecret) || (!code && roomSecret)) {
    throw new Error('ROOM_CREDENTIALS_INCOMPLETE');
  }
  if (code && roomSecret) return store.authenticate(code, roomSecret);
  if (!access.canUseActiveRoom) throw new Error('ACTIVE_ROOM_OWNER_TOKEN_REQUIRED');
  const room = store.activeRoom();
  if (!room) throw new Error('ACTIVE_ROOM_NOT_AVAILABLE');
  return room;
};

const roomError = (error: unknown): string => {
  const message = error instanceof Error ? error.message : '';
  if (message === 'ROOM_CREDENTIALS_INCOMPLETE') {
    return 'code and roomSecret must be provided together, or connect to /mcp with MCP_OWNER_TOKEN to use the active room.';
  }
  if (message === 'ACTIVE_ROOM_OWNER_TOKEN_REQUIRED') {
    return 'Missing code/roomSecret. Active room fallback requires an MCP_OWNER_TOKEN-protected /mcp connection.';
  }
  if (message === 'ACTIVE_ROOM_NOT_AVAILABLE') {
    return 'No active room is available. Confirm the phone is syncing, or provide code/roomSecret explicitly.';
  }
  return 'Room not found or roomSecret is invalid.';
};

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
    text: note.text,
    positionMs: note.positionMs,
    createdAt: note.createdAt
  }));
};

export function createMcpServer(store: RoomStore, access: McpAccess = { canUseActiveRoom: false }): McpServer {
  const server = new McpServer({ name: 'tongpin-clean', version: '1.3.1' });

  server.registerTool('create_room', {
    title: 'Create Tongpin room',
    description: 'Create a new room. The MCP response intentionally does not expose roomSecret; use the phone switch flow or REST owner setup for room credentials.',
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
        switchQueued: true,
        switchCommandId: switched.pendingCommand?.id,
        switchMessage: 'New room created and a switch command was queued for the old room.'
      });
    } catch {
      return textResult({
        ok: true,
        code: room.code,
        switchQueued: false,
        switchMessage: 'New room created, but the old room could not be authenticated so the phone was not switched.'
      });
    }
  });

  server.registerTool('get_room', {
    title: 'Read Tongpin room',
    description: 'Read full room state. code/roomSecret are optional only when this MCP request is protected by MCP_OWNER_TOKEN and the phone has an active room.',
    inputSchema: {
      code: optionalRoomCode,
      roomSecret: optionalRoomSecret
    }
  }, async ({ code, roomSecret }) => {
    try {
      return textResult({ ok: true, room: toMcpRoom(resolveRoom(store, access, { code, roomSecret })) });
    } catch (error) {
      return textResult({ ok: false, error: roomError(error) });
    }
  });

  server.registerTool('get_current_context', {
    title: 'Read current music context',
    description: 'Read current playback, lyrics, and listeningDurationMs. code/roomSecret are optional only for MCP_OWNER_TOKEN-protected active room access.',
    inputSchema: {
      code: optionalRoomCode,
      roomSecret: optionalRoomSecret
    }
  }, async ({ code, roomSecret }) => {
    try {
      const room = toMcpRoom(resolveRoom(store, access, { code, roomSecret }));
      return textResult({ ok: true, context: currentContextOf(room) });
    } catch (error) {
      return textResult({ ok: false, error: roomError(error) });
    }
  });

  server.registerTool('set_playback_command', {
    title: 'Control playback',
    description: 'Send play, pause, seek, next, or previous to the phone. code/roomSecret are optional only for MCP_OWNER_TOKEN-protected active room access.',
    inputSchema: {
      code: optionalRoomCode,
      roomSecret: optionalRoomSecret,
      command: z.enum(['play', 'pause', 'seek', 'next', 'previous']),
      positionMs: z.number().int().nonnegative().optional()
    }
  }, async ({ code, roomSecret, command, positionMs }) => {
    if (command === 'seek' && positionMs === undefined) {
      return textResult({ ok: false, error: 'seek requires positionMs' });
    }
    try {
      const target = resolveRoom(store, access, { code, roomSecret });
      const room = await store.setCommand(target.code, target.secret, { type: command, positionMs });
      return textResult({ ok: true, command: sanitizeCommand(room.pendingCommand), result: room.lastCommandResult });
    } catch (error) {
      return textResult({ ok: false, error: roomError(error) });
    }
  });

  server.registerTool('search_and_play', {
    title: 'Search and play',
    description: 'Ask the phone to search and play a song. code/roomSecret are optional only for MCP_OWNER_TOKEN-protected active room access.',
    inputSchema: {
      code: optionalRoomCode,
      roomSecret: optionalRoomSecret,
      title: z.string().min(1).max(160),
      artist: z.string().max(160).optional()
    }
  }, async ({ code, roomSecret, title, artist }) => {
    try {
      const cleanTitle = title.trim();
      const cleanArtist = artist?.trim() ?? '';
      const query = [cleanTitle, cleanArtist].filter(Boolean).join(' ');
      const target = resolveRoom(store, access, { code, roomSecret });
      const room = await store.setCommand(target.code, target.secret, {
        type: 'search_play',
        query,
        title: cleanTitle,
        artist: cleanArtist || undefined
      });
      return textResult({ ok: true, command: sanitizeCommand(room.pendingCommand), result: room.lastCommandResult });
    } catch (error) {
      return textResult({ ok: false, error: roomError(error) });
    }
  });

  server.registerTool('add_listening_note', {
    title: 'Add listening note',
    description: 'Write a listening note into the room. code/roomSecret are optional only for MCP_OWNER_TOKEN-protected active room access.',
    inputSchema: {
      code: optionalRoomCode,
      roomSecret: optionalRoomSecret,
      text: z.string().min(1).max(500),
      positionMs: z.number().int().nonnegative().optional()
    }
  }, async ({ code, roomSecret, text, positionMs }) => {
    try {
      const target = resolveRoom(store, access, { code, roomSecret });
      const room = await store.addNote(target.code, target.secret, text, positionMs);
      return textResult({ ok: true, note: room.notes.at(-1) });
    } catch (error) {
      return textResult({ ok: false, error: roomError(error) });
    }
  });

  server.registerTool('get_song_memory', {
    title: 'Read song memory',
    description: 'Read notes previously saved for a song title. code/roomSecret are optional only for MCP_OWNER_TOKEN-protected active room access.',
    inputSchema: {
      code: optionalRoomCode,
      roomSecret: optionalRoomSecret,
      title: z.string().min(1).max(200),
      artist: z.string().min(1).max(200)
    }
  }, async ({ code, roomSecret, title, artist }) => {
    try {
      const cleanTitle = title.trim();
      const cleanArtist = artist.trim();
      const room = resolveRoom(store, access, { code, roomSecret });
      const memories = songMemoriesOf(room, cleanTitle);
      return textResult({
        ok: true,
        song: {
          title: cleanTitle,
          artist: cleanArtist
        },
        memories
      });
    } catch (error) {
      return textResult({ ok: false, error: roomError(error) });
    }
  });

  server.registerTool('get_song_context', {
    title: 'Read current song context and memory',
    description: 'Primary music-chat context tool. Returns current song, playback, lyrics, listeningDurationMs, and recent memories. code/roomSecret are optional only for MCP_OWNER_TOKEN-protected active room access.',
    inputSchema: {
      code: optionalRoomCode,
      roomSecret: optionalRoomSecret
    }
  }, async ({ code, roomSecret }) => {
    try {
      const room = toMcpRoom(resolveRoom(store, access, { code, roomSecret }));
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
    } catch (error) {
      return textResult({ ok: false, error: roomError(error) });
    }
  });

  return server;
}

export async function handleMcpRequest(store: RoomStore, req: Request, res: Response): Promise<void> {
  const ownerToken = process.env.MCP_OWNER_TOKEN ?? '';
  const auth = req.header('authorization') ?? '';
  const bearer = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  const queryToken = typeof req.query.access_token === 'string' ? req.query.access_token : '';
  const canUseActiveRoom = Boolean(ownerToken) && (bearer === ownerToken || queryToken === ownerToken);
  const server = createMcpServer(store, { canUseActiveRoom });
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
