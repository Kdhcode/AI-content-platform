"use client";
import { useState } from "react";
import { api, qs, type ContentChangeResult, type ContentDetail, type ContentLink } from "@/lib/api";
import { LIMITS } from "@/lib/contentBody";
import { Counter, safeHref } from "./common";
import type { Change } from "./SourcesPanel";

const asContent = (r: ContentChangeResult) => r.content;
const TYPES = { PRODUCT: "상품", OFFICIAL: "공식", MAP: "지도", RESERVATION: "예약", PURCHASE: "구매", REFERENCE: "참고", OTHER: "기타" } as const;
const POSITIONS = { BODY_TOP: "본문 위", BODY_BOTTOM: "본문 아래", SOURCES: "출처 영역" } as const;
type Form = { linkType: string; title: string; url: string; siteVisible: boolean; blogVisible: boolean; displayPosition: string };
const EMPTY: Form = { linkType: "REFERENCE", title: "", url: "", siteVisible: true, blogVisible: false, displayPosition: "BODY_BOTTOM" };
const formOf = (l: ContentLink): Form => ({ linkType: l.linkType, title: l.title, url: l.url, siteVisible: l.siteVisible,
  blogVisible: l.blogVisible, displayPosition: l.displayPosition });

function LinkFields({ form, setForm }: { form: Form; setForm: (f: Form) => void }) {
  return (
    <div className="row">
      <select aria-label="링크 종류" value={form.linkType} onChange={(e) => setForm({ ...form, linkType: e.target.value })}>
        {Object.entries(TYPES).map(([k, v]) => <option key={k} value={k}>{v}({k})</option>)}
      </select>
      <input type="text" aria-label="링크 제목" placeholder="제목(선택)" maxLength={LIMITS.linkTitleChars} value={form.title}
        onChange={(e) => setForm({ ...form, title: e.target.value })} />
      <input type="text" aria-label="링크 URL" className="grow" placeholder="https://…" maxLength={LIMITS.urlChars} value={form.url}
        onChange={(e) => setForm({ ...form, url: e.target.value })} />
      <select aria-label="표시 위치" value={form.displayPosition} onChange={(e) => setForm({ ...form, displayPosition: e.target.value })}>
        {Object.entries(POSITIONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
      </select>
      <label className="check"><input type="checkbox" checked={form.siteVisible} onChange={(e) => setForm({ ...form, siteVisible: e.target.checked })} /> 사이트 노출</label>
      <label className="check"><input type="checkbox" checked={form.blogVisible} onChange={(e) => setForm({ ...form, blogVisible: e.target.checked })} /> 블로그 노출</label>
    </div>
  );
}

export function LinksPanel({ content, change, disabled }: { content: ContentDetail; change: Change; disabled: boolean }) {
  const id = content.id;
  const links = content.links;
  const [form, setForm] = useState<Form>(EMPTY);
  const [editing, setEditing] = useState<{ id: number; form: Form } | null>(null);
  const full = links.length >= LIMITS.links;

  /** Reorder sends the full id list in the new order. */
  function reorder(from: number, to: number) {
    if (to < 0 || to >= links.length) return;
    const ids = links.map((l) => l.id);
    const [x] = ids.splice(from, 1);
    ids.splice(to, 0, x);
    change("관련 URL 순서를 바꿨습니다.", (version) => api<ContentChangeResult>(`/api/admin/contents/${id}/links/order`,
      { method: "PUT", body: { version, linkIds: ids } }), asContent);
  }

  return (
    <div className="card">
      <div className="row spread"><h2 style={{ margin: 0 }}>관련 URL</h2><span className="muted">링크 <Counter value={links.length} max={LIMITS.links} /></span></div>
      <p className="notice">노출 설정(사이트·블로그·표시 위치)은 저장만 됩니다. 지금은 승인돼도 공개 화면·블로그 어디에도 게시되지 않습니다.</p>
      <table>
        <thead><tr><th>순서</th><th>종류</th><th>제목 · URL</th><th>위치</th><th>노출</th><th></th></tr></thead>
        <tbody>
          {links.map((l, i) => editing?.id === l.id ? (
            <tr key={l.id}><td colSpan={6}>
              <LinkFields form={editing.form} setForm={(f) => setEditing({ id: l.id, form: f })} />
              <div className="row tight">
                <button type="button" className="primary" disabled={disabled || !editing.form.url.trim()} onClick={async () => {
                  const ok = await change("관련 URL을 수정했습니다.", (version) => api<ContentChangeResult>(`/api/admin/contents/${id}/links/${l.id}`,
                    { method: "PATCH", body: { version, ...editing.form, url: editing.form.url.trim() } }), asContent);
                  if (ok) setEditing(null);
                }}>저장</button>
                <button type="button" onClick={() => setEditing(null)}>취소</button>
              </div>
            </td></tr>
          ) : (
            <tr key={l.id}>
              <td><div className="row tight">{i + 1}
                <button type="button" aria-label={`링크 ${i + 1} 위로`} disabled={disabled || i === 0} onClick={() => reorder(i, i - 1)}>↑</button>
                <button type="button" aria-label={`링크 ${i + 1} 아래로`} disabled={disabled || i === links.length - 1} onClick={() => reorder(i, i + 1)}>↓</button></div></td>
              <td>{TYPES[l.linkType as keyof typeof TYPES] ?? l.linkType}</td>
              <td>{l.title || <span className="muted">(제목 없음)</span>}
                <div>{safeHref(l.url) ? <a href={safeHref(l.url)} target="_blank" rel="noopener noreferrer nofollow" className="url">{l.url}</a> : <span className="url">{l.url}</span>}</div></td>
              <td>{POSITIONS[l.displayPosition] ?? l.displayPosition}</td>
              <td>{l.siteVisible ? "사이트" : ""}{l.siteVisible && l.blogVisible ? " · " : ""}{l.blogVisible ? "블로그" : ""}{!l.siteVisible && !l.blogVisible && <span className="muted">없음</span>}</td>
              <td><div className="row tight">
                <button type="button" disabled={disabled} onClick={() => setEditing({ id: l.id, form: formOf(l) })}>수정</button>
                <button type="button" className="danger" disabled={disabled} onClick={() => {
                  if (!window.confirm("이 관련 URL을 삭제할까요?")) return;
                  change("관련 URL을 삭제했습니다.", (version) => api<ContentChangeResult>(`/api/admin/contents/${id}/links/${l.id}${qs({ version })}`,
                    { method: "DELETE" }), asContent);
                }}>삭제</button></div></td>
            </tr>
          ))}
          {links.length === 0 && <tr><td colSpan={6} className="muted">관련 URL이 없습니다.</td></tr>}
        </tbody>
      </table>
      <fieldset className="section-block" disabled={disabled || full}>
        <legend>관련 URL 추가{full && ` — 한도 ${LIMITS.links}개에 도달`}</legend>
        <LinkFields form={form} setForm={setForm} />
        <button type="button" className="primary" disabled={!form.url.trim()} onClick={async () => {
          const ok = await change("관련 URL을 추가했습니다.", (version) => api<ContentChangeResult>(`/api/admin/contents/${id}/links`,
            { method: "POST", body: { version, ...form, url: form.url.trim() } }), asContent);
          if (ok) setForm(EMPTY);
        }}>추가</button>
      </fieldset>
    </div>
  );
}
