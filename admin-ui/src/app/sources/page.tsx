"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, fmt, type Job, type NewsSource } from "@/lib/api";
import { Badge, ErrorText, Shell } from "@/components/ui";

export default function SourcesPage() {
  const [sources, setSources] = useState<NewsSource[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [pending, setPending] = useState<number | null>(null);
  const [job, setJob] = useState<Job | null>(null);
  const load = useCallback(async () => {
    try { setSources(await api<NewsSource[]>("/api/admin/sources")); }
    catch (e) { setError(e); }
  }, []);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const timer = setInterval(load, 5000); return () => clearInterval(timer); }, [load]);

  async function collect(id: number) {
    setPending(id); setError(null); setJob(null);
    try {
      setJob(await api<Job>(`/api/admin/sources/${id}/collect`, { method: "POST" }));
      await load();
    } catch (e) { setError(e); }
    finally { setPending(null); }
  }

  return (
    <Shell>
      <h1>뉴스 소스</h1>
      <div className="row">
        <button onClick={() => { setError(null); load(); }}>새로고침</button>
        <span className="muted">5초마다 자동 갱신</span>
      </div>
      <ErrorText error={error} />
      {job && <p>수집 작업 #{job.id}을 등록했습니다. <Link href="/jobs">진행 상태 확인</Link></p>}
      <table>
        <thead><tr><th>소스</th><th>유형</th><th>상태</th><th>수집 간격</th><th>최근 성공</th><th>오류</th><th></th></tr></thead>
        <tbody>
          {sources.map((source) => (
            <tr key={source.id}>
              <td>{source.name}<div className="muted">{source.baseUrl}</div></td>
              <td>{source.type}</td>
              <td><Badge value={source.enabled ? source.status : "DISABLED"} /></td>
              <td>{source.collectionIntervalSeconds}초</td>
              <td>{fmt(source.lastSuccessAt)}</td>
              <td>{source.lastError && <><strong>{source.failureCount}회 실패</strong><div>{source.lastError}</div></>}</td>
              <td><button disabled={!source.enabled || pending !== null} onClick={() => collect(source.id)}>
                {pending === source.id ? "등록 중…" : "지금 수집"}
              </button></td>
            </tr>
          ))}
          {sources.length === 0 && <tr><td colSpan={7} className="muted">등록된 뉴스 소스가 없습니다.</td></tr>}
        </tbody>
      </table>
    </Shell>
  );
}
