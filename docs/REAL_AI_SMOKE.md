# 실제 AI 공급자 소량 검증 절차

목적: stub·모의 서버가 아닌 **실제 OpenAI API**로 기사 1~3건이 분석 → 1536차원 임베딩 → 이슈 분류까지
끝나는지, `ai_log`에 공급자·모델·성공 여부·토큰이 남는지 확인한다. 분류 **품질**은 여기서 판단하지 않는다
(평가 세트로 따로 측정, `docs/EVALUATION.md`).

작성 2026-10-12. 이 절차는 아직 실제로 실행되지 않았다. 실행 후 맨 아래 기록란을 채운다.

## 0. 준비물

| 항목 | 확인 |
|---|---|
| OpenAI API 키 | 이 프로젝트 전용 키. 사용 한도(예산)를 소액으로 걸어 둔다 |
| 모델 접근 | `gpt-4o-mini`, `text-embedding-3-small` 사용 가능 |
| 키 보관 | 이 터미널의 환경 변수, 또는 Git 제외 대상 `backend/application-local.yml`. 문서·커밋·Notion·채팅에 붙여 넣지 않는다 |
| 실제 모델 전용 빈 DB | `aicontent_live`. stub 벡터가 든 `aicontent`와 섞지 않는다 |
| 호출 상한(선택) | 실수로 많이 호출되지 않게 `$env:AI_DAILY_MAX_CALLS = '30'`처럼 하루 호출 수를 제한할 수 있다(기사 3건이면 분석·임베딩·분류 합쳐 10건 안팎). 한도에 닿으면 작업은 실패하지 않고 다음 날까지 대기하며, 관리자 **AI 사용량** 화면에 표시된다. OpenAI 계정의 사용 한도와는 별개다 |
| 단가(선택) | `app.ai.pricing`에 모델별 단가를 설정하면 호출마다 `ai_log.estimated_cost_usd`에 비용이 기록된다. 설정하지 않으면 NULL로 남는다(0이 아님). **2026-10-14:** `live` 프로파일에 공식 단가가 들어 있다(확인일·출처는 `application-live.yml` 주석). 가격이 바뀌면 그 값을 갱신한다. 기록되는 값은 추정 비용이며 청구액이 아니다 |

주의: `gpt-4o-mini` 대신 temperature를 받지 않는 추론 모델로 바꾸면 요청이 HTTP 400으로 실패한다.
이번 검증은 기본 모델 그대로 한다.

## 1. DB 준비 (최초 1회)

```powershell
& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata status   # 꺼져 있으면 DB_SETUP.md의 start 명령
& .local/pg16/pgsql/bin/createdb.exe -h 127.0.0.1 -p 55432 -U aicontent aicontent_live
```

## 2. 백엔드 실행 (새 PowerShell)

```powershell
cd backend
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/aicontent_live'
$env:DB_PASSWORD = '<아무 값: 로컬 trust DB>'
$env:ADMIN_USERNAME = 'admin'
$env:ADMIN_PASSWORD = '<로컬 관리자 비밀번호>'
$env:AI_PROVIDER = 'openai'
$env:OPENAI_API_KEY = '<키>'                 # 또는 application-local.yml + 프로파일 live,local
$env:NEWS_MAX_ARTICLES_PER_RUN = '3'         # 소스 1개만 수집하면 최대 3건
mvn spring-boot:run '-Dspring-boot.run.profiles=live'
```

- 키가 없으면 서버가 설정 오류로 바로 종료한다(stub으로 대체하지 않음). 이것도 정상 동작이다.
- 자동 수집 스케줄러는 꺼져 있다(`NEWS_SCHEDULER_ENABLED=false`). 수동으로 한 번만 수집한다.

## 3. 수집 1회

관리자 UI(`http://localhost:3000`) → 뉴스 소스 → **`yonhap-latest` 하나만** 수집 버튼.
BBC는 이번에 누르지 않는다(최대 3건 유지). **대시보드의 "뉴스 수집" 버튼은 켜진 소스를 모두 수집하므로
쓰지 않는다**(소스 2개 × 3건 = 최대 6건). `NEWS_MAX_ARTICLES_PER_RUN`은 소스 1개당 한도다.
대시보드에서 작업이 모두 SUCCESS/FAILED로 끝날 때까지 기다린다(보통 1~2분).

## 4. 결과 확인

```powershell
& .local/pg16/pgsql/bin/psql.exe -h 127.0.0.1 -p 55432 -U aicontent -d aicontent_live -f docs/sql/real_ai_smoke_check.sql
```

마지막 `7. 판정`의 8개 값이 모두 `t`이면 통과다.

| 판정 열 | 뜻 |
|---|---|
| `article_count_1_to_3` | 중복을 뺀 기사가 1~3건 |
| `only_openai_in_log` | `ai_log`가 전부 openai (stub 섞임 없음) |
| `all_analyzed_and_classified` | 모든 기사가 ANALYZED, 분류 상태 CLASSIFIED 또는 REVIEW |
| `all_embeddings_1536` | 임베딩이 모두 1536차원 |
| `analysis_tokens_logged` / `embedding_tokens_logged` | 성공 호출에 토큰 수가 기록됨 |
| `no_jobs_in_flight` / `no_failed_jobs` | 대기·실행 중·실패 작업 없음 |

REVIEW 판정은 실패가 아니다. 애매한 경우 사람에게 넘기는 설계대로의 결과다.
첫 기사는 비교할 이슈가 없으므로 LLM 호출 없이 NEW_ISSUE(`NO_CANDIDATE`)가 정상이다.
그래서 `ISSUE_CLASSIFY` 호출은 기사 2건째부터, 후보 이슈가 검색될 때만 생긴다.

## 5. 실패했을 때

API 응답 본문과 키는 로그에 남기지 않으므로 `ai_log.error_code`(HTTP 상태)로 원인을 좁힌다.

| error_code | 흔한 원인 | 조치 |
|---|---|---|
| `OPENAI_HTTP_401` | 키가 틀리거나 폐기됨 | 키 재발급, 환경 변수 확인 |
| `OPENAI_HTTP_403` / `404` | 프로젝트에 해당 모델 권한 없음, 모델 이름 오타 | 대시보드에서 모델 접근 확인 |
| `OPENAI_HTTP_429` | 속도 제한 **또는 결제 미설정·한도 소진**. 재시도 대상이라 3회까지 다시 시도한 뒤 FAILED | 결제·사용 한도 확인. 한도 문제면 재시도로 해결되지 않음 |
| `OPENAI_HTTP_5xx`, 시간 초과 | 일시 장애 | 자동 재시도. 계속되면 나중에 다시 |
| `AI_OUTPUT_INVALID` | 모델 출력이 JSON Schema 검증 실패(2회 시도 후) | `ai_log.response`와 `error_message`로 어느 필드가 틀렸는지 확인. 프롬프트 수정은 새 버전으로 |
| `EMBEDDING_DIMENSION_MISMATCH` | 임베딩 모델이 1536차원이 아님 | `OPENAI_EMBEDDING_MODEL` 확인 |
| `OPENAI_REFUSAL` | 모델이 응답 거부 | 해당 기사 제목·본문과 함께 기록 |

실패 작업은 관리자 화면에서 재시도할 수 있다. 원인을 고치기 전에는 재시도하지 않는다(비용만 든다).

## 6. 기록란

실행 후 아래를 채워 `docs/collaboration/TASK.md` 4절과 Notion `13. 개발 일지`에 옮긴다. 키 값은 적지 않는다.

- 실행 일시(KST) / 기준 커밋:
- 모델: chat `gpt-4o-mini` · embedding `text-embedding-3-small` (응답의 실제 모델명: )
- 기사 수 / 이슈 수 / REVIEW 수:
- 판정 8개 결과:
- 토큰 합계(분석 입력·출력, 임베딩):
- 실패와 원인(있으면 기사 id, error_code):
- 다음 단계: 통과 시 평가 세트 품질 측정(합격 기준 먼저 확정)
