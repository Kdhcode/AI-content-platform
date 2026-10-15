"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { ApiFailure, clearCredentials, hasCredentials, localVerifyMode, type LocalVerifyMode } from "@/lib/api";

const BAD = new Set(["FAILED", "EXCLUDED"]);
const WARN = new Set(["REVIEW", "PENDING", "RUNNING", "DEGRADED", "ANALYSIS_PENDING", "NOT_CLASSIFIED"]);
const OK = new Set(["ACTIVE", "SUCCESS", "CLASSIFIED", "ANALYZED"]);

export function Badge({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">-</span>;
  const cls = BAD.has(value) ? "bad" : WARN.has(value) ? "warn" : OK.has(value) ? "ok" : "";
  return <span className={`badge ${cls}`}>{value}</span>;
}

export function ErrorText({ error }: { error: unknown }) {
  if (!error) return null;
  const msg = error instanceof ApiFailure ? `[${error.code}] ${error.message}` : String(error);
  return <div className="error">{msg}</div>;
}

/** Shown on every screen while the backend runs in local-verify mode, with the data set it is looking at. */
export function LocalVerifyBanner({ audience }: { audience: "admin" | "public" }) {
  const [mode, setMode] = useState<LocalVerifyMode | null>(null);
  useEffect(() => { localVerifyMode().then(setMode); }, []);
  if (!mode?.loginBypass) return null;
  return (
    <div className="local-verify-banner" role="status">
      <strong>로컬 검증 모드</strong>
      <span>{audience === "admin" ? "로그인 생략 · 이 PC(127.0.0.1)에서만 접속" : "검증용 화면 · 실제 공개 서비스 아님"}</span>
      <span className="local-verify-data">데이터: {mode.dataLabel || "표시 없음"}</span>
      <span>AI: {mode.aiProvider === "stub" ? "stub(유료 호출 없음)" : "꺼짐(유료 호출 없음)"}</span>
    </div>
  );
}

/** Redirects to /login when no credentials are present (unless local-verify mode is on), and renders the navigation. */
export function Shell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const path = usePathname();
  const [ready, setReady] = useState(false);
  const [bypass, setBypass] = useState(false);
  useEffect(() => {
    let live = true;
    localVerifyMode().then((m) => {
      if (!live) return;
      setBypass(m.loginBypass);
      if (hasCredentials()) setReady(true);
      else router.replace("/login");
    });
    return () => { live = false; };
  }, [router]);
  if (!ready) return null;
  const link = (href: string, label: string, icon: string) => (
    <Link href={href} className={(href === "/" ? path === "/" : path.startsWith(href)) ? "active" : ""}>
      <span className="nav-icon" aria-hidden="true">{icon}</span>{label}
    </Link>
  );
  return (
    <div className="workspace">
      <aside className="sidebar">
        <Link href="/" className="brand"><span className="brand-mark">n<span>.</span></span>
          <span>NEWSROOM<small>AI CONTENT PLATFORM</small></span></Link>
        <div className="nav-label">WORKSPACE</div>
        <nav aria-label="관리자 메뉴">
          {link("/", "대시보드", "◫")}
          {link("/issues", "이슈", "▤")}
          {link("/articles", "기사", "▥")}
          {link("/sources", "뉴스 소스", "◎")}
          {link("/jobs", "작업", "⇄")}
          {link("/ai-usage", "AI 사용량", "◔")}
          <Link href="/news"><span className="nav-icon" aria-hidden="true">↗</span>일반 사용자 화면</Link>
        </nav>
        <div className="sidebar-note"><span className="status-dot" /> Phase 1 · 뉴스 이슈 엔진
          <p>수집에서 검수까지,<br />뉴스의 흐름을 한곳에서.</p></div>
        <div className="sidebar-account"><span className="avatar">A</span><div>{bypass ? "local-verify" : "관리자"}<small>{bypass ? "로그인 생략(로컬 검증)" : "Editorial workspace"}</small></div>
          {!bypass && <button aria-label="로그아웃" title="로그아웃" onClick={() => { clearCredentials(); router.replace("/login"); }}>↗</button>}</div>
      </aside>
      <div className="workspace-content">
        <LocalVerifyBanner audience="admin" />
        <header className="topbar"><span>워크스페이스 <span className="crumb-slash">/</span> <strong>{path === "/" ? "대시보드" : path.startsWith("/sources") ? "뉴스 소스" : path.startsWith("/articles") ? "기사 관리" : path.startsWith("/jobs") ? "작업 관리" : path.startsWith("/ai-usage") ? "AI 사용량" : "이슈 관리"}</strong></span>
          <span className="topbar-tag">NEWS INTELLIGENCE <span className="status-dot" /></span></header>
        <main className="workspace-main">{children}</main>
      </div>
    </div>
  );
}

export function PageHeading({ eyebrow, title, description, children }: { eyebrow: string; title: string; description: string; children?: ReactNode }) {
  return <div className="page-heading"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1><p>{description}</p></div><div className="heading-actions">{children}</div></div>;
}

export function Pager({ page, totalPages, total, onPage }: { page: number; totalPages: number; total: number; onPage: (p: number) => void }) {
  return (
    <div className="pager">
      <button disabled={page <= 0} onClick={() => onPage(page - 1)}>이전</button>
      <span>{totalPages === 0 ? 0 : page + 1} / {totalPages} 페이지 · 총 {total}건</span>
      <button disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>다음</button>
    </div>
  );
}
