# 현재 작업

최종 갱신: 2026-10-19 KST · 20절(새 노트북 점검·실행 스크립트) 추가, 기준 `claude/content-a` `ad9d5a2`. 아래 이전 기록은 2026-10-13 기준 커밋 `191bc8b` + 커밋 9개(6절, 클라우드에 GitHub 쓰기 권한이 없어 패치로 전달)

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
- 2026-10-13 · 로그인 수정(`0a0faf3`)과 테스트 연결 풀 제한(`6a4201f`)을 `claude/ops-policy`에 추가 · 기준 `6a4201f` · 사용자 PC, 저장소 밖 임시 worktree에서 실행(개발 서버 8080·3000은 켜 둔 채) · `mvn test` · **143개 통과, 실패 0, 오류 0, 건너뜀 0** / `npm run typecheck` · **통과** / `npm run build` · **통과** / `npm run verify:auth-client` · **PASS**
- 2026-10-13 · 연결 풀 제한의 배경: 개발 서버를 켠 채 `mvn test`를 돌리면 `OpenAiFlowIntegrationTest`가 `FATAL: sorry, too many clients already`로 컨텍스트를 띄우지 못함(PostgreSQL `max_connections` 100, 테스트 컨텍스트 10개 × 기본 풀 10 + 개발 서버 10, 측정한 최대 동시 연결 98). `application-test.yml`에 `maximum-pool-size: 5`를 넣은 뒤 같은 조건에서 통과
- 2026-10-13 · 같은 기준 · 화면 확인(Chrome headless, 1440px·390px) · **PASS** — 작업 화면 "대기 재큐" 열 표시(값 3인 행 확인), AI 사용량 화면의 호출 한도 표시(`오늘 호출 7 / 500`, `최근 1분 7 / 60`), 이슈 상세의 "제목 출처: AUTO" 표시, `npm run verify:ai-usage` PASS, 세 화면 모두 390px 가로 넘침 없음, 브라우저 오류·API 실패 0. 임시 DB(`aicontent_uicheck`)에 stub 백엔드(18080)와 UI(3100)를 따로 띄워 예시 기사 3건으로 확인했고 끝난 뒤 임시 DB는 삭제함. "대기 재큐" 값 3은 표시 확인용으로 DB에서 직접 넣은 값. 한도에 실제로 도달했을 때의 차단 안내 문구는 화면으로 **미확인**(통합 테스트로만 확인)
- 2026-10-13 · 화면 확인 중 발견(미수정): 390px 이슈 상세의 기사 표에서 제목 칸이 한 글자 폭으로 줄어 세로로 표시됨
- 2026-10-13 · 일반 사용자 뉴스룸(`/news`, `/api/public/**`)은 Phase 1 범위 밖이라 `main`에 넣지 않고 `feature/public-news` 브랜치(`claude/ops-policy` 기준 + 커밋 1개)로 분리 · 같은 방식으로 `mvn test` **151개 통과**(공개 API 통합 테스트 8개 포함: `PublicNewsApiIntegrationTest` 6, `PublicNewsIntegrationTest` 2), `npm run typecheck`·`npm run build` **통과**. `verify-public-news.cjs`는 **미실행**
- 2026-10-13 · `claude/ops-policy`(위 커밋 포함)를 `main`에 fast-forward 병합. 실제 OpenAI 호출과 평가 세트 측정은 API 키 대기로 **미실행**
- 2026-10-14 · 기준 `651b7b2` + 소스 확대 커밋 · RSS 후보 27곳을 한 번씩 받아 봄(읽기만) · 19곳 정상, 8곳 실패(404 6, 오류 페이지로 이동 2). 정상 중 JTBC는 2024-10-29 이후 갱신 없음, MBC는 방송 편성 목록
- 2026-10-14 · 같은 기준 · 수집기 호환성(stub AI, 임시 DB `aicontent_rsscheck`, 소스당 10건) · **6개 소스 모두 수집 성공**, 실패 0, 60건 전부 발행 시각 파싱. RSS 제목 외 텍스트 평균: 연합뉴스 27자(3건은 제목만), 뉴시스 801자, SBS 1,035~1,861자, 동아 301자, 경향 163자. SBS 본문 앞에 저작권 안내 문구가 섞여 분석 입력에 들어감(미수정). 임시 DB는 삭제
- 2026-10-14 · 같은 기준 · **실제 OpenAI**(gpt-4o-mini, text-embedding-3-small), 새 빈 DB `aicontent_openai_20261014_2`(보존), 7개 소스 × 최신 5건, 자동 수집 끔, 한도 하루 150회·분당 40회 · **기사 35건 전부 분석·임베딩·분류 성공**, 작업 112개 SUCCESS·실패 0·재시도 0
  - AI 호출 77회: 분석 36(입력 38,971 / 출력 13,679 토큰, 1회는 `category` 값이 허용 목록 밖이라 스키마 검증 실패 → 같은 작업 안에서 재시도해 성공), 임베딩 35(입력 13,190), 분류 6(입력 8,125 / 출력 725). `app.ai.pricing` 미설정이라 `estimated_cost_usd`는 전부 NULL(0원 아님)
  - 분류: 후보 없음(NO_CANDIDATE) 29건, LLM 판단 6건 모두 SAME_ISSUE(신뢰도 0.90)로 자동 연결. 묶인 이슈 4개: 비서실장 임명(SBS 2 + 동아), 중수청장 후보 인사청문회(SBS 3), 9호선 급행 확대(뉴시스 + 경향), 울주군 공장 화재(SBS 2). 6건 모두 사람이 보기에 같은 사건이 맞음. 단독 이슈 25개를 훑어본 범위에서 놓친 같은 사건은 없었음. REVIEW·NEW_ISSUE(LLM 판단)는 이번 데이터에서 나오지 않아 **미검증**
  - 발견한 품질 문제(미수정): 카테고리 분류가 약함. BBC 국제 기사 5건 중 4건이 OTHER(WORLD여야 함), "인천 흉기 난동"이 LIFE, 국민연금 국감 기사가 OTHER
  - 평가 세트(대표 27건) 측정과 합격 기준 확정은 **미실행**
- 2026-10-14 · 브랜치 `claude/ai-quality`(기준 `885b2f5` = main) · 분석 프롬프트 v2, AI 입력 정리, live 단가, 실제 OpenAI 재검증. 상세 `docs/AI_QUALITY_20261014.md`
  - `mvn test -Dtest=AnalysisInputCleanerTest,PromptFilesTest,AiCostCalculatorTest,EvalRunnerTest,EvalDatasetTest` · **통과**(새 테스트: 입력 정리 10개, 프롬프트 v2 스키마·정의 검사 1개)
  - `CleanerRealDataReport`(실제 기사 35건, AI 호출 없음) · 15건 정리(SBS 10, 뉴시스 5), 지워진 조각 전부 이용 조건·앱 안내·링크·서명으로 확인, 기사 문장 삭제 0
  - **실제 OpenAI** 66회(상한 100, 실패·재시도 0), 새 DB `aicontent_aicheck_20261014`, 입력 89,594 / 출력 15,880 토큰, 추정 비용 $0.022947(청구액 아님). 비용 NULL 0건(응답 모델명 `gpt-4o-mini-2024-07-18` 접두사 일치 확인)
  - 카테고리(실제 기사 20건, 기대값은 실행 전 작성): v1 6/20 → v2 1차 17/20 → v2 2차 **19/20**. 남은 오분류 1건(은평구 금성당제 → OTHER). 같은 20건으로 조정해 과적합 가능
  - 분류 평가 세트 27건 1회: precision·recall 0.923, **오병합 1건(E09)** → 합격 기준 초안 필수 조건 미달. REVIEW 3, 스키마 실패 0. 3회 반복·묶음 판별은 **미실행**(호출 상한)
  - 전체 `mvn test`(stub, 로컬 `aicontent_test`) · **154개 통과, 실패 0** (기존 143 + 새 11). 첫 실행에서 `ClassificationFlowIntegrationTest`가 분석 프롬프트 버전을 `v1`로 고정해 실패 → 기본값을 v2로 바꾼 의도된 변경이라 기대값을 `v2`로 수정. 유료 하네스(`AnalysisCompareIT`, `EvalLlmIT`)와 `CleanerRealDataReport`는 시스템 속성이 없으면 실행되지 않음
  - `npm run typecheck`·`build` · **미실행**(관리자 UI 변경 없음)
- 2026-10-14 오후 · 브랜치 `claude/ai-quality`(기준 `17a1222`) · 오병합 대응·추가 검증. 상세 `docs/AI_QUALITY_20261014.md` 8절
  - 원인: E09는 AI 판단 단계(다른 분기라고 쓰고도 SAME 0.9), E10은 E09 결과의 연쇄. 분류 프롬프트 v2(지시문) 3회 → E09 오병합 그대로. v3(항목별 비교 + DecisionPolicy 모순 강등, 기본값) 초안·최종
  - **실제 OpenAI** 282회(작업 한도 300회·$1, 재시도 1 포함), 입력 519,600 / 출력 39,568 토큰, 추정 $0.101390(청구액 아님, 청구 대조 미검증). 비용 NULL 0. 호출 전 한도 차단(예산·호출 수) 확인
  - 평가 27건: v2 3회 오병합 1(E09)·SAME 13건 중 12. **v3 최종 3회 오병합 0/0/0**, SAME 13건 중 10/11/10, REVIEW 3/4/3, 스키마 실패 0 → 합격 기준 초안 충족(재현율 경계값). 묶음 판별 연결 실행(4번째)은 SAME 8/13으로 미달
  - 묶음 판별(eval.analysis, 분석 v2): 2/2, 잘못된 판정 0, 묶음 기사는 MULTI_EVENT로 REVIEW
  - 새 기사 7건(독립 5): 수집 → 분석 v2 → 임베딩 → 분류 v3 → 저장 성공. 독립 5건 카테고리 모두 기대 범위 안. 조정에 쓴 교황 기사가 이번엔 OTHER(이전 WORLD 2회) → 카테고리 흔들림. 분류는 7건 모두 후보 없음 → 실제 기사에서 분류 v3 호출·같은 사건 다매체 처리는 **미검증**
  - 전체 `mvn test`(stub, 로컬 `aicontent_test`) · **161개 통과, 실패 0**. 첫 실행에서 기본 분류 프롬프트 v3 전환으로 stub 응답에 `comparison`이 없어 20건 실패 → `StubAiClient`가 v3 스키마면 `comparison`을 넣도록 수정. 모의 서버 테스트(`OpenAiFlowIntegrationTest`)가 `issue-classifier.v1` 문자열로 분류 요청을 구분해 1건 실패 → 버전 무관하게 구분하고 v3 형식으로 응답하도록 수정
  - 관리자 UI는 수정하지 않아 `npm run typecheck`·`build` **미실행**
- 2026-10-14 오후(3차) · 브랜치 `claude/ai-quality`(기준 `e6bde0d`) · 지표 수정, 검수 대기 후속 기사, 실제 다매체 검증. 상세 `docs/AI_QUALITY_20261014.md` 9절
  - `EvalMetrics`: precision·recall을 명확한 사례로만 계산, 모호 사례 분리 보고, SAME 정답 분해(연결·다른 이슈·NEW 분리·REVIEW 보류). 회귀 테스트 `ambiguousHitsNoLongerInflateRecall`(v2의 recall 1.077 → 12/13). 저장된 평가 로그 11개를 `EvalLogRecompute`로 유료 호출 없이 재계산
  - 검수 대기 이슈를 후보로 제시하고 같은 사건이면 보류 연결(REVIEW 유지, 자동 ACTIVE 없음). 묶음 기사 검수 이슈는 후보 제외. stub 통합 테스트 4개(보류 연결 → 관리자 확정, 분리, 묶음 제외, ACTIVE 자동 연결 유지)
  - **실제 OpenAI** 93회(작업 한도 300회·$1, 재시도 0), 입력 186,208 / 출력 14,539 토큰, 추정 $0.036155(청구액 아님, 대조 미검증). 비용 NULL 0. 호출 전 한도 차단 재확인
  - 평가 27건 v3 + 새 정책 3회: 오병합 0/0/1(3회차 E09 재발, 모델이 period를 NOT_APPLICABLE로 표시), 연결 10/10/10, NEW 분리 1/1/2, REVIEW 보류 2/2/1, REVIEW 5/5/3 → 초안 필수 기준 미달
  - 실제 다매체 8건(사건 3개, 사건당 언론사 2~3곳, 이전 검증에 안 쓴 기사): 수집 → 분석 v2 → 임베딩 → pgvector 후보 검색 → 분류 v3 → 연결 모두 성공. 후속 5건에서 v3 후보 비교 실제 호출, 오병합·누락·REVIEW 0. 비슷한 다른 사건은 후보 검색 단계에서 분리돼 AI가 다른 사건을 거부하는 경우는 **미검증**
  - 전체 `mvn test`(stub, 로컬 `aicontent_test`) · **169개 통과, 실패 0**. 관리자 UI 변경 없음 → `npm run typecheck`·`build` **미실행**. 공개 API 회귀 테스트는 이 브랜치에 없어 **미실행**

## 7. 4차: 기간 규칙, 평가 세트 v2, 관리자 검수 화면 (2026-10-14 저녁, `claude/ai-quality`)
- 기간 규칙 `PeriodGuard`와 `DecisionPolicy.applyPeriodGuard`(운영 분류와 평가 실행기가 함께 사용). 단위 테스트 11개, 회귀 테스트 1개(E07~E09)
- 새 평가 세트 v2 17건(정답은 실행 전 작성). 실제 OpenAI로 v2 3회, v1 3회: 오병합은 모두 0. 병합 누락 V15·E08. 검수 비율 v2 0.059~0.294, v1 0.185
- **실제 OpenAI** 117회(재시도 0), 입력 259,542 / 출력 16,989 토큰, 추정 $0.049132(청구액 아님, 대조 미검증), 비용 NULL 0
- 관리자 이슈 상세: 검수 사유, 보류 연결 표시, 비교 후보, 검수 안내, “같은 사건으로 확정”·“별도 사건으로 분리” 문구
- 별도 검증 DB와 포트(18081/13081)에서 내장 브라우저로 분리·확정·병합 확인(stub, 유료 호출 0)
- 전체 `mvn test` 182개 통과, `npm run typecheck`·`build` 통과
- 공개 API 확인은 통합 브랜치에서 한다(`main` 병합 안 함)

## 8. 통합 확인 브랜치 `claude/integration-review` (2026-10-14 밤, 검토용 · `main` 병합 안 함)
- `claude/ai-quality` + `feature/public-news`, 충돌 없음
- 새 통합 테스트 `PublicNewsReviewFlowIntegrationTest`: 검수 대기·보류 연결 기사는 공개되지 않고, 관리자의 분리·확정·병합 뒤에만 공개
- 전체 `mvn test` 191개 통과, `npm run typecheck`·`build` 통과
- 별도 DB와 포트(18082/13082)에서 `/news`와 관리자 화면을 브라우저로 확인(stub, 유료 호출 0)

## 9. 로컬 검증 모드와 화면 확인 준비 (2026-10-14 밤, `claude/integration-review`)
- 로그인 생략은 `local-verify` 프로필 전용, 기본 꺼짐. 루프백 주소·`live` 미사용·AI `none/stub`·OpenAI 키 없음이 아니면 시작 거부(설정 직후와 빈 생성 시 2회 검사). 인증 정보를 보낸 요청은 기존대로 검사
- 화면: 모든 관리자 화면·뉴스룸에 “로컬 검증 모드” 띠와 데이터 구분(시나리오/실제 기사 복사본), 로그인 화면 건너뜀
- 데이터: 시나리오 DB(가상 기사 8건, 기간 충돌 검수 2건·보류 연결 2건·분리·확정·병합 사례), 실제 기사 복사본 DB(`aicontent_view_20261014` 복사, AI·작업 실행기 꺼짐, 검수 대기 이슈 없음). 원본 DB와 8090/3090 서버는 그대로
- IntelliJ 개인 DB 연결 설정 2개 파일은 Git 추적 해제(`.idea/.gitignore`), 연결 방법은 `docs/DB_SETUP.md`에 비밀값 없이 기록
- 전체 `mvn test` 199개 통과, `npm run typecheck`·`build` 통과. 사용자 화면 확인 뒤 통합 PR·`main` 병합 예정

## 10. 2026-10-14 작업 마감 기록

### 브랜치·커밋 (모두 push 완료, 미커밋 변경 없음)
| 브랜치 | 최신 커밋 | 내용 |
|---|---|---|
| `claude/ai-quality` | `07bb7f8` | 기간 규칙, 평가 세트 v2, 관리자 검수 화면(검수 사유·보류 연결·비교 후보) |
| `claude/integration-review` | 이 기록 커밋 (직전 `832d019`) | 위 브랜치 + `feature/public-news` 병합(충돌 없음), 공개 노출 통합 테스트, 로컬 검증 모드, IntelliJ 개인 DB 설정 추적 해제 |
| `feature/public-news` | `478d4b8` | 변경 없음 |
| `main` | `885b2f5` | **변경 없음. PR·병합 안 함** |

사용자 작업 폴더(`ai-content-platform`, `feature/public-news`)에는 `.idea/db-forest-config.xml` 수정이 커밋되지 않은 채 남아 있다(사용자 IntelliJ 설정, 손대지 않음). 백업: `.local/idea-db-settings-backup-20261014/`.

### 오늘 완료
- 기간 규칙(`PeriodGuard`)과 평가 세트 v2. 실제 OpenAI 평가 6회 오병합 0. 오늘 4차 작업 호출 117회, 추정 $0.049132(청구액 대조 미검증)
- 관리자 검수 화면: 검수 사유, 보류 연결 표시, 비교 후보, 확정·분리 문구
- 통합 브랜치: 검수 대기·보류 연결 기사가 공개 API·뉴스룸에 나오지 않고 관리자 처리 뒤에만 나오는 것을 통합 테스트와 브라우저로 확인
- 로컬 검증 모드(로그인 생략, 루프백 전용, 유료 AI 차단)와 화면 확인 환경 2개. 사용자가 시나리오 환경에서 분리 → 확정 → 확정 → 병합을 진행(감사 로그 4건, 처리자 `local-verify`, 18:25~18:27). 최종 상태 이슈 ACTIVE 6·MERGED 1, 기사 전부 CLASSIFIED. 실제 기사 복사본은 조작 기록 없음
- 테스트: 통합 브랜치 전체 `mvn test` 199개 통과, `npm run typecheck`·`build` 통과

### 남은 문제
- 사용자 화면 확인 의견(검수 사유가 이해되는지, 분리·확정·병합이 쉬운지) 아직 받지 않음
- 실제 기사 복사본에는 검수 대기 이슈가 없어 실제 기사로 검수 화면을 본 적은 없음
- 자동 분류 개선 항목(`OPEN_ITEMS` #11): 병합 누락 V15·E08, 카테고리 흔들림, 같은 분기·다른 회사 구분은 AI 의존, 기간 규칙 단독 효과는 테스트로만 확인
- 시나리오 기사는 제목만 있음(본문을 넣으면 stub 유사도가 후보 기준 미만)
- 작업 트리(`q4`, `int`, `view`)와 확인용 스크립트가 세션 임시 폴더(scratchpad)에 있음. 코드는 모두 push됐지만, 8090/3090 화면 서버는 임시 폴더의 `view` 작업 트리에서 실행 중이라 임시 폴더가 지워지면 다시 띄워야 함

### 실행 상태
- 종료: 확인용 서버 4개(13091·13092·18091·18092)
- 유지: 사용자 화면 서버 8090/3090, PostgreSQL(55432)
- 유지한 DB: `aicontent`, `aicontent_test`, `aicontent_view_20261014`(원본), `aicontent_verify_scenario`(사용자 조작 후 상태), `aicontent_verify_real`, 이전 검증 DB(`aicontent_aiq4_*` 등)

### 다음 시작 순서
1. 화면 확인 의견 정리. 필요하면 확인용 서버 다시 띄우기(`docs/LOCAL_VERIFY.md` 3절). 시나리오를 처음부터 보려면 `aicontent_verify_scenario`를 다시 만들고 3회 수집
2. 의견 반영(문구·화면 수정) → 통합 브랜치 테스트 재실행
3. `claude/integration-review` → `main` PR 생성(사용자 승인 후), 검토 뒤 병합
4. 사용자 작업 폴더 정리: 병합본을 받기 전에 `.idea` 백업 확인, `db-forest-config.xml` 수정 정리
5. 평소 사용할 주소·DB 확정(stub DB와 실제 AI DB 분리), 8090/3090 화면 서버 정리 시점 결정
6. NEWS·LIFE 콘텐츠 생성·편집·검수: 노션 명세서 기준으로 범위 정리 후 별도 작업

## 11. 2026-10-15 통합 PR 준비 (`claude/integration-review`)

### 시작 상태
- 원격 `claude/integration-review` = `854c89d`(10-14 마감 이후 변경 없음), `main` = `885b2f5`. 사용자 작업 폴더 미커밋 변경은 `.idea/db-forest-config.xml` 하나(백업과 동일, 손대지 않음)
- PostgreSQL과 8090/3090 서버는 10-14 18:45에 함께 종료돼 있었음(실행 세션 종료로 프로세스가 끝난 것으로 보임). DB는 재시작 후 정상 복구, 데이터 이상 없음. 8090/3090은 다시 띄우지 않음

### 최종 검토에서 찾은 문제와 수정
1. **10-14 기록 정정:** `832d019`에서 IntelliJ 개인 DB 설정 두 파일의 추적 해제가 실제로는 빠져 있었다(커밋을 나누는 과정에서 삭제 스테이징이 풀림). 10-14 기록의 "추적 해제"는 사실이 아니었다. `c702764`에서 실제로 해제(파일은 디스크에 남음)
2. 검수 대기 이슈를 제외 → 다시 활성화하면 기사가 계속 REVIEW로 남던 문제, 검수 대기 이슈를 API로 바로 CLOSED(공개 상태)로 바꿀 수 있던 문제 수정(`65669c8`)
3. 로그인 생략 강화: CSRF 보호가 꺼져 있어 같은 브라우저의 다른 사이트가 검증 서버에 POST할 수 있었음. 요청의 Host·Origin·X-Forwarded-Host가 모두 이 PC일 때만 로그인 생략(`70a3ae5`). 실제 서버로 직접·프록시 경유 모두 확인

그 밖의 점검 항목(기간 규칙·보류 연결, 확정·분리·병합·감사 기록, 공개 비노출, 로그인 생략 조건, 비밀값·실제 기사 원문 미포함)은 코드·테스트·추가 커밋 확인 결과 문제 없음

### 검증
- `mvn package`(테스트 포함) **202개 통과**(기준: `c702764`와 같은 코드). 관리자 UI 코드는 10-14 이후 변경 없음 → 타입 검사·빌드는 10-14 기록(`400d1be` 기준 통과)을 유지하고 반복하지 않음. 유료 AI 호출 0
- 로컬 검증 서버(시나리오)로 로그인 생략 동작·차단 확인, daily 구성으로 인증 유지(401/200) 확인

### 실행 환경
- `docs/RUNBOOK.md`, `scripts/run/*.ps1` 추가. 평소 사용 DB `aicontent_main`(실제 OpenAI 임베딩, `aicontent_view_20261014` 복사) 신규 생성. stub DB(`aicontent`)와 분리
- 다음 기능 범위: `docs/NEXT_CONTENT_SCOPE.md`(구현은 시작하지 않음)

## 12. 2026-10-15 오후: 실행 스크립트 점검, A 묶음 계획

- 통합 PR: 생성 못 함. `gh` 미설치, 내장 브라우저는 GitHub 미로그인(비밀번호 입력은 하지 않음). 본문 초안은 준비됨
- **확인된 문제 1:** 터미널에 `SPRING_PROFILES_ACTIVE=local-verify`, `APP_LOCALVERIFY_LOGINBYPASS=true`가 남아 있으면 daily에서 로그인 생략이 켜졌다(재현: 관리자 API 200). 스크립트가 환경을 정하는 값을 명령줄 인자로 넘기고 위험한 잔여 변수는 거부(`1d47522`). 재확인: 스크립트 거부, 같은 인자로 직접 실행해도 생략 꺼짐·401
- **확인된 문제 2:** daily에서 `-Ai openai`를 켜면 단가가 없어 비용이 NULL로 기록될 수 있었다(단가는 `live` 프로필에만 있음). 유료 AI는 `live` 프로필로 실행, 앱은 단가 없는 openai 시작 거부(`c5042f9`)
- stub↔실제 임베딩 혼합: 스크립트 고정에 더해 앱 시작 시 `ai_log` 근거로 거부(`c5042f9`). 실제 실행으로 3가지 거부 확인, `live`+한도 1 정상 시작·AI 호출 0
- 전체 `mvn package` **205개 통과**(기준 `c5042f9`; 이후 커밋은 스크립트·문서만). UI 코드 변경 없음(타입 검사·빌드 기준 `400d1be` 유지). 유료 호출 0
- NEWS·LIFE A 묶음 구현 계획: `docs/CONTENT_A_PLAN.md`(코드·마이그레이션 미구현)

## 13. 2026-10-15 병합 전 최종 검토 (PR #1)

- 확인된 문제: 검수 대기 이슈를 제외(EXCLUDED)한 뒤 API로 CLOSED로 바꾸면 검수 안 된 기사가 공개됐다(REVIEW→CLOSED만 막혀 있었음). 검수 대기 기사가 남은 이슈는 어떤 상태에서든 CLOSED 불가로 수정
- 실행 스크립트: 혼합 검사를 끄는 잔여 변수(`APP_AI_EMBEDDINGGUARD*`)도 거부 목록에 추가
- 전체 `mvn test` 205개 통과(UI 코드 변경 없음). 유료 호출 0

## 15. 2026-10-15 NEWS·LIFE 콘텐츠 A1·A2 (`claude/content-a`, `main` 미병합)

- 범위: A1 스키마(V4), A2 `POST /api/admin/issues/{id}/contents`, `GET /api/admin/contents`, `GET /api/admin/contents/{id}`, `PATCH /api/admin/contents/{id}`. 상태 전이·출처/URL 관리 API·화면·자동저장·AI 생성·LIFE 판정·회원·TV·블로그 발행은 하지 않음
- 저장소에 `AGENTS.md`는 없음 → `docs/collaboration/RULES.md` 기준
- 테스트(로컬 `aicontent_test`, stub): 새 테스트 20개(본문 검증 6, API 8, 동시성·트랜잭션 3, 스키마 제약 3) 포함 전체 `mvn package` **225개 통과**(기준: 이 커밋의 코드). 대시보드 스키마 버전 기대값 3→4 갱신, 테스트 정리 대상에 콘텐츠 테이블 추가
- 마이그레이션 실제 확인(별도 DB): 빈 DB `aicontent_mig_fresh`에 V1~V4 적용. `aicontent_main`(V3)의 복사본 `aicontent_mig_upgrade`에 V4만 적용, 기존 11개 테이블 행 전체 md5 동일(데이터 보존). 원본 `aicontent_main`은 V3 그대로, daily 서버는 건드리지 않음
- UI 변경 없음 → 타입 검사·빌드 생략. 유료 AI 호출 0
- A3 전에 정할 것: `CONTENT_A_PLAN.md` 7절(승인 권한, REVIEW 중 편집, 기본 소제목 등)

## 16. 2026-10-15 콘텐츠 A3 상태 전이 + 기존 문제 2건 (`claude/content-a`, `main` 미병합)

- 수정 ①: V4 `ck_content_source_one_ref`의 EXTERNAL_URL 조건이 NULL URL을 통과시킴(CHECK 결과가 NULL이면 PostgreSQL이 허용). V4가 이미 적용된 DB(`aicontent_test`, `aicontent_mig_fresh`, `aicontent_mig_upgrade`)가 있어 V4는 고치지 않고 V5로 제약 교체(URL 필수, `http(s)://호스트`, 공백 불가). 직접 삽입 회귀 테스트 추가
- 수정 ②: `CONTENT_A_PLAN.md`의 "A1·A2 구현"과 "V4·코드 미구현" 모순 정리 → 맨 앞에 작업별 진행 상태 표
- A3: `POST /api/admin/contents/{id}/{submit,approve,return,hold,resume,exclude,archive,restore}`, 전이·권한표와 검수 기록 칸 규칙은 계획 4절(최종). 승인·복귀 SYSTEM_ADMIN만(본인 작성분 승인 허용), 사유 필수(반려·보류·제외·복귀), 제출 조건 422, version 필수·행 잠금
- 테스트(로컬 `aicontent_test`, stub): 새 테스트 12개(정책 표 3, 상태 API 6, 동시성·원자성 3) + 스키마 회귀 보강 포함 전체 `mvn package` **237개 통과**(기준: A3 코드 커밋). 대시보드 스키마 버전 기대값 4→5
- 마이그레이션 실제 확인(별도 DB): 빈 DB `aicontent_mig5_fresh` V1~V5, `aicontent_mig_upgrade` V4→V5, `aicontent_main` 새 복사본 `aicontent_mig5_upgrade` V3→V5 — 두 업그레이드 모두 기존 11개 테이블 행 md5 동일, 업그레이드 DB에서 NULL URL 출처 삽입 거부 확인(확인용 콘텐츠 1건이 그 검증 DB에 남음). 원본 `aicontent_main`은 V3, daily 서버 그대로
- UI 변경 없음 → 타입 검사·빌드 생략. 유료 AI 호출 0

## 17. 2026-10-15 콘텐츠 A4 출처·A5 관련 URL (`claude/content-a`, `main` 미병합)

- API: `GET/POST /api/admin/contents/{id}/sources`, `PATCH/DELETE .../sources/{sourceId}`, `GET/POST .../links`, `PATCH/DELETE .../links/{linkId}`, `PUT .../links/order`. 규칙은 `CONTENT_A_PLAN.md` 5절
- 공통 변경 경로 `ContentChanges`로 제목·본문 편집(A2)도 옮김(동작 동일, 응답에 `changed`·`itemId` 추가)
- V6: 링크 표시 위치 BODY_TOP·BODY_BOTTOM(기본)·SOURCES, NULL은 BODY_BOTTOM으로 채우고 다른 값은 마이그레이션이 멈추게 함(행 삭제·추측 없음), 링크 URL 규칙을 V5 출처와 통일
- **테스트 코드 문제 발견·수정:** MockMvc 응답을 `getContentAsString()`(ISO-8859-1)으로 읽어 한글이 깨졌고, 그래서 A2·A3의 "승인 콘텐츠가 공개 응답에 없음"(`doesNotContain(한글)`) 검사는 노출돼도 통과할 수 있었다. 콘텐츠 테스트 전체를 UTF-8로 읽도록 고침(이제 실제로 검사함)
- 테스트(로컬 `aicontent_test`, stub): 새 테스트 8개(출처·링크 API 6, 동시성·원자성 2) + 스키마 V6 검사 포함 전체 `mvn package` **245개 통과**(기준: A4·A5 코드 커밋)
- 마이그레이션 실제 확인(별도 DB): 빈 DB `aicontent_mig6_fresh` V1~V6(기본값 BODY_BOTTOM, NOT NULL), `aicontent_mig5_upgrade` V5→V6(미리 넣은 NULL 위치 링크 → BODY_BOTTOM, 나머지 13개 테이블 행 동일), `aicontent_main` 새 복사본 `aicontent_mig6_upgrade` V3→V6(기존 11개 테이블 행 동일). 원본 `aicontent_main`은 V3, daily 서버 그대로
- UI 변경 없음 → 타입 검사·빌드 생략. 유료 AI 호출 0

## 18. 2026-10-15 A5 보완(링크 500개) · A6 원천 이슈 변경 표시 (`claude/content-a`, `main` 미병합)

- A5 보완: 관련 URL 콘텐츠당 500개(정렬 요청 한도와 같음), 501번째 409 `CONTENT_LINK_LIMIT_EXCEEDED`, 콘텐츠 행 잠금 안에서 셈. 모든 DB에서 콘텐츠당 링크 최대 1개 → 기존 데이터 영향 없음
- A6: 이슈 갱신 경로 점검 결과 시각 비교는 ① 트랜잭션 시작 시각(`now()`) 때문에 동시 변경을 놓치고 ② 예전 기사 추가를 놓치며 ③ 재계산만으로도 바뀜 → V7 `issue.change_seq`(트리거) + `content.origin_ack_seq`. 목록·상세 `originChange`, `updateNeeded` 필터(쿼리에서 적용), `POST .../acknowledge-origin {version, originChangeSeq}`. 규칙은 `CONTENT_A_PLAN.md` 5절
- 테스트(로컬 `aicontent_test`, stub): 새 테스트 10개(링크 한도 2, 원천 변경 8) 포함 전체 `mvn package` **255개 통과**(기준: A6 코드 커밋). 대시보드 스키마 버전 기대값 6→7
- 마이그레이션 실제 확인(별도 DB): 빈 DB `aicontent_mig7_fresh` V1~V7, `aicontent_mig6_upgrade` V6→V7(미리 넣은 콘텐츠 2건: 이슈 변경 뒤 확인한 것 → 확인됨, 그 전 것 → 미확인; 기존 14개 테이블 행 동일[새 열 제외]; 업그레이드 뒤 제목 변경은 번호 +1, 임베딩만 갱신은 그대로), `aicontent_main` 새 복사본 `aicontent_mig7_upgrade` V3→V7(기존 11개 테이블 동일). 원본 `aicontent_main`은 V3, daily 서버 그대로
- UI 변경 없음 → 타입 검사·빌드 생략. 유료 AI 호출 0

## 19. 2026-10-15 상세 조회 일관성 · A7 콘텐츠 화면 · A8 공개 분리 (`claude/content-a`, `main` 미병합)

- 상세 조회: 출처·링크를 따로 읽던 3개 문장 → 한 SQL 문(`jsonb_agg`). 동시 변경 중 읽기 테스트가 고치기 전 실패("version 3, 출처 3, 링크 2"), 고친 뒤 통과. 추가 잠금 없음
- `GET /api/admin/me`(역할 조회) + 테스트
- A7 화면: `/contents` 목록, `/contents/{id}` 편집기(섹션·문단, 자동저장 큐, 충돌, 보관본, 상태 버튼, 출처, 관련 URL, 원천 이슈), 이슈 상세 "콘텐츠 만들기". 규칙은 `CONTENT_A_PLAN.md` 5절
- 테스트: `npm run verify:content-session` 15개(자동저장 2초·되돌림·IME·저장 중 입력·순서·version 전달·저장 실패 시 동작 차단·한도·충돌·연타·키 순서·보관본·id, 서버 상수와 한도 비교) — 일부러 깨뜨린 코드(응답이 입력 덮기, 큐 제거, 실패 뒤 동작 실행)에서 실패 확인. 끝나지 않는 테스트도 실패로 처리
- 기존 `verify-auth-client`는 로컬 검증 모드 조회(`/api/local-verify/mode`)를 요청으로 세어 이전부터 실패하고 있었음 → "관리자 요청 없음"을 검사하도록 수정
- 전체 `mvn package` **257개 통과**, 타입 검사·`next build` 통과, `verify-dashboard`·`verify-ai-usage`(검증 UI, SYSTEM_ADMIN 로그인)·`verify-public-news` 통과. `verify-flow`는 실제 RSS 수집·실제 AI가 필요해 실행 안 함
- 검증 환경 `verify-content`(DB `aicontent_verify_content` = `aicontent_verify_scenario` 복사본 V3→V7, 백엔드 18093·UI 13093, stub, 작업자 끔, 화면에 "콘텐츠 화면 검증용 데이터" 표시). 검증 DB에만 OPERATOR·SYSTEM_ADMIN 계정 생성(비밀번호는 기록하지 않음)
- 브라우저 확인(내장 브라우저, 실제 로그인): NEWS·LIFE 생성, 제목·본문 자동저장·새로고침 유지, 출처 4종·중복·잘못된 URL·원천 출처 삭제 불가·역할 변경, 링크 추가·수정·삭제·정렬·`javascript:` 거부, 검수 요청→승인→편집 시작 확인 1회→DRAFT, 반려·보류·보관·제외·복귀·보류 해제와 사유, 두 탭 충돌→비교→다시 불러오기→보관본 복원, 저장 중 입력, 저장 실패 시 검수 요청 미전송, 연타 1회, 생성 응답 유실 뒤 같은 키 재시도(콘텐츠 1개), 원천 변경 표시·확인 경합 409·재확인·새 변경 재표시·병합 안내, 역할별 버튼과 UI 우회 시 403, 승인 콘텐츠가 공개 API·뉴스룸에 없음
- 브라우저 검증 중 고친 것: 출처 역할 선택이 예전 값을 보내던 문제(비동기 요청이 React가 되돌린 값을 읽음), 버튼 연타 시 여러 번 전송(상태 대신 ref로 막음), 같은 값 저장 시 "바꿨습니다" 대신 "바뀐 내용 없음"
- 실제 한글 IME는 자동화로 칠 수 없어 composition 이벤트로 확인. 이탈 확인 창은 사용자 조작이 없으면 브라우저가 띄우지 않아 처리기 동작(`beforeunload` 취소 여부)으로 확인
- 유료 AI 호출 0. `aicontent_main`(V3)·daily 서버(3000·8080) 그대로

## 20. 2026-10-19 새 노트북 이전 점검 · 실행 스크립트 PC별 경로 (`claude/content-a` 기준 패치, 미커밋)

- 기준: 노트북 작업 폴더 `main` = `8e0c6d7`(10-19 13:01 clone, fetch 완료, 미커밋 변경 없음). **다른 PC의 최신 작업이 `main`에 없음**: `origin/claude/content-a` 19커밋(`ad9d5a2`, 15~19절), `origin/claude/post-merge-check` 1커밋(`6c75b16`, 14절 문서). 두 브랜치 모두 `main` 위에서 갈라져 뒤처짐 0. 서로는 `TASK.md`에서 충돌(병합 시 14절과 15절 이후를 둘 다 남기면 됨). `main`에서 중복 구현하지 않고 `content-a`(`ad9d5a2`) 기준으로 작업
- 저장소에 `AGENTS.md` 없음(15절과 같음) → `RULES.md`·이 문서 기준
- 노트북 환경(파일 목록으로 확인, 이 세션은 노트북 터미널 입력 불가): JDK 21.0.8·17.0.10(`C:\Program Files\Java`), Git 있음. **Node.js·npm 없음, Maven 없음(저장소에 wrapper 없음), Docker Desktop 없음, PostgreSQL 없음.** `.local/`(프로젝트 PostgreSQL·pgvector·Maven·`machine.ps1`·계정 비밀번호 파일)과 이전 PC의 DB 데이터(`aicontent_main` 등)는 Git에 없어 넘어오지 않음. IntelliJ 설정 폴더는 표준 위치(`Program Files`, `AppData\Roaming`)에서 찾지 못함
- 확인된 결함(새 PC에서 실행을 막음): `common.ps1`이 `machine.ps1`을 **기본값보다 먼저** 읽어 `$PgBin`·`$PgData`·`$MavenCmd`를 덮어써도 되돌아감(`.local` 경로만 가능). DB 포트 `55432`가 `start-db.ps1`·`start-backend.ps1`에 고정돼 docker compose(5432)와 함께 쓸 수 없음. 도구가 없으면 "명령을 찾을 수 없음"으로만 실패
- 수정: `common.ps1`(기본값 → `machine.ps1` 순서, `$DbPort` 추가·검증, `$MavenCmd`가 없으면 PATH의 `mvn`, 금지 변수 검사를 `Get-RiskyEnvNames`로 옮겨 공유), `start-db.ps1`(도구가 없을 때 포트가 열려 있으면 외부 DB로 인정, 아니면 안내와 함께 중단, 클러스터 없음 안내), `start-backend.ps1`(DB URL에 `$DbPort`, 주소는 `127.0.0.1` 고정 유지), `build.ps1`(Maven 없음 안내)
- 추가: `check-env.ps1`(읽기 전용 점검. Git 브랜치·미커밋·현재 브랜치에 없는 원격 커밋, Java·Maven·Node·`node_modules`, DB 포트·환경별 DB·pgvector·스키마 버전, Docker, 환경 변수는 설정 여부만, 실행 중인 서버·health. 필수 누락 시 종료 코드 1), `test-run-scripts.ps1`(임시 폴더 복사본으로 위 동작 15개 확인, 서버·DB·Maven·Node 불필요). RUNBOOK 3절 갱신
- 테스트(클라우드 Linux, PowerShell 7.4.6): `test-run-scripts.ps1` 15개 통과. 이전 `common.ps1`로 되돌리면 9개 실패, `check-env`가 비밀값을 출력하도록 일부러 바꾸면 1개 실패 확인. 6개 스크립트 구문 오류 0. 별도로 가짜 Java·Node·Maven과 임시 PostgreSQL 16(pgvector 없음/있음)으로 `check-env.ps1` 출력 확인(비밀값 미출력, pgvector 없는 서버 `[MISSING]`)
- **미실행:** Windows PowerShell 5.1에서의 실행(노트북 터미널 입력 불가), `mvn package`·`npm ci`·타입 검사·`next build`(클라우드에서 Maven Central·npm 레지스트리 차단, 노트북에는 Maven·Node 없음). 백엔드·화면 코드는 바꾸지 않음. 유료 AI 호출 0

### 20-1. 2026-10-19 오후 노트북 실행 결과 (Windows PowerShell 5.1.26100, JDK 21.0.8)

- 기준 커밋: `claude/content-a` = `ad9d5a2`(origin과 같음) + 위 패치(미커밋). 이전 PC의 `.local`(pg16·pgdata·Maven·m2)을 복사해 옴, `machine.ps1`은 `JAVA_HOME = jdk-21`만 지정
- 실행 명령: `powershell -ExecutionPolicy Bypass -File .\.local\setup-laptop.ps1`(브랜치 전환 → 패치 적용 → 자체 점검 → check-env → npm ci → DB 시작 → `build.ps1` → `mvn test` → check-env). 1회차 로그는 `.local\setup-laptop.run1.log`, 2회차는 `.local\setup-laptop.log`(Git 밖)
- 1회차에서 나온 문제 2건과 수정:
  - `test-run-scripts.ps1`이 4개 통과 뒤 중단: Windows PowerShell 5.1은 `$ErrorActionPreference = 'Stop'` 상태에서 네이티브 명령의 stderr를 `2>&1`로 받으면 종료 오류로 바꿈(클라우드 PowerShell 7에서는 안 생김). 자식 셸 실행부(`Invoke-Copy`)만 `Continue`로 바꿈 → **15개 통과**(수정 전 같은 PC에서 실패 확인). 검사 내용은 바꾸지 않음
  - DB 시작 단계에서 멈춤: `setup-laptop.ps1`이 `start-db.ps1` 출력을 파이프로 받는데, `pg_ctl`이 띄운 postgres가 그 파이프 핸들을 물려받아 파이프가 닫히지 않음. DB는 정상 기동(이전 PC에서 비정상 종료된 데이터라 자동 복구 후 `accepting connections`). `.local\setup-laptop.ps1`(Git 밖)에서 이 단계만 별도 숨김 창으로 실행하도록 바꿈. `start-db.ps1`을 터미널에서 직접 실행하는 경우는 해당 없음
- 2회차 결과:
  - 자체 점검 `test-run-scripts.ps1` **15개 통과**
  - `build.ps1`(`mvn package -DskipTests`) **BUILD SUCCESS**, `.local\run\platform-backend.jar` 갱신(15:10)
  - `mvn test`(`TEST_DB_URL` = `127.0.0.1:55432/aicontent_test`, stub AI): **257개 통과, 실패 0, 오류 0, 건너뜀 0**(surefire 보고서 합계, 19절과 같은 개수)
  - check-env: Java 21.0.8·Maven 3.9.9·프로젝트 PostgreSQL 정상. **`[MISSING]` node, admin-ui deps** → 이때는 `npm ci`·타입 검사 미실행(Node.js 미설치)
- 사용자가 Node.js LTS 설치(v24.20.0, npm 11.19.0) 뒤 이어서 실행:
  - `admin-ui`에서 `npm ci --no-audit --no-fund` **통과**(exit 0), `npm run typecheck`(`tsc --noEmit`) **통과**(exit 0). npm이 `next@14.2.15`에 보안 취약점 경고를 냄 → 21절에서 업그레이드
  - `start-ui.ps1 -Target verify-content`: `next build` **Compiled successfully**, 127.0.0.1:13093 기동. `/`·`/contents`·`/news` 200. 내장 브라우저에서 `/contents` 목록이 백엔드 18093 데이터(콘텐츠 4건, 이전 PC 검증 데이터)를 표시하는 것 확인. 화면 기능 전체 재검증은 하지 않음(19절에서 이전 PC로 확인)
  - 기동 뒤에도 `aicontent_main` V3
- DB 버전 확인(실행 전후 같음): `aicontent_main` **V3 그대로**, `aicontent_verify_content` V7, `aicontent_test` V7. daily 환경(8080·3000)·`aicontent_main` 실행 안 함
- 검증 환경 `verify-content`: 백엔드 `start-backend.ps1 -Target verify-content`(127.0.0.1:18093, stub, 작업자 끔) **기동 확인**: `/actuator/health` UP, `/api/local-verify/mode` = loginBypass true·aiProvider stub, Flyway "V7, 마이그레이션 없음", 기동 뒤에도 `aicontent_main` V3. 화면 13093은 처음엔 Node.js가 없어 못 띄웠고, 설치 뒤 기동(위 항목)
- 이번 세션에서 띄운 PostgreSQL·백엔드는 도구 세션에서 시작돼 세션이 끝나면 함께 멈출 수 있음. 계속 쓰려면 사용자 터미널에서 `start-db.ps1`부터 다시 실행
- 유료 AI 호출 0. 커밋·push·병합 안 함

## 21. 2026-10-19 관리자 화면 Next.js 보안 업그레이드 14.2.15 → 15.5.28 (`claude/content-a` 기준, 미커밋)

- 선정 이유: A 묶음(A1~A8)은 끝났고, B(LIFE 판정)·C(AI 생성)는 `NEXT_CONTENT_SCOPE.md` 6절 3·4·7·10·11번 결정이 필요해 임의로 시작하지 않음. 오늘 확인된 결함 중 남은 것이 `npm audit` **critical**(next 직접 권고 30개 이상). 그중 "Windows에서 실행하는 서버의 인증 없는 원격 코드 실행"(<15.5.24)은 이 프로젝트가 Windows에서 돌기 때문에 해당됨. 14.2.x 최신(14.2.35)으로는 대부분이 안 고쳐져서(수정 버전이 15.5.x) 15.5 최신으로 올림
- 영향 범위 확인: 관리자 화면은 전부 클라이언트 컴포넌트, `middleware` 없음, 인증·검수·공개 조건은 모두 Spring 백엔드가 판단(Next는 `/api/*` 프록시만). 백엔드 코드 변경 없음 → 검수·공개 조건 우회 경로 변화 없음
- 변경:
  - `admin-ui/package.json`·`package-lock.json`: `next` 15.5.28, `react`·`react-dom` 19.1.10(Next 15 앱 라우터 기준), `@types/react` 19.1.17·`@types/react-dom` 19.1.11. 잠금 파일에 새로 들어온 것은 Next 15의 선택 의존성 `sharp`(+플랫폼별 바이너리)뿐
  - `admin-ui/src/app/news/[id]/page.tsx`: Next 15는 페이지 `params`를 Promise로 넘기므로 다른 상세 페이지(`issues/[id]` 등)처럼 `useParams`로 읽게 바꿈. 화면·요청 동작은 같음
- 검증(기준 `ad9d5a2` + 20절 패치 + 이 변경, Node 24.20.0, npm 11.19.0):
  - `npm audit --omit=dev`: next 직접 권고 **0**. 남은 것은 next 안에 묶인 `postcss`(high, 빌드할 때 이 프로젝트의 CSS에만 쓰여 외부 입력이 들어가지 않음). 없애려면 next 16 필요 → 이번에는 하지 않음
  - `npm ci`(잠금 파일로 처음부터 설치) **통과**, `npm run typecheck` **통과**
  - `start-ui.ps1 -Target verify-content -Rebuild`: `next build`(Next.js 15.5.28) **Compiled successfully**, 14개 경로, 13093 기동
  - `npm run verify:content-session` **15/15 통과**, `npm run verify:auth-client` **통과**
  - 내장 브라우저(13093 → 백엔드 18093, local-verify): 대시보드, `/news`, `/news/4`(이번에 바꾼 페이지), `/issues/4`, `/contents/4` 표시, 콘솔 오류 0. `/contents/4`에 제목 입력 → 자동저장 `PATCH` 200 → 새로고침 뒤 유지(version 0→1, 검증 DB `aicontent_verify_content`에 남음)
  - 공개 분리 재확인(새 프록시 경유): `/api/public/issues` 응답에 콘텐츠 제목 없음, `/api/public/contents` 403
  - 백엔드 변경 없음 → `mvn test`는 20-1절 결과(257개 통과) 유지, 다시 돌리지 않음
- **미실행:** `verify:dashboard`·`verify:ai-usage`(관리자 계정 비밀번호를 입력해야 함), `verify:public-news`(주소가 `localhost:3000` = daily 환경으로 고정돼 있고 daily 데이터를 기대함, daily는 실행 금지), `verify:flow`(실제 RSS·실제 AI 필요). 화면 기능 전체를 하나씩 다시 확인하지는 않음(19절 항목은 이전 PC에서 확인)
- `aicontent_main` V3 그대로, daily 실행 안 함, 유료 AI 호출 0. 커밋·push·병합 안 함

## 22. 2026-10-19 밤 `claude/content-a` → `main` 병합 전 검토와 PR 준비

**20·21절 이후 실제 상태(위 절의 "미커밋·push 안 함"은 그 시점 기록):** 20절 패치는 `9762eb0`, 21절은 `9fee68b`로 커밋해 같은 날 `origin/claude/content-a`에 일반 push함. 검토 시작 시 원격 `9fee68b`, `main` `8e0c6d7`(변경 없음), 이 브랜치의 PR 없음(GitHub API 조회). 브랜치는 `main`보다 21커밋 앞, 뒤처짐 0, 67개 파일

**검토 범위와 결과(main 대비 전체 diff):**
- 상태 전이: `ContentStatusPolicy` 표가 `CONTENT_A_PLAN.md` 4절 최종 표와 같음(출발 상태·결과·사유 필수·SYSTEM_ADMIN 전용). 검수 기록 칸(`submitted_at`·`approved_*`·`status_reason`)도 4절 표대로(`storesReason`이 아닌 동작의 사유는 감사 기록에만). 상태는 이 동작과 편집 규칙으로만 바뀌고 `PATCH`는 `status`를 받지 않음
- 권한: 승인·복귀는 `SecurityConfig`(경로)와 서비스 정책(역할) 두 곳에서 SYSTEM_ADMIN 검사. 콘텐츠 API는 모두 `/api/admin/**`, 공개 경로에 콘텐츠 없음
- 검수·공개 우회: 제목·본문·출처·URL 변경이 모두 `ContentChanges` 한 경로(행 잠금 → 잠금 상태 409 → version 409 → 실제 변경 시에만 version +1, REVIEW·APPROVED → DRAFT, 승인 해제 기록). 원천 이슈 출처 삭제 불가. 확인 처리(acknowledge)는 승인 상태를 바꾸지 않는 별도 경로(계획 5절)
- 중복 요청·동시 수정: 생성은 `creation_key` UNIQUE + 요청 지문, 상태·편집은 같은 행 잠금·version. 화면은 요청 큐 하나로 순서대로 보냄(`verify:content-session`)
- 마이그레이션: V4(새 테이블 3개만) → V5(V4 CHECK 교체) → V6(링크 위치 값·URL 규칙) → V7(`issue.change_seq`·트리거, `content.origin_ack_seq`). V3 DB에 적용 시 기존 테이블 행은 바뀌지 않음(15~18절에서 `aicontent_main` 복사본으로 행 md5 비교). 영향: V7 이후 `issue` 갱신마다 BEFORE 트리거, `issue_article` 추가·삭제·이동마다 해당 `issue` 행 UPDATE(그 행 잠금)가 추가됨 — 기존 동시성 테스트는 통과. 한 번 적용하면 Flyway 기록이 V7이 되므로, 예전 코드(V3)로 그 DB를 띄울 때는 Flyway가 "미래 버전"으로 보고 넘어가지만 트리거는 남음(되돌리는 마이그레이션 없음)
- Next.js 15·React 19: 모든 페이지가 클라이언트 컴포넌트, `middleware` 없음, 인증은 브라우저 `sessionStorage`의 Basic 헤더를 Next `rewrites`가 백엔드로 넘김. 15에서 바뀐 것 중 해당은 페이지 `params`(21절 `news/[id]`)뿐
- **찾은 문제와 수정:**
  1. `check-env.ps1` Node 기준이 next 14.2 값(18.17+) 그대로였음 → next 15.5 `engines`(`^18.18 || ^19.8 || >=20`)로 수정
  2. `check-env.ps1`이 `node_modules` 폴더만 있으면 OK → 다른 PC가 next 15 커밋을 받고 `npm ci`를 안 하면(next 14 남음) 통과로 보였음. 설치된 next와 `package.json`의 next가 다르면 `[MISSING]`. `test-run-scripts.ps1`에 2개 추가(**17개**), 이전 `check-env.ps1`로 바꾸면 새 2개 실패 확인
  3. `verify-public-news.cjs` 주소가 `localhost:3000`(daily) 고정, 첫 페이지 카드 12개·페이지 넘김을 항상 기대 → 기본값은 그대로 두고 `VERIFY_UI_URL`로 검증 환경 지정(다른 verify 스크립트와 같은 방식, 로컬 주소만 허용). 카드 수는 `min(12, 전체)`, 페이지 넘김은 12개 넘을 때만(건너뛰면 결과 줄에 표시). daily에서는 전과 같은 검사
  4. RUNBOOK 3절 Node 기준·의존성 변경 뒤 `npm ci`·`-Rebuild` 안내 갱신
- 백엔드 코드에서 결함은 찾지 못함(변경 없음)

**postcss 권고(남은 high 1건) 판단:**
- 경로: `next@15.5.28` → `postcss@8.4.31`(next가 정확한 버전으로 고정, 직접 의존성 아님). 권고 4건: GHSA-qx2v-qp2m-jg93(<8.5.10, 문자열화 출력에 `</style>` 미이스케이프 → XSS), GHSA-6g55-p6wh-862q(≤8.5.11)·GHSA-fxqj-rqcc-2cmp(≤8.5.22)·GHSA-r28c-9q8g-f849(≤8.5.17)(CSS 주석의 `sourceMappingURL`로 임의 `.map` 파일 읽기)
- 성립 조건: 공격자가 PostCSS에 넣는 CSS를 정할 수 있어야 함(그 결과를 HTML `<style>`에 넣거나, source map을 읽게 하는 경우)
- 이 프로젝트: next 안에서 postcss를 부르는 코드는 빌드용(webpack CSS 로더·글꼴 로더·CSS 압축)뿐이고, 빌드 결과 서버 코드(`.next/server`)에는 postcss가 없음. 처리되는 CSS는 저장소의 `globals.css`·`news/news.css` 2개(사람이 커밋, `sourceMappingURL` 없음, postcss 설정 파일 없음). 사용자 입력은 CSS로 처리되지 않고 텍스트로만 렌더링(A7 규칙). 따라서 실행 중 서버로는 성립하지 않고, 빌드 입력을 바꿀 수 있는 사람은 이미 저장소 쓰기 권한(빌드 스크립트 실행)을 가진 경우뿐
- 판단: **이번 PR의 병합 차단 아님**, 후속 작업. 해결 방법은 ① postcss 8.5.23 이상을 쓰는 next 15.5.x가 나오면 올림 ② next 16 이전(주 버전 변경, 별도 작업·검증) ③ `overrides`로 postcss 강제(next가 8.4.31로 고정해 호환성 미검증이라 이번에는 하지 않음). `npm audit fix --force`(next 16 설치)는 실행하지 않음

**검증(기준: `9fee68b` + 이 절의 미커밋 변경, Node 24.20.0, Windows PowerShell 5.1):**
- `mvn test`(`aicontent_test`, stub): **257개 통과, 실패·오류·건너뜀 0**(21:09 새로 실행, surefire 합계)
- `test-run-scripts.ps1` **17개 통과**, `check-env.ps1` 필수 누락 0(next 15.5.28 인식)
- `npm ci`·`typecheck` 통과(21절), `next build` 15.5.28 통과(13093, 그리고 아래 13094 복사본)
- `verify:content-session` 15/15, `verify:auth-client` 통과(21절, UI 코드는 그 뒤 변경 없음)
- **검증 모드**(verify-content, 18093·13093, 로그인 생략): `verify:public-news`를 `VERIFY_UI_URL=http://127.0.0.1:13093`으로 **통과**(이슈 5개라 페이지 넘김은 건너뜀으로 표시), 콘텐츠 편집기 자동저장 PATCH 200·새로고침 유지(21절), 빈 본문 검수 요청 → 422 `CONTENT_NOT_READY`가 화면 경고로 표시되고 상태 안 바뀜
- **일반(로그인) 모드**: 검증 DB 복사본 `aicontent_verify_auth_20261019`(V7)에 기본 프로필 백엔드 127.0.0.1:18094(로그인 생략 꺼짐, stub, 작업자·스케줄러 끔)와 그 주소로 빌드한 화면 13094. 시험용 SYSTEM_ADMIN은 기동 시 `ADMIN_USERNAME`/`ADMIN_PASSWORD`로 만들고 비밀번호는 `.local/run/verify-auth.cred`(Git 제외)에만 둠, 백엔드 로그에 비밀번호 없음 확인
  - 백엔드 직접·Next 프록시 경유 각각: 로그인 생략 꺼짐, 인증 없이 `/api/admin/me`·목록·PATCH 401, 틀린 비밀번호 401, 시험 계정 200(SYSTEM_ADMIN), 공개 이슈 200. `/api/public/contents`는 인증 없이 401(경로 없음 → `denyAll`; 로그인 생략 모드에서는 이미 인증된 요청이라 403) — 어느 쪽이든 열리지 않음
  - 프록시 경유 로그인 흐름: 생성 201 → 같은 `Idempotency-Key` 재요청 200·같은 id → 편집 200 → 오래된 version 409 → 검수 요청 REVIEW → 승인 APPROVED → 공개 이슈 상세에 콘텐츠 제목 없음 → 승인 후 편집 DRAFT(`statusChanged=true`). 콘텐츠 #5가 그 복사본 DB에 남음
  - `verify:dashboard`·`verify:ai-usage`(`VERIFY_UI_URL=http://127.0.0.1:13094`, 시험 계정): **둘 다 통과**(브라우저 오류 0·API 실패 0)
  - 확인 뒤 18094·13094 종료
- **미실행:** OPERATOR 계정으로 승인·복귀 403 화면 확인(시험 계정은 SYSTEM_ADMIN 하나뿐, `ContentStatusApiIntegrationTest`가 서비스·보안 두 곳의 403을 검사), `verify:flow`(실제 RSS·실제 AI 필요), daily(8080·3000)·`aicontent_main`(실행 금지), Docker compose 구성(노트북에 Docker 없음)
- `aicontent_main` V3 그대로, 유료 AI 호출 0
