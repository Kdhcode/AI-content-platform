# 관리자 API (Phase 1)

기준 경로 `/api/admin`. 전부 JSON, 인증 필수(HTTP Basic). 구현: `backend/.../admin/*Controller.java`. 코드 검증 상태는 [VERIFY.md](VERIFY.md) 참고(컨트롤러는 아직 실행해 보지 못함).

## 공통

**응답 형식** — 성공/실패 모두 같은 봉투
```json
{ "success": true,  "data": { }, "error": null }
{ "success": false, "data": null, "error": { "code": "ISSUE_NOT_FOUND", "message": "이슈를 찾을 수 없습니다.", "details": null } }
```

**목록 응답(`data`)** — `{ "items": [], "totalElements": 0, "totalPages": 0, "page": 0, "size": 20 }`

**페이지 파라미터** — `page`(0부터, 기본 0), `size`(1~100, 기본 20), `sort`(`키,asc|desc`, 허용 키만, 기본 정렬은 각 목록에 표기). 범위를 벗어나면 400 `VALIDATION_ERROR`. 정렬은 항상 `id`를 보조 키로 붙여 페이지가 흔들리지 않음.

**헤더** — 요청 `X-Correlation-Id`(선택, `[A-Za-z0-9._-]{1,64}`)를 받거나 서버가 생성해서 응답 헤더로 돌려주며, 로그·`async_job.correlation_id`·감사 로그에 남음.

**권한(역할)**
| 역할 | 가능한 것 |
|---|---|
| `OPERATOR` | 모든 조회, 기사 재분석/이동, 이슈 수정/병합/분리 |
| `SYSTEM_ADMIN` | OPERATOR의 모든 것 + 작업 재시도/취소, 소스 수동 수집 |

미인증 401 `UNAUTHORIZED`, 권한 부족 403 `FORBIDDEN`, 잘못된 경로 404 `NOT_FOUND`, 잘못된 메서드 405 `METHOD_NOT_ALLOWED`, 예기치 못한 오류 500 `INTERNAL_ERROR`(상세는 로그에만).

**비동기 규칙** — `202 Accepted`를 돌려주는 API는 즉시 `async_job`을 등록하고 `JobView`를 반환합니다. 결과는 `GET /jobs/{id}`(또는 대상 상세)로 확인. 같은 대상의 작업이 이미 PENDING/RUNNING이면 새로 만들지 않고 기존 작업을 돌려줍니다.

**오류 코드**
| code | HTTP | 의미 |
|---|---|---|
| VALIDATION_ERROR | 400 | 파라미터/본문 검증 실패, 지원하지 않는 정렬 키 등 |
| UNAUTHORIZED / FORBIDDEN | 401 / 403 | 인증 필요 / 권한 없음 |
| ARTICLE_NOT_FOUND, ISSUE_NOT_FOUND, SOURCE_NOT_FOUND, JOB_NOT_FOUND | 404 | 대상 없음 |
| ISSUE_MERGE_SELF | 400 | 자기 자신에 병합 |
| ARTICLE_NOT_ANALYZABLE | 409 | 재분석 불가 기사(DUPLICATE) |
| ARTICLE_NOT_IN_ISSUE | 409 | 기사가 해당 이슈의 멤버가 아니거나 어떤 이슈에도 없음 |
| ISSUE_STATE_INVALID | 409 | 현재 이슈 상태에서 불가(MERGED 수정, 닫힌 이슈 병합 등) |
| ISSUE_MERGE_SOURCE_INVALID | 409 | 병합 원본 이슈가 ACTIVE/REVIEW가 아님 |
| ISSUE_SPLIT_WOULD_EMPTY | 409 | 분리 후 원본에 기사가 남지 않음 |
| JOB_NOT_RETRYABLE / JOB_NOT_CANCELLABLE | 409 | FAILED가 아닌 작업 재시도 / PENDING이 아닌 작업 취소 |
| INTERNAL_ERROR | 500 | 서버 오류 |

---

## 기사

### `GET /articles` — 목록 (OPERATOR+)
쿼리: `status`(COLLECTED·ANALYSIS_PENDING·ANALYZED·DUPLICATE·FAILED), `classificationStatus`(NOT_CLASSIFIED·CLASSIFIED·REVIEW·FAILED), `sourceId`, `issueId`, `q`(제목 부분 일치, 대소문자 무시), `publisher`(정확히 일치, 대소문자 무시), `page`, `size`, `sort`(`id`·`publishedAt`·`collectedAt`; 기본 `collectedAt desc`).
응답 `items[]`: `id, title, publisherName, publishedAt, collectedAt, status, classificationStatus, sourceId, issueId, issueStatus`.

### `GET /articles/{id}` — 상세 (OPERATOR+)
응답: 기사 필드 + `analysis`(검증된 AI 분석 JSON: `summary, keyFacts[], entities[{name,type}], category, eventType, multiEvent?, confidence`), `analysisError`, `hasEmbedding`, 현재 연결 `issue`(이슈 id/제목/상태, 연결 방식, LLM 판단·신뢰도·사유, 유사도, 수동 수정 여부), `classificationRuns[]`(최근 20건: 결정, LLM 원판단, 방식, 신뢰도, 사유, 후보 목록과 유사도, 적용 여부).
오류: 404 `ARTICLE_NOT_FOUND`.

### `POST /articles/{id}/reanalyze` — 재분석 (OPERATOR+) · **비동기 202**
본문 없음. `ANALYZE_ARTICLE` 작업을 등록하고 이후 임베딩·분류가 이어집니다(이미 연결된 기사는 분류 결과가 *기록만* 됨).
응답 `JobView`: `id, jobType, status, payload, result, retryCount, maxRetries, deferCount, errorCode, errorMessage, requestedBy, requestedAt, startedAt, finishedAt, correlationId`.
오류: 404 `ARTICLE_NOT_FOUND`, 409 `ARTICLE_NOT_ANALYZABLE`(DUPLICATE).

### `POST /articles/{id}/move` — 다른 이슈로 이동 (OPERATOR+) · 동기
본문 `{ "targetIssueId": 12, "reason": "선택, ≤500자" }` (`targetIssueId` 필수·양수).
응답 `{ articleId, fromIssueId, toIssueId }`. 두 이슈 집계를 다시 계산하고 감사 로그를 남깁니다. 원래 이슈가 비면 CLOSED.
오류: 400 `VALIDATION_ERROR`(이미 그 이슈), 404 `ARTICLE_NOT_FOUND`/`ISSUE_NOT_FOUND`, 409 `ARTICLE_NOT_IN_ISSUE`/`ISSUE_STATE_INVALID`(대상이 ACTIVE/REVIEW가 아님).

---

## 이슈

### `GET /issues` — 목록 (OPERATOR+)
쿼리: `status`(ACTIVE·REVIEW·MERGED·CLOSED·EXCLUDED), `category`, `q`(제목/요약 부분 일치), `page`, `size`, `sort`(`id`·`lastUpdatedAt`·`firstPublishedAt`·`articleCount`·`createdAt`; 기본 `lastUpdatedAt desc`).
응답 `items[]`: `id, title, summary, category, status, articleCount, publisherCount, firstPublishedAt, lastUpdatedAt, mergedIntoIssueId`.

### `GET /issues/{id}` — 상세 (OPERATOR+)
응답: 목록 필드 + `titleSource`(AUTO|MANUAL, 2026-10-13), `summarySource`(AUTO|MANUAL), `keyFacts[]`, `entities[]`, `mergedFromIssueIds[]`, `createdAt`, `articles[]`(기사 id/제목/언론사/발행시각, 연결 방식, LLM 판단·신뢰도·사유, 유사도, 수동 수정 여부).
오류: 404 `ISSUE_NOT_FOUND`.

### `PATCH /issues/{id}` — 수정 (OPERATOR+) · 동기
본문(전부 선택, 보낸 필드만 변경): `{ "title": "≤300자", "summary": "≤5000자", "category": "≤50자", "status": "ACTIVE|REVIEW|CLOSED|EXCLUDED", "reason": "≤500자" }`.
- `summary`를 보내면 `summarySource=MANUAL`이 되어 이후 자동 갱신하지 않음. `title`도 같음(`titleSource=MANUAL`). AUTO 제목은 멤버십이 바뀔 때 가장 이른 기사 제목으로 맞춰짐.
- `REVIEW → ACTIVE`는 검수 확정: 소속 기사들의 `classificationStatus`가 `CLASSIFIED`로 바뀜.
- `status=MERGED`는 직접 지정 불가(병합 API 사용).
응답: 수정 후 이슈 상세.
오류: 400 `VALIDATION_ERROR`, 404 `ISSUE_NOT_FOUND`, 409 `ISSUE_STATE_INVALID`(MERGED 이슈 수정, 기사 없는 이슈를 ACTIVE/REVIEW로).

### `POST /issues/{id}/merge` — 병합 (OPERATOR+) · 동기
**경로의 id가 남는 이슈(target)**. 본문 `{ "sourceIssueIds": [3, 4], "targetIssueId": 선택, "reason": "선택" }` — `targetIssueId`를 보내면 경로 id와 같아야 합니다. `sourceIssueIds`는 1~50개.
동작: 원본의 모든 기사를 target으로 옮기고 원본은 `MERGED`(삭제 안 함), 모든 이슈 집계 재계산, 감사 로그.
응답 `{ targetIssueId, mergedIssueIds[], movedArticleIds[] }`.
오류: 400 `VALIDATION_ERROR`/`ISSUE_MERGE_SELF`, 404 `ISSUE_NOT_FOUND`, 409 `ISSUE_STATE_INVALID`(target이 ACTIVE/REVIEW가 아님)/`ISSUE_MERGE_SOURCE_INVALID`(원본이 이미 MERGED/CLOSED 등). 실패 시 아무것도 바뀌지 않음(단일 트랜잭션).

### `POST /issues/{id}/split` — 분리 (OPERATOR+) · 동기
본문 `{ "articleIds": [10, 11], "newTitle": "선택, ≤300자(없으면 첫 기사 제목)", "reason": "선택" }` — `articleIds` 1~200개, 모두 이 이슈의 기사여야 하고 원본에 1건 이상 남아야 함.
응답 `{ sourceIssueId, newIssueId, movedArticleIds[] }`. 새 이슈는 ACTIVE.
오류: 400 `VALIDATION_ERROR`, 404 `ISSUE_NOT_FOUND`, 409 `ARTICLE_NOT_IN_ISSUE`/`ISSUE_SPLIT_WOULD_EMPTY`/`ISSUE_STATE_INVALID`.

---

## 콘텐츠 (NEWS·LIFE, A2~A5 — 수동 작성·검수·출처·관련 URL)

관리자 전용(OPERATOR+). 공개 API(`/api/public/*`)와 뉴스룸은 콘텐츠를 읽지 않으며, 승인돼도 자동 공개되지 않는다. 본문 형식·한도는 `docs/CONTENT_A_PLAN.md` 2절.

### `POST /issues/{issueId}/contents` — 수동 초안 생성 (OPERATOR+) · 동기
헤더 `Idempotency-Key` 필수(영문·숫자·`._:-` 8~100자, 대화상자를 열 때마다 새로 만듦). 본문 `{contentType: NEWS|LIFE, title?, body?}`(title·body 생략 시 빈 제목·빈 본문 `{"schemaVersion":1,"sections":[]}`).
- 201: 새 DRAFT(`version` 0). 원천 이슈가 PRIMARY 출처로 같은 트랜잭션에서 등록되고 감사 기록 `CONTENT_CREATE`.
- 200 + `Idempotent-Replayed: true`: 같은 키·같은 요청(이슈·유형·제목·본문, JSON 키 순서 무관)의 재요청 → 처음 만든 콘텐츠. 동시 재요청도 콘텐츠·출처·감사 기록은 1건.
- 409 `IDEMPOTENCY_KEY_CONFLICT`(`details.contentId`): 같은 키로 다른 요청.
- 409 `ISSUE_STATE_INVALID`: 이슈가 REVIEW·EXCLUDED·MERGED(MERGED면 병합 대상 이슈 번호 안내). 404 `ISSUE_NOT_FOUND`.
- 400 `VALIDATION_ERROR`(키 없음·형식, 유형, 제목 200자 초과·줄바꿈), 400 `CONTENT_BODY_INVALID`(`details.violations[]: {path, message}`, 최대 20개).

### `GET /contents` — 목록 (OPERATOR+)
`type`(NEWS|LIFE), `status`(DRAFT|REVIEW|APPROVED|HOLD|EXCLUDED|ARCHIVED), `issueId`, `q`(제목 검색, 200자 이하), `page`, `size`, `sort`(`updatedAt|createdAt|title|id`, 기본 최근 수정순). 항목: `id, contentType, status, title, version, originIssueId, originIssueTitle, originIssueStatus, updatedBy, updatedAt`.

### `GET /contents/{id}` — 상세 (OPERATOR+)
`id, contentType, status, title, body, version, originIssue{id, title, status, mergedIntoIssueId}, originCheckedAt, sources[]{id, kind, issueId, newsArticleId, externalUrl, label, role, checkedAt}, links[]{id, linkType, title, url, siteVisible, blogVisible, displayPosition, sortOrder, createdAt, updatedAt}, statusReason, createdBy/At, updatedBy/At, submittedAt, approvedBy/At`. 404 `CONTENT_NOT_FOUND`.

### `PATCH /contents/{id}` — 제목·본문 저장 (OPERATOR+) · 동기
본문 `{version, title?, body?}` — 생략한 값은 유지, 둘 다 없으면 400. `version`은 편집기가 불러온 값.
- 200 `{content, changed, statusChanged, itemId}`: 저장되면 `version`+1, 감사 기록 `CONTENT_UPDATE`. REVIEW·APPROVED 콘텐츠는 DRAFT로 돌아가고(`statusChanged: true`, 승인·검수 요청 기록 지움) APPROVED였으면 `CONTENT_APPROVAL_REVOKED_BY_EDIT`도 기록. 값이 같으면 아무것도 바꾸지 않음(version 그대로).
- 409 `CONTENT_VERSION_CONFLICT`: `details{currentVersion, sentVersion, updatedBy, updatedAt}` — 다시 불러와 확인. 검사와 저장은 행 잠금 안에서 함께 처리.
- 409 `CONTENT_STATE_INVALID`: HOLD·EXCLUDED·ARCHIVED(먼저 `resume`·`restore`).
- 400 `CONTENT_BODY_INVALID` / `VALIDATION_ERROR`.

### 상태 동작 `POST /contents/{id}/{submit|approve|return|hold|resume|exclude|archive|restore}` (A3) · 동기
본문 `{version, reason?}`(사유는 앞뒤 공백 제거 후 1~500자). 응답은 바뀐 상세(version +1). 상태는 이 동작으로만 바뀌고 `PATCH`는 `status`를 받지 않는다. 전이·권한표는 `docs/CONTENT_A_PLAN.md` 4절.

| 동작 | 시작 → 결과 | 역할 | 사유 |
|---|---|---|---|
| `submit` 검수 요청 | DRAFT → REVIEW | OPERATOR+ | 선택 |
| `approve` 승인 | REVIEW → APPROVED | **SYSTEM_ADMIN**(본인 작성분 포함) | 선택 |
| `return` 반려 | REVIEW → DRAFT | OPERATOR+ | 필수 |
| `hold` 보류 | DRAFT·REVIEW·APPROVED → HOLD | OPERATOR+ | 필수 |
| `resume` 보류 해제 | HOLD → DRAFT | OPERATOR+ | 선택 |
| `exclude` 제외 | DRAFT·REVIEW·HOLD → EXCLUDED | OPERATOR+ | 필수 |
| `archive` 보관 | APPROVED·HOLD → ARCHIVED | OPERATOR+ | 선택 |
| `restore` 복귀 | EXCLUDED·ARCHIVED → DRAFT | **SYSTEM_ADMIN** | 필수 |

오류: 403 `FORBIDDEN`(역할), 409 `CONTENT_STATE_INVALID`(`details{status, action, allowedFrom}` — 예: DRAFT에서 approve), 409 `CONTENT_VERSION_CONFLICT`(`details{currentVersion, sentVersion, updatedBy, updatedAt, status}`), 422 `CONTENT_NOT_READY`(submit·approve: `details.violations[]` — 제목, 섹션·소제목·문단, PRIMARY 출처), 400 `VALIDATION_ERROR`(version 없음, 필수 사유 없음, 사유 500자 초과), 404 `CONTENT_NOT_FOUND`. 감사 기록 `CONTENT_<동작>`(처리자, 전후 상태·version, 사유). 승인돼도 공개 API·뉴스룸에는 나오지 않는다.

### 출처 (A4) — `.../contents/{id}/sources` (OPERATOR+)
- `GET` — 목록(PRIMARY·SUPPORTING·REFERENCE 순, 같은 역할은 id 순).
- `POST` `{version, kind: ISSUE|NEWS_ARTICLE|EXTERNAL_URL|MANUAL, issueId?, newsArticleId?, externalUrl?, label?, role?: PRIMARY|SUPPORTING(기본)|REFERENCE}` — 201.
- `PATCH /{sourceId}` `{version, role?, label?}` — label `""`은 지움(MANUAL은 불가).
- `DELETE /{sourceId}?version=N` — 원천 이슈 출처는 409 `CONTENT_SOURCE_PROTECTED`.
- 오류: 400 `VALIDATION_ERROR`(종류와 맞지 않는 칸, URL, 빈 설명, 역할), 404 `ISSUE_NOT_FOUND`·`ARTICLE_NOT_FOUND`(참조 대상 없음), 404 `CONTENT_SOURCE_NOT_FOUND`(이 콘텐츠의 출처가 아님), 409 `ISSUE_STATE_INVALID`(ACTIVE·CLOSED 아님), 409 `CONTENT_SOURCE_INVALID`(중복 기사), 409 `CONTENT_SOURCE_DUPLICATE`.

### 관련 URL (A5) — `.../contents/{id}/links` (OPERATOR+)
- `GET` — 목록(정렬 순서, 같으면 id 순).
- `POST` `{version, linkType, title?, url, siteVisible?(true), blogVisible?(false), displayPosition?: BODY_TOP|BODY_BOTTOM(기본)|SOURCES}` — 201, 맨 뒤에 추가.
- `PATCH /{linkId}` `{version, linkType?, title?, url?, siteVisible?, blogVisible?, displayPosition?}` — 생략한 값 유지.
- `DELETE /{linkId}?version=N`.
- `PUT /order` `{version, linkIds: [이 콘텐츠의 링크 id 전부, 한 번씩]}` — 중복·모르는 id·빠진 id가 있으면 400 `details{duplicateIds, unknownIds, missingIds}`, 아무것도 바뀌지 않음.
- **한도: 콘텐츠당 500개.** 501번째 `POST`는 409 `CONTENT_LINK_LIMIT_EXCEEDED`(`details{limit, current}`, 콘텐츠 행 잠금 안에서 세므로 동시 추가도 넘지 못함). 정렬 `linkIds`도 최대 500개(넘으면 400).
- 오류: 400 `VALIDATION_ERROR`, 409 `CONTENT_LINK_LIMIT_EXCEEDED`, 404 `CONTENT_LINK_NOT_FOUND`(이 콘텐츠의 링크가 아님).

**출처·URL 공통 응답** `{content(상세: 새 version·상태·sources·links), changed, statusChanged, itemId}`. 실제 변경이 있을 때만 version +1·감사 기록(`CONTENT_SOURCE_ADD|UPDATE|REMOVE`, `CONTENT_LINK_ADD|UPDATE|REMOVE`, `CONTENT_LINKS_REORDER`), REVIEW·APPROVED는 DRAFT로(`statusChanged: true`, APPROVED면 `CONTENT_APPROVAL_REVOKED_BY_EDIT`). 같은 값이면 `changed: false`로 아무것도 쓰지 않음. HOLD·EXCLUDED·ARCHIVED는 409 `CONTENT_STATE_INVALID`, version 불일치는 409 `CONTENT_VERSION_CONFLICT`. 노출 설정은 저장만 하며 공개 API·뉴스룸에는 나오지 않는다.

---

## 작업 (ASYNC_JOB)

### `GET /jobs` — 목록 (OPERATOR+)
쿼리: `status`(PENDING·RUNNING·SUCCESS·FAILED·CANCELLED), `type`(COLLECT_NEWS·ANALYZE_ARTICLE·EMBED_ARTICLE·CLASSIFY_ARTICLE), `articleId`, `page`, `size`, `sort`(`id`·`requestedAt`·`finishedAt`; 기본 `id desc`). 응답 `items[]`는 `JobView`.

### `GET /jobs/{id}` (OPERATOR+) — `JobView`. 오류 404 `JOB_NOT_FOUND`.

`deferCount`는 작업을 실행하지 못하고 되돌린 횟수입니다(현재는 분류 락 대기 실패 `LOCK_TIMEOUT`). `retryCount`와 별개이며 재시도 횟수를 쓰지 않습니다. `app.worker.max-defers`(기본 20)를 넘으면 FAILED가 됩니다.

### `POST /jobs/{id}/retry` (SYSTEM_ADMIN) · 동기
FAILED → PENDING(재시도 횟수 추가 부여). 응답 `JobView`. 오류: 404 `JOB_NOT_FOUND`, 409 `JOB_NOT_RETRYABLE`(FAILED가 아니거나, 같은 대상의 작업이 이미 대기/실행 중).

### `POST /jobs/{id}/cancel` (SYSTEM_ADMIN) · 동기
PENDING만 취소. RUNNING 작업은 끝까지 실행됩니다. 응답 `JobView`. 오류: 404 `JOB_NOT_FOUND`, 409 `JOB_NOT_CANCELLABLE`.

---

## AI 사용량

### `GET /ai-usage` (OPERATOR+)
쿼리: `days`(1~90, 기본 7. 범위 밖이거나 숫자가 아니면 400 `VALIDATION_ERROR`). 오늘을 포함한 최근 `days`일을 한국 시간(KST) 자정 기준으로 집계합니다.
응답 `data`: `days`, `rows[]`, `totals`, `limits`.
- `rows[]`: `day`(KST 날짜 `YYYY-MM-DD`), `callType`(ARTICLE_ANALYSIS·EMBEDDING·ISSUE_CLASSIFY), `provider`, `model`, `calls`, `failures`, `inputTokens`, `outputTokens`, `costUsd`, `unpricedCalls`. 정렬은 `day` 내림차순, `callType`, `model`.
- `totals`: `calls, failures, inputTokens, outputTokens, costUsd, unpricedCalls`.
- `costUsd`는 `ai_log.estimated_cost_usd`의 합이며, 비용이 기록된 호출이 하나도 없으면 0이 아니라 `null`입니다. `unpricedCalls`는 토큰은 있는데 단가가 없어 비용을 계산하지 못한 호출 수입니다(`app.ai.pricing` 미설정).
- `limits`(2026-10-13): `app.ai.limits` 설정과 오늘 사용량. `dailyBudgetUsd`, `dailyMaxCalls`, `maxCallsPerMinute`(꺼진 한도는 `null`), `timeZone`(하루의 기준), `callsToday`, `costToday`(단가 없는 호출은 0으로 합산), `callsLastMinute`, `blockedBy`(지금 걸려 있는 한도 코드 `AI_DAILY_BUDGET_REACHED`·`AI_DAILY_CALL_LIMIT_REACHED`·`AI_RATE_LIMITED`, 없으면 `null`). 기간 `days`와 무관하게 항상 오늘 기준입니다.
- 모델 응답 원문과 오류 메시지는 포함하지 않습니다. 이 API는 조회 전용입니다. 한도 적용은 AI 호출 직전에 서버가 하고(`AiUsageLimiter`), 한도는 설정으로만 바꿉니다.

---

## 소스

### `GET /sources` (OPERATOR+)
응답 `data[]`: `id, name, type, baseUrl, enabled, status(ACTIVE|DEGRADED|DISABLED), collectionIntervalSeconds, lastAttemptAt, lastSuccessAt, failureCount, lastError`. `config`는 자격증명이 들어갈 수 있어 반환하지 않음.

### `POST /sources/{id}/collect` (SYSTEM_ADMIN) · **비동기 202**
지금 바로 `COLLECT_NEWS` 작업을 등록. 응답 `JobView`. 오류: 404 `SOURCE_NOT_FOUND`.

---

## 헬스
`GET /actuator/health` — 인증 불필요. Redis 상태는 포함하지 않음(Redis는 선택 사항).
