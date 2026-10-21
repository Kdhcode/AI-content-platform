"use client";
import { useState } from "react";
import { api, type ContentDetail } from "@/lib/api";
import { LIMITS } from "@/lib/contentBody";
import { ACTIONS, type ActionName } from "./common";
import type { Change } from "./SourcesPanel";

/**
 * The status buttons the current status allows (A3 table). Approve and restore are shown to everyone but enabled only
 * for SYSTEM_ADMIN; the server checks the role and the status again. Each action asks for its reason first (required
 * ones can't be sent empty), then runs through the session queue, which saves pending edits before it.
 */
export function StatusActions({ content, isAdmin, change, blocked }: {
  content: ContentDetail; isAdmin: boolean; change: Change; blocked: boolean;
}) {
  const [open, setOpen] = useState<ActionName | null>(null);
  const [reason, setReason] = useState("");
  const [sending, setSending] = useState(false);
  const available = ACTIONS.filter((a) => a.from.includes(content.status));
  const current = ACTIONS.find((a) => a.name === open);

  async function send() {
    if (!current || sending) return;
    setSending(true);
    try {
      const ok = await change(`${current.label} 처리했습니다.`, (version) => api<ContentDetail>(`/api/admin/contents/${content.id}/${current.name}`,
        { method: "POST", body: { version, reason: reason.trim() || undefined } }), (d) => d);
      if (ok) { setOpen(null); setReason(""); }
    } finally { setSending(false); }
  }

  return (
    <div className="status-actions">
      <div className="row tight">
        {available.map((a) => (
          <button key={a.name} type="button" className={a.danger ? "danger" : a.name === "submit" || a.name === "approve" ? "primary" : ""}
            disabled={blocked || sending || (a.adminOnly && !isAdmin)} title={a.adminOnly && !isAdmin ? "SYSTEM_ADMIN만 할 수 있습니다" : undefined}
            onClick={() => { setOpen(a.name); setReason(""); }}>{a.label}{a.adminOnly && !isAdmin ? " (SYSTEM_ADMIN)" : ""}</button>
        ))}
        {available.length === 0 && <span className="muted">이 상태에서 할 수 있는 동작이 없습니다.</span>}
      </div>
      {current && (
        <div className="action-form">
          <label className="field-label" htmlFor="action-reason">{current.label} 사유 {current.reasonRequired ? "(필수)" : "(선택)"} — {reason.trim().length}/{LIMITS.reasonChars}</label>
          <textarea id="action-reason" maxLength={LIMITS.reasonChars} value={reason} onChange={(e) => setReason(e.target.value)}
            placeholder={current.reasonRequired ? "사유를 입력하세요" : "필요하면 사유를 남기세요(감사 기록)"} />
          <div className="row tight">
            <button type="button" className={current.danger ? "danger" : "primary"}
              disabled={blocked || sending || (current.reasonRequired && !reason.trim())} onClick={send}>
              {sending ? "처리 중…" : `${current.label} 실행`}</button>
            <button type="button" disabled={sending} onClick={() => setOpen(null)}>취소</button>
            <span className="muted">저장되지 않은 편집이 있으면 먼저 저장한 뒤 실행합니다. 저장에 실패하면 실행하지 않습니다.</span>
          </div>
        </div>
      )}
    </div>
  );
}
