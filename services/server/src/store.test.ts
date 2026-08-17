import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { RoomStore } from './store.js';
import { toPublicRoom } from './types.js';

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
    const store = new RoomStore(join(dir, 'rooms.json'));
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
  const file = join(dir, 'rooms.json');
  try {
    const first = new RoomStore(file);
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
    const second = new RoomStore(file);
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
    const store = new RoomStore(join(dir, 'rooms.json'));
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

test('public room exposes playerName derived from packageName', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = new RoomStore(join(dir, 'rooms.json'));
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
    const store = new RoomStore(join(dir, 'rooms.json'));
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
    const store = new RoomStore(join(dir, 'rooms.json'));
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
    const store = new RoomStore(join(dir, 'rooms.json'));
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

test('active room follows the latest authenticated phone sync and expires', async () => {
  const originalNow = Date.now;
  const dir = await mkdtemp(join(tmpdir(), 'tongpin-clean-'));
  try {
    const store = new RoomStore(join(dir, 'rooms.json'));
    setNow(1_000);
    const first = await store.create();
    const second = await store.create();

    assert.equal(store.activeRoom(), null);

    store.touchActiveRoom(first.code, first.secret);
    assert.equal(store.activeRoom()?.code, first.code);

    setNow(2_000);
    await store.publishPlayback(second.code, second.secret, playback({ title: 'Second', playing: true }));
    assert.equal(store.activeRoom()?.code, second.code);

    setNow(16_999);
    assert.equal(store.activeRoom()?.code, second.code);

    setNow(17_001);
    assert.equal(store.activeRoom(), null);
  } finally {
    Date.now = originalNow;
    await rm(dir, { recursive: true, force: true });
  }
});
