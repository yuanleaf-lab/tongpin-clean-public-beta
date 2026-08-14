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
  } : null;
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

export function createMcpServer(store: RoomStore): McpServer {
  const server = new McpServer({ name: 'tongpin-clean', version: '1.3.1' });

  server.registerTool('create_room', {
    title: '创建同频房间',
    description: '创建新的共同听歌房间，返回房间码与私密密钥。适用于 ChatGPT、Claude、Gemini SDK 或其他支持 MCP 的 AI 客户端。',
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
        error: 'currentCode 和 currentRoomSecret 必须同时提供；未创建新房间'
      });
    }

    const room = await store.create();
    if (!cleanCurrentCode && !cleanCurrentRoomSecret) {
      return textResult({
        ok: true,
        code: room.code,
        roomSecret: room.secret,
        switchQueued: false,
        switchMessage: '已创建新房间；未提供旧房间信息，手机不会自动切换'
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
        switchMessage: '已创建新房间，并已向旧房间写入切换命令'
      });
    } catch {
      return textResult({
        ok: true,
        code: room.code,
        roomSecret: room.secret,
        switchQueued: false,
        switchMessage: '已创建新房间；旧房间不存在、密钥错误或切换命令写入失败，手机未自动切换'
      });
    }
  });

  server.registerTool('get_room', {
    title: '读取同频房间',
    description: '读取同频房间的完整状态，适用于调试、管理和开发测试。返回完整房间数据，包括播放状态、命令执行结果和听歌笔记。它不是 AI 日常聊歌入口；当用户询问“我现在听什么歌”“这首歌怎么样”“这句歌词什么意思”或“帮我回忆这首歌”等当前歌曲聊天和记忆场景时，应统一优先使用 get_song_context。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10)
    }
  }, async ({ code, roomSecret }) => {
    try {
      return textResult({ ok: true, room: toPublicRoom(store.authenticate(code, roomSecret)) });
    } catch {
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });

  server.registerTool('get_current_context', {
    title: '读取当前音乐上下文',
    description: '只读取当前房间的实时音乐状态。适用于需要当前播放状态、播放进度 positionMs、当前歌词 lyric、下一句歌词 nextLyric、歌词同步状态和歌词来源的场景；不包含历史听歌记忆。如果用户想聊当前歌曲或回忆这首歌过去的记录，优先使用 get_song_context。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10)
    }
  }, async ({ code, roomSecret }) => {
    try {
      const room = toPublicRoom(store.authenticate(code, roomSecret));
      return textResult({
        ok: true,
        context: currentContextOf(room)
      });
    } catch {
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });

  server.registerTool('set_playback_command', {
    title: '控制同频播放',
    description: '向手机当前选中的播放器发送播放、暂停、上一首、下一首或跳转进度命令。支持 QQ 音乐、酷狗音乐、网易云音乐的基础媒体会话控制；调用后需等待手机后台服务领取并回传执行结果。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      command: z.enum(['play', 'pause', 'seek', 'next', 'previous']),
      positionMs: z.number().int().nonnegative().optional()
    }
  }, async ({ code, roomSecret, command, positionMs }) => {
    if (command === 'seek' && positionMs === undefined) {
      return textResult({ ok: false, error: 'seek 命令必须提供 positionMs' });
    }
    try {
      const room = await store.setCommand(code, roomSecret, { type: command, positionMs });
      return textResult({ ok: true, command: room.pendingCommand, result: room.lastCommandResult });
    } catch {
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });


  server.registerTool('search_and_play', {
    title: '搜索并播放歌曲',
    description: '让手机自动搜索指定歌曲并开始播放。优先使用系统媒体搜索；QQ 音乐不响应时，可由已授权的无障碍服务自动打开 QQ 音乐、输入关键词并点击最匹配结果。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10),
      title: z.string().min(1).max(160),
      artist: z.string().max(160).optional()
    }
  }, async ({ code, roomSecret, title, artist }) => {
    try {
      const cleanTitle = title.trim();
      const cleanArtist = artist?.trim() ?? '';
      const query = [cleanTitle, cleanArtist].filter(Boolean).join(' ');
      const room = await store.setCommand(code, roomSecret, {
        type: 'search_play',
        query,
        title: cleanTitle,
        artist: cleanArtist || undefined
      });
      return textResult({ ok: true, command: room.pendingCommand, result: room.lastCommandResult });
    } catch {
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });

  server.registerTool('add_listening_note', {
    title: '添加听歌笔记',
    description: '实际写入同频房间的听歌笔记，而不是只在聊天中口头记住。当用户表达“帮我记一下”“记录这一刻”“收藏这句”“把现在记下来”“留一句笔记”等记录当前听歌的意图时，应优先调用本工具写入房间笔记；工具调用成功后，才可以回复“已记录”。保存听歌记录前，可以先调用 get_song_context 获取当前歌曲、当前歌词和 positionMs，用于记录准确的听歌节点。如果用户没有明确给出笔记文本，可以使用用户刚才的话、当前歌词 lyric，或简短整理后的文字作为 text。positionMs 可以使用当前播放进度，也可以省略，让服务器采用当前进度。必须区分聊天中的口头记住和写入同频房间笔记：用户要求记录当前听歌时，应实际调用 add_listening_note。',
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
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });

  server.registerTool('get_song_memory', {
    title: '查询歌曲听歌记忆',
    description: '查询当前同频房间里某首歌曲过去留下的听歌笔记。当用户询问“我以前听这首歌的时候说过什么”“这首歌有什么回忆”“我有没有记录过这首歌”等历史记忆问题时，可以调用本工具。当前依据 notes 中保存的歌曲标题 trackTitle 匹配历史笔记；artist 用于表达目标歌曲但暂不参与过滤，因为当前 notes 结构不保存歌手。未来如果 notes 保存 artist，可进一步提高匹配精度。',
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
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
    }
  });

  server.registerTool('get_song_context', {
    title: '读取当前歌曲上下文与记忆',
    description: 'AI 聊歌时的主要入口。获取当前播放歌曲、当前歌词、播放状态，以及这首歌过去保存的听歌记忆，供 AI 在聊天时自然引用历史回忆。用户询问正在听的歌、歌词含义、想聊当前歌曲、问这首歌以前有没有记录，或想回忆之前听这首歌的时刻时，应优先调用本工具。调用本工具后，不需要再分别调用 get_current_context 和 get_song_memory；它会读取当前播放歌曲，并按当前歌曲标题合并最多 5 条最近的历史听歌笔记。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10)
    }
  }, async ({ code, roomSecret }) => {
    try {
      const room = toPublicRoom(store.authenticate(code, roomSecret));
      const context = currentContextOf(room);
      if (!context) {
        return textResult({
          ok: true,
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
      return textResult({ ok: false, error: '房间不存在或密钥错误' });
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
