"use client";
import Link from "next/link";
import { useState } from "react";
import { api, qs, type ArticleListItem, type ContentChangeResult, type ContentDetail, type ContentSource, type IssueListItem, type Page } from "@/lib/api";
import { LIMITS } from "@/lib/contentBody";
import { Badge } from "@/components/ui";
import { safeHref } from "./common";

export type Change = <R>(label: string, op: (version: number) => Promise<R>, apply: (r: R) => ContentDetail) => Promise<boolean>;
const asContent = (r: ContentChangeResult) => r.content;

const KINDS = { ISSUE: "이슈", NEWS_ARTICLE: "수집 기사", EXTERNAL_URL: "외부 URL", MANUAL: "직접 입력(설명)" } as const;
const ROLES = { PRIMARY: "주 출처", SUPPORTING: "보조", REFERENCE: "참고" } as const;
type Kind = keyof typeof KINDS;
type Role = keyof typeof ROLES;

/** Search by title with the existing admin list APIs and pick one. */
function Picker<T extends { id: number; title: string }>({ label, search, describe, selectable, onPick }: {
  label: string; search: (q: string) => Promise<T[]>; describe: (x: T) => string; selectable: (x: T) => boolean; onPick: (x: T) => void;
}) {
  const [q, setQ] = useState("");
  const [items, setItems] = useState<T[] | null>(null);
  const [error, setError] = useState("");
  async function find() {
    setError("");
    try { setItems(await search(q.trim())); } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }
  return (
    <div className="picker">
      <div className="row tight">
        <input type="text" placeholder={`${label} 제목 검색`} value={q} onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && !e.nativeEvent.isComposing) { e.preventDefault(); find(); } }} />
        <button type="button" onClick={find}>검색</button>
      </div>
      {error && <div className="field-error">{error}</div>}
      {items && (items.length === 0 ? <div className="muted">결과가 없습니다.</div> : (
        <ul className="pick-list">
          {items.map((x) => (
            <li key={x.id}><button type="button" className="link-button" disabled={!selectable(x)} onClick={() => onPick(x)}>#{x.id} {x.title}</button>
              <span className="muted"> {describe(x)}</span></li>
          ))}
        </ul>
      ))}
    </div>
  );
}

export function SourcesPanel({ content, change, disabled }: { content: ContentDetail; change: Change; disabled: boolean }) {
  const id = content.id;
  const [kind, setKind] = useState<Kind>("ISSUE");
  const [role, setRole] = useState<Role>("SUPPORTING");
  const [target, setTarget] = useState<{ id: number; title: string } | null>(null);
  const [url, setUrl] = useState("");
  const [label, setLabel] = useState("");
  const [labels, setLabels] = useState<Record<number, string>>({});

  const isOrigin = (s: ContentSource) => s.kind === "ISSUE" && s.issueId === content.originIssue.id;
  const reset = () => { setTarget(null); setUrl(""); setLabel(""); };

  async function add() {
    const body: Record<string, unknown> = { kind, role, label: label.trim() || undefined };
    if (kind === "ISSUE") body.issueId = target?.id;
    if (kind === "NEWS_ARTICLE") body.newsArticleId = target?.id;
    if (kind === "EXTERNAL_URL") body.externalUrl = url.trim();
    const ok = await change("출처를 추가했습니다.", (version) =>
      api<ContentChangeResult>(`/api/admin/contents/${id}/sources`, { method: "POST", body: { version, ...body } }), asContent);
    if (ok) reset();
  }

  const target_ = (s: ContentSource) => {
    if (s.kind === "ISSUE") return <Link href={`/issues/${s.issueId}`}>이슈 #{s.issueId}</Link>;
    if (s.kind === "NEWS_ARTICLE") return <Link href={`/articles/${s.newsArticleId}`}>기사 #{s.newsArticleId}</Link>;
    if (s.kind === "EXTERNAL_URL") {
      const href = safeHref(s.externalUrl);
      return href ? <a href={href} target="_blank" rel="noopener noreferrer nofollow" className="url">{s.externalUrl}</a> : <span className="url">{s.externalUrl}</span>;
    }
    return <span className="muted">(설명만)</span>;
  };

  const ready = kind === "ISSUE" || kind === "NEWS_ARTICLE" ? !!target : kind === "EXTERNAL_URL" ? !!url.trim() : !!label.trim();

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>출처 ({content.sources.length})</h2>
      <p className="muted">원천 이슈 출처는 삭제할 수 없지만 역할은 바꿀 수 있습니다. 검수 요청·승인에는 주 출처(PRIMARY)가 하나 이상 필요합니다.</p>
      <table>
        <thead><tr><th>역할</th><th>종류</th><th>대상</th><th>설명</th><th></th></tr></thead>
        <tbody>
          {content.sources.map((s) => (
            <tr key={s.id}>
              <td><select aria-label={`출처 ${s.id} 역할`} value={s.role} disabled={disabled}
                onChange={(e) => {
                  const role = e.target.value; // read now: the request runs later, after React resets the controlled select
                  change("출처 역할을 바꿨습니다.", (version) => api<ContentChangeResult>(`/api/admin/contents/${id}/sources/${s.id}`,
                    { method: "PATCH", body: { version, role } }), asContent);
                }}>
                {(Object.keys(ROLES) as Role[]).map((r) => <option key={r} value={r}>{ROLES[r]}({r})</option>)}
              </select></td>
              <td>{KINDS[s.kind]}{isOrigin(s) && <div><Badge value="원천 이슈" /></div>}</td>
              <td>{target_(s)}</td>
              <td>
                <div className="row tight">
                  <input type="text" aria-label={`출처 ${s.id} 설명`} maxLength={LIMITS.labelChars} disabled={disabled}
                    value={labels[s.id] ?? s.label ?? ""} onChange={(e) => setLabels({ ...labels, [s.id]: e.target.value })} />
                  {labels[s.id] !== undefined && labels[s.id] !== (s.label ?? "") && (
                    <button type="button" disabled={disabled} onClick={async () => {
                      const ok = await change("출처 설명을 바꿨습니다.", (version) => api<ContentChangeResult>(`/api/admin/contents/${id}/sources/${s.id}`,
                        { method: "PATCH", body: { version, label: labels[s.id] } }), asContent);
                      if (ok) setLabels(({ [s.id]: _, ...rest }) => rest);
                    }}>설명 저장</button>
                  )}
                </div>
              </td>
              <td>{isOrigin(s) ? <span className="muted">삭제 불가</span> : (
                <button type="button" className="danger" disabled={disabled} onClick={() => {
                  if (!window.confirm("이 출처를 삭제할까요?")) return;
                  change("출처를 삭제했습니다.", (version) => api<ContentChangeResult>(`/api/admin/contents/${id}/sources/${s.id}${qs({ version })}`,
                    { method: "DELETE" }), asContent);
                }}>삭제</button>)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <fieldset className="section-block" disabled={disabled}>
        <legend>출처 추가</legend>
        <div className="row">
          <select aria-label="출처 종류" value={kind} onChange={(e) => { setKind(e.target.value as Kind); reset(); }}>
            {(Object.keys(KINDS) as Kind[]).map((k) => <option key={k} value={k}>{KINDS[k]}</option>)}
          </select>
          <select aria-label="새 출처 역할" value={role} onChange={(e) => setRole(e.target.value as Role)}>
            {(Object.keys(ROLES) as Role[]).map((r) => <option key={r} value={r}>{ROLES[r]}({r})</option>)}
          </select>
        </div>
        {kind === "ISSUE" && (target ? <div className="row tight">선택: #{target.id} {target.title} <button type="button" onClick={() => setTarget(null)}>다시 선택</button></div>
          : <Picker<IssueListItem> label="이슈" selectable={(x) => x.status === "ACTIVE" || x.status === "CLOSED"}
              describe={(x) => x.status + (x.status === "ACTIVE" || x.status === "CLOSED" ? "" : " — ACTIVE·CLOSED만 출처로 쓸 수 있음")}
              search={async (q) => (await api<Page<IssueListItem>>("/api/admin/issues" + qs({ q, size: 10 }))).items} onPick={setTarget} />)}
        {kind === "NEWS_ARTICLE" && (target ? <div className="row tight">선택: #{target.id} {target.title} <button type="button" onClick={() => setTarget(null)}>다시 선택</button></div>
          : <Picker<ArticleListItem> label="기사" selectable={() => true} describe={(x) => `${x.publisherName} · ${x.status}`}
              search={async (q) => (await api<Page<ArticleListItem>>("/api/admin/articles" + qs({ q, size: 10 }))).items} onPick={setTarget} />)}
        {kind === "EXTERNAL_URL" && <div className="row"><input type="text" className="grow" placeholder="https://…" maxLength={LIMITS.urlChars}
          value={url} onChange={(e) => setUrl(e.target.value)} /></div>}
        <div className="row">
          <input type="text" className="grow" maxLength={LIMITS.labelChars} value={label} onChange={(e) => setLabel(e.target.value)}
            placeholder={kind === "MANUAL" ? "설명(필수) — 예: 현장 취재 메모" : "설명(선택)"} />
          <button type="button" className="primary" disabled={!ready} onClick={add}>출처 추가</button>
        </div>
      </fieldset>
    </div>
  );
}
