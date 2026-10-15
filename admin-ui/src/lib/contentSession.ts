// One editing session of one content: the autosave and every other change request go through a single queue, so
// requests for the content run one at a time, in order, each with the version the previous successful response
// returned. No React here (scripts/verify-content-session.cjs drives it with fake timers and a fake server).
//
// Rules:
// - Edits only change the local document. A save starts 2 s after the last edit, only when the document differs from
//   the saved one, and never while an IME composition (Korean input) is open.
// - A save sends the document as it is when the save starts. Its response becomes the saved document and the new
//   version; it never replaces the local document, so typing during a save is kept and saved next.
// - run(op) (status, source, link, acknowledge) first saves pending edits; if that save fails the op is not sent.
// - A 409 CONTENT_VERSION_CONFLICT stops everything: no more saves or ops until the person reloads. The local
//   document stays as it is (nothing is overwritten, nothing is retried).

import { sameJson, type Body, type Doc, type Violation } from "./contentBody";

export type SaveState = "saved" | "dirty" | "saving" | "error" | "conflict";

/** The server's content as far as the session needs it; the page keeps the rest of the detail on the same object. */
export type ServerContent = { version: number; title: string; body: Body };

type Timers = { set: (fn: () => void, ms: number) => unknown; clear: (handle: unknown) => void };

export type SessionOptions<T extends ServerContent> = {
  initial: T;
  /** PATCH with the given version and only the changed fields; resolves to the server content after the save. */
  save: (version: number, change: { title?: string; body?: Body }) => Promise<T>;
  /** Checks run before a save is sent; a document with violations is not sent. */
  validate?: (doc: Doc) => Violation[];
  delayMs?: number;
  timers?: Timers;
};

/** Thrown by run() when the op was not sent: a conflict is open, or saving the pending edits failed first. */
export class SessionBlocked extends Error {
  constructor(public reason: "conflict" | "save-failed", public cause?: unknown) {
    super(reason === "conflict" ? "다른 곳에서 먼저 바뀐 내용이 있어 요청을 보내지 않았습니다. 최신본을 확인해 주세요."
      : "저장하지 못한 편집이 있어 요청을 보내지 않았습니다. 저장 오류를 먼저 해결해 주세요.");
  }
}

/** A save refused before sending because the document breaks the limits. */
export class ClientInvalid extends Error {
  code = "CLIENT_INVALID";
  constructor(public violations: Violation[]) {
    super(violations[0]?.path + " " + violations[0]?.message);
  }
}

export function isVersionConflict(e: unknown): boolean {
  return !!e && typeof e === "object" && (e as { code?: unknown }).code === "CONTENT_VERSION_CONFLICT";
}

export type SessionSnapshot<T extends ServerContent> = {
  state: SaveState; server: T; local: Doc; saved: Doc; busy: boolean; composing: boolean;
  /** The last failed save (cleared by the next successful one); for a conflict, the 409. */
  error: unknown; conflict: unknown;
};

const docOf = (c: ServerContent): Doc => ({ title: c.title, body: c.body });

export class ContentSession<T extends ServerContent> {
  private server: T;
  private saved: Doc;
  private local: Doc;
  private saving = false;
  private composing = false;
  private lastError: unknown = null;
  private conflict: unknown = null;
  private queued = 0;
  private timer: unknown = null;
  private tail: Promise<unknown> = Promise.resolve();
  private listeners = new Set<() => void>();
  private snap: SessionSnapshot<T>;
  private readonly delay: number;
  private readonly timers: Timers;

  constructor(private opts: SessionOptions<T>) {
    this.server = opts.initial;
    this.saved = docOf(opts.initial);
    this.local = this.saved;
    this.delay = opts.delayMs ?? 2000;
    this.timers = opts.timers ?? { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>) };
    this.snap = this.makeSnapshot();
  }

  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; };
  snapshot = () => this.snap;

  get dirty(): boolean { return !sameJson(this.local, this.saved); }

  /** Something would be lost if the page closed now. */
  get unsaved(): boolean { return this.dirty || this.saving || this.conflict !== null; }

  edit(doc: Doc) {
    this.local = doc;
    if (!this.dirty && !this.saving) this.lastError = null;
    this.schedule();
    this.emit();
  }

  setComposing(composing: boolean) {
    this.composing = composing;
    this.schedule();
    this.emit();
  }

  /** Saves now (queued behind running requests). Resolves true when the save succeeded or there was nothing to save. */
  flush(): Promise<boolean> {
    this.cancelTimer();
    return this.enqueue(() => this.saveStep()).then(() => true, () => false);
  }

  /**
   * Runs a change request with the current version after saving pending edits. {@code apply} turns the response into
   * the new server content. Rejects with SessionBlocked when the request was not sent, or with the request's error.
   */
  run<R>(op: (version: number) => Promise<R>, apply: (response: R) => T): Promise<R> {
    this.cancelTimer();
    return this.enqueue(async () => {
      if (this.conflict) throw new SessionBlocked("conflict");
      try {
        await this.saveStep();
      } catch (e) {
        throw new SessionBlocked(this.conflict ? "conflict" : "save-failed", e);
      }
      let response: R;
      try {
        response = await op(this.server.version);
      } catch (e) {
        if (isVersionConflict(e)) this.openConflict(e);
        throw e;
      }
      this.server = apply(response);
      this.saved = docOf(this.server);
      this.schedule();
      this.emit();
      return response;
    });
  }

  /** Changes fields of the server content that are not the document or the version (e.g. a refreshed origin notice). */
  updateServer(change: (current: T) => T) {
    const next = change(this.server);
    this.server = { ...next, version: this.server.version, title: this.server.title, body: this.server.body };
    this.emit();
  }

  /**
   * Starts over from a freshly read server content: the editor shows it, the conflict and errors are cleared. The
   * caller keeps the person's draft elsewhere first (draft storage); nothing here sends it.
   */
  reload(fresh: T) {
    this.cancelTimer();
    this.server = fresh;
    this.saved = docOf(fresh);
    this.local = this.saved;
    this.conflict = null;
    this.lastError = null;
    this.emit();
  }

  /** Stops the pending autosave timer (listeners stay: React may subscribe again after a remount). */
  dispose() {
    this.cancelTimer();
  }

  private async saveStep(): Promise<void> {
    if (this.conflict) throw new SessionBlocked("conflict");
    const sent = this.local;
    const change: { title?: string; body?: Body } = {};
    if (sent.title !== this.saved.title) change.title = sent.title;
    if (!sameJson(sent.body, this.saved.body)) change.body = sent.body;
    if (change.title === undefined && change.body === undefined) {
      this.lastError = null;
      this.emit();
      return;
    }
    const violations = this.opts.validate?.(sent) ?? [];
    if (violations.length > 0) {
      this.lastError = new ClientInvalid(violations);
      this.emit();
      throw this.lastError;
    }
    this.saving = true;
    this.emit();
    try {
      const result = await this.opts.save(this.server.version, change);
      this.server = result;
      this.saved = docOf(result);
      this.lastError = null;
    } catch (e) {
      if (isVersionConflict(e)) this.openConflict(e);
      else this.lastError = e;
      throw e;
    } finally {
      this.saving = false;
      // a failed save waits for the next edit or an explicit save; a successful one saves what was typed meanwhile
      if (this.lastError === null) this.schedule();
      this.emit();
    }
  }

  private openConflict(e: unknown) {
    this.conflict = e;
    this.cancelTimer();
  }

  private schedule() {
    this.cancelTimer();
    if (this.conflict || this.composing || !this.dirty) return;
    this.timer = this.timers.set(() => {
      this.timer = null;
      if (!this.composing) this.flush();
    }, this.delay);
  }

  private cancelTimer() {
    if (this.timer !== null) { this.timers.clear(this.timer); this.timer = null; }
  }

  private enqueue<R>(task: () => Promise<R>): Promise<R> {
    this.queued++;
    this.emit();
    const run = async () => {
      try { return await task(); } finally { this.queued--; this.emit(); }
    };
    const p = this.tail.then(run);
    this.tail = p.then(() => undefined, () => undefined);
    return p;
  }

  private state(): SaveState {
    if (this.conflict) return "conflict";
    if (this.saving) return "saving";
    if (!this.dirty) return "saved";
    return this.lastError ? "error" : "dirty";
  }

  private makeSnapshot(): SessionSnapshot<T> {
    return { state: this.state(), server: this.server, local: this.local, saved: this.saved, busy: this.queued > 0,
      composing: this.composing, error: this.lastError, conflict: this.conflict };
  }

  private emit() {
    this.snap = this.makeSnapshot();
    this.listeners.forEach((fn) => fn());
  }
}
