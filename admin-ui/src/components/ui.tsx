"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { ApiFailure, clearCredentials, hasCredentials } from "@/lib/api";

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

/** Redirects to /login when no credentials are present, and renders the navigation. */
export function Shell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const path = usePathname();
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!hasCredentials()) router.replace("/login");
    else setReady(true);
  }, [router]);
  if (!ready) return null;
  const link = (href: string, label: string) => (
    <Link href={href} className={path.startsWith(href) ? "active" : ""}>{label}</Link>
  );
  return (
    <>
      <nav>
        <strong>AI 콘텐츠 플랫폼 · 관리자</strong>
        {link("/issues", "이슈")}
        {link("/articles", "기사")}
        {link("/jobs", "작업")}
        <span className="spacer" />
        <button onClick={() => { clearCredentials(); router.replace("/login"); }}>로그아웃</button>
      </nav>
      <main>{children}</main>
    </>
  );
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
