// Autosave, request ordering, version hand-over and conflict handling of src/lib/contentSession.ts, with fake timers
// and a fake server (no browser, no backend). Also checks that the editor's limits equal the server's Java constants.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const lib = path.join(__dirname, '../src/lib');
const cache = new Map();
function load(name) {
  if (cache.has(name)) return cache.get(name);
  const exports = {};
  cache.set(name, exports);
  const out = ts.transpileModule(fs.readFileSync(path.join(lib, name + '.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  vm.runInNewContext(out, { exports, require: (m) => load(m.replace('./', '')), Error, Promise, JSON, Math, Set, Map, Date, encodeURIComponent, setTimeout, clearTimeout });
  return exports;
}
const { ContentSession, SessionBlocked } = load('contentSession');
const body = load('contentBody');
const { draftKey, draftStorage } = load('contentDraft');
const { createBackGuard } = load('backGuard');

// objects made inside the vm have other prototypes; compare plain copies
const plain = (v) => JSON.parse(JSON.stringify(v));
const tick = () => new Promise((r) => setImmediate(r));
async function settle() { for (let i = 0; i < 5; i++) await tick(); }

function fakeTimers() {
  let now = 0; let seq = 0; const pending = new Map();
  return {
    timers: { set: (fn, ms) => { const id = ++seq; pending.set(id, { at: now + ms, fn }); return id; }, clear: (id) => pending.delete(id) },
    async advance(ms) {
      const until = now + ms;
      for (;;) {
        const due = [...pending.entries()].filter(([, t]) => t.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
        if (!due) break;
        pending.delete(due[0]); now = due[1].at; due[1].fn(); await settle();
      }
      now = until; await settle();
    },
    count: () => pending.size,
  };
}

/** A server that records requests and answers each one when the test says so. */
function fakeServer(initial) {
  const state = { ...initial };
  const log = [];
  const waiting = [];
  function request(kind, version, payload, answer) {
    log.push({ kind, version, payload });
    return new Promise((resolve, reject) => waiting.push({ kind, resolve, reject, answer, version }));
  }
  return {
    state, log, waiting,
    save: (version, change) => request('save', version, change, () => {
      if (change.title !== undefined) state.title = change.title;
      if (change.body !== undefined) state.body = JSON.parse(JSON.stringify(change.body));
      state.version++; return { ...state };
    }),
    op: (kind) => (version) => request(kind, version, null, () => { state.version++; return { content: { ...state } }; }),
    /** Answers the oldest open request (normally) or fails it with the given error. */
    async answer(error) {
      const w = waiting.shift();
      assert.ok(w, 'no open request');
      if (error) w.reject(error); else w.resolve(w.answer());
      await settle();
    },
  };
}

const conflict = () => Object.assign(new Error('version'), { status: 409, code: 'CONTENT_VERSION_CONFLICT', details: { currentVersion: 9 } });
const doc = (title, text) => ({ title, body: { schemaVersion: 1, sections: text === undefined ? [] : [{ id: 's-1', heading: 'h', paragraphs: [{ id: 'p-1', text }] }] } });

function session(server, t, extra = {}) {
  return new ContentSession({ initial: { ...server.state }, save: server.save, timers: t.timers, delayMs: 2000, validate: body.saveViolations, ...extra });
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('limits equal the server constants', () => {
  const java = (rel) => fs.readFileSync(path.join(__dirname, '../../backend/src/main/java/com/aicontent/platform', rel), 'utf8');
  const constant = (src, name) => Number(new RegExp(`\\b${name}\\s*=\\s*([0-9_]+);`).exec(src)[1].replace(/_/g, ''));
  const v = java('content/ContentBodyValidator.java');
  const r = java('content/ContentRelationService.java');
  assert.deepEqual({ ...body.LIMITS }, {
    sections: constant(v, 'MAX_SECTIONS'), paragraphsPerSection: constant(v, 'MAX_PARAGRAPHS_PER_SECTION'),
    headingChars: constant(v, 'MAX_HEADING_CHARS'), paragraphChars: constant(v, 'MAX_PARAGRAPH_CHARS'),
    totalChars: constant(v, 'MAX_TOTAL_CHARS'), titleChars: constant(v, 'MAX_TITLE_CHARS'),
    links: constant(r, 'MAX_LINKS_PER_CONTENT'), linkTitleChars: constant(r, 'MAX_LINK_TITLE_CHARS'),
    urlChars: constant(java('content/ContentUrls.java'), 'MAX_URL_CHARS'), labelChars: constant(r, 'MAX_LABEL_CHARS'),
    reasonChars: constant(java('content/ContentStatusPolicy.java'), 'MAX_REASON_CHARS'),
  });
});

test('saves 2 s after the last edit, once, only the changed field', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 0, ...doc('') }); const s = session(server, t);
  s.edit(doc('a')); await t.advance(1500);
  s.edit(doc('ab')); await t.advance(1999);
  assert.equal(server.log.length, 0, 'the timer restarts on every edit');
  assert.equal(s.snapshot().state, 'dirty');
  await t.advance(1);
  assert.deepEqual(plain(server.log.map((r) => [r.kind, r.version, r.payload])), [['save', 0, { title: 'ab' }]]);
  assert.equal(s.snapshot().state, 'saving');
  await server.answer();
  assert.equal(s.snapshot().state, 'saved');
  assert.equal(s.snapshot().server.version, 1);
  await t.advance(10000);
  assert.equal(server.log.length, 1);
});

test('no save when the edit is undone before the timer fires', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 3, ...doc('x', 'p') }); const s = session(server, t);
  s.edit(doc('xy', 'p')); s.edit(doc('x', 'p'));
  await t.advance(5000);
  assert.equal(server.log.length, 0);
  assert.equal(s.snapshot().state, 'saved');
});

test('no save while an IME composition is open', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 0, ...doc('') }); const s = session(server, t);
  s.setComposing(true); s.edit(doc('ㅎ')); s.edit(doc('하')); s.edit(doc('한'));
  await t.advance(10000);
  assert.equal(server.log.length, 0);
  s.setComposing(false);
  await t.advance(1999); assert.equal(server.log.length, 0);
  await t.advance(1);
  assert.deepEqual(plain(server.log[0].payload), { title: '한' });
});

test('typing during a save is kept and saved next with the new version', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 0, ...doc('', '') }); const s = session(server, t);
  s.edit(doc('제목', 'one')); await t.advance(2000);
  assert.equal(server.log.length, 1);
  s.edit(doc('제목', 'one two'));               // typed while the first save is on the wire
  await server.answer();                           // the older response arrives
  assert.equal(s.snapshot().local.body.sections[0].paragraphs[0].text, 'one two', 'the response did not overwrite newer input');
  assert.equal(s.snapshot().state, 'dirty');
  await t.advance(2000);
  assert.deepEqual(server.log.map((r) => [r.version, r.payload.body?.sections[0].paragraphs[0].text]), [[0, 'one'], [1, 'one two']]);
  await server.answer();
  assert.equal(s.snapshot().state, 'saved');
  assert.equal(server.state.version, 2);
});

test('requests run one at a time, in order, each with the previous response version', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 5, ...doc('t', 'a') }); const s = session(server, t);
  s.edit(doc('t', 'ab'));
  const saved = s.flush();
  const submit = s.run(server.op('submit'), (r) => r.content);
  const ack = s.run(server.op('ack'), (r) => r.content);
  await settle();
  assert.equal(server.log.length, 1, 'the second request waits for the first');
  await server.answer(); await server.answer(); await server.answer();
  assert.equal(await saved, true); await submit; await ack;
  assert.deepEqual(server.log.map((r) => [r.kind, r.version]), [['save', 5], ['submit', 6], ['ack', 7]]);
  assert.equal(s.snapshot().server.version, 8);
  s.edit(doc('t2', 'ab')); await t.advance(2000);
  assert.deepEqual(plain(server.log.at(-1)), { kind: 'save', version: 8, payload: { title: 't2' } }, 'the acknowledgement version is used by the next save');
});

test('a status action first saves pending edits, then uses that version', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 0, ...doc('t', 'a') }); const s = session(server, t);
  s.edit(doc('t', 'changed'));                     // no timer has fired yet
  const p = s.run(server.op('submit'), (r) => r.content);
  await settle();
  assert.deepEqual(server.log.map((r) => [r.kind, r.version]), [['save', 0]]);
  await server.answer(); await server.answer(); await p;
  assert.deepEqual(server.log.map((r) => [r.kind, r.version]), [['save', 0], ['submit', 1]]);
});

test('a failed save blocks the status action and keeps the edit', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 0, ...doc('t', 'a') }); const s = session(server, t);
  s.edit(doc('t', 'b'));
  const p = s.run(server.op('submit'), (r) => r.content);
  await settle();
  await server.answer(Object.assign(new Error('down'), { status: 500, code: 'INTERNAL_ERROR' }));
  await assert.rejects(p, (e) => e instanceof SessionBlocked && e.reason === 'save-failed');
  assert.deepEqual(server.log.map((r) => r.kind), ['save'], 'submit was never sent');
  assert.equal(s.snapshot().state, 'error');
  assert.equal(s.snapshot().local.body.sections[0].paragraphs[0].text, 'b');
  await t.advance(10000);
  assert.equal(server.log.length, 1, 'a failed save is not retried in a loop');
  s.edit(doc('t', 'bc')); await t.advance(2000);
  assert.equal(server.log.length, 2, 'the next edit tries again');
});

test('a document over the limits is not sent', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 0, ...doc('') }); const s = session(server, t);
  s.edit(doc('x'.repeat(201)));
  await t.advance(2000);
  assert.equal(server.log.length, 0);
  assert.equal(s.snapshot().state, 'error');
  assert.equal(s.snapshot().error.code, 'CLIENT_INVALID');
  await assert.rejects(s.run(server.op('submit'), (r) => r.content), (e) => e.reason === 'save-failed');
  assert.equal(server.log.length, 0);
});

test('a version conflict stops saves and actions and keeps the draft until reload', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 2, ...doc('t', 'a') }); const s = session(server, t);
  s.edit(doc('t', 'mine')); await t.advance(2000);
  await server.answer(conflict());
  assert.equal(s.snapshot().state, 'conflict');
  assert.equal(s.snapshot().local.body.sections[0].paragraphs[0].text, 'mine');
  s.edit(doc('t', 'mine more')); await t.advance(10000);
  assert.equal(server.log.length, 1, 'no autosave after a conflict');
  await assert.rejects(s.run(server.op('submit'), (r) => r.content), (e) => e.reason === 'conflict');
  assert.equal(await s.flush(), false);
  assert.equal(server.log.length, 1, 'no forced retry');
  assert.equal(s.unsaved, true);
  s.reload({ version: 9, ...doc('theirs', 'x') });
  assert.equal(s.snapshot().state, 'saved');
  assert.equal(s.snapshot().local.title, 'theirs');
  s.edit(doc('theirs', 'y')); await t.advance(2000);
  assert.deepEqual(plain(server.log.at(-1)), { kind: 'save', version: 9, payload: { body: doc('', 'y').body } });
});

test('a conflict from an action also stops the autosave', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 1, ...doc('t', 'a') }); const s = session(server, t);
  const p = s.run(server.op('hold'), (r) => r.content);
  await settle(); await server.answer(conflict());
  await assert.rejects(p, (e) => e.code === 'CONTENT_VERSION_CONFLICT');
  s.edit(doc('t', 'b')); await t.advance(5000);
  assert.equal(server.log.length, 1);
  assert.equal(s.snapshot().state, 'conflict');
});

test('two quick clicks run one after the other with the new version', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 4, ...doc('t', 'a') }); const s = session(server, t);
  const a = s.run(server.op('submit'), (r) => r.content);
  const b = s.run(server.op('submit'), (r) => r.content);
  await settle(); assert.equal(server.log.length, 1);
  await server.answer(); await a;
  assert.deepEqual(server.log.map((r) => r.version), [4, 5]);
  await server.answer(Object.assign(new Error('state'), { status: 409, code: 'CONTENT_STATE_INVALID' }));
  await assert.rejects(b, (e) => e.code === 'CONTENT_STATE_INVALID');
  assert.equal(s.snapshot().state, 'saved', 'a state error is not a conflict');
});

test('a server body with reordered keys counts as saved', async () => {
  const t = fakeTimers(); const server = fakeServer({ version: 0, ...doc('t', '') }); const s = session(server, t);
  s.edit(doc('t', 'z')); await t.advance(2000);
  const w = server.waiting.shift(); server.log; w.resolve({ version: 1, title: 't', body: { sections: [{ paragraphs: [{ text: 'z', id: 'p-1' }], heading: 'h', id: 's-1' }], schemaVersion: 1 } });
  await settle();
  assert.equal(s.snapshot().state, 'saved');
  await t.advance(5000); assert.equal(server.log.length, 1);
});

test('draft storage: key per environment, account and content; failures are reported; only the document is stored', () => {
  const values = new Map();
  const ok = { getItem: (k) => values.get(k) ?? null, setItem: (k, v) => values.set(k, v), removeItem: (k) => values.delete(k) };
  const store = draftStorage(() => ok);
  const k1 = draftKey('127.0.0.1:13093|verify', 'op', 7);
  assert.notEqual(k1, draftKey('127.0.0.1:13093|verify', 'root', 7));
  assert.notEqual(k1, draftKey('127.0.0.1:3000|daily', 'op', 7));
  assert.notEqual(k1, draftKey('127.0.0.1:13093|verify', 'op', 8));
  assert.equal(store.write(k1, 7, 3, doc('제목', '본문')), null);
  assert.deepEqual(Object.keys(JSON.parse(values.get(k1))).sort(), ['baseVersion', 'body', 'contentId', 'savedAt', 'title']);
  assert.equal(store.read(k1).draft.body.sections[0].paragraphs[0].text, '본문');
  assert.equal(store.remove(k1), null);
  assert.equal(store.read(k1).draft, null);
  const full = draftStorage(() => ({ getItem: () => { throw new Error('denied'); }, setItem: () => { throw new Error('QuotaExceededError'); }, removeItem: () => { throw new Error('denied'); } }));
  assert.match(full.write(k1, 7, 3, doc('a')), /QuotaExceededError/);
  assert.match(full.read(k1).error, /denied/);
  assert.match(draftStorage(() => null).write(k1, 7, 3, doc('a')), /저장소/);
  values.set(k1, '{"title":1}');
  assert.equal(store.read(k1).draft, null);
});

test('new ids are valid and unique within the body', () => {
  const b = { schemaVersion: 1, sections: [{ id: 's-00000000', heading: '', paragraphs: [] }] };
  let n = 0; const seq = [0, 0, 0.5];
  const id = body.newId('s', b, () => seq[n++]);
  assert.match(id, /^[a-z0-9-]{1,40}$/);
  assert.notEqual(id, 's-00000000');
});


/** Browser history as far as the guard sees it: entries, Back/Forward that fire popstate afterwards (async). */
function fakeHistory(urls) {
  const entries = urls.map((url) => ({ url, state: { __NA: true } }));
  let index = entries.length - 1;
  const listeners = new Set();
  const asked = [];
  let answer = true;
  const go = (delta) => {
    const next = index + delta;
    if (next < 0 || next >= entries.length) return;
    index = next;
    setImmediate(() => listeners.forEach((fn) => fn(entries[index].state)));
  };
  const env = {
    state: () => entries[index].state,
    href: () => entries[index].url,
    pushState: (data, url) => { entries.splice(index + 1); entries.push({ url, state: { __NA: true, ...data } }); index++; },
    back: () => go(-1),
    onPopState: (fn) => { listeners.add(fn); return () => listeners.delete(fn); },
    confirm: (m) => { asked.push(m); return answer; },
  };
  return { env, asked, answer: (v) => { answer = v; }, back: async () => { go(-1); await settle(); }, forward: async () => { go(1); await settle(); },
    jump: async (delta) => { go(delta); await settle(); },
    url: () => entries[index].url, length: () => entries.length, index: () => index, listeners };
}

const EDITOR = 'http://127.0.0.1:13093/contents/1';
const LIST = 'http://127.0.0.1:13093/contents';

test('back guard: no extra history entry until something is unsaved, then exactly one', () => {
  const h = fakeHistory([LIST, EDITOR]);
  let unsaved = false;
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => unsaved, message: 'leave?' });
  assert.equal(h.length(), 2);
  assert.equal(g.armed(), false);
  unsaved = true; g.arm(); g.arm();
  assert.equal(h.length(), 3, 'one guard entry however often it is armed');
  assert.equal(h.url(), EDITOR, 'same URL');
  assert.equal(h.env.state().__NA, true, 'the router state stays on the entry');
});

test('back guard: Back with unsaved edits asks; staying keeps the URL and re-arms', async () => {
  const h = fakeHistory([LIST, EDITOR]);
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => true, message: 'leave?' });
  g.arm();
  h.answer(false);
  await h.back();
  assert.equal(h.asked.length, 1);
  assert.equal(h.url(), EDITOR);
  assert.equal(g.armed(), true, 'the guard entry is back on top');
  assert.equal(h.length(), 3);
  await h.back();                              // asks again, every time
  assert.equal(h.asked.length, 2);
  assert.equal(h.url(), EDITOR);
});

test('back guard: Back with unsaved edits and "leave" goes to the previous page in one press', async () => {
  const h = fakeHistory([LIST, EDITOR]);
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => true, message: 'leave?' });
  g.arm();
  h.answer(true);
  await h.back();
  assert.equal(h.asked.length, 1);
  assert.equal(h.url(), LIST);
  assert.equal(g.armed(), false);
});

test('back guard: once everything is saved, Back leaves without asking and still in one press', async () => {
  const h = fakeHistory([LIST, EDITOR]);
  let unsaved = true;
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => unsaved, message: 'leave?' });
  g.arm();
  unsaved = false;                             // the autosave finished
  await h.back();
  assert.equal(h.asked.length, 0);
  assert.equal(h.url(), LIST);
});

test('back guard: Forward to another page is not intercepted', async () => {
  const h = fakeHistory([LIST, EDITOR, 'http://127.0.0.1:13093/issues/4']);
  await h.back();                              // now on the editor, with the issue page ahead
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => true, message: 'leave?' });
  // no guard entry pushed: Forward goes to the issue page
  await h.forward();
  assert.equal(h.asked.length, 0);
  assert.equal(h.url(), 'http://127.0.0.1:13093/issues/4');
  assert.equal(g.armed(), false);
});

test('back guard: jumping several entries back (history menu) is not intercepted and pushes nothing', async () => {
  const h = fakeHistory(['http://127.0.0.1:13093/', LIST, EDITOR]);
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => true, message: 'leave?' });
  g.arm();
  h.answer(false);
  await h.jump(-3);                            // from the guard entry straight to the dashboard
  assert.equal(h.asked.length, 0, 'the page has already changed; asking could not keep it');
  assert.equal(h.url(), 'http://127.0.0.1:13093/');
  assert.equal(h.length(), 4, 'no guard entry pushed for another page');
  assert.equal(g.armed(), false);
});

test('back guard: opened on a guard entry left by an earlier visit, Back still takes one press', async () => {
  const h = fakeHistory([LIST, EDITOR]);
  h.env.pushState({ contentBackGuard: 'content-1' }, EDITOR);   // left from before
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => false, message: 'leave?' });
  assert.equal(g.armed(), true);
  await h.back();
  assert.equal(h.url(), LIST);
  assert.equal(h.asked.length, 0);
});

test('back guard: a guard entry of another content is not taken as its own', () => {
  const h = fakeHistory([LIST, EDITOR]);
  h.env.pushState({ contentBackGuard: 'content-2' }, EDITOR);
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => true, message: 'leave?' });
  assert.equal(g.armed(), false);
});

test('back guard: after dispose nothing is intercepted', async () => {
  const h = fakeHistory([LIST, EDITOR]);
  const g = createBackGuard(h.env, { id: 'content-1', isUnsaved: () => true, message: 'leave?' });
  g.arm();
  g.dispose();
  assert.equal(h.listeners.size, 0);
  await h.back();
  assert.equal(h.asked.length, 0);
  assert.equal(h.url(), EDITOR, 'only stepped off the guard entry; the page is gone anyway');
});

let current = null;
// a test whose promise never settles empties the event loop and node would exit 0 without a summary
process.on('exit', (code) => { if (current) { console.log('FAIL (never finished)', current); process.exitCode = 1; } });
(async () => {
  let failed = 0;
  for (const { name, fn } of tests) {
    current = name;
    try { await fn(); console.log('PASS', name); } catch (e) { failed++; console.log('FAIL', name); console.log(e); }
  }
  current = null;
  console.log(`${tests.length - failed}/${tests.length} passed`);
  process.exit(failed ? 1 : 0);
})();
