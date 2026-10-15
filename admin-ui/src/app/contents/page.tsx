"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, fmt, qs, type ContentListItem, type Page } from "@/lib/api";
import { Badge, ErrorText, Pager, Shell, PageHeading } from "@/components/ui";
import { CONTENT_STATUSES, OriginBadges, TYPE_LABEL } from "@/components/content/common";

export default function ContentsPage() {
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const [updateNeeded, setUpdateNeeded] = useState(false);
  const [issueId, setIssueId] = useState<string>("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<ContentListItem> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const p = new URLSearchParams(window.location.search);
    const issue = p.get("issueId");
    if (issue && /^\d+$/.test(issue)) setIssueId(issue);
    if (p.get("updateNeeded") === "true") setUpdateNeeded(true);
    setReady(true);
  }, []);

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      setError(null);
      setData(await api<Page<ContentListItem>>("/api/admin/contents" + qs({
        type, status, q: q.trim(), issueId, updateNeeded: updateNeeded ? "true" : undefined, page, size: 20 })));
    } catch (e) { setError(e); }
  }, [ready, type, status, q, issueId, updateNeeded, page]);
  useEffect(() => { load(); }, [load]);

  return (
    <Shell>
      <PageHeading eyebrow="CONTENT DESK" title="콘텐츠 관리"
        description="이슈에서 만든 NEWS·LIFE 콘텐츠를 작성하고 검수합니다. 승인해도 공개 화면에는 아직 나오지 않습니다." />
      <div className="row">
        <select aria-label="유형" value={type} onChange={(e) => { setPage(0); setType(e.target.value); }}>
          <option value="">유형 전체</option>
          <option value="NEWS">{TYPE_LABEL.NEWS}</option>
          <option value="LIFE">{TYPE_LABEL.LIFE}</option>
        </select>
        <select aria-label="상태" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
          <option value="">상태 전체</option>
          {CONTENT_STATUSES.map((s) => <option key={s}>{s}</option>)}
        </select>
        <input type="text" placeholder="제목 검색" maxLength={200} value={q} onChange={(e) => { setPage(0); setQ(e.target.value); }} />
        <label className="check"><input type="checkbox" checked={updateNeeded}
          onChange={(e) => { setPage(0); setUpdateNeeded(e.target.checked); }} /> 원천 이슈 변경 미확인만</label>
        {issueId && <span className="badge">원천 이슈 #{issueId} <button className="link-button" aria-label="이슈 필터 해제"
          onClick={() => { setPage(0); setIssueId(""); }}>×</button></span>}
        <button onClick={load}>새로고침</button>
      </div>
      <ErrorText error={error} />
      <table>
        <thead><tr><th>ID</th><th>유형</th><th>제목</th><th>상태</th><th>원천 이슈</th><th>수정</th></tr></thead>
        <tbody>
          {data?.items.map((c) => (
            <tr key={c.id}>
              <td>{c.id}</td>
              <td>{TYPE_LABEL[c.contentType] ?? c.contentType}</td>
              <td><Link href={`/contents/${c.id}`}>{c.title.trim() ? c.title : <span className="muted">(제목 없음)</span>}</Link></td>
              <td><Badge value={c.status} /></td>
              <td><Link href={`/issues/${c.originIssueId}`}>#{c.originIssueId} {c.originIssueTitle}</Link>
                <div className="row tight"><Badge value={c.originIssueStatus} /><OriginBadges change={c.originChange} /></div></td>
              <td>{fmt(c.updatedAt)}<div className="muted">{c.updatedBy}</div></td>
            </tr>
          ))}
          {data && data.items.length === 0 && <tr><td colSpan={6} className="muted">결과가 없습니다. 콘텐츠는 이슈 상세 화면에서 만듭니다.</td></tr>}
        </tbody>
      </table>
      {data && <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
    </Shell>
  );
}
