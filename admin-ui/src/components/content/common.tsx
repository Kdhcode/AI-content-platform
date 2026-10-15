"use client";
import { ApiFailure, type OriginChange } from "@/lib/api";
import { ClientInvalid, SessionBlocked } from "@/lib/contentSession";
import type { Violation } from "@/lib/contentBody";

export const CONTENT_STATUSES = ["DRAFT", "REVIEW", "APPROVED", "HOLD", "EXCLUDED", "ARCHIVED"] as const;
export const TYPE_LABEL: Record<string, string> = { NEWS: "NEWS(뉴스)", LIFE: "LIFE(생활 정보)" };
/** Content can't be changed in these (resume / restore first). */
export const LOCKED = new Set(["HOLD", "EXCLUDED", "ARCHIVED"]);
/** A real change sends these back to DRAFT (approval / review request cleared). */
export const BACK_TO_DRAFT = new Set(["REVIEW", "APPROVED"]);

export type ActionName = "submit" | "approve" | "return" | "hold" | "resume" | "exclude" | "archive" | "restore";
/** docs/CONTENT_A_PLAN.md §4 (ContentStatusPolicy). The server decides; this only picks the buttons to show. */
export const ACTIONS: { name: ActionName; label: string; from: string[]; adminOnly: boolean; reasonRequired: boolean; danger?: boolean }[] = [
  { name: "submit", label: "검수 요청", from: ["DRAFT"], adminOnly: false, reasonRequired: false },
  { name: "approve", label: "승인", from: ["REVIEW"], adminOnly: true, reasonRequired: false },
  { name: "return", label: "반려", from: ["REVIEW"], adminOnly: false, reasonRequired: true },
  { name: "hold", label: "보류", from: ["DRAFT", "REVIEW", "APPROVED"], adminOnly: false, reasonRequired: true },
  { name: "resume", label: "보류 해제", from: ["HOLD"], adminOnly: false, reasonRequired: false },
  { name: "exclude", label: "제외", from: ["DRAFT", "REVIEW", "HOLD"], adminOnly: false, reasonRequired: true, danger: true },
  { name: "archive", label: "보관", from: ["APPROVED", "HOLD"], adminOnly: false, reasonRequired: false },
  { name: "restore", label: "복귀", from: ["EXCLUDED", "ARCHIVED"], adminOnly: true, reasonRequired: true },
];

/** Badges for the origin issue: an unacknowledged change, and (separately) a MERGED / EXCLUDED notice. */
export function OriginBadges({ change }: { change: OriginChange }) {
  return (
    <>
      {change.unacknowledged && <span className="badge warn" title="확인한 뒤 원천 이슈가 바뀌었습니다">원천 변경 미확인</span>}
      {change.notice === "MERGED" && <span className="badge bad">원천 이슈 병합됨{change.mergedIntoIssueId ? ` → #${change.mergedIntoIssueId}` : ""}</span>}
      {change.notice === "EXCLUDED" && <span className="badge bad">원천 이슈 제외됨</span>}
    </>
  );
}

/** Field-level problems from a failed request (server violations) or from the editor's own check. */
export function violationsOf(error: unknown): Violation[] {
  if (error instanceof ClientInvalid) return error.violations;
  if (error instanceof SessionBlocked) return violationsOf(error.cause);
  if (error instanceof ApiFailure && error.details && typeof error.details === "object") {
    const v = (error.details as { violations?: unknown }).violations;
    if (Array.isArray(v)) return v.filter((x) => x && typeof x.path === "string").map((x) => ({ path: x.path, message: String(x.message) }));
  }
  return [];
}

export function describe(error: unknown): string {
  if (error instanceof SessionBlocked) {
    const inner = error.cause ? " (" + describe(error.cause) + ")" : "";
    return error.message + inner;
  }
  if (error instanceof ClientInvalid) return "한도를 넘은 곳이 있어 저장하지 않았습니다: " + error.message;
  if (error instanceof ApiFailure) return `[${error.code}] ${error.message}`;
  return error instanceof Error ? error.message : String(error);
}

export function Problem({ error }: { error: unknown }) {
  if (!error) return null;
  const v = violationsOf(error);
  return (
    <div className="error" role="alert">{describe(error)}
      {v.length > 1 && <ul>{v.map((x, i) => <li key={i}><code>{x.path}</code> {x.message}</li>)}</ul>}
    </div>
  );
}

/** Only http(s) addresses become links; anything else is shown as text. */
export function safeHref(url: string | null | undefined): string | undefined {
  return url && /^https?:\/\//i.test(url) ? url : undefined;
}

export function Counter({ value, max }: { value: number; max: number }) {
  return <span className={`counter${value > max ? " over" : ""}`}>{value.toLocaleString()} / {max.toLocaleString()}</span>;
}
