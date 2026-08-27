import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import cors from 'cors';
import express from 'express';
import { AiConfigurationError, AiRequestError, askAI } from './ai.js';
import { handleMcpRequest } from './mcp.js';
import { RoomStore } from './store.js';
import { playerNameOf, toPublicRoom, type CommandResultDetails, type CommandStatus, type ListeningNote, type PlaybackCommandType, type PlaybackSnapshot } from './types.js';

const port = Number(process.env.PORT ?? 3000);
const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) throw new Error('DATABASE_URL is required');
const publicDir = fileURLToPath(new URL('../public/', import.meta.url));
const controlFile = fileURLToPath(new URL('../public/control.html', import.meta.url));
const store = new RoomStore(databaseUrl);
await store.load();

const app = express();
app.disable('x-powered-by');
app.use(cors());
app.use(express.json({ limit: '128kb' }));
app.use(express.static(publicDir));

const secretOf = (req: express.Request): string => {
  const auth = req.header('authorization') ?? '';
  return auth.startsWith('Bearer ') ? auth.slice(7) : '';
};

const text = (value: unknown, limit: number): string | undefined => {
  if (value === undefined || value === null) return undefined;
  return String(value).slice(0, limit);
};

const formatTime = (ms: number): string => {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
};

const buildChatPrompt = (room: ReturnType<typeof toPublicRoom>, message: string): string => {
  const playback = room.playback;
  const songTitle = playback?.title?.trim() || '未知歌曲';
  const memories = room.notes
    .filter(note => note.trackTitle.trim().toLocaleLowerCase() === songTitle.trim().toLocaleLowerCase())
    .slice(-5)
    .reverse()
    .map(note => `- ${new Date(note.createdAt).toISOString()} @ ${formatTime(note.positionMs)}: ${note.text}`)
    .join('\n') || '暂无历史听歌记录';

  return [
    '你是同频 Clean 的“共听”聊天助手。',
    '你陪用户聊当前正在听的歌、当前歌词和过去留下的听歌记录。',
    '回复要自然、简洁、有陪伴感；不要假装知道没有提供的信息；不要替用户下确定心理结论。',
    '',
    '当前歌曲上下文：',
    `- 歌曲：${songTitle}`,
    `- 歌手：${playback?.artist || '未知歌手'}`,
    `- 专辑：${playback?.album || '未知专辑'}`,
    `- 播放器：${playback?.playerName || '未知播放器'}`,
    `- 播放状态：${playback?.playing ? '正在播放' : '已暂停或未播放'}`,
    `- 播放进度：${formatTime(playback?.positionMs ?? 0)}`,
    `- 当前歌词：${playback?.lyric || '暂无当前歌词'}`,
    `- 下一句歌词：${playback?.nextLyric || '暂无下一句歌词'}`,
    '',
    '这首歌最近的听歌记录：',
    memories,
    '',
    '用户消息：',
    message
  ].join('\n');
};

app.get('/', (_req, res) => res.redirect('/control'));
app.get('/lan', (_req, res) => res.json({
  ok: true,
  mode: 'lan',
  message: 'Use http://YOUR_COMPUTER_LAN_IP:' + port + ' as the Android server address when phone and computer are on the same Wi-Fi.',
  health: '/health',
  control: '/control',
  mcp: '/mcp'
}));
app.get('/control', (_req, res) => res.sendFile(controlFile));
app.get('/health', (_req, res) => res.json({ ok: true, service: 'tongpin-clean', version: '1.3.1', lan: true }));

app.post('/api/rooms', async (_req, res) => {
  const room = await store.create();
  res.status(201).json({ code: room.code, roomSecret: room.secret, room: toPublicRoom(room) });
});

app.get('/api/rooms/:code', (req, res) => {
  try {
    res.json(toPublicRoom(store.authenticate(req.params.code, secretOf(req))));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/playback', async (req, res) => {
  try {
    const body = req.body as Partial<PlaybackSnapshot>;
    if (!body.title || !body.artist || !Number.isFinite(body.positionMs) || !Number.isFinite(body.durationMs)) {
      res.status(400).json({ error: 'INVALID_PLAYBACK' });
      return;
    }
    const room = await store.publishPlayback(req.params.code, secretOf(req), {
      title: String(body.title).slice(0, 200),
      artist: String(body.artist).slice(0, 200),
      album: text(body.album, 200),
      durationMs: Math.max(0, Math.trunc(body.durationMs!)),
      positionMs: Math.max(0, Math.trunc(body.positionMs!)),
      playing: Boolean(body.playing),
      packageName: text(body.packageName, 120),
      playerName: text(body.playerName, 80) ?? playerNameOf(text(body.packageName, 120)),
      sourceUrl: text(body.sourceUrl, 2_000),
      observedAt: Number.isFinite(body.observedAt) ? Number(body.observedAt) : Date.now(),
      publishedAt: Date.now(),
      lyric: text(body.lyric, 500) ?? '',
      nextLyric: text(body.nextLyric, 500) ?? '',
      lyricsSource: text(body.lyricsSource, 120) ?? '',
      lyricsSynced: Boolean(body.lyricsSynced)
    }, {
      listeningDurationMs: Number(req.body?.listeningDurationMs),
      notes: Array.isArray(req.body?.notes) ? req.body.notes as ListeningNote[] : undefined,
      deletedNoteIds: Array.isArray(req.body?.deletedNoteIds) ? req.body.deletedNoteIds.map(String) : undefined
    });
    res.json(toPublicRoom(room));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/commands', async (req, res) => {
  try {
    const type = req.body?.type as PlaybackCommandType;
    if (!['play', 'pause', 'seek', 'next', 'previous', 'search_play', 'switch_room'].includes(type)) {
      res.status(400).json({ error: 'INVALID_COMMAND' });
      return;
    }
    const positionMs = type === 'seek' ? Math.max(0, Math.trunc(Number(req.body.positionMs))) : undefined;
    if (type === 'seek' && !Number.isFinite(positionMs)) {
      res.status(400).json({ error: 'INVALID_POSITION' });
      return;
    }
    const title = type === 'search_play' ? String(req.body?.title ?? '').trim().slice(0, 160) : undefined;
    const artist = type === 'search_play' ? String(req.body?.artist ?? '').trim().slice(0, 160) : undefined;
    const query = type === 'search_play'
      ? String(req.body?.query ?? [title, artist].filter(Boolean).join(' ')).trim().slice(0, 320)
      : undefined;
    if (type === 'search_play' && (!title || !artist || !query)) {
      res.status(400).json({ error: 'INVALID_SEARCH_QUERY' });
      return;
    }
    const targetCode = type === 'switch_room' ? String(req.body?.targetCode ?? '').trim().toUpperCase() : undefined;
    const targetSecret = type === 'switch_room' ? String(req.body?.targetSecret ?? '').trim() : undefined;
    if (type === 'switch_room' && (!targetCode || !targetSecret)) {
      res.status(400).json({ error: 'INVALID_SWITCH_ROOM_TARGET' });
      return;
    }
    const room = await store.setCommand(req.params.code, secretOf(req), {
      type,
      positionMs,
      query,
      title,
      artist: artist || undefined,
      targetCode,
      targetSecret
    });
    res.json(toPublicRoom(room));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/commands/:id/ack', async (req, res) => {
  try {
    const status = req.body?.status as CommandStatus;
    if (!['received', 'picked_up', 'search_success', 'search_failed', 'playback_confirmed', 'playback_mismatch', 'execution_failed', 'executed', 'failed'].includes(status)) {
      res.status(400).json({ error: 'INVALID_STATUS' });
      return;
    }
    const message = String(req.body?.message ?? '').trim() || status;
    const details = commandResultDetails(req.body?.details);
    const room = await store.acknowledgeCommand(req.params.code, secretOf(req), req.params.id, status, message, details);
    res.json(toPublicRoom(room));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/notes', async (req, res) => {
  try {
    const noteText = String(req.body?.text ?? '').trim();
    if (!noteText || noteText.length > 500) {
      res.status(400).json({ error: 'INVALID_NOTE' });
      return;
    }
    const positionMs = req.body?.positionMs === undefined ? undefined : Math.max(0, Math.trunc(Number(req.body.positionMs)));
    const room = await store.addNote(req.params.code, secretOf(req), noteText, positionMs);
    res.json(toPublicRoom(room));
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.get('/api/rooms/:code/commands/:id', (req, res) => {
  try {
    const room = store.authenticate(req.params.code, secretOf(req));
    const result = (room.commandResults ?? []).find(value => value.commandId === req.params.id);
    if (!result) {
      res.status(404).json({ error: 'COMMAND_NOT_FOUND' });
      return;
    }
    res.json({ commandId: req.params.id, result, pending: room.pendingCommand?.id === req.params.id });
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

const commandTrack = (value: unknown): { title: string; artist: string } | undefined => {
  if (!value || typeof value !== 'object') return undefined;
  const record = value as Record<string, unknown>;
  const title = String(record.title ?? '').trim().slice(0, 200);
  const artist = String(record.artist ?? '').trim().slice(0, 200);
  return title || artist ? { title, artist } : undefined;
};

const commandResultDetails = (value: unknown): CommandResultDetails | undefined => {
  if (!value || typeof value !== 'object') return undefined;
  const record = value as Record<string, unknown>;
  const details: CommandResultDetails = {
    query: text(record.query, 320),
    target: commandTrack(record.target),
    selectedCandidate: commandTrack(record.selectedCandidate),
    actualPlayback: commandTrack(record.actualPlayback)
  };
  return Object.values(details).some(Boolean) ? details : undefined;
};

app.delete('/api/rooms/:code/notes/:noteId', async (req, res) => {
  try {
    const result = await store.deleteNote(req.params.code, secretOf(req), String(req.params.noteId ?? ''));
    res.json({ ok: true, deleted: result.deleted, noteId: req.params.noteId, room: toPublicRoom(result.room) });
  } catch {
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/api/rooms/:code/chat', async (req, res) => {
  try {
    const message = String(req.body?.message ?? '').trim();
    if (!message || message.length > 1_000) {
      res.status(400).json({ error: 'INVALID_MESSAGE' });
      return;
    }
    const roomSecret = secretOf(req) || String(req.body?.roomSecret ?? '').trim();
    const room = toPublicRoom(store.authenticate(req.params.code, roomSecret));
    const reply = await askAI(buildChatPrompt(room, message));
    res.json({ ok: true, reply });
  } catch (error) {
    if (error instanceof AiConfigurationError) {
      res.status(503).json({ error: 'AI_NOT_CONFIGURED', message: error.message });
      return;
    }
    if (error instanceof AiRequestError) {
      res.status(502).json({ error: 'AI_REQUEST_FAILED', message: error.message });
      return;
    }
    res.status(404).json({ error: 'ROOM_NOT_FOUND_OR_SECRET_INVALID' });
  }
});

app.post('/mcp', (req, res) => void handleMcpRequest(store, req, res));
app.get('/mcp', (_req, res) => res.status(405).json({ error: 'POST_ONLY' }));
app.delete('/mcp', (_req, res) => res.status(405).json({ error: 'POST_ONLY' }));

createServer(app).listen(port, '0.0.0.0', () => {
  console.log(`Tongpin Clean listening on http://0.0.0.0:${port}`);
});
