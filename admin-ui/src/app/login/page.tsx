"use client";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, ApiFailure, clearCredentials, setCredentials } from "@/lib/api";
import { ErrorText } from "@/components/ui";

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setCredentials(username, password);
    try {
      await api("/api/admin/issues?size=1"); // cheapest authenticated call: proves the credentials
      router.replace("/");
    } catch (err) {
      clearCredentials();
      setError(err instanceof ApiFailure && err.status === 401
        ? new Error("아이디 또는 비밀번호를 확인해 주세요. 기존 관리자 계정의 비밀번호로 로그인해야 합니다.")
        : err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen"><section className="login-story"><div className="login-wordmark">NEWSROOM<span>AI CONTENT PLATFORM</span></div><div className="login-story-copy"><div className="eyebrow">FROM INFORMATION TO INSIGHT</div><h1>뉴스를 모으고.<br />맥락을 연결하고.<br /><span>판단을 더하다.</span></h1><p>뉴스 수집부터 이슈 검수까지.<br />편집 작업의 모든 흐름을 한곳에서 관리하세요.</p></div><div className="login-story-footer">PHASE 1 <span>NEWS INTELLIGENCE WORKSPACE</span></div></section>
    <main className="login-main"><div className="eyebrow">WELCOME TO YOUR WORKSPACE</div><h1>관리자 로그인</h1><p className="login-description">계정으로 로그인해 뉴스 워크스페이스를 시작하세요.</p>
      <form className="login-form" onSubmit={submit}>
        <label htmlFor="username">아이디</label>
        <div className="row"><input id="username" type="text" placeholder="아이디" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" /></div>
        <label htmlFor="password">비밀번호</label>
        <div className="row"><input id="password" type="password" placeholder="비밀번호" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></div>
        <button className="primary" disabled={busy || !username || !password}>로그인</button>
        <ErrorText error={error} />
      </form><p className="login-note">허가된 관리자 계정으로 이용할 수 있습니다.</p><p className="login-note"><Link href="/news">일반 사용자 뉴스룸 보기 →</Link></p>
    </main></div>
  );
}
