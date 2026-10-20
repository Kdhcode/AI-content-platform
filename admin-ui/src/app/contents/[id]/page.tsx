"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { api, ApiFailure, currentUser, fmt, localVerifyMode, type ContentChangeResult, type ContentDetail, type Me } from "@/lib/api";
import { sameJson, saveViolations, type Doc } from "@/lib/contentBody";
import { createBackGuard } from "@/lib/backGuard";
import { draftKey, draftStorage, type Draft } from "@/lib/contentDraft";
import { ContentSession, type SessionSnapshot } from "@/lib/contentSession";
import { Badge, ErrorText, Shell } from "@/components/ui";
import { BodyEditor } from "@/components/content/BodyEditor";
import { BACK_TO_DRAFT, LOCKED, OriginBadges, Problem, TYPE_LABEL, violationsOf } from "@/components/content/common";
import { LinksPanel } from "@/components/content/LinksPanel";
import { OriginPanel } from "@/components/content/OriginPanel";
import { SourcesPanel, type Change } from "@/components/content/SourcesPanel";
import { StatusActions } from "@/components/content/StatusActions";

export default function ContentEditorPage() {
  const id = Number(useParams<{ id: string }>().id);
  const [start, setStart] = useState<{ content: ContentDetail; me: Me; env: string } | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let live = true;
    Promise.all([api<ContentDetail>(`/api/admin/contents/${id}`), currentUser(), localVerifyMode()])
      .then(([content, me, mode]) => { if (live) setStart({ content, me, env: `${window.location.host}|${mode.dataLabel ?? "-"}` }); })
      .catch((e) => { if (live) setError(e); });
    return () => { live = false; };
  }, [id]);
  return (
    <Shell>
      <div className="row"><Link href="/contents">← 콘텐츠 목록</Link></div>
      <ErrorText error={error} />
      {!start && !error && <div className="muted">불러오는 중…</div>}
      {start && <Editor key={id} {...start} />}
    </Shell>
  );
}

const LEAVE_MESSAGE = "저장되지 않은 편집이 있습니다. 이 화면을 떠날까요?\n"
  + "(떠나면 남은 편집을 바로 저장해 봅니다. 저장하지 못해도 이 브라우저의 임시 보관본에 남아 다시 열면 복원할 수 있습니다.)";

const SAVE_LABEL: Record<SessionSnapshot<ContentDetail>["state"], string> = {
  saved: "저장됨", dirty: "저장 안 됨 — 입력을 멈추면 2초 뒤 저장", saving: "저장 중…", error: "저장 실패", conflict: "충돌 — 자동 저장 중지",
};

function DocView({ doc }: { doc: Doc }) {
  return (
    <div className="doc-view">
      <div className="doc-title">{doc.title || <span className="muted">(제목 없음)</span>}</div>
      {doc.body.sections.length === 0 && <div className="muted">(본문 없음)</div>}
      {doc.body.sections.map((s) => (
        <div key={s.id}><h3>{s.heading || <span className="muted">(소제목 없음)</span>}</h3>
          {s.paragraphs.map((p) => <p key={p.id}>{p.text || <span className="muted">(빈 문단)</span>}</p>)}</div>
      ))}
    </div>
  );
}

function Editor({ content: initial, me, env }: { content: ContentDetail; me: Me; env: string }) {
  const id = initial.id;
  const isAdmin = me.roles.includes("SYSTEM_ADMIN");
  const sessionRef = useRef<ContentSession<ContentDetail> | null>(null);
  if (!sessionRef.current) {
    sessionRef.current = new ContentSession<ContentDetail>({
      initial,
      save: (version, change) => api<ContentChangeResult>(`/api/admin/contents/${id}`, { method: "PATCH", body: { version, ...change } })
        .then((r) => r.content),
      validate: saveViolations,
    });
  }
  const session = sessionRef.current;
  const snap = useSyncExternalStore(session.subscribe, session.snapshot, session.snapshot);
  const content = snap.server;

  const [notice, setNotice] = useState("");
  const [actionError, setActionError] = useState<unknown>(null);
  const [serverView, setServerView] = useState<ContentDetail | null>(null);
  const [conflictBusy, setConflictBusy] = useState(false);
  /** A status / source / link / acknowledge request is running: the editor waits, so nothing typed now lands after it. */
  const [acting, setActing] = useState(false);
  const actingNow = useRef(false); // state updates are not synchronous: a double click would otherwise send twice

  // ---- local draft backup -------------------------------------------------------------------------------------
  const key = draftKey(env, me.username, id);
  const store = useMemo(() => draftStorage(() => { try { return window.localStorage; } catch { return null; } }), []);
  const [storageNote, setStorageNote] = useState<string | null>(null);
  const [pendingDraft, setPendingDraft] = useState<Draft | null>(null);
  const wroteDraft = useRef(false);
  useEffect(() => {
    const { draft, error } = store.read(key);
    if (error) setStorageNote(error);
    if (draft && !sameJson({ title: draft.title, body: draft.body }, { title: initial.title, body: initial.body })) setPendingDraft(draft);
    else if (draft) store.remove(key);
  }, [store, key, initial]);
  const dirty = !sameJson(snap.local, snap.saved);
  useEffect(() => {
    if (pendingDraft) return; // never overwrite a kept draft the person has not restored or discarded yet
    if (dirty) {
      setStorageNote(store.write(key, id, snap.server.version, snap.local));
      wroteDraft.current = true;
    } else if (wroteDraft.current && !snap.conflict) {
      const err = store.remove(key);
      if (err) setStorageNote(err);
      wroteDraft.current = false;
    }
  }, [dirty, snap.local, snap.server.version, snap.conflict, pendingDraft, store, key, id]);

  // ---- leaving the page with unsaved edits --------------------------------------------------------------------
  useEffect(() => {
    const beforeUnload = (e: BeforeUnloadEvent) => { if (session.unsaved) { e.preventDefault(); e.returnValue = ""; } };
    const click = (e: MouseEvent) => {
      if (!session.unsaved || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const a = (e.target as Element | null)?.closest?.("a[href]") as HTMLAnchorElement | null;
      if (!a || a.target === "_blank" || a.origin !== window.location.origin) return;
      if (!window.confirm(LEAVE_MESSAGE)) {
        e.preventDefault(); e.stopPropagation();
      }
    };
    // Back/Forward: see lib/backGuard.ts. The guard entry is added only once something is unsaved.
    const guard = createBackGuard({
      state: () => window.history.state,
      href: () => window.location.href,
      pushState: (data, url) => window.history.pushState(data, "", url),
      back: () => window.history.back(),
      onPopState: (fn) => {
        const listener = (e: PopStateEvent) => fn(e.state);
        window.addEventListener("popstate", listener);
        return () => window.removeEventListener("popstate", listener);
      },
      confirm: (message) => window.confirm(message),
    }, { id: `content-${id}`, isUnsaved: () => session.unsaved, message: LEAVE_MESSAGE });
    const armWhenUnsaved = () => { if (session.unsaved) guard.arm(); };
    const unsubscribe = session.subscribe(armWhenUnsaved);
    armWhenUnsaved();
    window.addEventListener("beforeunload", beforeUnload);
    document.addEventListener("click", click, true);
    return () => {
      unsubscribe();
      guard.dispose();
      window.removeEventListener("beforeunload", beforeUnload);
      document.removeEventListener("click", click, true);
      session.dispose();
      // Left inside the app (link or Back, after confirming): send what the 2-second timer would have sent. The
      // request outlives the page; if it fails the browser draft (not removed here) still holds the edits.
      if (session.dirty && session.snapshot().conflict === null) void session.flush();
    };
  }, [session, id]);

  // ---- editing gate ---------------------------------------------------------------------------------------------
  const gateKey = `${content.status}|${content.submittedAt}|${content.approvedAt}`;
  const [unlocked, setUnlocked] = useState<string | null>(null);
  const needsConfirm = BACK_TO_DRAFT.has(content.status) && unlocked !== gateKey;
  function startEditing(): boolean {
    if (!needsConfirm) return true;
    const what = content.status === "APPROVED" ? "승인이 해제되고" : "검수 요청이 취소되고";
    if (!window.confirm(`이 콘텐츠는 ${content.status} 상태입니다.\n제목·본문·출처·관련 URL을 바꾸면 저장될 때 ${what} DRAFT로 돌아갑니다(다시 검수 요청 필요).\n편집을 시작할까요?`)) return false;
    setUnlocked(gateKey);
    return true;
  }
  const locked = LOCKED.has(content.status);
  const conflict = snap.conflict !== null;
  const canEdit = !locked && !conflict && !pendingDraft && !needsConfirm && !acting;

  const change: Change = useCallback(async (label, op, apply) => {
    if (actingNow.current) return false;
    actingNow.current = true;
    setNotice(""); setActionError(null); setActing(true);
    const before = session.snapshot().server.status;
    try {
      const response = await session.run(op, apply);
      const after = session.snapshot().server.status;
      const unchanged = !!response && typeof response === "object" && (response as { changed?: unknown }).changed === false;
      setNotice(unchanged ? "바뀐 내용이 없어 저장하지 않았습니다." : label + (before !== after ? ` (상태 ${before} → ${after})` : ""));
      return true;
    } catch (e) {
      setActionError(e);
      return false;
    } finally {
      actingNow.current = false;
      setActing(false);
    }
  }, [session]);

  const refreshOrigin = useCallback(async () => {
    try {
      const fresh = await api<ContentDetail>(`/api/admin/contents/${id}`);
      session.updateServer((cur) => ({ ...cur, originIssue: fresh.originIssue, originChange: fresh.originChange, originCheckedAt: fresh.originCheckedAt }));
    } catch (e) { setActionError(e); }
  }, [id, session]);

  async function viewServer() {
    setConflictBusy(true);
    try { setServerView(await api<ContentDetail>(`/api/admin/contents/${id}`)); } catch (e) { setActionError(e); } finally { setConflictBusy(false); }
  }

  async function reloadLatest() {
    setConflictBusy(true);
    try {
      const fresh = await api<ContentDetail>(`/api/admin/contents/${id}`);
      const mine = session.snapshot().local;
      const err = store.write(key, id, session.snapshot().server.version, mine);
      if (err && !window.confirm(err + "\n\n지금 다시 불러오면 내 초안이 사라집니다. 먼저 내 초안을 복사해 두세요. 그래도 다시 불러올까요?")) return;
      const kept = store.read(key).draft;
      setPendingDraft(err || !kept ? null : kept);
      wroteDraft.current = false;
      setServerView(null);
      setNotice(""); setActionError(null);
      session.reload(fresh);
    } catch (e) { setActionError(e); } finally { setConflictBusy(false); }
  }

  function restoreDraft() {
    if (!pendingDraft) return;
    if (BACK_TO_DRAFT.has(content.status) && !startEditing()) return;
    const d = pendingDraft;
    setPendingDraft(null);
    session.edit({ title: d.title, body: d.body });
  }

  function discardDraft() {
    if (!window.confirm("임시 보관본을 버릴까요? 되돌릴 수 없습니다.")) return;
    setStorageNote(store.remove(key));
    setPendingDraft(null);
  }

  const conflictDetails = snap.conflict instanceof ApiFailure ? snap.conflict.details as
    { currentVersion?: number; updatedBy?: string; updatedAt?: string; status?: string } | undefined : undefined;
  const violations = [...saveViolations(snap.local), ...violationsOf(snap.error), ...violationsOf(actionError)];

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">CONTENT #{id} · {TYPE_LABEL[content.contentType] ?? content.contentType}</div>
          <h1>{snap.local.title.trim() || "(제목 없음)"}</h1>
          <div className="row tight"><Badge value={content.status} /><span className="muted">version {content.version}</span>
            <OriginBadges change={content.originChange} />
            <span className="muted">작성 {content.createdBy} · {fmt(content.createdAt)} · 수정 {content.updatedBy} · {fmt(content.updatedAt)}</span></div>
        </div>
        <div className={`save-state ${snap.state}`} role="status" aria-live="polite">
          <strong>{SAVE_LABEL[snap.state]}</strong>
          {snap.state === "saved" && <span className="muted"> · {fmt(content.updatedAt)}</span>}
          {snap.composing && <span className="muted"> · 입력 중(조합)</span>}
          {snap.state === "error" && <div><button type="button" onClick={() => session.flush()}>다시 저장</button></div>}
        </div>
      </div>

      <div className="sticky-feedback">
        {notice && <div className="notice">{notice}</div>}
        <Problem error={actionError} />
        {snap.state === "error" && <Problem error={snap.error} />}
        {storageNote && <div className="error">{storageNote}</div>}
      </div>

      {conflict && (
        <div className="card conflict">
          <h2 style={{ marginTop: 0 }}>다른 곳에서 먼저 바뀌었습니다 — 자동 저장을 멈췄습니다</h2>
          <p>서버의 최신 version은 {conflictDetails?.currentVersion ?? "?"}입니다
            {conflictDetails?.updatedBy && <> (마지막 수정 {conflictDetails.updatedBy} · {fmt(conflictDetails.updatedAt)})</>}. 이 화면은 version {content.version}을 기준으로 편집하고 있었습니다.
            내 초안은 아래 편집기와 이 브라우저의 임시 보관본에 그대로 있습니다. 자동으로 덮어쓰거나 다시 보내지 않습니다.</p>
          <div className="row tight">
            <button type="button" disabled={conflictBusy} onClick={viewServer}>서버 최신본 보기</button>
            <button type="button" disabled={conflictBusy} onClick={() => navigator.clipboard?.writeText(JSON.stringify(session.snapshot().local, null, 2))
              .then(() => setNotice("내 초안을 클립보드에 복사했습니다."), () => setNotice("클립보드에 복사하지 못했습니다."))}>내 초안 복사</button>
            <button type="button" className="primary" disabled={conflictBusy} onClick={reloadLatest}>최신본으로 다시 불러오기(내 초안은 보관)</button>
          </div>
          {serverView && (
            <div className="grid2 compare">
              <div><h3>서버 최신본 (version {serverView.version}, {serverView.status})</h3><DocView doc={serverView} /></div>
              <div><h3>내 초안 (보관 중)</h3><DocView doc={snap.local} /></div>
            </div>
          )}
        </div>
      )}

      {pendingDraft && (
        <div className="card warn-box">
          <h2 style={{ marginTop: 0 }}>저장되지 않은 임시 보관본이 있습니다</h2>
          <p>이 브라우저에 {fmt(pendingDraft.savedAt)}에 보관된 초안(version {pendingDraft.baseVersion} 기준)이 서버 내용과 다릅니다.
            {pendingDraft.baseVersion !== content.version && <strong> 그 뒤 서버 내용이 바뀌었습니다(현재 version {content.version}). 복원하면 지금 서버 내용 위에 보관본이 저장됩니다.</strong>}
            {" "}복원하거나 버리기 전에는 편집할 수 없습니다.</p>
          <div className="grid2 compare">
            <div><h3>현재 서버 내용 (version {content.version})</h3><DocView doc={content} /></div>
            <div><h3>임시 보관본</h3><DocView doc={pendingDraft} /></div>
          </div>
          <div className="row tight">
            <button type="button" className="primary" disabled={locked} onClick={restoreDraft}>보관본 복원</button>
            <button type="button" className="danger" onClick={discardDraft}>보관본 버리기</button>
            {locked && <span className="muted">{content.status} 상태에서는 복원할 수 없습니다. 보관본은 그대로 둡니다.</span>}
          </div>
        </div>
      )}

      {locked && <div className="warn-box">{content.status} 상태에서는 내용을 바꿀 수 없습니다. {content.status === "HOLD" ? "보류 해제" : "복귀(SYSTEM_ADMIN)"} 후 편집하세요.</div>}
      {needsConfirm && !conflict && !pendingDraft && (
        <div className="warn-box">이 콘텐츠는 <strong>{content.status}</strong> 상태입니다. 제목·본문·출처·관련 URL을 바꾸면 저장될 때 DRAFT로 돌아갑니다.
          <button type="button" onClick={startEditing} style={{ marginLeft: 10 }}>편집 시작</button></div>
      )}

      <div className="card">
        <h2 style={{ marginTop: 0 }}>상태</h2>
        {content.statusReason && <div className="muted">사유: {content.statusReason}</div>}
        <div className="muted">검수 요청 {fmt(content.submittedAt)} · 승인 {content.approvedBy ?? "-"} {fmt(content.approvedAt)}</div>
        <StatusActions content={content} isAdmin={isAdmin} change={change} blocked={conflict || !!pendingDraft || acting} />
        <div className="muted">승인해도 공개 뉴스룸·공개 API에는 나오지 않습니다(게시 기능은 아직 없음).</div>
      </div>

      <div className="card">
        <BodyEditor doc={snap.local} disabled={!canEdit} violations={violations}
          onChange={(doc) => session.edit(doc)} onComposing={(open) => session.setComposing(open)} />
      </div>

      <OriginPanel content={content} change={change} refreshOrigin={refreshOrigin} blocked={conflict} />
      <SourcesPanel content={content} change={change} disabled={!canEdit} />
      <LinksPanel content={content} change={change} disabled={!canEdit} />
    </>
  );
}
