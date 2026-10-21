"use client";
import Link from "next/link";
import { useState } from "react";
import { api, ApiFailure, fmt, type ContentChangeResult, type ContentDetail } from "@/lib/api";
import { Badge } from "@/components/ui";
import type { Change } from "./SourcesPanel";

/**
 * The origin issue: an unacknowledged change (with "acknowledge") and, separately, the MERGED / EXCLUDED notice that
 * stays until the issue changes again. Acknowledging sends the change number shown here; if the issue changed again
 * in between the server answers 409 CONTENT_ORIGIN_CHANGED and the person reloads and looks again.
 */
export function OriginPanel({ content, change, refreshOrigin, blocked }: {
  content: ContentDetail; change: Change; refreshOrigin: () => Promise<void>; blocked: boolean;
}) {
  const c = content.originChange;
  const issue = content.originIssue;
  const [changedAgain, setChangedAgain] = useState(false);
  const [busy, setBusy] = useState(false);

  async function acknowledge() {
    const seen = c.issueChangeSeq; // the number on screen, never a newer one fetched behind the person's back
    setBusy(true);
    try {
      let originChanged = false;
      await change("원천 이슈 변경을 확인 처리했습니다.", async (version) => {
        try {
          return await api<ContentChangeResult>(`/api/admin/contents/${content.id}/acknowledge-origin`, { method: "POST", body: { version, originChangeSeq: seen } });
        } catch (e) {
          if (e instanceof ApiFailure && e.code === "CONTENT_ORIGIN_CHANGED") originChanged = true;
          throw e;
        }
      }, (r) => r.content);
      setChangedAgain(originChanged);
    } finally { setBusy(false); }
  }

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>원천 이슈</h2>
      <div className="row tight"><Link href={`/issues/${issue.id}`}>#{issue.id} {issue.title}</Link> <Badge value={issue.status} /></div>
      {c.notice === "MERGED" && (
        <div className="error">원천 이슈가 {c.mergedIntoIssueId ? <Link href={`/issues/${c.mergedIntoIssueId}`}>이슈 #{c.mergedIntoIssueId}</Link> : "다른 이슈"}(으)로 병합되었습니다.
          콘텐츠의 원천은 그대로 #{issue.id}입니다. 이 안내는 확인 처리와 관계없이 계속 보입니다.</div>
      )}
      {c.notice === "EXCLUDED" && <div className="error">원천 이슈가 제외(EXCLUDED)되었습니다. 이 안내는 확인 처리와 관계없이 계속 보입니다.</div>}
      {c.unacknowledged ? (
        <div className="warn-box">
          <strong>확인하지 않은 원천 이슈 변경이 있습니다.</strong>
          <div className="muted">변경 번호 {c.issueChangeSeq} (확인한 번호 {c.acknowledgedSeq}) · 마지막 변경 {fmt(c.issueChangedAt)}</div>
          <p>이슈 화면에서 무엇이 바뀌었는지 보고 콘텐츠에 반영할지 판단하세요. <strong>확인 처리는 콘텐츠의 제목·본문·출처·상태·승인을 바꾸지 않습니다</strong> — “변경 번호 {c.issueChangeSeq}까지 봤다”는 기록만 남깁니다.</p>
          <button type="button" disabled={blocked || busy} onClick={acknowledge}>변경 번호 {c.issueChangeSeq}까지 확인 처리</button>
        </div>
      ) : <div className="muted">확인한 뒤 바뀐 내용이 없습니다 (변경 번호 {c.issueChangeSeq} · 마지막 확인 {fmt(c.acknowledgedAt ?? content.originCheckedAt)}).</div>}
      {changedAgain && (
        <div className="error">확인 처리하는 사이 원천 이슈가 다시 바뀌어 처리하지 않았습니다. 원천 정보를 다시 불러와 바뀐 내용을 본 뒤 다시 확인 처리하세요.
          <div><button type="button" onClick={async () => { await refreshOrigin(); setChangedAgain(false); }}>원천 정보 다시 불러오기</button></div></div>
      )}
    </div>
  );
}
