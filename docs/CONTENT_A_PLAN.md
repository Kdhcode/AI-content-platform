# NEWS·LIFE 콘텐츠 A 묶음 구현 계획 (AI 없는 수동 제작·편집·검수)

## 진행 상태 (브랜치 `claude/content-a`, `main` 미병합)

| 작업 | 상태 | 비고 |
|---|---|---|
| A1 스키마 | **구현됨** | V4(`content`·`content_source`·`content_link`) + V5(EXTERNAL_URL 출처의 URL 필수 — V4 CHECK가 NULL을 통과시키던 문제 수정) |
| A2 생성·조회·편집 | **구현됨** | `docs/API.md` "콘텐츠" |
| A3 상태 전이 | **구현됨(2026-10-15)** | 4절 최종 표 |
| A4 출처 관리 · A5 관련 URL · A6 업데이트 필요 표시 · A7 화면 · A8 분리 확인 | 계획만 있음 | 6절 |

구현하면서 계획과 달라진 점: ① 생성 요청의 `body`는 선택이고 생략하면 빈 본문(기본 소제목 자동 채움은 7절 7번이 정해질 때까지 하지 않음) ② 같은 키의 다른 요청을 가리기 위해 `content.creation_fingerprint`(요청 해시) 열 추가 ③ 상태 동작 실패(검수 요청·승인 조건 미달)는 422 `CONTENT_NOT_READY`.

기준 명세는 노션 "AI 정보 콘텐츠 플랫폼"(02·04·05·06·09·10), 범위 전체는 [NEXT_CONTENT_SCOPE.md](NEXT_CONTENT_SCOPE.md).

## 0. 초기 구현 기준 (사용자 결정, 10-15)

- 첫 범위는 A 묶음: AI 없이 수동 콘텐츠 생성·편집·출처·관련 URL·검수.
- NEWS와 LIFE는 같은 콘텐츠 테이블·API를 쓰고 `content_type`으로 구분.
- 생성 대상 이슈: ACTIVE·CLOSED. REVIEW·EXCLUDED·MERGED는 거부.
- 같은 이슈·같은 유형 콘텐츠 여러 개 허용. 중복 클릭·재시도로 같은 초안이 두 번 생기지 않게 처리.
- 본문은 소제목·문단 순서를 보존하는 구조화 JSON.
- 상태 흐름 DRAFT → REVIEW → APPROVED. DRAFT 직접 승인 없음.
- 승인된 콘텐츠의 제목·본문·출처·관련 URL을 바꾸면 승인 해제 → DRAFT.
- 동시 편집은 version 충돌 감지(409 + 복구 안내). 조용히 덮어쓰지 않음.
- 자동저장은 변경이 있을 때만. 상태 전환 전 저장 완료 확인.
- 원천 이슈가 바뀌어도 본문을 자동으로 덮어쓰지 않음.
- AI 생성·발행·공개 콘텐츠 API 제외. 승인돼도 기존 뉴스룸에 자동 공개되지 않음.

## 1. 노션 명세와 다른 점 (숨기지 않음)

| # | 명세 | 이번 계획 | 이유·처리 |
|---|---|---|---|
| D1 | 04 ERD `CONTENT.body` 단일 필드(형식 미정) | `body`를 구조화 JSON(`jsonb`)으로 | 문단 추가·삭제·순서 변경(명세 02 §10) 보존. 형식 자체는 명세가 정하지 않았으나 "본문" 단일 텍스트로 읽힐 수 있어 표시 |
| D2 | 05 API에 수동 생성 없음(`POST /api/admin/contents/generate`=AI, `POST /api/admin/issues/{id}/life-content`) | `POST /api/admin/issues/{id}/contents` 추가(수동 빈 초안) | A 묶음은 AI 없음. AI 생성 API는 C 묶음에서 명세 경로로 추가 |
| D3 | 05 API 상태 동작은 `approve`, `hold`, `archive`만 | `submit`, `return`, `resume`, `exclude`, `restore` 추가 | DRAFT→REVIEW, 반려, HOLD 복귀, EXCLUDED·ARCHIVED 복귀 경로가 명세에 없음 |
| D4 | 02 "생활 이야기 후보는 기존 ISSUE에 대한 추가 분류 결과로 시작" | A에서는 LIFE 판정(B 묶음) 없이 아무 ACTIVE·CLOSED 이슈에서 LIFE 콘텐츠를 수동 생성 | B 묶음 전 임시. B가 들어오면 LIFE 후보 화면에서 시작하는 흐름을 추가(수동 생성 유지 여부는 결정 필요) |
| D5 | 04 `CONTENT_SOURCE.source_kind`에 `TV_ITEM` | `TV_ITEM` 제외(ISSUE·NEWS_ARTICLE·EXTERNAL_URL·MANUAL) | TV는 이번 범위 제외. Phase 4에서 열 추가 마이그레이션 |
| D6 | 04 `CONTENT.embedding` | 열 만들지 않음 | 용도(관련 콘텐츠 추천)가 Phase 3 |
| D7 | 06 콘텐츠 관리 "삭제" | 물리 삭제 API 없음. EXCLUDED·ARCHIVED로 대체 | 명세 공통 원칙(삭제는 확인·감사 로그)과 추적성. 정말 지울 기능이 필요하면 결정 필요 |
| D8 | 06 상세 편집 탭: 이미지·SEO·사이트/블로그 미리보기·발행 설정 | 제외 | 이미지(후순위), SEO(Phase 6), 미리보기·발행(Phase 3/5) |
| D9 | 05 공개 API `GET /api/public/contents` | 만들지 않음 | 사용자 지시(공개 콘텐츠 API 제외). 공개 판단은 명세대로 APPROVED + 발행 성공을 함께 보는 Phase 3에서 |

명세가 정하지 않아 이번에 새로 정하는 것(충돌은 아님): 상태 전이·권한표(4절), 승인 후 수정 시 DRAFT 복귀, 중복 생성 방지 방식, 업데이트 필요 표시 범위, 관련 URL 표시 위치 값.

## 2. 본문 JSON 스키마와 검증 규칙

```json
{
  "schemaVersion": 1,
  "sections": [
    {
      "id": "s-3f2a9c",
      "heading": "핵심 요약",
      "paragraphs": [
        { "id": "p-81bd04", "text": "문단 본문(일반 텍스트)" }
      ]
    }
  ]
}
```

| 규칙 | 저장(PATCH) | 검수 요청(submit) 추가 조건 |
|---|---|---|
| 최상위 키는 `schemaVersion`, `sections`만. 모르는 키 거부 | ✓ | |
| `schemaVersion` = 1 | ✓ | |
| `sections` 0~30개, 배열 순서가 표시 순서 | ✓ | 1개 이상 |
| 섹션 `id`·문단 `id`: `^[a-z0-9-]{1,40}$`, 본문 안에서 중복 없음(클라이언트가 만듦, 순서 변경·충돌 복구 때 같은 문단을 찾는 데 씀) | ✓ | |
| `heading`: 0~120자, 줄바꿈 없음 | ✓ | 모든 섹션에 비지 않은 heading |
| `paragraphs`: 섹션당 0~50개 | ✓ | 섹션마다 1개 이상 |
| 문단 `text`: 0~5,000자, 일반 텍스트(HTML 해석 안 함, 화면은 이스케이프해 표시) | ✓ | 공백만 있는 문단 없음 |
| 본문 전체 글자 수 ≤ 100,000자 | ✓ | |
| 제목: 0~200자 | ✓ | 비지 않음 |

- 생성 시 유형별 기본 소제목을 채운다(명세 02 §9 구조): NEWS = 핵심 요약 · 사건 개요 · 주요 내용 · 배경 · 현재 상황 · 추가 확인사항, LIFE = 실제 사례 · 생활과의 관련성 · 관련 정보/기술 · 알아둘 점. "출처"·"관련 URL"은 본문이 아니라 `content_source`·`content_link`에서 보여 준다.
- 검증은 서버 코드(`ContentBodyValidator`)가 한다. 위반 시 400 `CONTENT_BODY_INVALID`와 위치(예: `sections[2].paragraphs[0].text`).

## 3. 데이터 구조 (A1 — V4로 구현, V5에서 출처 URL 제약 보완)

**`content`**
| 열 | 내용 |
|---|---|
| id | BIGSERIAL |
| content_type | NEWS / LIFE (CHECK) |
| status | DRAFT / REVIEW / APPROVED / HOLD / EXCLUDED / ARCHIVED (CHECK, 기본 DRAFT) |
| title | TEXT NOT NULL DEFAULT '' |
| body | JSONB NOT NULL |
| version | BIGINT NOT NULL DEFAULT 0 (변경마다 +1) |
| origin_issue_id | 원천 이슈 FK(NOT NULL — A에서는 항상 이슈에서 생성) |
| origin_checked_at | 원천 이슈를 마지막으로 반영·확인한 시각(업데이트 필요 판단 기준) |
| creation_key | TEXT UNIQUE NULL (중복 생성 방지 키) |
| status_reason | 보류·반려·제외 사유 |
| created_by / created_at / updated_by / updated_at | 감사 보조 |
| submitted_at, approved_by, approved_at | 검수 기록(승인 해제 시 approved_* 비움) |

인덱스: `(status, updated_at DESC)`(명세 04), `(origin_issue_id)`, `(content_type, status)`.

**`content_source`** — id, content_id(FK, CASCADE), source_kind(ISSUE / NEWS_ARTICLE / EXTERNAL_URL / MANUAL), issue_id(FK NULL), news_article_id(FK NULL), external_url(NULL), source_label, source_role(PRIMARY / SUPPORTING / REFERENCE), source_checked_at, created_by, created_at.
CHECK: 유형에 맞는 칸 하나만 채움(ISSUE→issue_id만, NEWS_ARTICLE→news_article_id만, EXTERNAL_URL→external_url만, MANUAL→셋 다 비고 label 필수). 같은 콘텐츠에 같은 이슈·기사·URL 중복 금지(부분 UNIQUE).

**`content_link`** — id, content_id(FK, CASCADE), link_type(PRODUCT / OFFICIAL / MAP / RESERVATION / PURCHASE / REFERENCE / OTHER — 명세 02 §11의 상품·공식·지도·예약·구매·참고자료·기타), title, url(`^https?://`), site_visible(기본 true), blog_visible(기본 false), display_position(결정 필요, 6절), sort_order, created_at, updated_at.

감사 기록은 기존 `admin_audit_log`(target_type `CONTENT`)를 그대로 쓴다.

## 4. 상태 전이와 권한 (최종, A3 구현 기준)

2026-10-15 사용자 결정: 검수 요청과 일반 편집은 OPERATOR·SYSTEM_ADMIN, **최종 승인은 SYSTEM_ADMIN만**, 초기 운영에서는 SYSTEM_ADMIN이 본인이 작성한 콘텐츠도 승인 가능, REVIEW·APPROVED에서 실제 내용이 바뀌면 DRAFT로(승인 해제), 승인돼도 뉴스룸·외부 채널에 자동 공개하지 않음.

| 동작 | API | 시작 상태 → 결과 | OPERATOR | SYSTEM_ADMIN | 사유 | 서버 조건 |
|---|---|---|---|---|---|---|
| 생성 | `POST /issues/{id}/contents` | → DRAFT | ✓ | ✓ | - | 이슈 ACTIVE·CLOSED, `Idempotency-Key` |
| 편집(제목·본문) | `PATCH /contents/{id}` | DRAFT → DRAFT, **REVIEW·APPROVED → DRAFT** | ✓ | ✓ | - | version 일치. 값이 실제로 바뀔 때만. HOLD·EXCLUDED·ARCHIVED는 409 |
| 검수 요청 | `POST /contents/{id}/submit` | DRAFT → REVIEW | ✓ | ✓ | 선택 | 제출 조건(아래) |
| 승인 | `POST /contents/{id}/approve` | REVIEW → APPROVED | ✗ | ✓ (본인 작성분 포함) | 선택 | 제출 조건 재검사 |
| 반려 | `POST /contents/{id}/return` | REVIEW → DRAFT | ✓ | ✓ | **필수** | |
| 보류 | `POST /contents/{id}/hold` | DRAFT·REVIEW·APPROVED → HOLD | ✓ | ✓ | **필수** | |
| 보류 해제 | `POST /contents/{id}/resume` | HOLD → DRAFT | ✓ | ✓ | 선택 | 승인·검수 상태로 바로 돌아가지 않음 |
| 제외 | `POST /contents/{id}/exclude` | DRAFT·REVIEW·HOLD → EXCLUDED | ✓ | ✓ | **필수** | |
| 보관 | `POST /contents/{id}/archive` | APPROVED·HOLD → ARCHIVED | ✓ | ✓ | 선택 | |
| 복귀 | `POST /contents/{id}/restore` | EXCLUDED·ARCHIVED → DRAFT | ✗ | ✓ | **필수** | |

표에 없는 조합(예: DRAFT에서 approve, APPROVED에서 submit, ARCHIVED에서 resume)은 409 `CONTENT_STATE_INVALID`, 역할이 없으면 403 `FORBIDDEN`(보안 설정과 서비스 정책 두 곳에서 검사). 상태는 이 동작들로만 바뀌고 `PATCH`는 `status`를 받지 않는다.

**요청과 동시성:** 모든 상태 동작은 `{version, reason?}`를 보낸다. 서버는 콘텐츠 행을 잠그고(FOR UPDATE) version이 현재 값이 아니면 409 `CONTENT_VERSION_CONFLICT`(`currentVersion, sentVersion, updatedBy, updatedAt, status`). 성공하면 version +1. 편집과 상태 변경이 같은 행 잠금·version을 쓰므로, 같은 version으로 편집과 승인이 동시에 오면 하나만 성공하고 오래된 내용은 승인되지 않는다. 상태·version·검수 기록·감사 기록은 한 트랜잭션.

**사유:** 앞뒤 공백 제거 후 1~500자. 필수가 아닌 동작에 보낸 사유는 감사 기록에만 남긴다.

**제출 조건(검수 요청·승인 공통, 422 `CONTENT_NOT_READY` + `details.violations[]`):** 제목이 비지 않음, 섹션 1개 이상, 모든 섹션에 비지 않은 소제목과 문단 1개 이상, 공백만 있는 문단 없음, PRIMARY 출처 1개 이상. 저장(DRAFT)에는 적용하지 않는다.

**원천 이슈 상태:** 계획(5절 업데이트 필요 표시)은 원천 이슈가 EXCLUDED·MERGED가 돼도 표시만 하고 승인 상태를 바꾸지 않는다. 따라서 A3도 검수 요청·승인을 막지 않는다. 응답 상세(`originIssue.status`, `mergedIntoIssueId`)로 확인할 수 있다. 막아야 하면 7절 결정 사항.

**검수 기록 칸 규칙:**
| 칸 | 설정 | 유지 | 지움 |
|---|---|---|---|
| `submitted_at` | submit | approve, archive | return, hold, resume, exclude, restore, 편집으로 DRAFT 복귀 |
| `approved_by`·`approved_at` | approve | archive(무엇이 승인됐었는지 남김) | hold, exclude, restore, 편집으로 DRAFT 복귀 |
| `status_reason` | return, hold, exclude, restore(필수 사유), archive(사유가 있으면) | - | submit, approve, resume, 편집으로 DRAFT 복귀 |

**감사 기록:** 동작마다 `CONTENT_SUBMIT`, `CONTENT_APPROVE`, `CONTENT_RETURN`, `CONTENT_HOLD`, `CONTENT_RESUME`, `CONTENT_EXCLUDE`, `CONTENT_ARCHIVE`, `CONTENT_RESTORE`(처리자, 전후 상태·version, 사유). 편집으로 승인 해제 시 `CONTENT_UPDATE` + `CONTENT_APPROVAL_REVOKED_BY_EDIT`.

서버 규칙은 `ContentStatusPolicy`(동작 → 허용 출발 상태·결과·역할·사유 필수 여부)에 모아 표 전체를 단위 테스트한다.

## 5. 동작 세부

**중복 생성 방지:** 화면이 "만들기" 대화상자를 열 때 UUID를 만들어 `Idempotency-Key`로 보낸다. 서버는 `content.creation_key`(UNIQUE)에 저장하고, 같은 키가 다시 오면 새로 만들지 않고 기존 콘텐츠를 200으로 돌려준다(첫 생성은 201). 키가 없으면 400. 같은 이슈에 새 콘텐츠를 더 만들려면 대화상자를 다시 열면 된다(새 키).

**동시 편집:** `PATCH`는 `version`이 현재 값과 같을 때만 저장한다. 다르면 409 `CONTENT_VERSION_CONFLICT`와 `{currentVersion, updatedBy, updatedAt}`. 화면은 자동저장을 멈추고 "다른 곳에서 먼저 저장됨" 안내와 두 선택지를 준다: ① 내 편집본을 이 브라우저에 보관한 채 최신 내용 불러오기(보관본은 옆에 표시해 복사 가능) ② 취소. 내 편집본은 충돌 시점에 `localStorage`(콘텐츠 id별)에 남긴다.

**자동저장:** 편집 내용이 마지막 저장본과 다를 때만, 입력이 멈추고 2초 뒤 저장(간격은 결정 필요). 저장 중·미저장 상태 표시. 상태 버튼은 미저장 변경이 있으면 먼저 저장하고, 성공한 version으로 상태 동작을 보낸다. 저장 실패 시 상태 동작을 보내지 않는다. 페이지를 떠날 때 미저장 변경이 있으면 경고.

**원천 이슈 변경(업데이트 필요 표시) — 제안 범위:**
- 판단: 원천 이슈의 `GREATEST(updated_at, last_updated_at)`가 콘텐츠의 `origin_checked_at`보다 늦거나, 이슈가 MERGED·EXCLUDED가 됐으면 "원천 이슈 변경됨".
- 표시: 콘텐츠 목록 배지·필터(`updateNeeded=true`), 상세 상단 안내(병합이면 대상 이슈 링크).
- 동작: 관리자가 "확인함"을 누르면 `origin_checked_at`을 지금으로 바꾸고 감사 기록. 본문·출처는 건드리지 않는다. 승인 상태도 바꾸지 않는다(본문이 바뀐 것이 아니므로).
- 이번에 하지 않음: 변경된 기사 목록 비교, 자동 재생성, 알림.

**뉴스룸과 분리:** 공개 API(`/api/public/*`)는 지금처럼 이슈 테이블만 읽고 콘텐츠 테이블을 읽지 않는다. 승인된 콘텐츠가 있어도 공개 응답이 같다는 회귀 테스트를 넣는다. 콘텐츠 API는 `/api/admin/**`라 기존 인증 규칙을 그대로 받는다.

## 6. 작업 순서와 파일

| 순서 | 작업 | 변경 파일 | API | 검증 기준 | 의존 |
|---|---|---|---|---|---|
| A1 (구현됨) | 스키마 V4, V5 | `db/migration/V4__content.sql` | - | 빈 DB·기존 DB(V3) 모두 적용, CHECK·UNIQUE 위반 시 실패하는 통합 테스트, 기존 테스트 전부 통과 | 없음 |
| A2 (구현됨) | 생성·조회·편집 | `content/ContentRepository`, `ContentService`, `ContentBody`, `ContentBodyValidator`, `admin/ContentAdminController`, `AdminRequests`(요청 DTO), `ErrorCode`(CONTENT_*) | `POST /api/admin/issues/{id}/contents`, `GET /api/admin/contents`, `GET/PATCH /api/admin/contents/{id}` | 본문 규칙 단위 테스트, 이슈 상태별 생성 허용·거부, 같은 키 재요청 시 1건, version 충돌 409, 비로그인 401, 감사 기록 | A1 |
| A3 (구현됨) | 상태 전이 | `content/ContentStatusPolicy`, `ContentService` 상태 메서드 | `POST /api/admin/contents/{id}/{submit,approve,return,hold,resume,exclude,archive,restore}` | 표 전체 단위 테스트(허용·거부·역할), DRAFT 직접 승인 거부, 편집 시 승인 해제, version 불일치 409 | A2 |
| A4 | 출처 | `content/ContentSourceRepository`, 서비스·컨트롤러 | `GET/POST/DELETE /api/admin/contents/{id}/sources` | 생성 시 원천 이슈를 PRIMARY로 자동 등록, 유형별 칸 검증, 중복 거부, 변경 시 승인 해제 | A2 (A3과 병행 가능) |
| A5 | 관련 URL | `content/ContentLinkRepository`, 서비스·컨트롤러 | `POST/PATCH/DELETE /api/admin/contents/{id}/links` | 잘못된 URL 거부, 사이트만·블로그만·둘 다 저장(명세 09 §7), 변경 시 승인 해제 | A2 (병행 가능) |
| A6 | 업데이트 필요 표시 | 조회 쿼리, `acknowledge` 동작 | `GET /api/admin/contents?updateNeeded=true`, `POST /api/admin/contents/{id}/acknowledge-origin` | 기사 추가·병합 후 표시, 확인 후 해제, 본문 불변 | A2 |
| A7 | 관리자 화면 | `admin-ui/src/lib/api.ts`, `app/contents/page.tsx`(목록: 유형 탭·상태·업데이트 필요 필터·검색), `app/contents/[id]/page.tsx`(편집: 섹션·문단 추가/삭제/순서, 자동저장, 충돌 안내, 상태 버튼, 출처, 관련 URL), `app/issues/[id]/page.tsx`("NEWS/LIFE 콘텐츠 만들기"), 메뉴 | 위 API | 타입 검사·빌드, 로컬 검증 모드에서 브라우저로 생성→편집→검수 요청→승인→수정 시 DRAFT 복귀→충돌 안내 확인 | A3~A6 |
| A8 | 분리 확인·문서 | 공개 API 회귀 테스트, `docs/API.md`, `DECISIONS.md` | - | 승인 콘텐츠가 있어도 `/api/public/*` 응답 동일 | A7 |

외부 라이브러리 추가 없음(JSON은 기존 Jackson). 유료 AI 호출 없음.

## 7. 추가로 결정할 것

1. ~~승인 권한~~ → **결정(10-15):** SYSTEM_ADMIN만, 본인 작성분 승인 허용(초기 운영).
2. ~~REVIEW 중 편집~~ → **결정(10-15):** 실제 내용이 바뀌면 DRAFT로.
3. **관련 URL 표시 위치 값:** 명세는 "노출 문구 및 위치 설정"만 있음. 제안: 본문 위 / 본문 아래 / 출처 영역.
4. **크기 한도:** 섹션 30·문단 50·문단 5,000자·전체 100,000자·제목 200자 제안값 확인.
5. **자동저장 간격:** 제안 2초.
6. **LIFE 수동 생성 유지:** B 묶음(LIFE 판정) 이후에도 판정 없이 LIFE를 만들 수 있게 둘지.
7. **기본 소제목 자동 채움:** 명세 구조로 미리 채울지, 빈 본문으로 시작할지.
8. **원천 이슈가 EXCLUDED·MERGED일 때 검수 요청·승인:** 지금은 계획대로 막지 않고 상세에서 보이기만 한다. 막을지(또는 경고만 할지) 결정 필요.
9. **본인 승인 허용의 종료 시점:** 운영 인원이 늘면 작성자와 승인자를 분리할지.
