"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, fmt, type ArticleDetail, type Job } from "@/lib/api";
import { Badge, ErrorText, Shell } from "@/components/ui";

export default function ArticleDetailPage() {
  const id = Number(useParams<{ id: string }>().id);
  const [a, setA] = useState<ArticleDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState("");
  const [target, setTarget] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { setError(null); setA(await api<ArticleDetail>(`/api/admin/articles/${id}`)); } catch (e) { setError(e); }
  }, [id]);
  useEffect(() => { load(); }, [load]);

  async function reanalyze() {
    setBusy(true); setNotice(""); setError(null);
    try {
      const job = await api<Job>(`/api/admin/articles/${id}/reanalyze`, { method: "POST" });
      setNotice(`재분석 작업 #${job.id}을(를) 등록했습니다 (${job.status}). 작업 화면에서 진행을 확인하세요.`);
    } catch (e) { setError(e); } finally { setBusy(false); }
  }

  async function move() {
    setBusy(true); setNotice(""); setError(null);
    try {
      await api(`/api/admin/articles/${id}/move`, { method: "POST", body: { targetIssueId: Number(target), reason: reason || undefined } });
      setNotice(`이슈 #${target}(으)로 이동했습니다.`);
      setTarget("");
      await load();
    } catch (e) { setError(e); } finally { setBusy(false); }
  }

  return (
    <Shell>
      <h1>기사 #{id}</h1>
      <ErrorText error={error} />
      {notice && <div className="notice">{notice}</div>}
      {a && (
        <>
          <div className="card">
            <h2 style={{ marginTop: 0 }}>{a.title}</h2>
            <div className="muted">{a.publisherName} · {a.sourceName} · 발행 {fmt(a.publishedAt)} · 수집 {fmt(a.collectedAt)}</div>
            <div className="row" style={{ marginTop: 8 }}>
              <Badge value={a.status} /> <Badge value={a.classificationStatus} />
              <a href={a.originalUrl} target="_blank" rel="noreferrer">원문 ↗</a>
              {a.duplicateOfArticleId && <span>중복 원본: <Link href={`/articles/${a.duplicateOfArticleId}`}>#{a.duplicateOfArticleId}</Link></span>}
            </div>
            {a.analysisError && <div className="error">분석 오류: {a.analysisError}</div>}
            <div className="row">
              <button onClick={reanalyze} disabled={busy || a.status === "DUPLICATE"}>재분석</button>
            </div>
          </div>

          <div className="grid2">
            <div className="card">
              <h2 style={{ marginTop: 0 }}>AI 분석</h2>
              {a.analysis ? (
                <>
                  <p>{a.analysis.summary}</p>
                  <ul>{a.analysis.keyFacts.map((f, i) => <li key={i}>{f}</li>)}</ul>
                  <div className="muted">
                    유형 {a.analysis.eventType} · 분류 {a.analysis.category} · 신뢰도 {a.analysis.confidence}
                    {a.analysis.multiEvent && <> · <Badge value="REVIEW" /> 다중 사건 기사</>}
                  </div>
                  <div style={{ marginTop: 6 }}>{a.analysis.entities.map((e) => `${e.name}(${e.type})`).join(", ")}</div>
                </>
              ) : <span className="muted">아직 분석되지 않았습니다.</span>}
              <div className="muted" style={{ marginTop: 6 }}>임베딩: {a.hasEmbedding ? "있음" : "없음"}</div>
            </div>
            <div className="card">
              <h2 style={{ marginTop: 0 }}>현재 이슈</h2>
              {a.issue ? (
                <>
                  <div><Link href={`/issues/${a.issue.issueId}`}>#{a.issue.issueId} {a.issue.issueTitle}</Link> <Badge value={a.issue.issueStatus} /></div>
                  <div className="muted">방식 {a.issue.method}{a.issue.manuallyCorrected ? " · 수동 수정됨" : ""}
                    {a.issue.similarity != null && ` · 유사도 ${a.issue.similarity}`}</div>
                  {a.issue.llmDecision && <div className="muted">LLM {a.issue.llmDecision} ({a.issue.llmConfidence}) — {a.issue.llmReason}</div>}
                  <div className="row" style={{ marginTop: 10 }}>
                    <input type="text" placeholder="이동할 이슈 ID" value={target} onChange={(e) => setTarget(e.target.value.replace(/\D/g, ""))} style={{ minWidth: 120 }} />
                    <input type="text" placeholder="사유(선택)" value={reason} onChange={(e) => setReason(e.target.value)} />
                    <button className="primary" disabled={busy || !target} onClick={move}>다른 이슈로 이동</button>
                  </div>
                </>
              ) : <span className="muted">연결된 이슈가 없습니다.</span>}
            </div>
          </div>

          <h2>분류 이력</h2>
          <table>
            <thead><tr><th>시각</th><th>결정</th><th>방식</th><th>신뢰도</th><th>사유</th><th>후보 (유사도)</th><th>적용</th></tr></thead>
            <tbody>
              {a.classificationRuns.map((r) => (
                <tr key={r.id}>
                  <td>{fmt(r.createdAt)}</td>
                  <td><Badge value={r.decision} />{r.rawDecision && r.rawDecision !== r.decision && <div className="muted">LLM 원판단 {r.rawDecision}</div>}</td>
                  <td>{r.method}</td>
                  <td>{r.confidence ?? "-"}</td>
                  <td>{r.reason}</td>
                  <td>{r.candidates?.map((c) => <div key={c.issueId}><Link href={`/issues/${c.issueId}`}>#{c.issueId}</Link> {c.title} ({c.similarity})</div>)}</td>
                  <td>{r.applied ? `이슈 #${r.appliedIssueId}` : "기록만"}</td>
                </tr>
              ))}
              {a.classificationRuns.length === 0 && <tr><td colSpan={7} className="muted">분류 이력이 없습니다.</td></tr>}
            </tbody>
          </table>
        </>
      )}
    </Shell>
  );
}
