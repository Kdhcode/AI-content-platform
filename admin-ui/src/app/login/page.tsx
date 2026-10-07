"use client";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, clearCredentials, setCredentials } from "@/lib/api";
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
      router.replace("/issues");
    } catch (err) {
      clearCredentials();
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main style={{ maxWidth: 380 }}>
      <h1>관리자 로그인</h1>
      <form className="card" onSubmit={submit}>
        <div className="row"><input type="text" placeholder="아이디" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" /></div>
        <div className="row"><input type="password" placeholder="비밀번호" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></div>
        <button className="primary" disabled={busy || !username || !password}>로그인</button>
        <ErrorText error={error} />
      </form>
    </main>
  );
}
