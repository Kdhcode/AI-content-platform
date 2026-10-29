# LIFE 후보 판정 B 묶음 설계 — B1 판정 저장 구조

> **출처:** 이 문서는 PR #9(`claude/life-b-plan`, 미병합)의 `docs/LIFE_B_PLAN.md`를 `62e84b7`에서 가져와 B1 구현 PR #11(`claude/life-b1`)에서 갱신한 것이다. PR #9 브랜치 전체나 그쪽 `TASK.md`는 가져오지 않았다. PR #9는 2026-10-23 설계 기록으로 그대로 두며, **현재 설계·구현 기준은 이 문서(PR #11)**다. 원래 문장 중 바뀐 것은 지우지 않고 ~~취소선~~과 정정 표시로 남겼다.

## 진행 상태

| 작업 | 상태 | 비고 |
|---|---|---|
| B1 판정 저장 구조 | **구현(2026-10-29, PR #11)** — V8·저장소·서비스·통합 시험. 실제 DB 적용 전 ~~설계(2026-10-23) — 이 문서. V8 구현·실제 DB 적용 전~~ | 범위·순서는 [NEXT_CONTENT_SCOPE.md](NEXT_CONTENT_SCOPE.md) 4절 B |
| B2 `life-classifier` 프롬프트·스키마 | 미착수 | 유료 호출 없는 stub·모의 서버 테스트부터. `ai_log.call_type` 제약 확장(V9) 필요 |
| B3 판정 실행·조회 API | 미착수 | 정책은 0절 D9~D12에서 정함. 구현할 계약은 5절 |
| B4 평가 세트 | 미착수 | 사람이 정답을 먼저 정함. 코드와 무관하게 먼저 준비 가능 |

이 문서는 저장 구조와 그 규칙을 정한다. AI 호출, 판정 실행, 화면, 카테고리 목록, 후보 기준점수는 정하지 않는다.

## 0. 결정 사항

### 2026-10-23 (사용자)

| # | 결정 | 근거·비고 |
|---|---|---|
| D1 | `relevance_score`는 **0~1 소수**, 범위 검증 포함(DB CHECK와 서버 검증 둘 다) | 노션 명세 07의 출력 예시 `relevanceScore: 0.91`(사용자 확인. 노션 원문은 저장소에 없음) |
| D2 | 재판정마다 **새 행을 추가**하고 이전 판정은 고치거나 지우지 않음 | 판정 변화 추적, 평가 비교 |
| D3 | 판정에 사용한 입력 시점의 `issue_change_seq`를 함께 저장 | V7의 `issue.change_seq`(A6 원천 이슈 변경 표시와 같은 기준) |
| D4 | 이슈가 바뀌면 기존 판정은 보존하고 **재판정 필요**로 표시 | 자동 재판정은 하지 않음(유료 호출) |
| D5 | 관리자 제외는 판정과 따로 저장해 **재판정으로 해제되지 않음** | |
| D6 | 판정이 한 번도 없으면 **미판정**으로 구분 | "생활 무관" 판정과 섞지 않음 |
| D7 | 첫 판정 실행은 **관리자가 고른 이슈만** 대상으로 설계. 자동 판정 확대는 이후 판단 | `NEXT_CONTENT_SCOPE.md` 6절 4번에 대한 첫 단계 |
| D8 | 카테고리 목록과 후보 기준점수는 **평가(B4) 후 별도 결정** | 그때까지 카테고리는 자유 문자열, 기준점수는 저장하지 않음 |

### 2026-10-29 (사용자, 7절 질문에 대한 결정)

| # | 결정 | 구현 시점 |
|---|---|---|
| D9 | 상태만 바뀐 경우도 기존 `issue.change_seq`로 **재판정 필요** 판단. 판정용 번호를 따로 두지 않음(V7 트리거 변경 없음) | B1(구현됨) |
| D10 | 판정 행에 **입력 스냅샷(기사 본문 제외)과 SHA-256 해시** 저장 | B1(구현됨, 4-1절) |
| D11 | LIFE 제외·복귀 권한은 **OPERATOR 이상**. **제외 사유 필수**(NULL·빈 값·공백류만 있는 값 거부, 500자 이하), **복귀 사유 선택** | 사유 규칙: B1(서비스·DB 제약). 권한 검사: B3 API |
| D12 | **LIFE 제외된 이슈도 판정 허용**, 제외 상태는 유지. **판정 대상은 ACTIVE·CLOSED**, REVIEW·EXCLUDED·MERGED는 대상에서 제외 | B3(요청 시점·실행 시점 상태 검사) |

> 정정(2026-10-29): PR #11 첫 커밋 `b80aa4d`의 기록(`TASK.md` 39절, PR 본문)은 D11의 권한과 D12를 "아직 정하지 않음"으로 적었다. 실제로는 사용자 지시에서 채택된 방침이고, **구현만 B3로 미룬 것**이다.

## 1. 구조 (테이블 2개 + 기존 감사 로그, V8로 구현)

판정 결과(자주 쌓이고 고치지 않음)와 관리자 제외(이슈마다 현재 상태 하나, 사람이 바꿈)는 수명과 변경 주체가 달라서 나눈다. 테이블을 하나로 합치면 재판정 행이 추가될 때 제외 상태를 복사해야 하고, 그러다 빠지면 제외가 풀린다(D5 위반).

구현: `backend/src/main/resources/db/migration/V8__life_judgment.sql`, `life/LifeJudgmentRepository`, `life/LifeJudgmentService`.

### 1-1. `life_judgment` — 판정 이력 (추가만 함)

| 열 | 형식 | 내용 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `issue_id` | BIGINT NOT NULL FK → `issue` | 판정 대상 |
| `input_change_seq` | BIGINT NOT NULL CHECK (≥ 0) | 판정 입력을 만들 때 읽은 `issue.change_seq`(D3) |
| `input_snapshot` | JSONB NOT NULL | 판정 입력(D10, 4-1절). *(2026-10-29 추가)* |
| `input_hash` | CHAR(64) NOT NULL CHECK (소문자 16진수 64자) | 스냅샷의 SHA-256(4-1절). *(2026-10-29 추가)* |
| `life_related` | BOOLEAN NOT NULL | 생활 관련 여부 |
| `category` | VARCHAR(50) NULL | 지금은 자유 문자열(D8). 값이 있으면 공백류만 있는 값 거부. 서비스는 빈 값을 NULL로 저장 |
| `relevance_score` | NUMERIC(4,3) NOT NULL CHECK (0 ≤ 값 ≤ 1) | D1. 소수 셋째 자리까지(0.91 → 0.910). 서버는 넷째 자리 이상을 거부 |
| `reason` | TEXT NOT NULL | 판정 이유. 공백류만 있는 값 거부(길이 상한은 B2 스키마와 함께 정함) |
| `prompt_version` | TEXT NOT NULL | 예: `life-classifier.v1`. 공백류만 있는 값 거부 |
| `model` | TEXT NULL | 사용 모델(사람이 직접 판정하는 경로가 생기면 NULL) |
| `source_job_id` | BIGINT NULL FK → `async_job`(삭제 시 NULL), **UNIQUE** | 이 판정을 만든 작업. 재시도 중복 방지(4절) |
| `judged_by` | TEXT NOT NULL | 요청한 관리자(작업의 `requested_by`) |
| `judged_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | 결과를 저장한 시각 |

인덱스: `(issue_id, input_change_seq DESC, id DESC)` — 현재 판정 선택(2절)과 이슈별 이력 조회에 같이 씀.

행을 고치거나 지우지 않는다. 판정이 틀렸으면 재판정(새 행) 또는 관리자 제외(1-2)로 다룬다.

### 1-2. `life_exclusion` — 관리자 제외 (이슈당 현재 상태 하나)

| 열 | 형식 | 내용 |
|---|---|---|
| `issue_id` | BIGINT PK FK → `issue` | 이슈당 한 행 |
| `excluded` | BOOLEAN NOT NULL | 제외 여부. 다시 포함하면 행을 지우지 않고 `false`로 |
| `reason` | TEXT NULL, 500자 이하 | 제외 사유. **제외(`excluded = true`)면 필수**이고 공백류만 있는 값은 거부(D11, DB 제약 `ck_life_exclusion_reason`). 복귀하면 NULL(사유는 감사 기록에 남음) ~~(제외할 때 필수로 할지는 7절 질문)~~ |
| `updated_by` | TEXT NOT NULL | 마지막 처리자 |
| `updated_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | 마지막 처리 시각 |

- 판정 행과 연결하지 않으므로 재판정이 몇 번 추가돼도 제외 상태는 그대로다(D5).
- 처리 이력(누가 언제 제외·복귀했는지)은 기존 `admin_audit_log`에 남긴다: `LIFE_EXCLUDE`, `LIFE_INCLUDE`(전후 상태 `{lifeExcluded, reason}`·사유·처리자·correlation id). 별도 이력 테이블은 만들지 않는다(콘텐츠 상태 동작과 같은 방식).
- 구현: 이슈 행 잠금 → 제외 저장 → 감사 기록이 **한 트랜잭션**(감사 기록이 실패하면 제외도 되돌려짐, 시험으로 확인). 바뀌는 것이 없으면(이미 같은 사유로 제외, 이미 포함) 쓰지 않고 감사 기록도 남기지 않음.
- LIFE 제외는 이슈 상태 `EXCLUDED`(이슈 자체가 비공개·콘텐츠 생성 불가)와 다르다. LIFE 후보 목록에서만 뺀다. 판정·제외는 이슈 행을 바꾸지 않는다(시험으로 확인).

## 2. 현재 판정과 상태 계산

**현재 판정 선택 기준:** 이슈의 판정 행 중 `input_change_seq`가 가장 큰 것, 같으면 `id`가 가장 큰 것(나중에 저장된 것).

- 오래된 입력의 판정이 늦게 끝난 경우: 예를 들어 change_seq 3에서 시작한 판정 A가 change_seq 5에서 시작한 판정 B보다 늦게 저장돼도, 현재 판정은 B다. A는 이력으로만 남는다. 저장 시각(`judged_at`)이나 `id`만으로 고르면 오래된 입력이 새 입력을 덮는다.
- 같은 입력 시점으로 두 번 판정한 경우(관리자가 같은 이슈를 다시 요청): 나중에 저장된 쪽이 현재 판정이다.

**상태(조회 시 계산, 저장하지 않음 — `LifeJudgmentService.Status`):**

| 상태 | 조건 |
|---|---|
| 미판정 `UNJUDGED` | 판정 행이 없음(D6) |
| 판정됨 `JUDGED` | 현재 판정의 `input_change_seq` = `issue.change_seq` |
| 재판정 필요 `STALE` | 현재 판정의 `input_change_seq` < `issue.change_seq`(D4, 상태만 바뀐 경우 포함 — D9) |
| 관리자 제외 | `life_exclusion.excluded = true` — 판정 상태와 별개의 표시. 제외돼도 판정·재판정 필요 여부는 그대로 계산해 보여 줌 |

후보 여부(`life_related`와 기준점수)는 D8 결정 전까지 계산하지 않는다. 생활 관련 여부·점수·카테고리를 그대로 보여 준다.

## 3. 원천 이슈가 바뀔 때 (`change_seq` 경로 확인, 2026-10-23 코드 읽기)

V7 트리거(`trg_issue_change_seq`, `trg_issue_article_change_seq`, `trg_issue_article_move_change_seq`)가 실제로 `change_seq`를 올리는 경로:

| 동작 | 코드 경로 | 오르는 이슈 | 시험 |
|---|---|---|---|
| 기사 이동 | `IssueManagementService.move` → `IssueRepository.repointArticle`(`UPDATE issue_article SET issue_id`) | 원래 이슈·옮겨 간 이슈 둘 다 | 옮겨 간 쪽(`ContentOriginChangeIntegrationTest`), **떠난 쪽(B1 추가)** |
| 병합 | `merge` → 원본 기사마다 `repointArticle` + `markMerged`(`status = 'MERGED'`) | 원본(기사 이탈 + 상태 변경), 대상(기사 합류) | 원본 쪽(`mergedAndExcludedOrigins…`), **대상 쪽(B1 추가)** |
| 분리 | `split` → `issues.create`(새 이슈 `change_seq` 0) + 기사마다 `repointArticle` | 원래 이슈(기사 이탈), 새 이슈(기사 합류로 1 이상) | 원래 이슈 쪽(`articleMembership…`), **새 이슈 미판정(B1 추가)** |
| 제목·요약·분류·상태 수정 | `update` → `updateFields` | 해당 이슈(값이 실제로 바뀔 때만) | 있음 |
| 자동 집계 | `aggregates.refresh`가 AUTO 제목·요약을 바꿀 때 | 해당 이슈 | 간접 |

판정에 미치는 결과:
- 병합된 원본 이슈: 판정 행은 그대로 남는다(이력). 상태가 MERGED라 판정 대상에서 빠지고(D12), 병합 대상 이슈는 `change_seq`가 올라 **재판정 필요**가 된다. 원본의 판정을 대상 이슈로 옮기지 않는다.
- 분리로 생긴 새 이슈: 판정 행이 없으므로 **미판정**. 원래 이슈는 기사 이탈로 **재판정 필요**.
- ~~**B1 구현 때 추가할 테스트:** 병합 대상 이슈의 `change_seq` 증가, 분리로 생긴 새 이슈의 미판정 상태, 이동한 기사가 떠난 이슈의 증가(지금 테스트는 받는 쪽만 확인).~~ → B1에서 추가함(`LifeJudgmentIntegrationTest`).

~~참고: `change_seq`는 이슈 상태 변경(예: ACTIVE → CLOSED)에도 오른다. 상태만 바뀐 경우까지 재판정 필요로 볼지는 7절 질문.~~ → D9: 재판정 필요로 본다. 상태만 바뀌면 입력 해시는 같고 `change_seq`만 올라 STALE이 된다(시험으로 확인).

참고(2026-10-29 확인): 이미 이슈에 연결된 기사를 재분석해도 이슈의 핵심 사실은 다시 계산되지 않고 `change_seq`도 그대로다(`IssueClassificationService`의 "Already linked … record only"). 그래서 판정 입력에 기사 본문·분석 결과를 넣지 않는다(4-1절).

## 4. 재시도 중복과 재판정 구분

| 경우 | 처리 |
|---|---|
| 같은 작업의 재시도(작업자 중단·타임아웃 뒤 같은 `async_job`을 다시 실행) | `source_job_id` UNIQUE + `INSERT … ON CONFLICT (source_job_id) DO NOTHING`으로 판정 행이 하나만 남음. 결과는 4-2절의 `CREATED`/`REUSED`/`CONFLICT`. ~~결과 저장과 작업 성공 표시를 한 트랜잭션으로 처리해 판정 행이 하나만 남음~~ |
| 관리자의 재판정 요청 | 새 `async_job`을 만들므로 새 판정 행(D2) |
| 같은 요청 화면에서 중복 클릭 | B3 API에서 `Idempotency-Key`(콘텐츠 생성과 같은 방식)로 같은 작업을 돌려줌. 이 때문에 판정 행이 두 번 생기지 않음 |
| AI 응답 실패·스키마 위반 | 판정 행을 만들지 않음. 실패는 `async_job`·`ai_log`에 남고 이슈는 이전 상태(미판정 또는 이전 판정) 그대로 |

> **정정(2026-10-29): 판정 저장과 작업 성공 표시는 한 트랜잭션이 아니다.** `JobWorker`는 핸들러를 실행한 뒤 `markSuccess`를 **별도로** 호출한다. 그래서 판정 행은 커밋됐는데 작업은 SUCCESS가 아닌 상태로 멈출 수 있고, 그러면 같은 작업이 다시 실행된다. 중복 행을 막는 것은 트랜잭션이 아니라 **`source_job_id` UNIQUE**다. 불필요한 AI 재호출을 막는 것은 B3 핸들러의 몫이다(5절 계약 2). `JobWorker`는 B1에서 바꾸지 않았다.

입력 시점(`input_change_seq`)은 작업을 요청한 때가 아니라 **작업자가 판정 입력을 읽을 때** 읽는다. 구현은 입력·해시·`change_seq`·이슈 상태를 **한 SQL 문**(`readInput`)으로 읽어 같은 이슈 상태를 가리키게 한다. ~~같은 트랜잭션 안에서~~(한 문장이면 트랜잭션 격리 수준과 상관없이 같은 스냅샷).

### 4-1. 입력 스냅샷 계약 (형식 v1, D10)

`LifeJudgmentRepository.readInput`이 만드는 JSON 객체:

| 키 | 값 | 비고 |
|---|---|---|
| `schemaVersion` | `1` | 형식 버전(`SNAPSHOT_SCHEMA_VERSION`) |
| `title`, `summary`, `category` | 이슈 값 그대로 | NULL은 JSON `null`, 빈 문자열은 `""`. **둘을 같은 값으로 바꾸지 않음** |
| `keyFacts`, `entities` | 이슈 JSONB 값 그대로 | |
| `articleIds` | 대표 소속(`is_primary`) 기사 id, **오름차순** 배열 | 기사 연결 순서와 무관 |

- **해시에 들어가는 것:** 위 객체 전체(`schemaVersion` 포함). 이 필드들은 모두 `change_seq`를 올리는 필드(제목·요약·분류·핵심 사실·개체·기사 소속)이거나 형식 버전이다.
- **해시에 들어가지 않는 것:** 이슈 id(판정 행의 `issue_id` 열), 이슈 상태와 `change_seq`(`readInput`이 스냅샷 옆에 따로 돌려줌), 병합 대상, 집계 값(기사 수·시각·임베딩), 기사 제목·본문·분석 결과. 따라서 **상태만 바뀌면 해시는 같고 `change_seq`만 올라 STALE**이 된다.
- **해시 계산:** PostgreSQL이 스냅샷을 `jsonb`로 만든 뒤의 텍스트 출력(키 정렬·공백 정규화)을 UTF-8로 바꾼 바이트의 SHA-256(소문자 16진수). 기준은 **PostgreSQL `jsonb` 텍스트 직렬화**이며, 같은 서버 버전에서 같은 값이면 같은 텍스트가 나온다. Java 쪽 JSON 직렬화를 기준으로 삼지 않는다.
- **형식을 바꿀 때:** 필드를 더하거나 빼거나 의미를 바꾸면 `schemaVersion`을 올린다. 이전 버전으로 저장된 판정 행은 고치지 않는다(D2). 버전이 다른 해시끼리는 비교하지 않는다(같은 입력인지 판단할 수 없음). 판정 상태(STALE 여부)는 해시가 아니라 `change_seq`로 계산하므로 형식 변경이 기존 상태 계산을 바꾸지 않는다. PostgreSQL 주 버전을 올려 `jsonb` 텍스트 출력이 달라지면 같은 입력의 해시가 달라질 수 있다 — 그때도 기존 행은 고치지 않고, 재판정 필요 여부는 `change_seq`로만 판단한다.
- **저장 때 검사(`LifeJudgmentService.record`):** 호출자가 만든 입력도 받으므로 최소한만 다시 확인한다 — 이슈가 있음, `0 ≤ input_change_seq ≤ 현재 issue.change_seq`, 스냅샷이 JSON 객체이고 `schemaVersion`이 현재 버전이며 `articleIds` 배열이 있음, **`input_hash`가 스냅샷의 해시와 같음**. 하나라도 어기면 `VALIDATION_ERROR`이고 행을 만들지 않는다. **"지금 이슈 값과 같아야 함" 검사는 하지 않는다** — 정상적으로 읽은 오래된 입력의 늦은 결과도 저장해야 하기 때문이다(2절). 그래서 스냅샷이 정말 그 `change_seq` 시점의 그 이슈 내용인지는 증명하지 못한다. 그 보장은 입력을 `readInput`으로만 만드는 호출자(B3 핸들러) 몫이다.

### 4-2. 같은 작업 id의 저장 결과 계약 (`RecordResult.outcome`)

| 경우 | 결과 | 쓰기 | 호출자(B3) 처리 |
|---|---|---|---|
| 처음 저장 | `CREATED` | 새 행 | 성공 |
| 같은 작업 + 같은 입력(이슈·`input_change_seq`·`input_hash`) + 같은 결과(생활 관련·카테고리·점수·이유·프롬프트 버전·모델·요청자) | `REUSED` | 없음, 기존 행 반환 | 성공(CREATED와 같게) |
| 같은 작업 id인데 이슈·입력·결과 중 하나라도 다름 | `CONFLICT` | 없음, **기존 행을 덮어쓰지 않음** | **저장 성공으로 처리하지 않음.** 별도 오류(재시도 불가)로 끝내고 무조건 재시도하지 않음. `inputMatches`·`resultMatches`로 무엇이 달랐는지 기록 |

- `RecordResult.saved()`는 `CREATED`·`REUSED`일 때만 참이다.
- 같은 작업 id로 동시에 저장해도 UNIQUE 인덱스가 직렬화한다: 한쪽이 넣고, 다른 쪽은 그 커밋을 기다린 뒤 커밋된 행과 비교한다. 행은 하나이고 두 호출 모두 같은 행을 돌려준다(시험으로 확인: 같은 결과면 `CREATED`+`REUSED`, 다른 결과면 `CREATED`+`CONFLICT`).

## 5. B3 이후가 기대는 동작 (이번 범위 아님)

정책(D11·D12)은 정해졌고 **구현만 B3**다.

- 판정 요청: 관리자가 이슈를 골라 요청(D7). 이슈마다 `async_job` 하나. 권한은 OPERATOR 이상. **요청 시점에 상태 검사** — ACTIVE·CLOSED만 받고, REVIEW·EXCLUDED·MERGED는 거부(MERGED는 병합 대상에서 요청하라는 안내). LIFE 제외된 이슈도 받고 제외 상태는 그대로 둔다(D12). ~~제외된 이슈 요청을 막을지는 7절 질문.~~
- 판정 실행(작업 핸들러) 계약:
  1. **실행 시점에 상태 다시 검사:** `readInput`이 돌려준 `status`가 ACTIVE·CLOSED가 아니면 판정 행 없이 재시도하지 않는 실패로 끝낸다(요청과 실행 사이에 상태가 바뀔 수 있음).
  2. **AI 호출 전에 `findBySourceJob(jobId)` 확인:** 이미 행이 있으면(저장 뒤 `markSuccess` 전에 멈춘 경우) AI를 다시 부르지 않고 그 행으로 성공 처리한다.
  3. 입력은 `readInput`으로만 만든다(4-1절 마지막 항목).
  4. `record` 결과가 `CONFLICT`면 성공으로 표시하지 않고 재시도하지 않는 오류로 끝낸다(4-2절).
- 제외·복귀 API: OPERATOR 이상, 제외 사유 필수·복귀 사유 선택(서비스 규칙은 B1에 있음).
- 조회: 이슈별 현재 판정·상태(2절), 이력(최신순), 제외 여부.
- NEWS와 LIFE 동시 유지: 판정은 이슈의 부가 정보다. 판정 결과가 이슈 상태·이슈 요약·NEWS 콘텐츠 생성을 바꾸지 않는다.
- 공개 API·뉴스룸에는 판정 정보를 노출하지 않는다.

## 6. B1 구현 범위와 완료 기준

- 범위: V8(1절 두 테이블), 저장소(입력 읽기·판정 저장·현재 판정·상태 계산·이력, 제외 설정·해제 + 감사 기록), 서버 검증(점수 0~1, 필수값, 입력 형식·해시), 시험. AI 호출·작업 실행·API·화면·권한 검사 제외.
- 완료 기준과 결과(2026-10-29, PR #11):
  - [x] V8이 빈 DB에 적용(새 시험 DB·CI). ~~`aicontent_main` 복사본~~ — **복사본 리허설은 하지 않음**(이번 지시 범위 밖, 실제 적용 전에 별도로)
  - [x] 시험: 점수 범위, 미판정 → 판정됨 → 재판정 필요 → 판정됨, 오래된 입력의 늦은 판정, 같은 작업 재시도·충돌·동시 저장, 제외 유지·사유·감사 기록·롤백, 입력 해시 계약(4-1절), 3절 추가 시험
  - [x] 기존 시험 전체 통과(결과는 `TASK.md` 39·39-1절)
- 실제 `aicontent_main`에 V8 적용은 별도 승인(적용 전 백업·복원 확인·리허설).

## 7. 결정이 필요했던 것 (2026-10-23 질문 → 2026-10-29 결정)

1. ~~**상태만 바뀐 경우의 재판정 필요**~~ → D9
2. ~~**제외 사유 필수 여부와 권한**~~ → D11
3. ~~**제외된 이슈의 판정 요청**~~ → D12(허용, 제외 유지)
4. ~~**판정 입력 기록**~~ → D10(4-1절)
5. ~~**CLOSED 이슈**~~ → D12(ACTIVE·CLOSED 대상)
6. (D8) 카테고리 목록·후보 기준점수 — B4 평가 후. **남은 질문은 이것뿐**
