"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, fmt, type IssueDetail } from "@/lib/api";
import { Badge, ErrorText, Shell } from "@/components/ui";

export default function IssueDetailPage() {
  const id = Number(useParams<{ id: string }>().id);
  const [d, setD] = useState<IssueDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [newTitle, setNewTitle] = useState("");
  const [mergeIds, setMergeIds] = useState("");
  const [reason, setReason] = useState("");

  const load = useCallback(async () => {
    try {
      setError(null);
      const r = await api<IssueDetail>(`/api/admin/issues/${id}`);
      setD(r); setTitle(r.title); setSummary(r.summary ?? ""); setSelected(new Set());
    } catch (e) { setError(e); }
  }, [id]);
  useEffect(() => { load(); }, [load]);

  async function run(label: string, fn: () => Promise<unknown>) {
    setBusy(true); setNotice(""); setError(null);
    try { await fn(); setNotice(label); await load(); } catch (e) { setError(e); } finally { setBusy(false); }
  }

  const r = reason.trim() || undefined;
  const save = () => run("저장했습니다.", () => api(`/api/admin/issues/${id}`, { method: "PATCH", body: {
    title: title !== d?.title ? title : undefined, summary: summary !== (d?.summary ?? "") ? summary : undefined, reason: r } }));
  const setStatus = (status: string) => run(`상태를 ${status}(으)로 변경했습니다.`, () =>
    api(`/api/admin/issues/${id}`, { method: "PATCH", body: { status, reason: r } }));
  const split = () => run("선택한 기사를 새 이슈로 분리했습니다.", () => api(`/api/admin/issues/${id}/split`, { method: "POST", body: {
    articleIds: Array.from(selected), newTitle: newTitle.trim() || undefined, reason: r } }));
  const merge = () => {
    const ids = mergeIds.split(/[\s,]+/).filter(Boolean).map(Number);
    if (ids.some((n) => !Number.isInteger(n) || n <= 0)) { setError(new Error("병합할 이슈 ID를 숫자로 입력하세요.")); return; }
    return run(`이슈 ${ids.map((n) => "#" + n).join(", ")}을(를) 이 이슈로 병합했습니다.`, async () => {
      await api(`/api/admin/issues/${id}/merge`, { method: "POST", body: { sourceIssueIds: ids, reason: r } });
      setMergeIds("");
    });
  };
  const toggle = (articleId: number) => setSelected((s) => { const n = new Set(s); n.has(articleId) ? n.delete(articleId) : n.add(articleId); return n; });
  const editable = d && (d.status === "ACTIVE" || d.status === "REVIEW");

  return (
    <Shell>
      <h1>이슈 #{id}</h1>
      <ErrorText error={error} />
      {notice && <div className="notice">{notice}</div>}
      {d && (
        <>
          <div className="card">
            <div className="row"><Badge value={d.status} />
              {d.mergedIntoIssueId && <span>병합됨 → <Link href={`/issues/${d.mergedIntoIssueId}`}>#{d.mergedIntoIssueId}</Link></span>}
              {d.mergedFromIssueIds.length > 0 && <span>병합 출처: {d.mergedFromIssueIds.map((m) => <Link key={m} href={`/issues/${m}`}>#{m} </Link>)}</span>}
              <span className="muted">기사 {d.articleCount} · 언론사 {d.publisherCount} · {fmt(d.firstPublishedAt)} ~ {fmt(d.lastUpdatedAt)}</span></div>
            <div className="row"><input type="text" style={{ flex: 1 }} value={title} onChange={(e) => setTitle(e.target.value)} disabled={!editable} /></div>
            <textarea value={summary} onChange={(e) => setSummary(e.target.value)} disabled={!editable} />
            <div className="muted">요약 출처: {d.summarySource}{d.summarySource === "MANUAL" && " (자동 갱신 안 함)"}</div>
            <div className="row" style={{ marginTop: 8 }}>
              <input type="text" placeholder="변경 사유(선택, 감사 로그에 기록)" value={reason} onChange={(e) => setReason(e.target.value)} style={{ minWidth: 280 }} />
              <button className="primary" disabled={busy || !editable} onClick={save}>제목/요약 저장</button>
              {d.status === "REVIEW" && <button disabled={busy} onClick={() => setStatus("ACTIVE")}>검수 확정(ACTIVE)</button>}
              {editable && <button disabled={busy} onClick={() => setStatus("EXCLUDED")} className="danger">제외</button>}
              {(d.status === "CLOSED" || d.status === "EXCLUDED") && <button disabled={busy} onClick={() => setStatus("ACTIVE")}>다시 활성화</button>}
            </div>
            {d.keyFacts.length > 0 && <><h2>핵심 사실</h2><ul>{d.keyFacts.map((f, i) => <li key={i}>{f}</li>)}</ul></>}
            {d.entities.length > 0 && <div className="muted">{d.entities.map((e) => e.name).join(", ")}</div>}
          </div>

          <h2>기사 ({d.articles.length})</h2>
          <table>
            <thead><tr><th></th><th>ID</th><th>제목</th><th>언론사</th><th>발행</th><th>연결 방식</th><th>LLM 판단</th></tr></thead>
            <tbody>
              {d.articles.map((a) => (
                <tr key={a.articleId}>
                  <td><input type="checkbox" disabled={!editable} checked={selected.has(a.articleId)} onChange={() => toggle(a.articleId)} /></td>
                  <td>{a.articleId}</td>
                  <td><Link href={`/articles/${a.articleId}`}>{a.title}</Link></td>
                  <td>{a.publisherName}</td>
                  <td>{fmt(a.publishedAt)}</td>
                  <td>{a.classificationMethod}{a.manuallyCorrected && " ✎"}{a.similarity != null && <div className="muted">유사도 {a.similarity}</div>}</td>
                  <td>{a.llmDecision ? <>{a.llmDecision} ({a.llmConfidence})<div className="muted">{a.llmReason}</div></> : "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {editable && (
            <div className="grid2" style={{ marginTop: 14 }}>
              <div className="card">
                <h2 style={{ marginTop: 0 }}>선택한 기사 분리</h2>
                <div className="muted">선택 {selected.size}건 · 원본에 기사가 1건 이상 남아야 합니다.</div>
                <div className="row" style={{ marginTop: 8 }}>
                  <input type="text" placeholder="새 이슈 제목(비우면 첫 기사 제목)" value={newTitle} onChange={(e) => setNewTitle(e.target.value)} />
                  <button disabled={busy || selected.size === 0} onClick={split}>새 이슈로 분리</button>
                </div>
              </div>
              <div className="card">
                <h2 style={{ marginTop: 0 }}>다른 이슈를 이 이슈로 병합</h2>
                <div className="row">
                  <input type="text" placeholder="병합할 이슈 ID (쉼표/공백 구분)" value={mergeIds} onChange={(e) => setMergeIds(e.target.value)} />
                  <button disabled={busy || !mergeIds.trim()} onClick={merge}>병합</button>
                </div>
                <div className="muted">병합된 이슈는 삭제되지 않고 MERGED 상태로 남습니다.</div>
              </div>
            </div>
          )}
        </>
      )}
    </Shell>
  );
}
