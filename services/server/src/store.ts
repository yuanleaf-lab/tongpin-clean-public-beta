import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { customAlphabet, nanoid } from 'nanoid';
import type { CommandResult, CommandResultDetails, CommandStatus, ListeningNote, PlaybackCommand, PlaybackSnapshot, Room } from './types.js';

const roomCode = customAlphabet('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 6);
const MAX_LISTENING_INCREMENT_MS = 10_000;

interface ClientListeningState {
  listeningDurationMs?: number;
  notes?: ListeningNote[];
  deletedNoteIds?: string[];
}

const javaHashHex = (value: string): string => {
  let hash = 0;
  for (let i = 0; i < value.length; i++) {
    hash = Math.imul(31, hash) + value.charCodeAt(i);
    hash |= 0;
  }
  return (hash >>> 0).toString(16);
};

const stableNoteId = (note: Partial<ListeningNote>): string => {
  const id = String(note.id ?? '').trim();
  if (id) return id;
  const key = [
    String(note.trackTitle ?? ''),
    Math.trunc(Number(note.positionMs ?? 0)),
    Math.trunc(Number(note.createdAt ?? 0)),
    String(note.text ?? '')
  ].join('|');
  return `legacy_${javaHashHex(key)}`;
};

export class RoomStore {
  private rooms = new Map<string, Room>();
  private writeQueue: Promise<void> = Promise.resolve();

  constructor(private readonly filePath: string) {}

  async load(): Promise<void> {
    try {
      const raw = await readFile(this.filePath, 'utf8');
      const parsed = JSON.parse(raw) as Room[];
      this.rooms = new Map(parsed.map(room => [room.code, {
        ...room,
        listeningDurationMs: Math.max(0, Number(room.listeningDurationMs ?? 0)),
        deletedNoteIds: Array.isArray(room.deletedNoteIds) ? room.deletedNoteIds.filter(Boolean) : [],
        notes: (room.notes ?? []).map(note => ({ ...note, id: stableNoteId(note) })).filter(note => {
          const deleted = Array.isArray(room.deletedNoteIds) && room.deletedNoteIds.includes(note.id);
          return note.id && !deleted;
        }),
        commandResults: Array.isArray(room.commandResults)
          ? room.commandResults.slice(-100)
          : room.lastCommandResult ? [room.lastCommandResult] : []
      }]));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
    }
  }

  private persist(): Promise<void> {
    this.writeQueue = this.writeQueue.then(async () => {
      await mkdir(dirname(this.filePath), { recursive: true });
      const temp = `${this.filePath}.tmp`;
      await writeFile(temp, JSON.stringify([...this.rooms.values()], null, 2), 'utf8');
      await rename(temp, this.filePath);
    });
    return this.writeQueue;
  }

  async create(): Promise<Room> {
    let code = roomCode();
    while (this.rooms.has(code)) code = roomCode();
    const now = Date.now();
    const room: Room = {
      code,
      secret: nanoid(32),
      createdAt: now,
      updatedAt: now,
      revision: 0,
      listeningDurationMs: 0,
      playback: null,
      pendingCommand: null,
      lastCommandResult: null,
      commandResults: [],
      notes: [],
      deletedNoteIds: []
    };
    this.rooms.set(code, room);
    await this.persist();
    return room;
  }

  authenticate(code: string, secret: string): Room {
    const room = this.rooms.get(code.toUpperCase());
    if (!room || room.secret !== secret) throw new Error('ROOM_NOT_FOUND_OR_SECRET_INVALID');
    return room;
  }

  private mergeClientNotes(room: Room, notes?: ListeningNote[]): boolean {
    if (!Array.isArray(notes) || notes.length === 0) return false;
    const deletedIds = new Set(room.deletedNoteIds ?? []);
    const existingIds = new Set(room.notes.map(note => note.id));
    let changed = false;
    for (const incoming of notes) {
      const id = stableNoteId(incoming ?? {});
      const text = String(incoming?.text ?? '').trim();
      if (!id || !text || deletedIds.has(id) || existingIds.has(id)) continue;
      room.notes.push({
        id,
        text: text.slice(0, 500),
        positionMs: Math.max(0, Math.trunc(Number(incoming.positionMs ?? 0))),
        trackTitle: String(incoming.trackTitle ?? '未知歌曲').trim().slice(0, 200) || '未知歌曲',
        createdAt: Number.isFinite(incoming.createdAt) ? Number(incoming.createdAt) : Date.now()
      });
      existingIds.add(id);
      changed = true;
    }
    if (changed) {
      room.notes = room.notes
        .sort((a, b) => a.createdAt - b.createdAt)
        .slice(-200);
    }
    return changed;
  }

  private mergeDeletedNoteIds(room: Room, deletedNoteIds?: string[]): boolean {
    if (!Array.isArray(deletedNoteIds) || deletedNoteIds.length === 0) return false;
    const next = new Set(room.deletedNoteIds ?? []);
    let changed = false;
    for (const value of deletedNoteIds) {
      const id = String(value ?? '').trim();
      if (!id || next.has(id)) continue;
      next.add(id);
      changed = true;
    }
    if (!changed) return false;
    room.deletedNoteIds = [...next];
    const before = room.notes.length;
    room.notes = room.notes.filter(note => !next.has(note.id));
    return changed || before !== room.notes.length;
  }

  async publishPlayback(code: string, secret: string, snapshot: PlaybackSnapshot, clientState?: ClientListeningState): Promise<Room> {
    const room = this.authenticate(code, secret);
    const now = Date.now();
    const previous = room.playback;
    if (previous?.playing && Number.isFinite(previous.publishedAt)) {
      const elapsed = Math.max(0, now - Number(previous.publishedAt));
      room.listeningDurationMs = Math.max(0, room.listeningDurationMs ?? 0)
        + Math.min(MAX_LISTENING_INCREMENT_MS, elapsed);
    }
    if (Number.isFinite(clientState?.listeningDurationMs)) {
      room.listeningDurationMs = Math.max(room.listeningDurationMs, Math.max(0, Math.trunc(clientState!.listeningDurationMs!)));
    }
    const deletedChanged = this.mergeDeletedNoteIds(room, clientState?.deletedNoteIds);
    const notesChanged = this.mergeClientNotes(room, clientState?.notes);
    room.playback = { ...snapshot, publishedAt: now };
    room.revision += (deletedChanged || notesChanged) ? 2 : 1;
    room.updatedAt = now;
    await this.persist();
    return room;
  }

  async setCommand(code: string, secret: string, command: Omit<PlaybackCommand, 'id' | 'createdAt'>): Promise<Room> {
    const room = this.authenticate(code, secret);
    const createdAt = Date.now();
    room.pendingCommand = { ...command, id: nanoid(12), createdAt };
    room.lastCommandResult = {
      commandId: room.pendingCommand.id,
      status: 'queued',
      message: '命令已写入服务器，等待手机领取',
      updatedAt: createdAt
    };
    room.commandResults = [...(room.commandResults ?? []), room.lastCommandResult].slice(-100);
    room.revision += 1;
    room.updatedAt = createdAt;
    await this.persist();
    return room;
  }

  async acknowledgeCommand(
    code: string,
    secret: string,
    commandId: string,
    status: CommandStatus,
    message: string,
    details?: CommandResultDetails
  ): Promise<Room> {
    const room = this.authenticate(code, secret);
    const result: CommandResult = {
      commandId,
      status,
      message: message.slice(0, 300),
      updatedAt: Date.now(),
      details
    };
    room.lastCommandResult = result;
    const previousIndex = (room.commandResults ?? []).findIndex(value => value.commandId === commandId);
    if (previousIndex >= 0) room.commandResults[previousIndex] = result;
    else room.commandResults = [...(room.commandResults ?? []), result];
    room.commandResults = room.commandResults.slice(-100);
    if (room.pendingCommand?.id === commandId && isTerminalCommandStatus(status)) {
      room.pendingCommand = null;
    }
    room.revision += 1;
    room.updatedAt = result.updatedAt;
    await this.persist();
    return room;
  }

  async addNote(code: string, secret: string, text: string, positionMs?: number): Promise<Room> {
    const room = this.authenticate(code, secret);
    const note: ListeningNote = {
      id: nanoid(12),
      text: text.trim(),
      positionMs: positionMs ?? room.playback?.positionMs ?? 0,
      trackTitle: room.playback?.title ?? '未知歌曲',
      createdAt: Date.now()
    };
    room.notes.push(note);
    room.notes = room.notes.slice(-200);
    room.revision += 1;
    room.updatedAt = Date.now();
    await this.persist();
    return room;
  }

  async deleteNote(code: string, secret: string, noteId: string): Promise<{ room: Room; deleted: boolean }> {
    const room = this.authenticate(code, secret);
    const id = noteId.trim();
    if (!id) throw new Error('INVALID_NOTE_ID');
    const before = room.notes.length;
    room.deletedNoteIds = [...new Set([...(room.deletedNoteIds ?? []), id])];
    room.notes = room.notes.filter(note => note.id !== id);
    const deleted = room.notes.length !== before;
    room.revision += 1;
    room.updatedAt = Date.now();
    await this.persist();
    return { room, deleted };
  }
}

const isTerminalCommandStatus = (status: CommandStatus): boolean => [
  'executed', 'failed', 'search_failed', 'playback_confirmed', 'playback_mismatch', 'execution_failed'
].includes(status);
