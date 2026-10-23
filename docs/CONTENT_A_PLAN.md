# NEWS·LIFE 콘텐츠 A 묶음 구현 계획 (AI 없는 수동 제작·편집·검수)

## 진행 상태 (브랜치 `claude/content-a`, `main` 미병합)

| 작업 | 상태 | 비고 |
|---|---|---|
| A1 스키마 | **구현됨** | V4(`content`·`content_source`·`content_link`) + V5(EXTERNAL_URL 출처의 URL 필수 — V4 CHECK가 NULL을 통과시키던 문제 수정) + V6(관련 URL 표시 위치 값·기본값, URL 규칙 통일) |
| A2 생성·조회·편집 | **구현됨** | `docs/API.md` "콘텐츠" |
| A3 상태 전이 | **구현됨(2026-10-15)** | 4절 최종 표 |
| A4 출처 관리 | **구현됨(2026-10-15)** | 5절 "출처 관리" |
| A5 관련 URL | **구현됨(2026-10-15)** | 5절 "관련 URL", V6(표시 위치 값·URL 규칙), 콘텐츠당 500개 |
| A6 원천 이슈 변경 표시 | **구현됨(2026-10-15)** | 5절 "원천 이슈 변경 표시(A6)", V7(`issue.change_seq`) |
| 상세 조회 일관성 | **보완됨(2026-10-15)** | 5절 "상세 조회 일관성" — 한 SQL 문으로 읽음 |
| A7 관리자 화면 | **구현됨(2026-10-15)** | 5절 "A7 관리자 화면", "자동저장·요청 순서·충돌" |
| A8 공개 분리 확인 | **확인됨(2026-10-15)** | 5절 "A8 공개 분리 확인" |

구현하면서 계획과 달라진 점: ① 생성 요청의 `body`는 선택이고 생략하면 빈 본문(기본 소제목 자동 채움 없음 — 7절 7번 결정, 2026-10-23) ② 같은 키의 다른 요청을 가리기 위해 `content.creation_fingerprint`(요청 해시) 열 추가 ③ 상태 동작 실패(검수 요청·승인 조건 미달)는 422 `CONTENT_NOT_READY`.

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

- 수동 생성(NEWS·LIFE)은 빈 제목·빈 본문(`{"schemaVersion":1,"sections":[]}`)으로 시작하고 기본 소제목을 채우지 않는다(7절 7번 결정, 2026-10-23). 명세 02 §9 구조(NEWS = 핵심 요약 · 사건 개요 · 주요 내용 · 배경 · 현재 상황 · 추가 확인사항, LIFE = 실제 사례 · 생활과의 관련성 · 관련 정보/기술 · 알아둘 점)는 AI 초안 생성(C 묶음)의 구조로 따로 정한다. 정정: 10-15 계획 초안에는 "생성 시 유형별 기본 소제목을 채운다"로 적었고, 구현은 그때부터 빈 본문이었다(위 "구현하면서 계획과 달라진 점" ①, 5절 A7). "출처"·"관련 URL"은 본문이 아니라 `content_source`·`content_link`에서 보여 준다.
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

**출처 관리(A4, 2026-10-15 구현):**
- 종류별로 해당 참조값 하나만 받는다: ISSUE→`issueId`, NEWS_ARTICLE→`newsArticleId`, EXTERNAL_URL→`externalUrl`, MANUAL→`label`(공백만은 거부). 다른 칸이 오면 400.
- ISSUE는 존재하고 ACTIVE·CLOSED인 이슈만(생성 기준과 같음, REVIEW·EXCLUDED·MERGED는 409). NEWS_ARTICLE은 존재하는 기사이고 중복 기사(`duplicate_of_article_id`)가 아니어야 한다(409, 원본 기사 번호 안내). URL은 http(s)·호스트·공백 없음·2000자(서버가 접속하지 않음).
- 같은 콘텐츠에 같은 이슈·기사·URL은 한 번만(409 `CONTENT_SOURCE_DUPLICATE`, 원천 이슈도 포함). MANUAL은 중복 검사 없음.
- 수정은 역할(`role`)과 설명(`label`)만. 가리키는 대상을 바꾸려면 삭제 후 추가(감사 기록에 둘 다 남음).
- **원천 이슈 출처**(생성 때 자동 등록된 `ISSUE`·`issue_id = origin_issue_id`): 삭제 불가(409 `CONTENT_SOURCE_PROTECTED`) — 콘텐츠가 어디서 왔는지의 기록. 역할 변경은 허용. `origin_issue_id`는 어떤 출처 변경으로도 바뀌지 않는다.
- 초안은 PRIMARY 없이도 저장된다. 검수 요청·승인 때만 PRIMARY 1개 이상(A3, 422).

**관련 URL(A5, 2026-10-15 구현):**
- 종류 PRODUCT·OFFICIAL·MAP·RESERVATION·PURCHASE·REFERENCE·OTHER, 제목 0~200자(한 줄), URL은 출처와 같은 규칙, 사이트 노출 기본 true·블로그 노출 기본 false, 표시 위치 **BODY_TOP(본문 위)·BODY_BOTTOM(본문 아래, 기본)·SOURCES(출처 영역)** — 사용자 결정(10-15), V6에서 DB 제약.
- 콘텐츠당 최대 500개(정렬 요청이 전체 id를 받으므로 같은 한도). 501번째는 409 `CONTENT_LINK_LIMIT_EXCEEDED`, 콘텐츠 행 잠금 안에서 센다.
- 새 링크는 맨 뒤에 붙는다. 정렬은 `PUT .../links/order`에 이 콘텐츠의 링크 id를 **빠짐없이 한 번씩**: 중복·모르는 id(다른 콘텐츠 것 포함)·빠진 id가 하나라도 있으면 400(`details{duplicateIds, unknownIds, missingIds}`)이고 아무것도 바뀌지 않는다. 지금과 같은 순서면 변경 없음.
- 노출 설정은 저장만 한다. 뉴스룸·블로그 노출은 Phase 3·5.

**출처·URL 공통(`ContentChanges`, 제목·본문 편집과 같은 경로):** 콘텐츠 행 잠금 → HOLD·EXCLUDED·ARCHIVED면 409 → version 불일치면 409 `CONTENT_VERSION_CONFLICT` → 변경 → **실제로 달라졌을 때만** version +1, REVIEW·APPROVED → DRAFT(검수 기록 정리, APPROVED면 승인 해제 기록), 감사 기록. 같은 값·같은 순서 요청은 아무것도 쓰지 않는다(`changed: false`). 다른 콘텐츠의 sourceId·linkId는 404. 삭제는 `?version=`으로 version을 보낸다.

**중복 생성 방지:** 화면이 "만들기" 대화상자를 열 때 UUID를 만들어 `Idempotency-Key`로 보낸다. 서버는 `content.creation_key`(UNIQUE)에 저장하고, 같은 키가 다시 오면 새로 만들지 않고 기존 콘텐츠를 200으로 돌려준다(첫 생성은 201). 키가 없으면 400. 같은 이슈에 새 콘텐츠를 더 만들려면 대화상자를 다시 열면 된다(새 키).

**동시 편집:** `PATCH`는 `version`이 현재 값과 같을 때만 저장한다. 다르면 409 `CONTENT_VERSION_CONFLICT`와 `{currentVersion, updatedBy, updatedAt}`. 화면은 자동저장을 멈추고 "다른 곳에서 먼저 저장됨" 안내와 두 선택지를 준다: ① 내 편집본을 이 브라우저에 보관한 채 최신 내용 불러오기(보관본은 옆에 표시해 복사 가능) ② 취소. 내 편집본은 충돌 시점에 `localStorage`(콘텐츠 id별)에 남긴다.

**자동저장:** 편집 내용이 마지막 저장본과 다를 때만, 입력이 멈추고 2초 뒤 저장(간격은 결정 필요). 저장 중·미저장 상태 표시. 상태 버튼은 미저장 변경이 있으면 먼저 저장하고, 성공한 version으로 상태 동작을 보낸다. 저장 실패 시 상태 동작을 보내지 않는다. 페이지를 떠날 때 미저장 변경이 있으면 경고.

**원천 이슈 변경(업데이트 필요 표시) — 제안 범위:**
- 판단: 원천 이슈의 `GREATEST(updated_at, last_updated_at)`가 콘텐츠의 `origin_checked_at`보다 늦거나, 이슈가 MERGED·EXCLUDED가 됐으면 "원천 이슈 변경됨".
- 표시: 콘텐츠 목록 배지·필터(`updateNeeded=true`), 상세 상단 안내(병합이면 대상 이슈 링크).
- 동작: 관리자가 "확인함"을 누르면 `origin_checked_at`을 지금으로 바꾸고 감사 기록. 본문·출처는 건드리지 않는다. 승인 상태도 바꾸지 않는다(본문이 바뀐 것이 아니므로).
- 이번에 하지 않음: 변경된 기사 목록 비교, 자동 재생성, 알림.

**원천 이슈 변경 표시(A6, 2026-10-15 구현) — 위 제안을 다음처럼 바꿔 구현:**
- 시각 비교(`updated_at`·`last_updated_at` vs `origin_checked_at`)는 쓰지 않는다. ① `now()`는 트랜잭션 **시작** 시각이라 확인 처리 뒤에 커밋된 변경이 더 이른 시각을 가질 수 있고 ② `last_updated_at`은 기사 **발행** 시각이라 예전 기사가 추가되면 움직이지 않으며 ③ `updated_at`은 중심 임베딩·집계 재계산에도 바뀐다.
- 대신 V7에서 `issue.change_seq`(줄어들지 않는 변경 번호)를 두고 DB 트리거가 올린다: 이슈의 제목·요약·카테고리·상태·병합 대상·핵심 사실·엔티티가 바뀔 때, 기사가 들어오거나·빠지거나·옮겨질 때(`issue_article` 추가·삭제·이슈 변경). 서비스·배치·직접 SQL 어느 경로든 같다. 임베딩·집계 재계산만으로는 오르지 않는다.
- 콘텐츠는 확인한 번호 `origin_ack_seq`를 가진다. **미확인 변경** = `issue.change_seq > content.origin_ack_seq`. 목록·필터·상세가 같은 SQL 식을 쓰고, `updateNeeded` 필터는 페이지 나누기 전에 쿼리에서 적용한다(행마다 추가 조회 없음).
- **상태 안내**는 따로: 원천 이슈가 지금 MERGED·EXCLUDED면 `notice`(병합이면 `mergedIntoIssueId`). 확인 처리와 무관하게 항상 보인다. 확인 처리는 미확인 표시만 지운다. `origin_issue_id`는 병합 대상으로 바꾸지 않는다.
- **확인 처리** `POST /contents/{id}/acknowledge-origin {version, originChangeSeq}`: 관리자가 본 번호(`originChange.issueChangeSeq`)를 보낸다. 잠금 순서는 콘텐츠 행(FOR UPDATE) → 원천 이슈(FOR SHARE). 이슈 변경이 먼저 커밋되면 번호가 달라 409 `CONTENT_ORIGIN_CHANGED`(현재 번호 등), 확인이 먼저면 이슈 변경이 기다렸다가 번호를 올려 다시 미확인이 된다 — 어느 쪽이든 변경을 놓치지 않는다. "현재 시각까지 확인"이 아니라 "본 번호까지 확인".
- 확인 처리는 내용 수정이 아니다: 제목·본문·출처·URL·상태·검수 기록 그대로, `ContentChanges`(승인 해제 경로)를 쓰지 않는다. version +1(편집 중인 화면이 다시 불러오도록), 감사 기록 `CONTENT_ORIGIN_ACKNOWLEDGED`(처리자, 확인한 번호, 원천 이슈·상태·병합 대상). 같은 번호를 다시 확인하면 아무것도 쓰지 않는다.
- **HOLD·EXCLUDED·ARCHIVED에서도 확인 처리 허용**(명세에 없음, 내용 변경과 구분한 정책: 확인 기록만 남기고 콘텐츠는 그대로이므로). 원천이 MERGED·EXCLUDED여도 검수 요청·승인은 막지 않는다(기존 정책).
- 기존 콘텐츠의 시작값(V7): 모든 이슈 번호는 0에서 시작. 콘텐츠를 만든·확인한 뒤 이슈 행이 바뀌었거나(`updated_at > origin_checked_at`) 이슈가 MERGED·EXCLUDED이면 `origin_ack_seq = -1`(미확인), 아니면 0. 확인하지 않은 과거 변경을 확인 완료로 만들지 않는다(시각 비교의 한계 때문에 V7 이전 변경 판정은 최선 추정이며, 놓치기보다 미확인으로 남기는 쪽).

**상세 조회 일관성(2026-10-15 보완):** `GET /contents/{id}`(그리고 모든 변경 응답의 `content`)는 콘텐츠 행·원천 이슈·출처·관련 URL을 **한 SQL 문**으로 읽는다(출처·링크는 `jsonb_agg` 하위 질의). PostgreSQL에서 한 문장은 한 스냅샷을 보므로 제목·본문·상태·version·출처·링크가 항상 같은 시점이다. 여러 문장을 READ COMMITTED 트랜잭션으로 묶는 것으로는 부족하다(문장마다 새 스냅샷). 변경 트랜잭션 안에서 부르면 그 트랜잭션이 쓴 내용을 본다. 추가 잠금 없음. 확인: `ContentDetailConsistencyIntegrationTest`(작성 스레드가 출처·링크를 150번 바꾸는 동안 읽기 3개가 매번 `version == (출처-1) + 링크`를 검사, 고치기 전 코드에서는 실패).

**A7 관리자 화면(2026-10-15 구현):**
- 목록 `/contents`: 유형·상태·제목 검색·"원천 이슈 변경 미확인만" 필터, 페이지, `?issueId=` 원천 이슈 필터. 행마다 제목(상세 링크), 상태, 원천 이슈(링크·상태·미확인/병합/제외 배지), 수정 시각·수정자.
- 생성: 이슈 상세의 "콘텐츠 만들기" — ACTIVE·CLOSED만 버튼, 아니면 이유(병합이면 병합 대상 링크). 생성 시도마다 `Idempotency-Key` 하나: 실패 후 "다시 시도"는 같은 키(서버가 이미 만든 콘텐츠를 돌려줌), 4xx(재시도해도 같은 결과)면 키를 버림, 다른 유형·새 생성은 새 키. 성공하면 편집 화면으로. 빈 본문으로 시작(기본 소제목 없음).
- 편집 `/contents/{id}`: 제목, 섹션(소제목+문단) 추가·수정·삭제·위아래 이동 — 모두 폼 입력(JSON 없음). 한도(제목 200, 섹션 30, 문단 50/섹션, 소제목 120, 문단 5,000, 합계 100,000자)를 카운터로 보이고 넘으면 저장 요청을 보내지 않음. 서버 위반(`details.violations`)은 해당 칸 아래에 표시. 화면 한도 값은 서버 Java 상수와 같은지 `verify:content-session`이 검사.
- 저장 상태 표시: 저장됨 / 저장 안 됨 / 저장 중 / 저장 실패(다시 저장 버튼) / 충돌.
- 역할: `GET /api/admin/me`로 실제 로그인 계정의 역할만 읽어 버튼을 고른다(서버가 다시 검사). 승인·복귀는 SYSTEM_ADMIN만 활성. 사유 필수 동작(반려·보류·제외·복귀)은 빈 사유로 실행 불가.
- HOLD·EXCLUDED·ARCHIVED는 편집·출처·URL 잠금. REVIEW·APPROVED는 "편집 시작"을 눌러 한 번 확인한 뒤에만 편집(저장되면 DRAFT로 돌아감을 그때 한 번 설명, 자동저장마다 묻지 않음).
- 출처: 4종 추가(이슈·기사는 기존 목록 API로 제목 검색해 선택, ACTIVE·CLOSED가 아닌 이슈는 선택 불가), 역할·설명 변경, 삭제(원천 이슈 출처는 삭제 버튼 없음, 역할은 변경 가능). 중복·잘못된 URL 등 서버 오류 코드를 그대로 보임.
- 관련 URL: 종류·제목·URL·표시 위치·사이트/블로그 노출, 추가·수정·삭제·위아래 이동(정렬 요청은 항상 전체 id 목록), `n / 500` 표시. "노출 설정은 저장만 되고 아직 어디에도 게시되지 않음" 안내. http(s)가 아닌 URL은 링크로 만들지 않음. 사용자 입력은 모두 텍스트로만 렌더링(HTML 해석 없음).
- 원천 이슈: 미확인 변경(번호·시각, "확인 처리는 내용·상태·승인을 바꾸지 않음" 설명, "변경 번호 N까지 확인 처리")과 MERGED·EXCLUDED 안내(병합 대상 링크)를 따로 보인다. 화면에 보인 번호만 보내고, 409 `CONTENT_ORIGIN_CHANGED`면 "원천 정보 다시 불러오기" 후 새 번호를 보고 다시 누르게 한다(최신 번호로 몰래 바꾸지 않음).

**자동저장·요청 순서·충돌(`admin-ui/src/lib/contentSession.ts`):**
- 콘텐츠마다 요청 큐 하나. 자동저장과 상태·출처·URL·확인 처리 요청이 한 번에 하나씩 순서대로 나가고, 각 요청은 바로 앞 성공 응답의 version을 쓴다(확인 처리의 version +1도 같은 큐).
- 저장은 마지막 입력 2초 뒤, 실제로 바뀐 칸만(`title`/`body`) 보낸다. 되돌려서 같아지면 보내지 않는다. 한글 조합(IME composition) 중에는 시작하지 않는다.
- 저장 중 입력은 그대로 두고 응답 뒤 다음 저장으로 보낸다. 응답이 편집기 내용을 덮지 않는다(jsonb 키 순서 차이는 같은 것으로 봄).
- 상태 버튼은 먼저 남은 편집을 저장하고, 저장이 실패·충돌하면 상태 요청을 보내지 않는다. 동작 중에는 편집기를 잠가 그 사이 입력이 승인 뒤에 저장돼 승인을 해제하는 일이 없게 한다. 버튼 연타는 한 번만 보낸다.
- 409 `CONTENT_VERSION_CONFLICT`: 자동저장·모든 요청 중지, 충돌 안내(서버 version·수정자·시각), 편집기 잠금, 내 초안 유지. "서버 최신본 보기"(둘을 나란히), "내 초안 복사", "최신본으로 다시 불러오기(내 초안은 보관)". 자동 덮어쓰기·자동 재시도 없음. 다시 불러온 뒤 보관본 복원은 사람이 고른다(최신본 위에 저장됨을 경고).
- 브라우저 보관본: `localStorage` 키 `content-draft:v1:{호스트|데이터 표시}:{계정}:{콘텐츠 id}`, 값은 제목·본문·기준 version·시각뿐(인증 정보 없음). 저장 안 된 편집이 있으면 쓰고, 저장되면 지움. 서버와 다른 보관본이 있으면 열 때 비교 화면과 복원/버리기를 보이고 고를 때까지 편집 잠금. 저장소 오류는 화면에 표시.
- 페이지 이탈: 저장 안 된 편집·저장 중·충돌이면 `beforeunload`로 막고, 앱 안 링크는 확인 창을 띄운다.
- 브라우저 뒤로 가기(2026-10-16 보완, `admin-ui/src/lib/backGuard.ts`): 저장 안 된 것이 생기면 같은 주소의 기록 항목을 하나 더 쌓는다. 뒤로 가기는 그 항목에서만 내려오고(화면 그대로) 확인 창을 띄운다 — 머무르면 항목을 다시 쌓고, 떠나면 뒤로 가기를 이어서 한다. 그 사이 저장이 끝났으면 묻지 않고 한 번에 이전 화면으로 간다. 기록 메뉴로 여러 칸을 건너뛰거나 앞으로 가기로 다른 화면에 가는 것은 이미 일어난 이동이라 막지 않는다(보관본에 남음).
- 앱 안에서 떠날 때(링크·뒤로 가기, 확인 후) 2초를 기다리던 편집을 바로 저장 요청한다. 실패하면 보관본이 남아 다시 열 때 복원할 수 있다.

**A8 공개 분리 확인(2026-10-15):** 승인(APPROVED) 콘텐츠가 있는 검증 DB에서 `/api/public/issues`(목록·상세·카테고리)와 뉴스룸 `/news`, `/news/{id}`에 콘텐츠 제목·본문·URL이 나오지 않음을 브라우저로 확인. `/api/public/contents`는 경로가 없어 403. 공개 API 코드는 `content*` 테이블을 읽지 않는다.

**뉴스룸과 분리:** 공개 API(`/api/public/*`)는 지금처럼 이슈 테이블만 읽고 콘텐츠 테이블을 읽지 않는다. 승인된 콘텐츠가 있어도 공개 응답이 같다는 회귀 테스트를 넣는다. 콘텐츠 API는 `/api/admin/**`라 기존 인증 규칙을 그대로 받는다.

## 6. 작업 순서와 파일

| 순서 | 작업 | 변경 파일 | API | 검증 기준 | 의존 |
|---|---|---|---|---|---|
| A1 (구현됨) | 스키마 V4, V5 | `db/migration/V4__content.sql` | - | 빈 DB·기존 DB(V3) 모두 적용, CHECK·UNIQUE 위반 시 실패하는 통합 테스트, 기존 테스트 전부 통과 | 없음 |
| A2 (구현됨) | 생성·조회·편집 | `content/ContentRepository`, `ContentService`, `ContentBody`, `ContentBodyValidator`, `admin/ContentAdminController`, `AdminRequests`(요청 DTO), `ErrorCode`(CONTENT_*) | `POST /api/admin/issues/{id}/contents`, `GET /api/admin/contents`, `GET/PATCH /api/admin/contents/{id}` | 본문 규칙 단위 테스트, 이슈 상태별 생성 허용·거부, 같은 키 재요청 시 1건, version 충돌 409, 비로그인 401, 감사 기록 | A1 |
| A3 (구현됨) | 상태 전이 | `content/ContentStatusPolicy`, `ContentService` 상태 메서드 | `POST /api/admin/contents/{id}/{submit,approve,return,hold,resume,exclude,archive,restore}` | 표 전체 단위 테스트(허용·거부·역할), DRAFT 직접 승인 거부, 편집 시 승인 해제, version 불일치 409 | A2 |
| A4 (구현됨) | 출처 | `content/ContentSourceRepository`, 서비스·컨트롤러 | `GET/POST/DELETE /api/admin/contents/{id}/sources` | 생성 시 원천 이슈를 PRIMARY로 자동 등록, 유형별 칸 검증, 중복 거부, 변경 시 승인 해제 | A2 (A3과 병행 가능) |
| A5 (구현됨) | 관련 URL | `content/ContentLinkRepository`, 서비스·컨트롤러 | `POST/PATCH/DELETE /api/admin/contents/{id}/links` | 잘못된 URL 거부, 사이트만·블로그만·둘 다 저장(명세 09 §7), 변경 시 승인 해제 | A2 (병행 가능) |
| A6 (구현됨) | 원천 이슈 변경 표시 | 조회 쿼리, `acknowledge` 동작 | `GET /api/admin/contents?updateNeeded=true`, `POST /api/admin/contents/{id}/acknowledge-origin` | 기사 추가·병합 후 표시, 확인 후 해제, 본문 불변 | A2 |
| A7 | 관리자 화면 | `admin-ui/src/lib/api.ts`, `app/contents/page.tsx`(목록: 유형 탭·상태·업데이트 필요 필터·검색), `app/contents/[id]/page.tsx`(편집: 섹션·문단 추가/삭제/순서, 자동저장, 충돌 안내, 상태 버튼, 출처, 관련 URL), `app/issues/[id]/page.tsx`("NEWS/LIFE 콘텐츠 만들기"), 메뉴 | 위 API | 타입 검사·빌드, 로컬 검증 모드에서 브라우저로 생성→편집→검수 요청→승인→수정 시 DRAFT 복귀→충돌 안내 확인 | A3~A6 |
| A8 | 분리 확인·문서 | 공개 API 회귀 테스트, `docs/API.md`, `DECISIONS.md` | - | 승인 콘텐츠가 있어도 `/api/public/*` 응답 동일 | A7 |

외부 라이브러리 추가 없음(JSON은 기존 Jackson). 유료 AI 호출 없음.

## 7. 추가로 결정할 것

1. ~~승인 권한~~ → **결정(10-15):** SYSTEM_ADMIN만, 본인 작성분 승인 허용(초기 운영).
2. ~~REVIEW 중 편집~~ → **결정(10-15):** 실제 내용이 바뀌면 DRAFT로.
3. ~~관련 URL 표시 위치 값~~ → **결정(10-15):** BODY_TOP · BODY_BOTTOM(기본) · SOURCES.
4. **크기 한도:** 섹션 30·문단 50·문단 5,000자·전체 100,000자·제목 200자 제안값 확인.
5. **자동저장 간격:** 제안 2초.
6. **LIFE 수동 생성 유지:** B 묶음(LIFE 판정) 이후에도 판정 없이 LIFE를 만들 수 있게 둘지.
7. ~~기본 소제목 자동 채움~~ → **결정(2026-10-23, 사용자):** 수동 NEWS·LIFE 생성은 빈 제목·빈 본문으로 시작하고, 기본 소제목 자동 채움은 수동 생성 범위에 넣지 않는다. AI 초안 생성의 본문 구조는 C 묶음에서 별도로 정한다. 확인한 근거: 구현(`ContentService.create`의 `emptyBody()`), `API.md`(title·body 생략 시 빈 본문), 화면 안내("제목·본문은 비어 있는 상태로 시작"), 통합 테스트(생성 직후 `sections` 0개)가 모두 빈 본문 기준이고, 검수 요청에는 모든 섹션에 소제목과 비지 않은 문단이 필요해 미리 채운 섹션 중 쓰지 않는 것은 작성자가 지워야 한다.
8. **원천 이슈가 EXCLUDED·MERGED일 때 검수 요청·승인:** 지금은 계획대로 막지 않고 상세에서 보이기만 한다. 막을지(또는 경고만 할지) 결정 필요.
9. **본인 승인 허용의 종료 시점:** 운영 인원이 늘면 작성자와 승인자를 분리할지.
10. **원천 이슈 출처 삭제:** 지금은 삭제 불가(역할 변경만). 원천 이슈를 잘못 고른 콘텐츠는 새로 만들어야 한다. 원천 이슈를 바꾸는 별도 동작이 필요하면 결정 필요.
11. **같은 URL의 관련 링크 중복:** 지금은 허용(같은 지도 링크를 위·아래에 둘 수 있음). 막을지 결정 필요.
12. **원천 변경 내용 보기:** 지금은 "바뀌었음"과 번호·시각·상태만 준다. 무엇이 바뀌었는지(기사 목록 비교, 제목 전후)는 A7 이후 결정.
