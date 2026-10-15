"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { api, ApiFailure, type ContentDetail } from "@/lib/api";
import { describe, TYPE_LABEL } from "./common";

const CREATABLE = new Set(["ACTIVE", "CLOSED"]);

function newKey(): string {
  const c = typeof crypto !== "undefined" ? crypto : undefined;
  if (c?.randomUUID) return c.randomUUID();
  return "k-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 12);
}

/** Retrying the same request is safe; a 4xx answer (other than a timeout-like one) means a new attempt is needed. */
const retryable = (e: unknown) => !(e instanceof ApiFailure) || e.status >= 500 || e.status === 0 || e.status === 408 || e.status === 429;

/**
 * "Create content" on the issue page. One Idempotency-Key per create attempt: a retry after a failure sends the same
 * key (the server returns the content it already made instead of a second one); a new create gets a new key.
 */
export function CreateContent({ issueId, issueStatus, mergedIntoIssueId }: { issueId: number; issueStatus: string; mergedIntoIssueId: number | null }) {
  const router = useRouter();
  const [attempt, setAttempt] = useState<{ type: "NEWS" | "LIFE"; key: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  // refs, not state: a double click must neither send twice nor mint a second key
  const sending = useRef(false);
  const attemptRef = useRef<{ type: "NEWS" | "LIFE"; key: string } | null>(null);
  const allowed = CREATABLE.has(issueStatus);

  async function create(type: "NEWS" | "LIFE") {
    if (sending.current) return;
    sending.current = true;
    const prev = attemptRef.current;
    const current = prev && prev.type === type ? prev : { type, key: newKey() };
    attemptRef.current = current;
    setAttempt(current); setBusy(true); setError(null);
    try {
      const c = await api<ContentDetail>(`/api/admin/issues/${issueId}/contents`, { method: "POST", headers: { "Idempotency-Key": current.key },
        body: { contentType: type } });
      attemptRef.current = null;
      setAttempt(null);
      router.push(`/contents/${c.id}`);
    } catch (e) {
      setError(e);
      if (!retryable(e)) { attemptRef.current = null; setAttempt(null); }
    } finally { sending.current = false; setBusy(false); }
  }

  return (
    <div className="card">
      <div className="row spread"><h2 style={{ margin: 0 }}>콘텐츠 만들기</h2><Link href={`/contents?issueId=${issueId}`}>이 이슈로 만든 콘텐츠 보기 →</Link></div>
      {allowed ? (
        <>
          <p className="muted">이 이슈를 원천(주 출처)으로 하는 빈 초안을 만들고 편집 화면으로 이동합니다. 제목·본문은 비어 있는 상태로 시작합니다.</p>
          <div className="row tight">
            {(["NEWS", "LIFE"] as const).map((t) => (
              <button key={t} type="button" className="primary" disabled={busy} onClick={() => create(t)}>
                {attempt?.type === t && error !== null ? `${TYPE_LABEL[t]} 다시 시도` : `${TYPE_LABEL[t]} 만들기`}</button>
            ))}
            {busy && <span className="muted">만드는 중…</span>}
            {attempt && error !== null && !busy && <button type="button" onClick={() => { attemptRef.current = null; setAttempt(null); setError(null); }}>취소</button>}
          </div>
          {error !== null && <div className="error">{describe(error)}{attempt ? " — 다시 시도하면 같은 요청으로 보내므로 콘텐츠가 두 개 생기지 않습니다." : ""}</div>}
        </>
      ) : (
        <div className="warn-box">
          {issueStatus === "REVIEW" && "검수 대기(REVIEW) 이슈에서는 콘텐츠를 만들 수 없습니다. 이슈를 확정(ACTIVE)한 뒤 만드세요."}
          {issueStatus === "EXCLUDED" && "제외(EXCLUDED)된 이슈에서는 콘텐츠를 만들 수 없습니다."}
          {issueStatus === "MERGED" && <>병합된 이슈에서는 콘텐츠를 만들 수 없습니다.{mergedIntoIssueId && <> 병합 대상 <Link href={`/issues/${mergedIntoIssueId}`}>이슈 #{mergedIntoIssueId}</Link>에서 만드세요.</>}</>}
          {!["REVIEW", "EXCLUDED", "MERGED"].includes(issueStatus) && `${issueStatus} 상태의 이슈에서는 콘텐츠를 만들 수 없습니다(ACTIVE·CLOSED만 가능).`}
        </div>
      )}
    </div>
  );
}
