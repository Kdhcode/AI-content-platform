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
응답 `JobView`: `id, jobType, status, payload, result, retryCount, maxRetries, errorCode, errorMessage, requestedBy, requestedAt, startedAt, finishedAt, correlationId`.
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
응답: 목록 필드 + `summarySource`(AUTO|MANUAL), `keyFacts[]`, `entities[]`, `mergedFromIssueIds[]`, `createdAt`, `articles[]`(기사 id/제목/언론사/발행시각, 연결 방식, LLM 판단·신뢰도·사유, 유사도, 수동 수정 여부).
오류: 404 `ISSUE_NOT_FOUND`.

### `PATCH /issues/{id}` — 수정 (OPERATOR+) · 동기
본문(전부 선택, 보낸 필드만 변경): `{ "title": "≤300자", "summary": "≤5000자", "category": "≤50자", "status": "ACTIVE|REVIEW|CLOSED|EXCLUDED", "reason": "≤500자" }`.
- `summary`를 보내면 `summarySource=MANUAL`이 되어 이후 자동 갱신하지 않음.
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

## 작업 (ASYNC_JOB)

### `GET /jobs` — 목록 (OPERATOR+)
쿼리: `status`(PENDING·RUNNING·SUCCESS·FAILED·CANCELLED), `type`(COLLECT_NEWS·ANALYZE_ARTICLE·EMBED_ARTICLE·CLASSIFY_ARTICLE), `articleId`, `page`, `size`, `sort`(`id`·`requestedAt`·`finishedAt`; 기본 `id desc`). 응답 `items[]`는 `JobView`.

### `GET /jobs/{id}` (OPERATOR+) — `JobView`. 오류 404 `JOB_NOT_FOUND`.

### `POST /jobs/{id}/retry` (SYSTEM_ADMIN) · 동기
FAILED → PENDING(재시도 횟수 추가 부여). 응답 `JobView`. 오류: 404 `JOB_NOT_FOUND`, 409 `JOB_NOT_RETRYABLE`(FAILED가 아니거나, 같은 대상의 작업이 이미 대기/실행 중).

### `POST /jobs/{id}/cancel` (SYSTEM_ADMIN) · 동기
PENDING만 취소. RUNNING 작업은 끝까지 실행됩니다. 응답 `JobView`. 오류: 404 `JOB_NOT_FOUND`, 409 `JOB_NOT_CANCELLABLE`.

---

## 소스

### `GET /sources` (OPERATOR+)
응답 `data[]`: `id, name, type, baseUrl, enabled, status(ACTIVE|DEGRADED|DISABLED), collectionIntervalSeconds, lastAttemptAt, lastSuccessAt, failureCount, lastError`. `config`는 자격증명이 들어갈 수 있어 반환하지 않음.

### `POST /sources/{id}/collect` (SYSTEM_ADMIN) · **비동기 202**
지금 바로 `COLLECT_NEWS` 작업을 등록. 응답 `JobView`. 오류: 404 `SOURCE_NOT_FOUND`.

---

## 헬스
`GET /actuator/health` — 인증 불필요. Redis 상태는 포함하지 않음(Redis는 선택 사항).
