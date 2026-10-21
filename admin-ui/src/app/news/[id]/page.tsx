"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { publicApi, categoryName, newsDate, safeArticleUrl, type PublicDetail } from "@/lib/public-api";

export default function IssuePage() {
  // Next 15 passes page params as a Promise; read them like the other detail pages
  const params = useParams<{ id: string }>();
  const [data, setData] = useState<PublicDetail | null>(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); setData(null); setError("");
    publicApi<PublicDetail>(`issues/${encodeURIComponent(params.id)}`, controller.signal).then(setData)
      .catch(e => { if (!controller.signal.aborted) setError(e.message); });
    return () => controller.abort();
  }, [params.id, retry]);
  return <main className="news-main news-detail"><Link className="news-back" href="/news">← 전체 이슈</Link>
    {error ? <div className="news-state" role="alert"><h1>{error}</h1><button onClick={() => setRetry(r => r + 1)}>다시 시도</button></div> : !data ? <p role="status">이슈를 불러오고 있습니다…</p> : <>
      <header className="news-detail-heading"><span className="news-category">{categoryName(data.issue.category)}</span><h1>{data.issue.title}</h1><div className="news-detail-meta">{newsDate(data.issue.updatedAt)} 업데이트<span>관련 기사 {data.issue.articleCount} · 언론사 {data.issue.publisherCount}</span></div></header>
      <section className="news-summary"><div className="news-kicker">THE CONTEXT</div><h2>이슈 한눈에 보기</h2><p>{data.issue.summary || "이 이슈의 요약은 아직 준비되지 않았습니다. 아래 관련 기사에서 사건의 흐름을 확인하세요."}</p><small>요약은 참고용입니다. 정확한 내용은 언론사 원문을 확인하세요.</small></section>
      <section className="news-articles"><div className="news-section-heading"><div><span className="news-kicker">DIFFERENT PERSPECTIVES</span><h2>관련 기사 모아보기</h2></div><span>{data.articles.length}개의 기사</span></div>{data.articles.length === 0 && <div className="news-state">아직 공개할 수 있는 관련 기사가 없습니다.</div>}{data.articles.map((article, n) => { const url = safeArticleUrl(article.url); return <article className="news-source-article" key={article.id}><span className="news-article-number">{String(n + 1).padStart(2, "0")}</span><div><div className="news-source-meta">{article.publisherName}<span>{newsDate(article.publishedAt)}</span></div><h3>{url ? <a href={url} target="_blank" rel="noopener noreferrer">{article.title}<span aria-label="새 창에서 원문 열기"> ↗</span></a> : article.title}</h3></div><span className="news-original">원문</span></article>; })}</section>
    </>}
  </main>;
}
