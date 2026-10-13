# 현재 작업

최종 갱신: 2026-10-13 KST · 기준 커밋 `191bc8b` + 이번 커밋 9개(아래 6절, 클라우드에 GitHub 쓰기 권한이 없어 패치로 전달). `main` 병합은 PC 검증 후 (GitHub `Kdhcode/AI-content-platform`)

목표: 뉴스 수집 → 분석 → 임베딩 → 이슈 분류 → 관리자 검수 흐름 검토.

완료 기준:
- 확인된 버그와 추정 문제를 구분하고 코드 위치와 근거를 기록한다.
- Maven 테스트, 프런트 build/typecheck 결과를 별도로 기록한다.
- 실제 OpenAI 외부 호출을 실행하지 않았다면 미검증으로 표시한다.
- 기존 stub 테스트 결과를 실제 모델 정확도 근거로 사용하지 않는다.

## 1. 2026-10-09 합의 사항과 이유

| 합의 | 이유 |
|---|---|
| 작업 저장소는 `ai-content-platform/` 하나만 사용한다. GitHub `main`과 연결된 저장소가 기준이다. 상위 폴더의 별도 저장소는 삭제했다 | 상위 폴더에 원격이 없는 저장소가 따로 있어서 Claude Code 커밋(`openai-provider-admin-dashboard` 브랜치)이 GitHub에 반영되지 않았다. 저장소가 둘이면 같은 일이 반복된다 |
| Claude Code에 작업을 맡길 때는 `ai-content-platform/` 경로를 명시하고, 커밋 후 `git log origin/main`으로 push 여부를 확인한다 | 위와 같은 이유 |
| 역할 분담: Claude Code는 구현, Codex CLI는 커밋 단위 독립 리뷰를 맡는다. 세부 규칙은 `RULES.md` | 구현자와 리뷰어를 분리하기 위함. 리뷰는 커밋 diff 기준이고 테스트를 실행하지 않는다 |
| 비밀번호·API 키는 문서, Git, Notion 어디에도 적지 않는다. 실행 시 환경 변수(`ADMIN_PASSWORD`, `OPENAI_API_KEY`)나 비추적 `application-local.yml`로만 넣는다 | `VERIFY.md`에 로컬 관리자 비밀번호가 기록된 채 공개 저장소에 push되어 있었다 |
| 설계 문서는 코드와 함께 갱신한다. 기준은 저장소의 `docs/DECISIONS.md`·`OPEN_ITEMS.md`, Notion `12. 변경 이력`·`13. 개발 일지`이다 | `DECISIONS.md`가 10-07 변경(OpenAI 제공자, live RSS)을 반영하지 못한 채 남아 있었다 |
| Notion 10-01 문서 묶음(PHARMA 포함)은 `[구버전 · 10-01]`로 표시만 하고 삭제하지 않는다 | 현재 범위(NEWS/LIFE/TV)와 섞여 검색되는 것을 막되, 이력은 보존하기 위함 |
| Phase 1 완료 판정은 실제 모델로 평가 세트를 돌린 결과로 한다. stub이나 모의 서버 결과, 두 모델의 의견 일치는 근거로 쓰지 않는다 | 로드맵 P0의 마지막 항목이 "대표 평가셋과 분류 품질 기록"이고, 지금까지의 AI 검증은 모두 stub이나 모의 서버였다 |

## 2. 진행 상태 (2026-10-09 18:45 기준)

| 항목 | 상태 | 근거 |
|---|---|---|
| Phase 1 P0 구현 | 평가 세트 품질 기록을 뺀 나머지 완료 | Notion `10. 개발 로드맵` 체크리스트 11/12 |
| Maven 테스트 | 마지막 기록은 92개 통과·실패 0 (단위 63, DB 통합 29). **10-09에는 재실행하지 않음** | 10-07 16:20 실행, `backend/target/surefire-reports`. 이후 백엔드 코드 변경 없음 |
| 관리자 UI build/typecheck | 10-07 성공 기록. **10-09에는 재실행하지 않음** | `docs/VERIFY.md` |
| 실제 OpenAI 호출 | **미검증**. API 키 미준비 | 어댑터는 단위 테스트와 로컬 모의 서버 통합 테스트만 통과 |
| 분류 품질(정밀도·재현율) | **미측정**. 합격 기준도 아직 정하지 않음 | `EvalLlmIT`·평가 세트 `issue-eval-v1.json`은 있으나 실제 모델로 실행한 적 없음 |
| 문서 정리 | 완료. `e23323a`(DECISIONS 최신화, VERIFY 비밀번호 제거, `tsconfig.tsbuildinfo` 추적 해제) push됨 | GitHub `main` |
| Codex 리뷰 도구 | `98653b6` 리뷰 지적 4건은 `f2c985c`에서, `f2c985c` 리뷰 지적 3건은 이번 커밋에서 보완(아래 3-2, 4). **새 코드는 실제 Codex CLI로 아직 실행하지 않음**(가짜 codex로만 테스트). PC에서 감시 중인 리뷰어는 이전 코드로 실행 중이라 재시작이 필요하다(`f2c985c` 리포트에 새 형식의 '검토 범위' 절이 없음). `e23323a`는 아직 리뷰되지 않음 | 로컬 `.local/collaboration/reviews/` (Git 제외), `tools/test_codex_review.py` |
| 이전 비밀번호 | 문서에서는 지웠지만 GitHub 커밋 이력(`a8883d1`)에는 남아 있음. 로컬 관리자 비밀번호를 바꿔야 함 | — |

## 3. 다음 작업 (권장 순서)

1. **로컬 관리자 비밀번호 변경.** 새 값은 어디에도 기록하지 않는다.
2. **Codex 리뷰 도구 보완** (`tools/codex_review.py`, 리뷰 `98653b6` 결과) — 10-09 코드 보완 완료, 실제 CLI 확인 남음
   - [x] [중간·확인] 병합 커밋 diff 누락 → 병합 커밋은 **첫 번째 부모 대비 전체 diff**를 검토한다. 루트 커밋은 빈 트리 대비. 커밋 종류와 비교 기준을 리포트에 표시한다
   - [x] [중간·확인] 폴링 사이 중간 커밋 누락 → 마지막 처리 커밋을 `.local/collaboration/reviews/last_processed`에 저장하고, 그 뒤의 first-parent 커밋을 순서대로 모두 리뷰한다. 실패하면 그 지점에서 멈추고 상태를 넘기지 않는다. 최초 실행은 HEAD만, 이력이 바뀌어 기준 커밋이 없으면 HEAD만, 밀린 커밋이 20개를 넘으면 중단한다. 과거 커밋은 `--commit <sha>`로 따로 리뷰한다
   - [x] [높음·조건부] 비밀 전송 → 커밋 메시지·diff(삭제된 줄 포함)·문맥 문서 전체를 전송 전에 검사하고, 탐지되면 **아무것도 보내지 않고 중단**한다. 오류에는 위치만 표시하고 값은 출력하지 않는다. 검사 대상은 개인키, `sk-` 계열 API 키, AWS, GitHub, Slack, Google 키, JWT, 따옴표로 감싼 값이나 yaml/.env 형식의 password/secret/api_key 대입이다. `${...}`, `<...>`, `change-me` 같은 자리표시자는 제외하고, 알려진 가짜 값은 그 줄에 `codex-review: allow-secret`를 적으면 통과시킨다. 제외 파일 목록은 프롬프트와 리포트에 표시한다
   - [x] [중간·조건부] 외부 접근 → `codex exec`에 `--ignore-user-config`(사용자 config.toml의 MCP 서버 등을 읽지 않음, 로그인은 유지), `--ignore-rules`, `features.shell_tool/apps/plugins/hooks/multi_agent=false`를 추가했다. 옵션을 모르는 버전이면 codex가 오류로 끝나고 리뷰는 실패 처리되며, 제한 없이 돌지는 않는다
   - [x] 검증: 일반·루트·병합·빈 커밋, 연속 커밋, 실패 후 재시도, 이력 변경, 가짜 비밀값 차단(임의 파일명·문맥 문서·삭제된 줄), 제외 파일, CLI 실패·빈 응답·시간 초과, 동시 실행 락 → 21개 통과 (4절)
   - `f2c985c` 리뷰 반영 (10-09)
     - [x] [높음·확인] 값 뒤 주석, 따옴표 안 공백 → 대입 검사를 다시 작성함. 따옴표 없는 값은 공백·`#`·`//`·`;`·`,` 앞에서 끝난 것으로 보고, 따옴표 안의 값은 공백을 포함해 그대로 검사한다. `==`·`!=` 비교는 제외한다
     - [x] [높음·확인] `${변수:기본값}` 무조건 제외 → 자리표시자 목록에서 `${...}`를 빼고, 기본값을 분리해 다시 검사한다. 지원 형식은 `:`, `:-`, `:=`, `-`, `=`이고, 중첩된 `${A:${B:x}}`도 처리한다. 기본값이 없는 `${A}`, `${A:}`, `${A:?메시지}`만 허용한다
     - [x] [중간·확인] 상태 커밋이 첫 번째 부모 이력 밖에 있는 경우 → 존재 여부가 아니라 HEAD의 first-parent 이력에 들어 있는지 확인한다. 들어 있지 않으면 HEAD만 리뷰하고 `.local/collaboration/reviews/history_changes.log`에 시각·이전 커밋·HEAD·사유를 남긴다. 사유는 '이력 밖' 또는 '객체 없음'
     - [x] 회귀 테스트 10개 추가, 전체 31개 통과 (4절)
     - [x] 오탐 정리: 문자열 연결(`'KEY=' + 변수`)은 값으로 보지 않는다. 속성 접근(`props.apiKey`), `;`로 끝나는 코드, 숫자 없는 변수명(`password`, `apiKey`)도 코드 참조로 본다. 비밀 단어가 들어 있어도 숫자가 있는 값(`Pr0dSecret42`)은 비밀로 본다
     - [x] 2026-10-12 결정 (a): 기본값을 지우고 `DB_PASSWORD` 환경 변수를 필수로 함(`application.yml`, `application-test.yml`, `docker-compose.yml`, README·VERIFY 실행 안내). 이전 결정 필요 내용: 기본값까지 검사하면서 `application.yml:7`·`application-test.yml:6`의 `${DB_PASSWORD:…}`와 `docker-compose.yml:8`이 탐지된다. 이 줄이나 그 주변(diff 문맥 3줄)을 고치는 커밋은 리뷰가 차단된다. 선택지는 (a) 기본값을 지우고 환경 변수를 필수로 하기, (b) 로컬 전용 값이라는 확인 후 해당 줄에 `# codex-review: allow-secret` 표시하기
   - [ ] **사용자 PC에서 실제 Codex CLI로 1회 실행**: 새 제한 옵션을 설치된 codex 버전이 받아들이는지, 리뷰가 정상 생성되는지 확인. 실패하면 `.local/collaboration/reviews/<sha>.log`에서 어떤 옵션이 거부됐는지 본다
   - [ ] `e23323a`를 `--commit e23323a`로 리뷰
   - [x] 2026-10-12 코드 보완: 시간 초과(또는 중단) 시 프로세스 트리 전체를 종료한다. Windows는 `taskkill /T /F /PID`, 그 외는 새 세션으로 띄운 뒤 `killpg`. 회귀 테스트 `test_timeout_kills_codex_descendants` 추가(가짜 codex가 손자 프로세스를 띄우고 시간 초과 후 생존 여부 확인). **Windows 실제 실행은 아래 확인 방법으로 여전히 확인 필요**
   - [ ] **미검증: Windows 시간 초과 후 자식 Codex 프로세스 종료.** 10-09에 확인하지 못했다. 이 세션에는 사용자 PC의 셸이 없고, 컴퓨터 조작으로는 터미널에 입력할 수 없어 Windows에서 실행할 수 없었다. 코드상 추정(가설): 시간 초과 시 `subprocess.run`은 직접 자식인 `cmd.exe`만 종료한다. Windows는 자식의 자식을 함께 종료하지 않으므로 `.cmd` 셰임이 띄운 Codex 프로세스가 남을 수 있다. 남으면 락이 풀린 뒤 다음 리뷰와 동시에 돌거나 임시 폴더 삭제가 실패할 수 있다. 확인 방법: `--timeout 5`로 실행하고 시간 초과 직후 작업 관리자에서 `codex`/`node` 프로세스가 남는지 본다. 남으면 `taskkill /T /F /PID <pid>`로 프로세스 트리를 종료하도록 수정한다
3. **로컬 재시작 재현.** DB(`127.0.0.1:55432`, `docs/DB_SETUP.md`), 백엔드, 관리자 UI를 다시 띄워 health UP, 로그인, 기존 데이터 조회를 확인한다. `mvn test`와 `npm run build`·`typecheck` 결과는 명령과 기준 커밋을 함께 기록한다.
4. **실제 OpenAI 소량 검증.** 절차·실패 대응·기록란은 `docs/REAL_AI_SMOKE.md`, 결과 판정은 `docs/sql/real_ai_smoke_check.sql`(읽기 전용, 판정 8개가 모두 true면 통과). 키는 Git 밖에 두고, 실제 모델 전용 빈 DB를 쓴다(stub 벡터와 섞지 않음). 기사 1~3건이 분석·1536차원 임베딩·분류까지 성공하는지, `ai_log`에 공급자·모델·성공 여부·토큰이 기록되는지 확인한다.
5. **평가 세트 품질 측정.** 실행 전에 합격 기준(정밀도·재현율 목표)부터 정한다. 결과는 오분류 사례, 모델·프롬프트 버전과 함께 `docs/EVALUATION.md`에 기록하고, 임계값(`app.classifier.*`)을 확정한다.
6. **관리자 보정과 실패 복구 확인.** 병합·분리, 검수 확정, 중복 수집, API 오류 후 작업 재시도까지 확인한다.
7. **Phase 1 완료 판정.** 결과를 Notion `13. 개발 일지`에 기록하고 Phase 2(NEWS/LIFE 초안 생성) 진입 여부를 결정한다.

선택: `.idea/` 프로젝트 파일이 Git에 추적되고 있다. 공유할 의도가 없으면 추적 해제를 검토한다.

## 5. 구현 지시: Phase 1 보강 (2026-10-12, Claude Code 담당)

범위 근거: 사용자가 Phase 2 대신 **Phase 1 보강**을 선택함(Notion `10. 개발 로드맵`의 동결 원칙 유지). 아래 3개는 `docs/OPEN_ITEMS.md` #7·#2·#10과 실제 OpenAI 검증 준비에 해당하며 새 Phase 기능이 아니다.
작업 순서는 A → B → C. 기능마다 **별도 커밋**(RULES.md: 작은 단위). 각 기능 끝에서 `mvn test`, C는 추가로 `npm run typecheck`·`npm run build`. 결과는 4절 형식으로 기록.
아래 SQL 3종은 2026-10-12 클라우드 PostgreSQL 16.4 + pgvector 0.8.0에서 V1 위에 실행해 동작을 확인했다(Java 코드는 미작성).

### A. 락 대기 실패가 재시도 횟수를 소모하지 않게 (OPEN ITEM #7)

문제: `IssueClassificationService.classify()`가 분류 락을 못 잡으면 `JobExecutionException("LOCK_TIMEOUT", …, retryable=true)`를 던지고, `JobWorker.fail()` → `AsyncJobRepository.markFailure()`가 `retry_count`를 올린다. 대량 수집 시 기사에는 아무 문제가 없는데 대기만으로 재시도 3회를 다 써서 FAILED가 된다.

구현:
1. `V2__job_defer_count.sql` (Flyway, V1은 수정하지 않음):
   ```sql
   ALTER TABLE async_job
       ADD COLUMN defer_count INTEGER NOT NULL DEFAULT 0 CHECK (defer_count >= 0);
   COMMENT ON COLUMN async_job.defer_count IS
       'Times the job was put back without running (e.g. LOCK_TIMEOUT). Separate from retry_count, which counts real failures.';
   ```
2. `job/JobDeferredException`(새 클래스, `RuntimeException`): `errorCode`, `message`. 재시도 가능/불가 개념 없음.
3. `AsyncJobRepository.defer(id, workerId, code, msg, delaySeconds, maxDefers)` — 확인된 SQL:
   ```sql
   UPDATE async_job SET
       status        = CASE WHEN defer_count < :maxDefers THEN 'PENDING' ELSE 'FAILED' END,
       defer_count   = CASE WHEN defer_count < :maxDefers THEN defer_count + 1 ELSE defer_count END,
       available_at  = CASE WHEN defer_count < :maxDefers THEN now() + make_interval(secs => :delay) ELSE available_at END,
       finished_at   = CASE WHEN defer_count < :maxDefers THEN NULL ELSE now() END,
       error_code    = :code,
       error_message = :msg,
       locked_by     = NULL
   WHERE id = :id AND status = 'RUNNING' AND locked_by = :w
   RETURNING status
   ```
   반환이 비면 소유권을 잃은 것(기존 `markFailure`와 같은 처리). 결과가 FAILED면 `notifyFinalFailure` 호출.
4. `JobWorker`: `JobDeferredException`을 `JobExecutionException`보다 먼저 잡아 `defer`로 보낸다.
5. `AppProperties.Worker`에 `deferDelaySeconds`(기본 30), `maxDefers`(기본 20) 추가. `application.yml`에 키와 주석.
6. `IssueClassificationService`: `LOCK_TIMEOUT`을 `JobDeferredException("LOCK_TIMEOUT", …)`로 바꾼다. 다른 오류 경로는 그대로.
7. 관리자 작업 API(`JobView`, `/api/admin/jobs`)와 작업 화면에 `deferCount` 표시. `docs/API.md` 갱신.

완료 기준(테스트로 확인):
- 단위/통합: LOCK_TIMEOUT 후 `status=PENDING`, `retry_count` 불변, `defer_count`+1, `available_at` 미래.
- `maxDefers` 도달 시 FAILED + 기사 상태가 기존 최종 실패 처리와 같게 바뀜.
- 다른 워커가 소유한 작업은 `defer`가 아무것도 바꾸지 않음.
- 기존 재시도 테스트(일시 오류는 `retry_count` 증가) 그대로 통과.
- `DECISIONS.md` 3절에 결정 추가, `OPEN_ITEMS.md` #7을 "재큐 경로 구현, 락 단위 세분화는 미결"로 갱신.

### B. AI 호출 비용 계산·기록 (OPEN ITEM #2의 "단가 계산 미구현")

현재: `ai_log.estimated_cost_usd` 컬럼은 있으나 항상 NULL. 토큰은 기록됨.

구현:
1. 설정 `app.ai.pricing`: 모델별 `input-per-million-usd`, `output-per-million-usd`(임베딩은 input만). **코드·기본 설정에 단가를 넣지 않는다**(가격은 바뀌므로 사용자가 OpenAI 가격 페이지를 보고 `application-local.yml` 등에 직접 입력). 기본값은 빈 맵.
2. `ai/AiCostCalculator`(새 클래스): `(model, inputTokens, outputTokens) -> BigDecimal or null`.
   - 모델 이름 매칭: 정확히 일치 → 없으면 **가장 긴 접두사** 일치(응답 모델명이 `gpt-4o-mini-2024-07-18`처럼 날짜가 붙음). 그래도 없으면 null.
   - 단가가 없거나 토큰이 null이면 **0이 아니라 null**(모르는 비용을 0으로 보이게 하지 않음).
   - `cost = in × inPrice / 1e6 + out × outPrice / 1e6`, 소수 6자리 반올림(`NUMERIC(12,6)`).
3. `AiLogRepository.Entry`에 비용 필드 추가하고 INSERT에 `estimated_cost_usd` 포함. `StructuredOutputService`·`EmbeddingService`에서 계산해 넣는다. 계산 실패가 AI 호출 결과를 바꾸면 안 된다(기존 `safely` 패턴).
4. 단위 테스트: 정확 일치, 날짜 붙은 모델명 접두사 일치, 더 긴 접두사 우선, 단가 없음 → null, 토큰 null → null, 반올림.
5. `docs/REAL_AI_SMOKE.md` 0절에 "단가를 설정하면 비용이 기록됨" 한 줄, `OPEN_ITEMS.md` #2 갱신.

### C. 관리자 AI 사용량 화면 (OPEN ITEM #10 관찰용, 한도·차단 기능은 만들지 않음)

목적: 실제 OpenAI 검증·평가 때 호출 수·실패·토큰·비용을 화면에서 확인. 실시간 제한이나 예산 차단은 이번 범위가 아니다.

구현:
1. `GET /api/admin/ai-usage?days=7` (OPERATOR+, `days` 1~90, 기본 7, 범위 밖은 400). 응답 `{success,data,error}` 공통 형식. 확인된 집계 SQL:
   ```sql
   SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day, call_type, provider, model,
          count(*) AS calls,
          count(*) FILTER (WHERE NOT success) AS failures,
          coalesce(sum(input_tokens), 0) AS input_tokens,
          coalesce(sum(output_tokens), 0) AS output_tokens,
          sum(estimated_cost_usd) AS cost_usd,
          count(*) FILTER (WHERE estimated_cost_usd IS NULL
                           AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL)) AS unpriced_calls
   FROM ai_log
   WHERE created_at >= (date_trunc('day', now() AT TIME ZONE 'Asia/Seoul')
                        - make_interval(days => :days - 1)) AT TIME ZONE 'Asia/Seoul'
   GROUP BY 1, 2, 3, 4
   ORDER BY 1 DESC, 2, 4
   ```
   응답에 합계(`totals`)와 `unpricedCalls`를 함께 준다. 응답 원문(`response`)·오류 메시지는 포함하지 않는다.
2. 관리자 UI `/ai-usage` 페이지 + 메뉴 항목: 기간 선택(1·7·30일), 일자×호출 유형×모델 표, 합계. 비용이 null이면 "단가 미설정"으로 표시하고 0으로 쓰지 않는다. stub 호출은 `provider` 열로 구분되게.
3. 통합 테스트: 기간 경계(KST 자정), 권한(인증 없음 401), `days` 범위 검증, 비용 null 처리.
4. `docs/API.md`에 엔드포인트 추가. 1440px·390px 화면 확인(기존 `verify-dashboard.cjs` 방식 참고).

### 하지 않을 것
Phase 2 기능(초안 생성·제목 추천 등), 예산 초과 시 자동 차단, 공급자별 레이트 리미터, 분류 락 세분화. 필요하면 OPEN ITEM으로 남긴다.

## 6. 구현: 운영 정책 기능 (2026-10-13, 클라우드 작성 · PC 검증 대기)

범위 근거: 사용자가 "사용자 결정·API 키·PC 실행이 필요 없는 것"을 만들라고 요청(10-13). OPEN ITEM #8·#9·#10의 **동작 장치**만 만들고,
정책 값(보존 일수, 종료 기준 일수, 예산·호출 한도)은 정하지 않았다. 세 기능 모두 **기본 꺼짐**이라 설정하지 않으면 기존 동작과 같다.
Phase 2 기능이 아니며 스키마 변경(새 Flyway 버전)도 없다.

| 커밋 | 내용 |
|---|---|
| `feat(retention)` | `AiLogRetentionService`: `app.retention.ai-log-response-days`보다 오래된 `ai_log.response`만 NULL. 행·토큰·비용 유지. 1시간마다 1,000행 단위 |
| `feat(issue)` | `IssueAutoCloseService`: `app.issue-lifecycle.auto-close-after-days` 동안 활동 없는 ACTIVE 이슈 → CLOSED + 감사 로그 `ISSUE_AUTO_CLOSE`(actor `system`). REVIEW 제외, `SKIP LOCKED` |
| `feat(ai)` | `AiUsageLimiter`: `app.ai.limits`(하루 예산·하루 호출 수·분당 호출 수). 닿으면 작업이 실패하지 않고 대기(`AsyncJobRepository.postpone`, 재시도·재큐 횟수 미사용). 사용량 API·화면에 한도 표시 |
| `fix(ai)` | 실패한 AI 호출의 `ai_log.model`이 NULL이던 문제(공급자 오류 → 요청 모델, 차원 오류 → 응답 모델·토큰) |
| `docs` | README 설정표, VERIFY·DB_SETUP의 V2 반영, 이 절 |
| `feat(issue)` 제목 | V3 `issue.title_source` AUTO/MANUAL. AUTO 제목은 멤버십 변경 때 가장 이른 기사 제목으로 갱신, 관리자·분리 입력 제목은 유지. 대시보드 스키마 기대값 3 |
| `feat(eval)` | `-Deval.analysis=true`: 분석 모델의 `multiEvent` 판별 측정 + 예측값으로 분류 재평가. 기본 평가 결과는 그대로 |
| `feat(issue)` 락 | `app.classifier.lock-scope` GLOBAL(기본)/CATEGORY |
| `docs` | 이 절 갱신 |

**PC에서 할 일** (클라우드에서는 Maven Central·npm 레지스트리가 막혀 Java 컴파일과 프런트 빌드를 하지 못했다):
1. 패치 적용: `git switch -c claude/ops-policy 191bc8b` → `git am <ai-content-patches-2026-10-13.zip을 푼 폴더>/*.patch` (커밋 9개, 앞서 받은 5개 포함이라 이전 zip은 쓰지 않는다)
2. `mvn test` — 예상 **143개**(기존 109 + 새 34: `AiLogRetentionServiceTest` 4, `AiLogRetentionIntegrationTest` 1, `IssueAutoCloseIntegrationTest` 2, `MaintenanceDefaultsIntegrationTest` 1, `AiUsageLimiterTest` 8, `AiLimitIntegrationTest` 1, `AsyncJobIntegrationTest` +1, `AiUsageApiIntegrationTest` +1, `EmbeddingServiceLogTest` 3, `IssueManagementIntegrationTest` +3, `MultiEventMetricsTest` 3, `EvalRunnerTest` +1, `ClassificationLockKeyTest` 3, `LockScopeIntegrationTest` 2). Flyway가 기존 개발 DB에 V3를 적용한다(제목 출처 백필). 컴파일 오류가 나면 이 절의 기록과 함께 알려준다
3. `npm run typecheck`, `npm run build`
4. 서버를 띄운 상태에서 `npm run verify:ai-usage` (한도 패널은 `.stat-value`·표를 쓰지 않아 기존 검증 항목은 그대로여야 함). 한도 패널 화면 확인은 `AI_DAILY_MAX_CALLS=2` 같은 값을 넣고 1회
5. 이슈 상세 화면에서 "제목 출처" 표시 확인, 분리 후 제목이 남은 기사 중 가장 이른 기사 제목으로 바뀌는지 확인
6. 모두 통과하면 `main`에 병합하고 push. Codex 리뷰 감시가 새 커밋 9개를 차례로 리뷰한다(20개 상한 이내)
7. 결과를 4절 형식으로 기록

## 4. 구현 변경 내용과 실행한 검증

작업자가 아래에 추가한다. 형식: 날짜 · 기준 커밋 · 실행 명령 · 결과(통과/실패/미실행).

- 2026-10-09 · `a8883d1`~`98653b6` · 문서·협업 설정만 변경, 코드 변경 없음 · 테스트 미실행
- 2026-10-09 · 기준 `9ad9d0c` + Codex 리뷰 도구 보완 커밋 · `python -m unittest discover -s tools -p "test_*.py" -v` · **21개 통과, 실패 0** (Linux, Python 3.13, Git 2.43, 가짜 codex 사용. 실제 Codex CLI·AI 호출 없음)
- 2026-10-09 · 같은 기준 · 저장소 전체 커밋 5개에 비밀 검사를 적용함 · `27d2606`(최초 커밋)에서 `docker-compose.yml:8`의 로컬 DB 기본 비밀번호 1건이 탐지되어 차단됨(의도한 동작. 이 커밋은 diff 크기 제한도 초과). 나머지 4개 커밋은 탐지 0건. 이번 변경의 diff와 문맥 문서도 탐지 0건(테스트의 가짜 비밀값은 실행 시 조합하도록 작성)
- 2026-10-09 · Maven 테스트, 프런트 build/typecheck · **미실행**. 백엔드와 프런트 코드는 변경하지 않음
- 2026-10-09 · 기준 `f2c985c` + `f2c985c` 리뷰 반영 커밋 · `python -m unittest discover -s tools -p "test_*.py" -v` · **31개 통과, 실패 0** (기존 21개와 회귀 10개. Linux, Python 3.13, Git 2.43, 가짜 codex 사용. 실제 Codex CLI·AI 호출 없음). 같은 테스트를 수정 전 `f2c985c` 코드로 돌리면 31개 중 12개가 실패한다(실패 10, 오류 2. 오류 2개는 새로 추가한 이력 변경 기록 함수가 없어서 생김). 대상은 값 뒤 주석, 따옴표 안 공백, `${…:기본값}`, 이력 밖 상태 커밋 3종, 상태 정리 1종, 코드 참조 오탐이다
- 2026-10-09 · 같은 기준 · 비밀 검사 재실행 · 이력 5개 중 `27d2606`에서 3건(`application.yml:7`, `application-test.yml:6`, `docker-compose.yml:8`. 기본값 검사로 2건이 새로 탐지됨). 나머지 커밋 0건. 이번 변경의 diff와 문맥 문서 0건(테스트 고정값 1줄에 `allow-secret` 표시)
- 2026-10-09 · Windows 시간 초과 후 자식 프로세스 종료 · **미검증** (3-2 참조)
- 2026-10-12 · 기준 `7db8fe3` + 시간 초과 프로세스 트리 종료 커밋 · `python -m unittest discover -s tools -p "test_*.py" -v` · **32개 통과, 실패 0** (Linux, Python 3.13, 가짜 codex. 실제 Codex CLI·Windows 실행 없음). 새 테스트를 수정 전 코드로 돌리면 실패함(손자 프로세스 생존)
- 2026-10-12 · Maven 테스트, 관리자 UI build/typecheck · **미실행**. 클라우드 작업 환경에서 PostgreSQL 16.4 + pgvector 0.8.0은 띄웠으나 Maven Central·npm 레지스트리가 네트워크 정책으로 차단되어 의존성을 받지 못함. 사용자 PC에서 실행 필요
- 2026-10-12 · 로컬 DB 기본 비밀번호 제거 · 설정·문서만 변경. 이 환경에서 Maven 의존성을 받을 수 없어 `mvn test`로 기동 확인은 **미실행**. 비밀 검사로 변경 diff·문맥 문서 확인(아래 커밋 기록)
- 2026-10-12 · `--skip <sha> --reason <사유>` 추가: 다음 대기 커밋 1개만 리뷰 없이 처리 완료로 넘기고 `history_changes.log`에 기록. 삭제된 줄의 옛 기본값처럼 비밀 검사가 반드시 차단하는 커밋 때문에 감시가 멈추지 않게 하기 위함. 리포트를 만들지 않으므로 리뷰된 것으로 보이지 않음 · `python -m unittest discover -s tools -p "test_*.py" -v` · **33개 통과, 실패 0** (Linux, 가짜 codex)
- 2026-10-12 · 기준 `7db8fe3` 계열 · 클라우드 Linux에서 PostgreSQL 16.4 + pgvector 0.8.0을 소스 빌드해 새 DB에 V1 적용 → `verify_schema.sql`·`verify_queries.sql` · **둘 다 통과**(ALL SCHEMA/QUERY ASSERTIONS PASSED). `vector(1536)`·`ix_issue_embedding_hnsw` 확인. 사용자 PC DB와 다른 독립 환경
- 2026-10-12 · 실제 OpenAI 경로 코드 점검(실행 아님) · `ai_log`에 공급자·모델·성공·입출력 토큰·지연이 기록됨을 코드로 확인(`StructuredOutputService`, `EmbeddingService`). JSON 모드 요구(메시지에 'JSON' 포함) 충족. 429는 재시도 대상이라 결제 미설정·한도 소진도 3회 재시도 후 FAILED. `estimated_cost_usd`는 미기록(단가 계산 미구현, OPEN ITEM #2). 임베딩 실패 로그에는 모델명이 비어 있음(경미)
- 2026-10-12 · `docs/REAL_AI_SMOKE.md`, `docs/sql/real_ai_smoke_check.sql` 추가 · 확인 SQL은 빈 스키마와 정상 시나리오 모의 데이터로 실행해 판정 8개가 모두 true가 되는 것까지 확인. 실제 API 호출은 **미실행**
- 2026-10-12 · 평가 세트 분석 · SAME 13·NEW 10·묶음 2·모호 2. 완벽한 판단기 시뮬레이션에서 후보 단순화로 인한 강제 누락 0건. 하네스는 묶음 여부를 정답 라벨로 처리해 분석 모델의 `multiEvent` 판별을 재지 않음(별도 확인 필요). 합격 기준 초안을 `docs/EVALUATION.md`에 **미확정**으로 추가(오병합 0, 스키마 실패 ≤1, recall ≥10/13, REVIEW ≤8, 3회 반복)
- 2026-10-12 · 5절 Phase 1 보강 지시서(A 락 대기 재큐, B AI 비용 계산, C AI 사용량 화면) 작성 · 포함된 SQL 3종(V2 마이그레이션, defer UPDATE, 사용량 집계)을 클라우드 PG 16.4 + pgvector 0.8.0에서 V1 위에 실행해 확인: defer 시 retry_count 불변·defer_count 증가·소유권 없으면 0행·상한 도달 시 FAILED, 집계는 KST 기간 필터와 단가 미설정 건수 정상. Java/TS 구현은 **미착수**(Claude Code 담당)
- 2026-10-12 · 기준 `4e5b7e7`(패치 6개를 `7db8fe3` 위에 `git am`으로 적용, 충돌 없음) · 사용자 PC(Windows 10, Temurin 25, Maven 3.9.9, 로컬 PostgreSQL 16 `127.0.0.1:55432/aicontent_test` trust 인증, `DB_PASSWORD`는 세션 전용 임의 값) · `mvn test` · **92개 통과, 실패 0, 오류 0, 건너뜀 0**. 기본값을 지운 `${DB_PASSWORD}` 설정으로 컨텍스트가 정상 기동함
- 2026-10-12 · 기준 `4e5b7e7` · 사용자 PC(Node 24.19.0) · `npm run typecheck` · **통과** / `npm run build` · **통과**(9개 경로)
- 2026-10-12 · 기준 `4e5b7e7` · 사용자 PC(Windows 10, Python 3.14.8) · `python -m unittest discover -s tools -p "test_*.py"` · **33개 통과, 실패 0** (가짜 codex 사용. 실제 Codex CLI·AI 호출 없음). `test_timeout_kills_codex_descendants`가 Windows에서 통과해 `taskkill /T /F` 경로가 가짜 codex 기준으로는 확인됨. 실제 Codex CLI의 `.cmd` 셰임으로는 **미검증**
- 2026-10-12 · 5절 A(락 대기 재큐) 구현 · 기준 `0de9e27` + 이 커밋 · 사용자 PC(Windows 10, Temurin 25, 로컬 PostgreSQL 16 `aicontent_test`) · `mvn test` · **95개 통과, 실패 0, 오류 0, 건너뜀 0** (기존 92 + 새 3: `LockDeferIntegrationTest` 2개, `AsyncJobIntegrationTest.deferPutsTheJobBack…` 1개). 확인한 것: LOCK_TIMEOUT 뒤 `PENDING`·`retry_count` 0·`defer_count` 1·`available_at` 미래, 락 해제 뒤 SUCCESS, `max-defers` 초과 시 FAILED + 기사 `classification_status=FAILED`, 다른 워커 소유 작업은 변화 없음, 기존 재시도 테스트 그대로 통과. V2 적용으로 대시보드 `schemaVersion` 기대값을 1 → 2로 고침(`AdminApiIntegrationTest`). 지시서 SQL은 그대로 사용
- 2026-10-12 · 같은 기준 · `npm run typecheck` · **통과** / `npm run build` · **통과**. 작업 화면의 "대기 재큐" 열은 브라우저로 **미확인**(서버 미기동)
- 2026-10-12 · 5절 B(AI 호출 비용 계산·기록) 구현 · 기준 `7ef28f2` + 이 커밋 · 사용자 PC(Windows 10, Temurin 25, 로컬 PostgreSQL 16 `aicontent_test`) · `mvn test` · **104개 통과, 실패 0, 오류 0, 건너뜀 0** (기존 95 + `AiCostCalculatorTest` 9개: 정확 일치, 날짜 붙은 모델명 접두사 일치, 더 긴 접두사 우선, 단가 없음 → null, 토큰 null → null, 임베딩 입력 단가만, 소수 6자리 반올림). `OpenAiFlowIntegrationTest`에 가짜 단가를 넣어 로컬 모의 서버의 토큰 수로 계산한 비용이 `ai_log.estimated_cost_usd`에 실제로 기록되는 것을 확인(채팅 3건 0.000285, 임베딩 2건 0.000010). 테스트의 단가는 임의 값이며 실제 OpenAI 가격이 아님. 실제 API 호출은 **미실행**
- 2026-10-12 · 같은 기준 · 관리자 UI는 변경 없음 · `npm run typecheck`·`npm run build` **미실행**
- 2026-10-12 · 5절 C(관리자 AI 사용량 화면) 구현 · 기준 `979faf7` + 이 커밋 · 사용자 PC(Windows 10, Temurin 25, 로컬 PostgreSQL 16 `aicontent_test`) · `mvn test` · **109개 통과, 실패 0, 오류 0, 건너뜀 0** (기존 104 + `AiUsageApiIntegrationTest` 5개: 인증 없음 401, `days` 0·91·-1·문자 400 / 1·90·생략 200, KST 자정 경계(자정 정각은 오늘, 1초 전은 어제), 비용 null 유지와 `unpricedCalls`, 비용이 전혀 없을 때 합계 null). 지시서 집계 SQL은 그대로 사용
- 2026-10-12 · 같은 기준 · 사용자 PC(Node 24.19.0) · `npm run typecheck` · **통과** / `npm run build` · **통과**(`/ai-usage` 포함 10개 경로)
- 2026-10-12 · 같은 기준 · `npm run verify:ai-usage`(새 스크립트, Chrome headless) · **PASS** — 1440px·390px, 기간 1·7·30일 전환, 화면 합계와 API 합계 일치, "단가 미설정" 표시 행 수 일치, 390px 가로 넘침 없음, 브라우저 오류·API 실패 0. 임시 빈 DB(`aicontent_uicheck`)에 stub 백엔드와 관리자 UI를 띄우고 `ai_log`에 손으로 넣은 예시 7행으로 확인했으며, 끝난 뒤 임시 DB는 삭제함. 예시 행의 토큰·비용은 임의 값이고 실제 OpenAI 호출이 아님. 캡처: `.local/screenshots/ai-usage-desktop.png`, `ai-usage-mobile.png`(Git 제외). 390px에서 표는 기존 목록 화면처럼 가로 스크롤되고 일자 칸이 두 줄로 접힘(미수정)
- 2026-10-12 · 5절 A의 작업 화면 "대기 재큐" 열 · 브라우저 **미확인** (이번 화면 확인은 `/ai-usage`만)

- 2026-10-13 · 6절 기능 구현(기준 `191bc8b` + 커밋 5개) · 클라우드 Linux(OpenJDK 21.0.12, PostgreSQL 16.15 + pgvector 0.8.0 소스 빌드). 새 SQL 6종(응답 원문 정리, 비활동 이슈 종료, 사용량 스냅샷, `postpone`, 테스트 고정 데이터 2종)을 실제 PG에서 실행해 확인: 정리는 배치 2·2·1 후 종료, 최근 원문과 토큰·비용 유지 / 종료는 오래된 ACTIVE 2건만, 최근 수정·최근 기사·REVIEW 유지, **다른 세션이 행 잠금 중인 이슈는 건너뛰고 잠금이 풀린 뒤 다음 실행에서 종료** / 스냅샷은 서버 시간대를 UTC로 두고도 KST 자정 포함·전날 제외, 단가 없는 호출 비용 제외, 최근 1분 경계, 다음 날까지 초 / `postpone`은 소유자만, 횟수 불변
- 2026-10-13 · 같은 기준 · `mvn test` **미실행**(Maven Central 403). 대신 의존성 없이 `javac`로 HEAD와 작업본의 진단을 비교: 새 진단은 모두 라이브러리 부재(Spring·JUnit·AssertJ)에서 나온 것이고 구문 오류·프로젝트 클래스 참조 오류는 0. 라이브러리 타입이 걸린 호출(`JdbcClient`, `TransactionTemplate`, AssertJ 등)은 이 방법으로 확인되지 않음
- 2026-10-13 · 같은 기준 · `npm run typecheck`·`npm run build` **미실행**(npm 레지스트리 403). TypeScript 6.0.3 구문 검사 통과, React/Next 최소 shim으로 바뀐 페이지 타입 검사: 원본 페이지에도 똑같이 나는 shim 한계 1건 외 오류 0. `verify:ai-usage` **미실행**
- 2026-10-13 · 같은 기준 · `tools/codex_review.py`의 비밀 검사(`scan_secrets` + 문맥 문서)를 코드 커밋 4개에 실행 · 탐지 0건, diff 크기 모두 제한(180,000자) 이하
- 2026-10-13 · 6절 2차(이슈 제목 V3, 묶음 판별 평가, 락 범위) · 같은 클라우드 환경. V3 마이그레이션을 V1·V2가 적용된 새 DB에 실행해 백필 5가지 경우(자동/관리자 수정/분리 후 남은 옛 제목/분리 시 입력/상태만 수정) 확인, 제목 갱신 SQL이 두 번째 실행에서 0행(불필요한 `updated_at` 변경 없음)인 것 확인. V1~V3 위에서 `verify_schema.sql`·`verify_queries.sql`(제목 규칙 사본 추가) **둘 다 통과**. `javac` 비교 새 오류 0(라이브러리 부재 제외), TypeScript 구문 검사 통과, 비밀 검사 0건. `mvn test`·`npm run build` **미실행**(같은 이유)
- 2026-10-13 · 기준 `0cfe5ee`(브랜치 `claude/ops-policy`: `191bc8b` 위에 `.local/patches/2026-10-13/` 패치 9개를 `git am`으로 적용, 충돌 없음) · 사용자 PC(Windows 10, Temurin 25, Maven 3.9.9, 로컬 PostgreSQL 16 `127.0.0.1:55432/aicontent_test`, `DB_PASSWORD`는 세션 전용 임의 값) · `mvn test` · **143개 통과, 실패 0, 오류 0, 건너뜀 0** (예상 143개와 일치. V3 마이그레이션이 테스트 DB에 적용됨)
- 2026-10-13 · 같은 기준 · 사용자 PC(Node 24.19.0) · `npm run typecheck` · **통과** / `npm run build` · **통과**
- 2026-10-13 · 같은 기준 · `npm run verify:ai-usage`·`verify:dashboard`·`verify:flow`, 실제 OpenAI 호출, `python -m unittest discover -s tools` · **미실행** (이번 지시 범위 밖). 한도 표시가 추가된 `/ai-usage` 화면과 이슈 상세 화면은 브라우저로 **미확인**
- 2026-10-13 · `claude/ops-policy`를 GitHub에 push(`git push -u main claude/ops-policy`. 이 저장소의 원격 이름은 `origin`이 아니라 `main`). `main` 브랜치에는 병합하지 않음
