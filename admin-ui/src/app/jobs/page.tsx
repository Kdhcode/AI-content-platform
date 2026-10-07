"use client";
import { useCallback, useEffect, useState } from "react";
import { api, fmt, qs, type Job, type Page } from "@/lib/api";
import { Badge, ErrorText, Pager, Shell } from "@/components/ui";

export default function JobsPage() {
  const [status, setStatus] = useState("");
  const [type, setType] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Job> | null>(null);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async () => {
    try {
      setError(null);
      setData(await api<Page<Job>>("/api/admin/jobs" + qs({ status, type, page, size: 20 })));
    } catch (e) { setError(e); }
  }, [status, type, page]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(load, 5000); return () => clearInterval(t); }, [load]);

  async function act(id: number, action: "retry" | "cancel") {
    try { setError(null); await api(`/api/admin/jobs/${id}/${action}`, { method: "POST" }); await load(); } catch (e) { setError(e); }
  }

  return (
    <Shell>
      <h1>작업</h1>
      <div className="row">
        <select value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
          <option value="">상태 전체</option>
          {["PENDING", "RUNNING", "SUCCESS", "FAILED", "CANCELLED"].map((s) => <option key={s}>{s}</option>)}
        </select>
        <select value={type} onChange={(e) => { setPage(0); setType(e.target.value); }}>
          <option value="">유형 전체</option>
          {["COLLECT_NEWS", "ANALYZE_ARTICLE", "EMBED_ARTICLE", "CLASSIFY_ARTICLE"].map((s) => <option key={s}>{s}</option>)}
        </select>
        <button onClick={load}>새로고침</button>
        <span className="muted">5초마다 자동 갱신 · 재시도/취소는 SYSTEM_ADMIN만 가능</span>
      </div>
      <ErrorText error={error} />
      <table>
        <thead><tr><th>ID</th><th>유형</th><th>상태</th><th>대상</th><th>재시도</th><th>오류</th><th>등록</th><th></th></tr></thead>
        <tbody>
          {data?.items.map((j) => (
            <tr key={j.id}>
              <td>{j.id}</td>
              <td>{j.jobType}</td>
              <td><Badge value={j.status} /></td>
              <td className="muted">{JSON.stringify(j.payload)}</td>
              <td>{j.retryCount}/{j.maxRetries}</td>
              <td>{j.errorCode && <><strong>{j.errorCode}</strong><div className="muted">{j.errorMessage}</div></>}</td>
              <td>{fmt(j.requestedAt)}</td>
              <td>
                {j.status === "FAILED" && <button onClick={() => act(j.id, "retry")}>재시도</button>}
                {j.status === "PENDING" && <button onClick={() => act(j.id, "cancel")}>취소</button>}
              </td>
            </tr>
          ))}
          {data && data.items.length === 0 && <tr><td colSpan={8} className="muted">작업이 없습니다.</td></tr>}
        </tbody>
      </table>
      {data && <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
    </Shell>
  );
}
