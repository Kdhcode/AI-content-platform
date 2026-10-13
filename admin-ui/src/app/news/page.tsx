"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { publicApi, categoryName, newsDate, type PublicPage } from "@/lib/public-api";

export default function NewsPage() {
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [category, setCategory] = useState("");
  const [page, setPage] = useState(0);
  const [categories, setCategories] = useState<string[]>([]);
  const [data, setData] = useState<PublicPage | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    publicApi<string[]>("categories", controller.signal).then(setCategories).catch(() => {});
    return () => controller.abort();
  }, [retry]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(""); setData(null);
    const params = new URLSearchParams({ q, category, page: String(page), size: "12" });
    publicApi<PublicPage>(`issues?${params}`, controller.signal).then(setData)
      .catch(e => { if (!controller.signal.aborted) setError(e.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [q, category, page, retry]);
  return <main className="news-main">
    <section className="news-hero"><div className="news-kicker">THE BIG PICTURE</div><h1>흩어진 뉴스,<br /><em>하나의 맥락으로.</em></h1><p>같은 사건을 다룬 뉴스를 한곳에 모았습니다.<br />이슈의 흐름을 살펴보고, 여러 언론사의 시선을 비교해 보세요.</p><div className="hero-orbit" aria-hidden="true"><span>n.</span><i /><i /><i /></div></section>
    <section className="news-discovery" aria-label="이슈 검색"><div><span className="news-kicker">EXPLORE THE STORIES</span><h2>지금 읽어볼 이슈</h2></div><form className="news-search" onSubmit={e => { e.preventDefault(); setQ(search.trim()); setPage(0); }}><label className="news-sr-only" htmlFor="news-query">이슈 검색</label><input id="news-query" placeholder="궁금한 이슈를 검색하세요" value={search} onChange={e => setSearch(e.target.value)} maxLength={200} /><button type="submit">검색 ↗</button></form></section>
    <div className="news-filters" aria-label="카테고리"><button aria-pressed={!category} className={!category ? "selected" : ""} onClick={() => { setCategory(""); setPage(0); }}>전체</button>{categories.map(c => <button key={c} aria-pressed={category === c} className={category === c ? "selected" : ""} onClick={() => { setCategory(c); setPage(0); }}>{categoryName(c)}</button>)}</div>
    <div className="news-results" aria-live="polite">{loading ? "이슈를 불러오고 있습니다…" : data ? `${q ? `“${q}” 검색 결과 · ` : ""}${data.totalElements.toLocaleString()}개의 이슈` : ""}</div>
    {error && <div className="news-state" role="alert"><h2>잠시 연결이 끊겼습니다.</h2><p>{error}</p><button onClick={() => setRetry(r => r + 1)}>다시 시도</button></div>}
    {loading && <div className="news-grid" aria-hidden="true">{[0,1,2].map(n => <div className="news-skeleton" key={n} />)}</div>}
    {!loading && data && data.items.length === 0 && <div className="news-state"><h2>{q || category ? "검색 결과가 없습니다." : "새로운 이슈를 기다리고 있습니다."}</h2><p>{q || category ? "다른 검색어나 카테고리로 찾아보세요." : "공개할 수 있는 이슈가 준비되면 여기에 표시됩니다."}</p>{(q || category) && <button onClick={() => { setQ(""); setSearch(""); setCategory(""); setPage(0); }}>전체 이슈 보기</button>}</div>}
    <div className="news-grid">{data?.items.map((issue, index) => <Link key={issue.id} href={`/news/${issue.id}`} className={`news-card ${index === 0 && page === 0 && !q ? "featured" : ""}`}><div className="news-card-top"><span className="news-category">{categoryName(issue.category)}</span><span>{newsDate(issue.updatedAt)}</span></div><h2>{issue.title}</h2><p>{issue.summary || "관련 기사를 함께 읽으며 이슈의 맥락을 살펴보세요."}</p><div className="news-card-bottom"><span>관련 기사 <strong>{issue.articleCount}</strong> · 언론사 <strong>{issue.publisherCount}</strong></span><span className="news-arrow">↗</span></div></Link>)}</div>
    {data && data.totalPages > 1 && <div className="news-pagination"><button disabled={page === 0 || loading} onClick={() => { setPage(p => p - 1); }}>← 이전</button><span>{page + 1} / {data.totalPages}</span><button disabled={page + 1 >= data.totalPages || loading} onClick={() => { setPage(p => p + 1); }}>다음 →</button></div>}
    <aside className="news-about"><span className="news-kicker">WHY NEWSROOM</span><h2>한 사건을, 여러 시선으로.</h2><p>기사 하나만으로는 보이지 않던 맥락.<br />관련 뉴스를 함께 읽고 직접 판단하세요.</p><span aria-hidden="true">↗</span></aside>
  </main>;
}
