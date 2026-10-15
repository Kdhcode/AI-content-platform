// Content body editing helpers. The limits are the server's (ContentBodyValidator, ContentRelationService,
// ContentUrls, ContentStatusPolicy); scripts/verify-content-session.cjs reads the Java constants and fails when they
// drift apart. The server still validates every save; these only let the editor show counters and warn early.

export const LIMITS = {
  sections: 30,
  paragraphsPerSection: 50,
  headingChars: 120,
  paragraphChars: 5_000,
  totalChars: 100_000,
  titleChars: 200,
  links: 500,
  linkTitleChars: 200,
  urlChars: 2_000,
  labelChars: 300,
  reasonChars: 500,
} as const;

export type Paragraph = { id: string; text: string };
export type Section = { id: string; heading: string; paragraphs: Paragraph[] };
export type Body = { schemaVersion: 1; sections: Section[] };
/** What the editor changes and the autosave sends. */
export type Doc = { title: string; body: Body };

export const EMPTY_BODY: Body = { schemaVersion: 1, sections: [] };

/** A new id for a section or paragraph: matches ^[a-z0-9-]{1,40}$ and is not used anywhere in {@code body}. */
export function newId(prefix: "s" | "p", body: Body, random: () => number = Math.random): string {
  const used = new Set<string>();
  for (const s of body.sections) { used.add(s.id); for (const p of s.paragraphs) used.add(p.id); }
  for (;;) {
    const id = `${prefix}-${Math.floor(random() * 36 ** 8).toString(36).padStart(8, "0")}`;
    if (!used.has(id)) return id;
  }
}

/** Characters as the server counts them (Java String.length, the same UTF-16 length as JavaScript). */
export function totalChars(body: Body): number {
  let n = 0;
  for (const s of body.sections) { n += s.heading.length; for (const p of s.paragraphs) n += p.text.length; }
  return n;
}

export type Violation = { path: string; message: string };

/** The save rules the editor can check before sending (same paths as the server's violations). */
export function saveViolations(doc: Doc): Violation[] {
  const out: Violation[] = [];
  if (doc.title.length > LIMITS.titleChars) out.push({ path: "title", message: `최대 ${LIMITS.titleChars}자입니다.` });
  if (/[\r\n]/.test(doc.title)) out.push({ path: "title", message: "줄바꿈을 넣을 수 없습니다." });
  const sections = doc.body.sections;
  if (sections.length > LIMITS.sections) out.push({ path: "body.sections", message: `최대 ${LIMITS.sections}개입니다(현재 ${sections.length}개).` });
  sections.forEach((s, i) => {
    const sp = `body.sections[${i}]`;
    if (s.heading.length > LIMITS.headingChars) out.push({ path: `${sp}.heading`, message: `최대 ${LIMITS.headingChars}자입니다.` });
    if (/[\r\n]/.test(s.heading)) out.push({ path: `${sp}.heading`, message: "줄바꿈을 넣을 수 없습니다." });
    if (s.paragraphs.length > LIMITS.paragraphsPerSection) out.push({ path: `${sp}.paragraphs`, message: `섹션당 최대 ${LIMITS.paragraphsPerSection}개입니다.` });
    s.paragraphs.forEach((p, j) => {
      if (p.text.length > LIMITS.paragraphChars) out.push({ path: `${sp}.paragraphs[${j}].text`, message: `문단은 최대 ${LIMITS.paragraphChars}자입니다.` });
    });
  });
  const total = totalChars(doc.body);
  if (total > LIMITS.totalChars) out.push({ path: "body", message: `소제목과 문단을 합쳐 최대 ${LIMITS.totalChars}자입니다(현재 ${total}자).` });
  return out;
}

/** JSON equality that ignores object key order (the server stores the body as jsonb, which reorders keys). */
export function sameJson(a: unknown, b: unknown): boolean {
  return canonical(a) === canonical(b);
}

function canonical(v: unknown): string {
  if (Array.isArray(v)) return "[" + v.map(canonical).join(",") + "]";
  if (v && typeof v === "object") {
    const o = v as Record<string, unknown>;
    return "{" + Object.keys(o).sort().map((k) => JSON.stringify(k) + ":" + canonical(o[k])).join(",") + "}";
  }
  return JSON.stringify(v ?? null);
}

/** Keeps only the fields the server accepts, in case a draft from storage carries anything else. */
export function cleanBody(raw: unknown): Body {
  const sections = (raw as { sections?: unknown })?.sections;
  if (!Array.isArray(sections)) return { schemaVersion: 1, sections: [] };
  return {
    schemaVersion: 1,
    sections: sections.map((s: { id?: unknown; heading?: unknown; paragraphs?: unknown }) => ({
      id: String(s?.id ?? ""), heading: String(s?.heading ?? ""),
      paragraphs: (Array.isArray(s?.paragraphs) ? s.paragraphs : []).map((p: { id?: unknown; text?: unknown }) => ({
        id: String(p?.id ?? ""), text: String(p?.text ?? "") })),
    })),
  };
}
