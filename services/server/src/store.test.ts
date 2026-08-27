import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { RoomStore } from './store.js';
import { toPublicRoom } from './types.js';

class InMemoryRoomStoreDatabase {
  private rooms: unknown = [];
  private exists = false;

  async query(queryText: string, values?: readonly unknown[]): Promise<{ rows: Array<{ rooms: unknown }> }> {
    if (queryText.startsWith('select rooms')) {
      return { rows: this.exists ? [{ rooms: structuredClone(this.rooms) }] : [] };
    }
    if (!queryText.startsWith('insert into app_private.tongpin_room_store')) {
      throw new Error(`Unexpected query: ${queryText}`);
    }
    assert.equal(values?.[0], 'current');
    this.rooms = JSON.parse(String(values?.[1]));
    this.exists = true;
    return { rows: [] };
  }
}

const createStore = (database = new InMemoryRoomStoreDatabase()): RoomStore =>
  new RoomStore('postgresql://test.invalid/tongpin', database);

const setNow = (value: number): void => {
  Date.now = () => value;
};

const playback = (overrides: Partial<Parameters<RoomStore['publishPlayback']>[2]> = {}): Parameters<RoomStore['publishPlayback']>[2] => ({
  title: 'Song',
  artist: 'Artist',
  durationMs: 240000,
  positionMs: 0,
  playing: false,
  observedAt: Date.now(),
  ...overrides
});

test('command lifecycle is visible and clears after execution', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = createStore();
    const room = await store.create();
    await store.publishPlayback(room.code, room.secret, {
      title: 'Song', artist: 'Artist', durationMs: 1000, positionMs: 10,
      playing: false, observedAt: Date.now(), publishedAt: Date.now()
    });
    const queued = await store.setCommand(room.code, room.secret, { type: 'play' });
    assert.equal(queued.lastCommandResult?.status, 'queued');
    assert.ok(queued.pendingCommand);
    const received = await store.acknowledgeCommand(room.code, room.secret, queued.pendingCommand!.id, 'received', 'received');
    assert.equal(received.lastCommandResult?.status, 'received');
    assert.ok(received.pendingCommand);
    const executed = await store.acknowledgeCommand(room.code, room.secret, queued.pendingCommand!.id, 'executed', 'done');
    assert.equal(executed.lastCommandResult?.status, 'executed');
    assert.equal(executed.pendingCommand, null);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('room data and synced lyric fields survive store reload', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const database = new InMemoryRoomStoreDatabase();
    const first = createStore(database);
    const room = await first.create();
    await first.publishPlayback(room.code, room.secret, {
      title: 'Song',
      artist: 'Artist',
      album: 'Album',
      durationMs: 240000,
      positionMs: 12000,
      playing: false,
      observedAt: Date.now(),
      publishedAt: Date.now(),
      lyric: 'current line',
      nextLyric: 'next line',
      lyricsSource: 'LRCLIB',
      lyricsSynced: true
    });
    const second = createStore(database);
    await second.load();
    const loaded = second.authenticate(room.code, room.secret);
    assert.equal(loaded.playback?.lyric, 'current line');
    assert.equal(loaded.playback?.nextLyric, 'next line');
    assert.equal(loaded.playback?.lyricsSynced, true);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('public room projects a recent playing position without mutating stored data', async () => {
  const originalNow = Date.now;
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = createStore();
    setNow(10_000);
    const room = await store.create();
    await store.publishPlayback(room.code, room.secret, {
      title: 'Song', artist: 'Artist', durationMs: 10000, positionMs: 1000,
      playing: true, observedAt: Date.now()
    });
    setNow(11_200);
    const stored = store.authenticate(room.code, room.secret);
    const publicRoom = toPublicRoom(stored);
    assert.ok((publicRoom.playback?.positionMs ?? 0) >= 2000);
    assert.equal(stored.playback?.positionMs, 1000);
  } finally {
    Date.now = originalNow;
    await rm(dir, { recursive: true, force: true });
  }
});

test('search command keeps its final device-confirmed result by command id', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-store-'));
  try {
    const store = createStore();
    await store.load();
    const room = await store.create();
    const queued = await store.setCommand(room.code, room.secret, {
      type: 'search_play', title: 'Anchor', artist: 'Novo Amor', query: 'Anchor Novo Amor'
    });
    const commandId = queued.pendingCommand!.id;
    const pickedUp = await store.acknowledgeCommand(room.code, room.secret, commandId, 'picked_up', 'picked up');
    assert.equal(pickedUp.pendingCommand?.id, commandId);
    const confirmed = await store.acknowledgeCommand(room.code, room.secret, commandId, 'playback_confirmed', 'confirmed', {
      target: { title: 'Anchor', artist: 'Novo Amor' },
      actualPlayback: { title: 'Anchor', artist: 'Novo Amor' }
    });
    assert.equal(confirmed.pendingCommand, null);
    assert.equal(confirmed.commandResults.find(result => result.commandId === commandId)?.status, 'playback_confirmed');
    assert.deepEqual(confirmed.commandResults.find(result => result.commandId === commandId)?.details?.actualPlayback, {
      title: 'Anchor', artist: 'Novo Amor'
    });
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('public room exposes playerName derived from packageName', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = createStore();
    const room = await store.create();
    await store.publishPlayback(room.code, room.secret, {
      title: 'Song', artist: 'Artist', durationMs: 1000, positionMs: 10,
      playing: false, observedAt: Date.now(), publishedAt: Date.now(),
      packageName: 'com.netease.cloudmusic'
    });
    const publicRoom = toPublicRoom(store.authenticate(room.code, room.secret));
    assert.equal(publicRoom.playback?.playerName, '网易云音乐');
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('new room starts with zero listening duration', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = createStore();
    const room = await store.create();
    assert.equal(room.listeningDurationMs, 0);
    assert.equal(toPublicRoom(room).listeningDurationMs, 0);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('listening duration accumulates only after a playing snapshot', async () => {
  const originalNow = Date.now;
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = createStore();
    setNow(1_000);
    const room = await store.create();

    await store.publishPlayback(room.code, room.secret, playback({ playing: true, positionMs: 0 }));
    assert.equal(room.listeningDurationMs, 0);

    setNow(4_500);
    await store.publishPlayback(room.code, room.secret, playback({ playing: true, positionMs: 3500 }));
    assert.equal(room.listeningDurationMs, 3_500);

    setNow(8_000);
    await store.publishPlayback(room.code, room.secret, playback({ playing: false, positionMs: 3500 }));
    assert.equal(room.listeningDurationMs, 7_000);

    setNow(12_000);
    await store.publishPlayback(room.code, room.secret, playback({ playing: false, positionMs: 3500 }));
    assert.equal(room.listeningDurationMs, 7_000);
  } finally {
    Date.now = originalNow;
    await rm(dir, { recursive: true, force: true });
  }
});

test('listening duration continues across track changes and caps one increment at ten seconds', async () => {
  const originalNow = Date.now;
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = createStore();
    setNow(10_000);
    const room = await store.create();

    await store.publishPlayback(room.code, room.secret, playback({ title: 'First', playing: true }));

    setNow(13_000);
    await store.publishPlayback(room.code, room.secret, playback({ title: 'Second', playing: true }));
    assert.equal(room.listeningDurationMs, 3_000);

    setNow(43_000);
    await store.publishPlayback(room.code, room.secret, playback({ title: 'Second', playing: true }));
    assert.equal(room.listeningDurationMs, 13_000);
  } finally {
    Date.now = originalNow;
    await rm(dir, { recursive: true, force: true });
  }
});

test('new room can inherit client listening history without deleting existing history', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const database = new InMemoryRoomStoreDatabase();
    const store = createStore(database);
    const room = await store.create();
    await store.publishPlayback(room.code, room.secret, playback({ title: 'New room song' }), {
      listeningDurationMs: 42_000,
      notes: [{
        id: 'saved-note',
        text: 'still here',
        positionMs: 12_000,
        trackTitle: 'Old song',
        createdAt: 1_700_000_000_000
      }]
    });

    assert.equal(room.listeningDurationMs, 42_000);
    assert.equal(room.notes.length, 1);
    assert.equal(room.notes[0].text, 'still here');

    const reloaded = createStore(database);
    await reloaded.load();
    const loaded = reloaded.authenticate(room.code, room.secret);
    assert.equal(loaded.listeningDurationMs, 42_000);
    assert.equal(loaded.notes[0].id, 'saved-note');

    await reloaded.publishPlayback(room.code, room.secret, playback({ title: 'New room song' }), {
      listeningDurationMs: 1_000,
      notes: []
    });
    assert.equal(loaded.listeningDurationMs, 42_000);
    assert.equal(loaded.notes.length, 1);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('deleted note tombstone prevents stale client cache from reviving it', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const database = new InMemoryRoomStoreDatabase();
    const store = createStore(database);
    const room = await store.create();
    await store.publishPlayback(room.code, room.secret, playback({ playing: false }), {
      listeningDurationMs: 90_000,
      notes: [
        {
          id: 'note-delete',
          text: 'delete me',
          positionMs: 10_000,
          trackTitle: 'Song',
          createdAt: 1_700_000_000_000
        },
        {
          id: 'note-keep',
          text: 'keep me',
          positionMs: 20_000,
          trackTitle: 'Song',
          createdAt: 1_700_000_001_000
        }
      ]
    });
    assert.equal(room.notes.length, 2);

    const beforeDuration = room.listeningDurationMs;
    const result = await store.deleteNote(room.code, room.secret, 'note-delete');
    assert.equal(result.deleted, true);
    assert.deepEqual(room.notes.map(note => note.id), ['note-keep']);
    assert.ok(room.deletedNoteIds.includes('note-delete'));
    assert.equal(room.listeningDurationMs, beforeDuration);

    await store.publishPlayback(room.code, room.secret, playback({ playing: false }), {
      listeningDurationMs: 1_000,
      notes: [
        {
          id: 'note-delete',
          text: 'delete me',
          positionMs: 10_000,
          trackTitle: 'Song',
          createdAt: 1_700_000_000_000
        },
        {
          id: 'note-keep',
          text: 'keep me',
          positionMs: 20_000,
          trackTitle: 'Song',
          createdAt: 1_700_000_001_000
        }
      ],
      deletedNoteIds: ['note-delete']
    });
    assert.deepEqual(room.notes.map(note => note.id), ['note-keep']);
    assert.equal(room.listeningDurationMs, beforeDuration);

    const reloaded = createStore(database);
    await reloaded.load();
    const loaded = reloaded.authenticate(room.code, room.secret);
    assert.deepEqual(loaded.notes.map(note => note.id), ['note-keep']);
    assert.ok(loaded.deletedNoteIds.includes('note-delete'));

    await reloaded.publishPlayback(room.code, room.secret, playback({ playing: false }), {
      notes: [{
        id: 'note-delete',
        text: 'delete me',
        positionMs: 10_000,
        trackTitle: 'Song',
        createdAt: 1_700_000_000_000
      }]
    });
    assert.deepEqual(loaded.notes.map(note => note.id), ['note-keep']);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('legacy notes without id receive stable ids during sync', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = createStore();
    const room = await store.create();
    await store.publishPlayback(room.code, room.secret, playback(), {
      notes: [{
        id: '',
        text: 'legacy note',
        positionMs: 12_000,
        trackTitle: 'Legacy Song',
        createdAt: 1_700_000_002_000
      }]
    });
    assert.match(room.notes[0].id, /^legacy_/);
    const legacyId = room.notes[0].id;
    await store.deleteNote(room.code, room.secret, legacyId);
    assert.equal(room.notes.length, 0);
    await store.publishPlayback(room.code, room.secret, playback(), {
      notes: [{
        id: '',
        text: 'legacy note',
        positionMs: 12_000,
        trackTitle: 'Legacy Song',
        createdAt: 1_700_000_002_000
      }]
    });
    assert.equal(room.notes.length, 0);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('new room inherits deleted note tombstones from client history', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = createStore();
    const room = await store.create();
    await store.publishPlayback(room.code, room.secret, playback(), {
      listeningDurationMs: 12_000,
      deletedNoteIds: ['old-deleted-note'],
      notes: [
        {
          id: 'old-deleted-note',
          text: 'should stay deleted',
          positionMs: 8_000,
          trackTitle: 'Old Song',
          createdAt: 1_700_000_003_000
        },
        {
          id: 'old-visible-note',
          text: 'should survive',
          positionMs: 9_000,
          trackTitle: 'Old Song',
          createdAt: 1_700_000_004_000
        }
      ]
    });

    assert.deepEqual(room.notes.map(note => note.id), ['old-visible-note']);
    assert.ok(room.deletedNoteIds.includes('old-deleted-note'));
    assert.equal(room.listeningDurationMs, 12_000);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
