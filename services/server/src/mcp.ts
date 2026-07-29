import type { Request, Response } from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import * as z from 'zod/v4';
import type { RoomStore } from './store.js';
import { toPublicRoom } from './types.js';

const textResult = (value: unknown) => ({
  content: [{ type: 'text' as const, text: JSON.stringify(value, null, 2) }]
});

const normalizeSongKey = (value: string): string => value.trim().toLocaleLowerCase();

export function createMcpServer(store: RoomStore): McpServer {
  const server = new McpServer({ name: 'tongpin-clean', version: '1.3.1' });

  server.registerTool('create_room', {
    title: '创建同频房间',
    description: '创建新的共同听歌房间，返回房间码与私密密钥。适用于 ChatGPT、Claude、Gemini SDK 或其他支持 MCP 的 AI 客户端。',
    inputSchema: {}
  }, async () => {
    const room = await store.create();
    return textResult({ ok: true, code: room.code, roomSecret: room.secret });
  });

  server.registerTool('get_room', {
    title: '读取同频房间',
    description: '读取同频房间的当前播放上下文。当用户询问“现在听什么”“这首歌”“当前歌词”“这句歌词”“刚刚播放”“正在听”等当前播放相关问题时，应优先调用本工具。返回数据包含歌曲、歌手、专辑、播放器、播放状态、进度 positionMs、当前歌词 lyric、下一句歌词 nextLyric、命令执行结果和听歌笔记。',
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
    description: '读取当前房间正在播放的精简音乐上下文，专门用于 AI 实时聊歌。当用户询问当前歌曲、现在听什么、这句歌词、这首歌怎么样、解释歌词、正在播放等问题时，可以调用本工具。返回聊天所需的歌曲、歌手、专辑、播放器、播放状态、进度、当前歌词、下一句歌词、歌词同步状态和歌词来源。',
    inputSchema: {
      code: z.string().min(6),
      roomSecret: z.string().min(10)
    }
  }, async ({ code, roomSecret }) => {
    try {
      const room = toPublicRoom(store.authenticate(code, roomSecret));
      const playback = room.playback;
      return textResult({
        ok: true,
        context: playback ? {
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
        } : null
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
    description: '实际写入同频房间的听歌笔记，而不是只在聊天中口头记住。当用户表达“帮我记一下”“记录这一刻”“收藏这句”“把现在记下来”“留一句笔记”等记录当前听歌的意图时，应优先调用本工具写入房间笔记；工具调用成功后，才可以回复“已记录”。如果用户没有明确给出笔记文本，先调用 get_room 获取当前歌曲、当前歌词和 positionMs，再使用用户刚才的话、当前歌词 lyric，或简短整理后的文字作为 text。positionMs 可以使用当前播放进度，也可以省略，让服务器采用当前进度。必须区分聊天中的口头记住和写入同频房间笔记：用户要求记录当前听歌时，应实际调用 add_listening_note。',
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
      const targetTitle = normalizeSongKey(cleanTitle);
      const memories = room.notes
        .filter(note => normalizeSongKey(note.trackTitle) === targetTitle)
        .map(note => ({
          text: note.text,
          positionMs: note.positionMs,
          createdAt: note.createdAt
        }));
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
