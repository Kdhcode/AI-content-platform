// Browser Back while the editor has unsaved edits. A click on a link and a reload or close are caught elsewhere
// (capture-phase click, beforeunload); Back/Forward are history moves that no event can cancel. So once there is
// something unsaved, the guard adds one history entry for the same URL on top of the editor's own entry. Back then
// only steps off that extra entry (same URL, the page stays as it is) and the guard asks: stay → the entry is put
// back; leave → the guard continues the Back itself. With nothing unsaved by then it continues without asking, so
// Back still takes one press. No React or Next.js here (scripts/verify-content-session.cjs drives it with a fake
// history); Next.js's patched pushState copies its own state onto the entry, so its router treats it as its own.

export type BackGuardEnv = {
  /** Current history entry's state and URL. */
  state: () => unknown;
  href: () => string;
  pushState: (data: Record<string, unknown>, url: string) => void;
  back: () => void;
  onPopState: (fn: (state: unknown) => void) => () => void;
  confirm: (message: string) => boolean;
};

export type BackGuard = { arm: () => void; armed: () => boolean; dispose: () => void };

const KEY = "contentBackGuard";

export function createBackGuard(env: BackGuardEnv, opts: { id: string; isUnsaved: () => boolean; message: string }): BackGuard {
  const mine = (state: unknown) => !!state && typeof state === "object" && (state as Record<string, unknown>)[KEY] === opts.id;
  // Opened on a guard entry left from an earlier visit (e.g. Back from the next page): it is already on top.
  let armedAt: string | null = mine(env.state()) ? env.href() : null;

  function arm() {
    if (armedAt !== null) return;
    armedAt = env.href();
    env.pushState({ [KEY]: opts.id }, armedAt);
  }

  const stop = env.onPopState((state) => {
    if (mine(state)) { armedAt = env.href(); return; } // Forward onto the guard entry
    // Only a step from the guard entry to the editor's own entry (same URL) is ours. Any other move (Forward to
    // another page) has already happened; the unsaved edits stay in the browser draft.
    if (armedAt === null || env.href() !== armedAt) { armedAt = null; return; }
    armedAt = null;
    if (opts.isUnsaved() && !env.confirm(opts.message)) {
      arm();      // stay: the URL never changed, the editor keeps its state
      return;
    }
    env.back();   // continue the Back the person asked for
  });

  return { arm, armed: () => armedAt !== null, dispose: stop };
}
