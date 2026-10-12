"use client";
import { useCallback, useEffect, useState } from "react";
import { api, qs, type AiUsage } from "@/lib/api";
import { Badge, ErrorText, PageHeading, Shell } from "@/components/ui";

const PERIODS = [1, 7, 30];
const num = (n: number) => n.toLocaleString("ko-KR");

/** An unknown cost is shown as "단가 미설정", never as 0. */
function Cost({ value, unpriced }: { value: number | null; unpriced: number }) {
  if (value === null) return <span className="muted">단가 미설정</span>;
  return <>${value.toFixed(6)}{unpriced > 0 && <div className="muted">단가 미설정 {num(unpriced)}건 제외</div>}</>;
}

export default function AiUsagePage() {
  const [days, setDays] = useState(7);
  const [data, setData] = useState<AiUsage | null>(null);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async () => {
    try {
      setError(null);
      setData(await api<AiUsage>("/api/admin/ai-usage" + qs({ days })));
    } catch (e) { setError(e); }
  }, [days]);
  useEffect(() => { load(); }, [load]);

  const totals = data?.totals;
  return (
    <Shell>
      <PageHeading eyebrow="AI USAGE" title="AI 사용량" description="호출 수·실패·토큰·비용을 일자와 모델별로 확인합니다. 한도나 차단 기능은 없습니다." />
      <div className="row">
        <select aria-label="기간" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {PERIODS.map((d) => <option key={d} value={d}>{d === 1 ? "오늘" : `최근 ${d}일`}</option>)}
        </select>
        <button onClick={load}>새로고침</button>
        <span className="muted">일자는 한국 시간 기준 · 비용은 설정한 단가로 계산한 추정치</span>
      </div>
      <ErrorText error={error} />
      {totals && (
        <div className="stats-grid">
          <div className="stat-card"><div className="stat-top">호출</div><div className="stat-value">{num(totals.calls)}<small>건</small></div><p>실패 {num(totals.failures)}건</p></div>
          <div className="stat-card"><div className="stat-top">입력 토큰</div><div className="stat-value">{num(totals.inputTokens)}</div><p>토큰을 보고하지 않는 호출은 0</p></div>
          <div className="stat-card"><div className="stat-top">출력 토큰</div><div className="stat-value">{num(totals.outputTokens)}</div><p>임베딩은 출력 토큰 없음</p></div>
          <div className="stat-card"><div className="stat-top">추정 비용 (USD)</div>
            <div className="stat-value">{totals.costUsd === null ? <small>단가 미설정</small> : `$${totals.costUsd.toFixed(6)}`}</div>
            <p>{totals.unpricedCalls > 0 ? `단가 미설정 ${num(totals.unpricedCalls)}건 제외` : "app.ai.pricing 기준"}</p></div>
        </div>
      )}
      <table>
        <thead><tr><th>일자</th><th>호출 유형</th><th>공급자</th><th>모델</th><th>호출</th><th>실패</th><th>입력 토큰</th><th>출력 토큰</th><th>추정 비용 (USD)</th></tr></thead>
        <tbody>
          {data?.rows.map((r) => (
            <tr key={`${r.day}/${r.callType}/${r.provider}/${r.model}`}>
              <td>{r.day}</td>
              <td>{r.callType}</td>
              <td><Badge value={r.provider} /></td>
              <td>{r.model ?? <span className="muted">-</span>}</td>
              <td>{num(r.calls)}</td>
              <td>{r.failures > 0 ? <strong>{num(r.failures)}</strong> : 0}</td>
              <td>{num(r.inputTokens)}</td>
              <td>{num(r.outputTokens)}</td>
              <td><Cost value={r.costUsd} unpriced={r.unpricedCalls} /></td>
            </tr>
          ))}
          {data && data.rows.length === 0 && <tr><td colSpan={9} className="muted">이 기간에 기록된 AI 호출이 없습니다.</td></tr>}
        </tbody>
      </table>
    </Shell>
  );
}
