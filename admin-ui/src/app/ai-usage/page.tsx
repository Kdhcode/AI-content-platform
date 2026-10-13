"use client";
import { useCallback, useEffect, useState } from "react";
import { api, qs, type AiLimits, type AiUsage } from "@/lib/api";
import { Badge, ErrorText, PageHeading, Shell } from "@/components/ui";

const PERIODS = [1, 7, 30];
const num = (n: number) => n.toLocaleString("ko-KR");

/** An unknown cost is shown as "단가 미설정", never as 0. */
function Cost({ value, unpriced }: { value: number | null; unpriced: number }) {
  if (value === null) return <span className="muted">단가 미설정</span>;
  return <>${value.toFixed(6)}{unpriced > 0 && <div className="muted">단가 미설정 {num(unpriced)}건 제외</div>}</>;
}

const BLOCKED: Record<string, string> = {
  AI_DAILY_BUDGET_REACHED: "오늘 예산에 도달해 AI 작업이 내일까지 대기합니다.",
  AI_DAILY_CALL_LIMIT_REACHED: "오늘 호출 한도에 도달해 AI 작업이 내일까지 대기합니다.",
  AI_RATE_LIMITED: "분당 호출 한도에 도달해 AI 작업이 잠시 대기합니다.",
};

/** Configured limits (app.ai.limits) and today's usage. Uses its own classes so the totals cards stay as they are. */
function Limits({ limits }: { limits: AiLimits }) {
  const off = limits.dailyBudgetUsd === null && limits.dailyMaxCalls === null && limits.maxCallsPerMinute === null;
  if (off) {
    return <div className="card"><strong>호출 한도</strong> <span className="muted">설정된 한도 없음 (app.ai.limits)</span></div>;
  }
  const item = (label: string, used: string, limit: string | null) => (
    <span>{label} <strong>{used}</strong>{limit === null ? <span className="muted"> / 한도 없음</span> : ` / ${limit}`}</span>
  );
  return (
    <div className="card">
      <div className="row">
        <strong>호출 한도</strong>
        {item("오늘 비용", `$${limits.costToday.toFixed(6)}`, limits.dailyBudgetUsd === null ? null : `$${limits.dailyBudgetUsd}`)}
        {item("오늘 호출", num(limits.callsToday), limits.dailyMaxCalls === null ? null : num(limits.dailyMaxCalls))}
        {item("최근 1분", num(limits.callsLastMinute), limits.maxCallsPerMinute === null ? null : num(limits.maxCallsPerMinute))}
      </div>
      {limits.blockedBy
        ? <div className="error">{BLOCKED[limits.blockedBy] ?? limits.blockedBy} 작업은 실패로 바뀌지 않습니다.</div>
        : <div className="muted">한도에 도달하면 AI 작업은 실패하지 않고 대기합니다. 하루 기준: {limits.timeZone}. 단가가 없는 호출은 예산에 0으로 계산됩니다.</div>}
    </div>
  );
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
      <PageHeading eyebrow="AI USAGE" title="AI 사용량" description="호출 수·실패·토큰·비용을 일자와 모델별로 확인합니다. 호출 한도는 설정(app.ai.limits)으로만 바꿀 수 있습니다." />
      <div className="row">
        <select aria-label="기간" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {PERIODS.map((d) => <option key={d} value={d}>{d === 1 ? "오늘" : `최근 ${d}일`}</option>)}
        </select>
        <button onClick={load}>새로고침</button>
        <span className="muted">일자는 한국 시간 기준 · 비용은 설정한 단가로 계산한 추정치</span>
      </div>
      <ErrorText error={error} />
      {data?.limits && <Limits limits={data.limits} />}
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
