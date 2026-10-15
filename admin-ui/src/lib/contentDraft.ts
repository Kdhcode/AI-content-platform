// A copy of the unsaved editor document in the browser (localStorage), so a closed tab, a failed save or a conflict
// does not lose what was typed. Only the title, the body, the version it was based on and the time are stored —
// never credentials. The key names the environment (host and data label), the account and the content.

import { cleanBody, type Body, type Doc } from "./contentBody";

export type Draft = { contentId: number; baseVersion: number; title: string; body: Body; savedAt: string };

type StorageLike = { getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void };

export function draftKey(env: string, user: string, contentId: number): string {
  return `content-draft:v1:${encodeURIComponent(env)}:${encodeURIComponent(user)}:${contentId}`;
}

/** Every call reports failure instead of throwing (private mode, full storage, blocked site data). */
export function draftStorage(storage: () => StorageLike | null) {
  const get = () => { const s = storage(); if (!s) throw new Error("브라우저 저장소를 쓸 수 없습니다."); return s; };
  return {
    read(key: string): { draft: Draft | null; error: string | null } {
      try {
        const raw = get().getItem(key);
        if (!raw) return { draft: null, error: null };
        const d = JSON.parse(raw) as Partial<Draft>;
        if (typeof d.contentId !== "number" || typeof d.baseVersion !== "number" || typeof d.title !== "string") {
          return { draft: null, error: "임시 보관본 형식이 올바르지 않아 무시했습니다." };
        }
        return { draft: { contentId: d.contentId, baseVersion: d.baseVersion, title: d.title, body: cleanBody(d.body),
          savedAt: String(d.savedAt ?? "") }, error: null };
      } catch (e) {
        return { draft: null, error: "임시 보관본을 읽지 못했습니다: " + (e instanceof Error ? e.message : String(e)) };
      }
    },
    write(key: string, contentId: number, baseVersion: number, doc: Doc, now: Date = new Date()): string | null {
      try {
        const d: Draft = { contentId, baseVersion, title: doc.title, body: doc.body, savedAt: now.toISOString() };
        get().setItem(key, JSON.stringify(d));
        return null;
      } catch (e) {
        return "브라우저 임시 보관에 실패했습니다(저장 공간·개인 정보 보호 모드 확인): " + (e instanceof Error ? e.message : String(e));
      }
    },
    remove(key: string): string | null {
      try { get().removeItem(key); return null; } catch (e) {
        return "임시 보관본을 지우지 못했습니다: " + (e instanceof Error ? e.message : String(e));
      }
    },
  };
}
