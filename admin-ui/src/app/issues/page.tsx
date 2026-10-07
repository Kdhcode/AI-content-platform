"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, fmt, qs, type IssueListItem, type Page } from "@/lib/api";
import { Badge, ErrorText, Pager, Shell } from "@/components/ui";

export default function IssuesPage() {
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("REVIEW");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<IssueListItem> | null>(null);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async () => {
    try {
      setError(null);
      setData(await api<Page<IssueListItem>>("/api/admin/issues" + qs({ q, status, page, size: 20 })));
    } catch (e) { setError(e); }
  }, [q, status, page]);
  useEffect(() => { load(); }, [load]);

  return (
    <Shell>
      <h1>이슈</h1>
      <div className="row">
        <input type="text" placeholder="제목/요약 검색" value={q} onChange={(e) => { setPage(0); setQ(e.target.value); }} />
        <select value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
          <option value="">상태 전체</option>
          {["REVIEW", "ACTIVE", "CLOSED", "MERGED", "EXCLUDED"].map((s) => <option key={s}>{s}</option>)}
        </select>
        <button onClick={load}>새로고침</button>
      </div>
      <ErrorText error={error} />
      <table>
        <thead><tr><th>ID</th><th>제목</th><th>상태</th><th>기사</th><th>언론사</th><th>기간</th></tr></thead>
        <tbody>
          {data?.items.map((i) => (
            <tr key={i.id}>
              <td>{i.id}</td>
              <td><Link href={`/issues/${i.id}`}>{i.title}</Link>{i.summary && <div className="muted">{i.summary}</div>}</td>
              <td><Badge value={i.status} />{i.mergedIntoIssueId && <> → #{i.mergedIntoIssueId}</>}</td>
              <td>{i.articleCount}</td>
              <td>{i.publisherCount}</td>
              <td>{fmt(i.firstPublishedAt)} ~ {fmt(i.lastUpdatedAt)}</td>
            </tr>
          ))}
          {data && data.items.length === 0 && <tr><td colSpan={6} className="muted">결과가 없습니다.</td></tr>}
        </tbody>
      </table>
      {data && <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
    </Shell>
  );
}
