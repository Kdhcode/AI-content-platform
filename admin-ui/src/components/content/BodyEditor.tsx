"use client";
import { LIMITS, newId, totalChars, type Doc, type Section, type Violation } from "@/lib/contentBody";
import { Counter } from "./common";

type Props = {
  doc: Doc;
  onChange: (doc: Doc) => void;
  /** IME composition (Korean input) open / closed: the autosave waits while it is open. */
  onComposing: (open: boolean) => void;
  disabled: boolean;
  violations: Violation[];
};

const move = <T,>(list: T[], from: number, to: number): T[] => {
  if (to < 0 || to >= list.length) return list;
  const next = list.slice();
  const [x] = next.splice(from, 1);
  next.splice(to, 0, x);
  return next;
};

/** Title and body as plain form fields: sections (heading + paragraphs) added, edited, removed and reordered. */
export function BodyEditor({ doc, onChange, onComposing, disabled, violations }: Props) {
  const ime = { onCompositionStart: () => onComposing(true), onCompositionEnd: () => onComposing(false), onBlur: () => onComposing(false) };
  const errorsAt = (path: string) => violations.filter((v) => v.path === path);
  const Errors = ({ path }: { path: string }) => <>{errorsAt(path).map((v, i) => <div key={i} className="field-error">{v.message}</div>)}</>;
  const sections = doc.body.sections;
  const setSections = (next: Section[]) => onChange({ ...doc, body: { ...doc.body, sections: next } });
  const setSection = (i: number, s: Section) => setSections(sections.map((x, j) => (j === i ? s : x)));
  const total = totalChars(doc.body);

  function removeSection(i: number) {
    const s = sections[i];
    const hasText = s.heading.trim() || s.paragraphs.some((p) => p.text.trim());
    if (hasText && !window.confirm(`섹션 ${i + 1}을(를) 삭제할까요? 소제목과 문단 ${s.paragraphs.length}개가 함께 지워집니다.`)) return;
    setSections(sections.filter((_, j) => j !== i));
  }

  return (
    <div className="body-editor">
      <label className="field-label" htmlFor="content-title">제목 <Counter value={doc.title.length} max={LIMITS.titleChars} /></label>
      <input id="content-title" type="text" className="title-input" value={doc.title} disabled={disabled} placeholder="제목(검수 요청 전까지 비워 둘 수 있음)"
        onChange={(e) => onChange({ ...doc, title: e.target.value.replace(/[\r\n]+/g, " ") })} {...ime} />
      <Errors path="title" />

      <div className="row spread">
        <h2>본문</h2>
        <span className="muted">섹션 <Counter value={sections.length} max={LIMITS.sections} /> · 전체 글자 <Counter value={total} max={LIMITS.totalChars} /></span>
      </div>
      <Errors path="body" /><Errors path="body.sections" />
      {sections.length === 0 && <div className="empty-state">본문이 비어 있습니다. 아래 “섹션 추가”로 소제목과 문단을 넣으세요.</div>}

      {sections.map((s, i) => {
        const sp = `body.sections[${i}]`;
        return (
          <fieldset key={s.id} className="section-block" disabled={disabled}>
            <legend>섹션 {i + 1}</legend>
            <div className="row">
              <label className="field-label grow" htmlFor={`h-${s.id}`}>소제목 <Counter value={s.heading.length} max={LIMITS.headingChars} /></label>
              <button type="button" aria-label={`섹션 ${i + 1} 위로`} disabled={i === 0} onClick={() => setSections(move(sections, i, i - 1))}>↑</button>
              <button type="button" aria-label={`섹션 ${i + 1} 아래로`} disabled={i === sections.length - 1} onClick={() => setSections(move(sections, i, i + 1))}>↓</button>
              <button type="button" className="danger" onClick={() => removeSection(i)}>섹션 삭제</button>
            </div>
            <input id={`h-${s.id}`} type="text" className="title-input" value={s.heading} placeholder="소제목"
              onChange={(e) => setSection(i, { ...s, heading: e.target.value.replace(/[\r\n]+/g, " ") })} {...ime} />
            <Errors path={`${sp}.heading`} /><Errors path={`${sp}.id`} /><Errors path={`${sp}.paragraphs`} />
            {s.paragraphs.map((p, j) => (
              <div key={p.id} className="paragraph-block">
                <div className="row">
                  <label className="field-label grow" htmlFor={`p-${p.id}`}>문단 {j + 1} <Counter value={p.text.length} max={LIMITS.paragraphChars} /></label>
                  <button type="button" aria-label={`문단 ${j + 1} 위로`} disabled={j === 0}
                    onClick={() => setSection(i, { ...s, paragraphs: move(s.paragraphs, j, j - 1) })}>↑</button>
                  <button type="button" aria-label={`문단 ${j + 1} 아래로`} disabled={j === s.paragraphs.length - 1}
                    onClick={() => setSection(i, { ...s, paragraphs: move(s.paragraphs, j, j + 1) })}>↓</button>
                  <button type="button" className="danger" onClick={() => {
                    if (p.text.trim() && !window.confirm(`문단 ${j + 1}을(를) 삭제할까요?`)) return;
                    setSection(i, { ...s, paragraphs: s.paragraphs.filter((_, k) => k !== j) });
                  }}>문단 삭제</button>
                </div>
                <textarea id={`p-${p.id}`} value={p.text} placeholder="문단 내용"
                  onChange={(e) => setSection(i, { ...s, paragraphs: s.paragraphs.map((x, k) => (k === j ? { ...x, text: e.target.value } : x)) })} {...ime} />
                <Errors path={`${sp}.paragraphs[${j}].text`} /><Errors path={`${sp}.paragraphs[${j}].id`} />
              </div>
            ))}
            <button type="button" disabled={s.paragraphs.length >= LIMITS.paragraphsPerSection}
              onClick={() => setSection(i, { ...s, paragraphs: [...s.paragraphs, { id: newId("p", doc.body), text: "" }] })}>
              + 문단 추가 <span className="muted">({s.paragraphs.length}/{LIMITS.paragraphsPerSection})</span></button>
          </fieldset>
        );
      })}
      <button type="button" disabled={disabled || sections.length >= LIMITS.sections}
        onClick={() => setSections([...sections, { id: newId("s", doc.body), heading: "", paragraphs: [] }])}>+ 섹션 추가</button>
    </div>
  );
}
