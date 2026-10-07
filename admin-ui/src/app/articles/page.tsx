"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, fmt, qs, type ArticleListItem, type Page } from "@/lib/api";
import { Badge, ErrorText, Pager, Shell } from "@/components/ui";

export default function ArticlesPage() {
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [cls, setCls] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<ArticleListItem> | null>(null);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async () => {
    try {
      setError(null);
      setData(await api<Page<ArticleListItem>>("/api/admin/articles" + qs({ q, status, classificationStatus: cls, page, size: 20 })));
    } catch (e) { setError(e); }
  }, [q, status, cls, page]);
  useEffect(() => { load(); }, [load]);

  return (
    <Shell>
      <h1>기사</h1>
      <div className="row">
        <input type="text" placeholder="제목 검색" value={q} onChange={(e) => { setPage(0); setQ(e.target.value); }} />
        <select value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
          <option value="">상태 전체</option>
          {["COLLECTED", "ANALYSIS_PENDING", "ANALYZED", "DUPLICATE", "FAILED"].map((s) => <option key={s}>{s}</option>)}
        </select>
        <select value={cls} onChange={(e) => { setPage(0); setCls(e.target.value); }}>
          <option value="">분류 상태 전체</option>
          {["NOT_CLASSIFIED", "CLASSIFIED", "REVIEW", "FAILED"].map((s) => <option key={s}>{s}</option>)}
        </select>
        <button onClick={load}>새로고침</button>
      </div>
      <ErrorText error={error} />
      <table>
        <thead><tr><th>ID</th><th>제목</th><th>언론사</th><th>발행</th><th>상태</th><th>분류</th><th>이슈</th></tr></thead>
        <tbody>
          {data?.items.map((a) => (
            <tr key={a.id}>
              <td>{a.id}</td>
              <td><Link href={`/articles/${a.id}`}>{a.title}</Link></td>
              <td>{a.publisherName}</td>
              <td>{fmt(a.publishedAt)}</td>
              <td><Badge value={a.status} /></td>
              <td><Badge value={a.classificationStatus} /></td>
              <td>{a.issueId ? <Link href={`/issues/${a.issueId}`}>#{a.issueId} <Badge value={a.issueStatus} /></Link> : "-"}</td>
            </tr>
          ))}
          {data && data.items.length === 0 && <tr><td colSpan={7} className="muted">결과가 없습니다.</td></tr>}
        </tbody>
      </table>
      {data && <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
    </Shell>
  );
}
