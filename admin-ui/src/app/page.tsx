"use client";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, fmt, hasCredentials, type Dashboard, type IssueListItem, type ArticleListItem, type NewsSource, type Page, type Job } from "@/lib/api";
import { Badge, ErrorText, PageHeading, Shell } from "@/components/ui";

const steps = [
  { type: "COLLECT_NEWS", label: "뉴스 수집", detail: "RSS 소스에서 새 기사 수집", icon: "◎" },
  { type: "ANALYZE_ARTICLE", label: "기사 분석", detail: "요약 · 핵심 사실 · 개체 추출", icon: "✳" },
  { type: "EMBED_ARTICLE", label: "임베딩", detail: "기사의 의미를 벡터로 저장", icon: "▦" },
  { type: "CLASSIFY_ARTICLE", label: "이슈 분류", detail: "사건 연결과 검수 대기 분류", icon: "▤" },
];

export default function Home() {
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [issues, setIssues] = useState<IssueListItem[]>([]);
  const [articles, setArticles] = useState<ArticleListItem[]>([]);
  const [sources, setSources] = useState<NewsSource[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [collecting, setCollecting] = useState(false);
  const inFlight = useRef(false);
  const load = useCallback(async () => {
    if (!hasCredentials() || inFlight.current) return;
    inFlight.current = true; setRefreshing(true);
    try {
      const [overview, issuePage, articlePage, sourceList] = await Promise.all([
        api<Dashboard>("/api/admin/dashboard"), api<Page<IssueListItem>>("/api/admin/issues?size=4"),
        api<Page<ArticleListItem>>("/api/admin/articles?size=5"), api<NewsSource[]>("/api/admin/sources"),
      ]);
      setDashboard(overview); setIssues(issuePage.items); setArticles(articlePage.items); setSources(sourceList); setError(null);
    } catch (e) { setError(e); }
    finally { inFlight.current = false; setRefreshing(false); }
  }, []);
  useEffect(() => { load(); const timer = setInterval(load, 10000); return () => clearInterval(timer); }, [load]);

  async function collect() {
    setCollecting(true); setNotice(""); setError(null);
    const enabled = sources.filter(s => s.enabled && s.status !== "DISABLED");
    const results = await Promise.allSettled(enabled.map(s => api<Job>(`/api/admin/sources/${s.id}/collect`, { method: "POST" })));
    const success = results.filter(r => r.status === "fulfilled").length;
    const failure = results.find(r => r.status === "rejected");
    if (success) setNotice(`${success}개 소스의 수집 작업을 등록했습니다. 작업 화면에서 진행 상태를 확인하세요.`);
    if (failure?.status === "rejected") setError(failure.reason);
    setCollecting(false); await load();
  }
  const stats = [
    { label: "수집된 기사", value: dashboard?.counts.articles, unit: "건", text: "저장된 전체 뉴스 기사", icon: "▥", color: "teal" },
    { label: "생성된 이슈", value: dashboard?.counts.issues, unit: "개", text: "기사에서 발견한 사건", icon: "▤", color: "blue" },
    { label: "검수 대기", value: dashboard?.counts.reviewIssues, unit: "건", text: "관리자의 판단이 필요한 이슈", icon: "◷", color: "orange" },
    { label: "완료된 작업", value: dashboard?.counts.successfulJobs, unit: "건", text: dashboard ? `진행 중 ${dashboard.counts.waitingJobs}건 · 실패 ${dashboard.counts.failedJobs}건` : "작업 상태 불러오는 중", icon: "✓", color: "violet" },
  ];
  return (
    <Shell>
      <PageHeading eyebrow="EDITORIAL OVERVIEW" title="뉴스의 흐름을 한눈에." description="수집된 뉴스에서 사건을 발견하고, 다음 검수를 시작하세요.">
        <button onClick={load} disabled={refreshing}>{refreshing ? "갱신 중…" : "↻ 새로고침"}</button>
        <button className="primary" onClick={collect} disabled={collecting || !sources.some(s => s.enabled && s.status !== "DISABLED")}>{collecting ? "등록 중…" : "+ 뉴스 수집"}</button>
      </PageHeading>
      <ErrorText error={error} />
      {notice && <div className="notice">{notice} <Link href="/jobs">작업 보기 →</Link></div>}
      {dashboard && dashboard.system.aiProvider !== "openai" && <div className="environment-banner"><span className="environment-icon">i</span><div><strong>{dashboard.system.aiProvider === "stub" ? "개발 모드 · 테스트 AI 사용 중" : "AI 공급자 연결 필요"}</strong><span>{dashboard.system.aiProvider === "stub" ? "실제 RSS 기사를 수집하고 있습니다. 요약과 분류는 테스트용 AI 결과이며, 실제 모델 호출은 아직 검증되지 않았습니다." : "뉴스 수집은 가능하지만, 분석과 임베딩에는 AI 공급자 설정이 필요합니다."}</span></div><span className="mode-label">{dashboard.system.aiProvider.toUpperCase()}</span></div>}
      <section className="stats-grid" aria-label="전체 현황">
        {stats.map(stat => <div className="stat-card" key={stat.label}><div className="stat-top"><span>{stat.label}</span><span className={`stat-icon ${stat.color}`} aria-hidden="true">{stat.icon}</span></div><div className="stat-value">{stat.value === undefined ? "—" : stat.value.toLocaleString()}<small>{stat.unit}</small></div><p>{stat.text}</p></div>)}
      </section>
      <section className="panel pipeline-panel"><div className="section-heading"><div><h2>수집부터 분류까지</h2><p>전체 작업의 누적 처리 현황</p></div><Link href="/jobs">모든 작업 보기 <span>↗</span></Link></div>
        <div className="pipeline-grid">{steps.map((step, index) => {
          const stage = dashboard?.pipeline.find(s => s.jobType === step.type);
          return <Link href="/jobs" className="pipeline-step" key={step.type}><div className="step-top"><span className="step-icon">{step.icon}</span><span className="step-number">0{index + 1}</span></div><h3>{step.label}</h3><p>{step.detail}</p><div className="step-progress"><span style={{ width: `${stage?.total ? stage.success / stage.total * 100 : 0}%` }} /></div><div className="step-footer"><strong>{dashboard ? stage?.success ?? 0 : "—"}<span>건 완료</span></strong><span>{dashboard ? stage?.total ?? 0 : "—"}건 중</span></div>{!!stage?.failed && <span className="stage-error">실패 {stage.failed}건</span>}</Link>;
        })}</div>
      </section>
      <div className="dashboard-grid">
        <div className="dashboard-primary"><section className="panel"><div className="section-heading"><div><h2>최근 이슈</h2><p>새롭게 연결된 사건과 주요 맥락</p></div><Link href="/issues">전체 보기 ↗</Link></div>
          <div className="issue-feed">{issues.map((issue, index) => <Link href={`/issues/${issue.id}`} className="issue-feed-item" key={issue.id}><span className="issue-order">{String(index + 1).padStart(2, "0")}</span><div className="issue-feed-copy"><div className="issue-meta"><span>ISSUE #{issue.id}</span><Badge value={issue.status} /></div><h3>{issue.title}</h3><p>{issue.summary || "기사 상세에서 사건의 맥락을 확인하세요."}</p><div className="issue-footer"><span>기사 {issue.articleCount}건</span><span>언론사 {issue.publisherCount}곳</span><span>{fmt(issue.lastUpdatedAt)}</span></div></div><span className="feed-arrow">↗</span></Link>)}
          {!issues.length && <p className="empty-state">{dashboard ? "아직 이슈가 없습니다. 뉴스 수집을 시작해 보세요." : "이슈를 불러오는 중입니다…"}</p>}</div>
        </section>
        <section className="panel"><div className="section-heading"><div><h2>방금 들어온 뉴스</h2><p>RSS에서 수집한 최신 기사</p></div><Link href="/articles">기사 보기 ↗</Link></div>
          <div className="article-feed">{articles.map(article => <Link href={`/articles/${article.id}`} className="article-feed-item" key={article.id}><span className={`publisher-mark ${article.publisherName === "BBC News" ? "bbc" : ""}`}>{article.publisherName === "BBC News" ? "B" : "연"}</span><div><span className="article-meta">{article.publisherName} <span>· {fmt(article.collectedAt)}</span></span><h3>{article.title}</h3></div><span className="feed-arrow">↗</span></Link>)}{!articles.length && <p className="empty-state">{dashboard ? "등록된 기사가 없습니다." : "기사를 불러오는 중입니다…"}</p>}</div>
        </section></div>
        <aside className="dashboard-secondary"><section className="review-card"><div className="eyebrow">NEXT IN YOUR QUEUE</div><span className="review-symbol">◷</span><h2>다음 판단을 기다립니다.</h2><p>자동 분류가 확신하지 못한 사건은<br />관리자의 검수를 거칩니다.</p><div className="review-count">{dashboard?.counts.reviewIssues ?? "—"}<span>건의 이슈가 검수 대기 중</span></div><Link href="/issues?status=REVIEW" className="review-link">검수 대기 목록 열기 <span>→</span></Link></section>
          <section className="panel"><div className="section-heading compact"><h2>연결된 뉴스 소스</h2><Link href="/sources" aria-label="뉴스 소스 관리">↗</Link></div>{sources.map(source => <div className="source-summary" key={source.id}><span className={`source-dot ${source.lastError || !source.enabled || source.status === "DISABLED" ? "inactive" : ""}`} /><div><strong>{source.name === "yonhap-latest" ? "연합뉴스" : source.name === "bbc-world" ? "BBC World" : source.name}</strong><small>{source.lastSuccessAt ? `최근 수집 ${fmt(source.lastSuccessAt)}` : "수집 기록 없음"}</small></div><span className="source-type">{source.type}</span></div>)}{!sources.length && <p className="empty-state">등록된 소스가 없습니다.</p>}</section>
          <section className="panel system-panel"><div className="section-heading compact"><h2>시스템 연결</h2><span className={`connection-tag ${dashboard && !error ? "" : "unknown"}`}>{dashboard && !error ? "DB 연결됨" : "확인 중"}</span></div><dl><div><dt>데이터베이스</dt><dd>{dashboard?.system.database ?? "—"}</dd></div><div><dt>PostgreSQL</dt><dd>{dashboard?.system.postgresVersion ?? "—"}</dd></div><div><dt>DB 포트</dt><dd>{dashboard?.system.port ?? "—"}</dd></div><div><dt>pgvector</dt><dd>{dashboard?.system.vectorVersion ?? "—"}</dd></div><div><dt>적용된 스키마</dt><dd>{dashboard?.system.schemaVersion ? `V${dashboard.system.schemaVersion}` : "—"}</dd></div><div><dt>AI 공급자</dt><dd>{dashboard?.system.aiProvider ?? "—"}</dd></div><div><dt>자동 수집</dt><dd>{dashboard ? dashboard.system.schedulerEnabled ? "켜짐" : "수동 수집" : "—"}</dd></div></dl><div className="system-footnote">관리자 변경 기록 <strong>{dashboard?.counts.auditEvents ?? "—"}건</strong></div></section>
        </aside>
      </div>
      <footer className="dashboard-footer"><span>NEWSROOM / PHASE 1</span><span>{dashboard ? `마지막 확인 ${fmt(dashboard.checkedAt)} · 10초마다 갱신` : "데이터를 확인하고 있습니다."}</span></footer>
    </Shell>
  );
}
