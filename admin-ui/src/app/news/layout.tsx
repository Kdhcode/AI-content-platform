import Link from "next/link";
import type { ReactNode } from "react";
import "./news.css";

export const metadata = { title: "NEWSROOM · 뉴스의 맥락을 읽다", description: "여러 언론사의 뉴스를 이슈별로 모아 읽는 뉴스룸" };
export default function NewsLayout({ children }: { children: ReactNode }) {
  return <div className="public-news"><header className="news-header"><Link href="/news" className="news-brand">NEWSROOM<span>뉴스의 맥락을 읽다</span></Link><div className="news-header-links"><Link href="/news">이슈 탐색</Link><Link href="/login">관리자 로그인 ↗</Link></div></header>{children}<footer className="news-footer"><strong>NEWSROOM</strong><span>다양한 시선, 하나의 맥락.</span><p>뉴스 원문과 저작권은 각 언론사에 있습니다. 요약은 원문과 함께 확인해 주세요.</p></footer></div>;
}
