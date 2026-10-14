# 실제 OpenAI 기사 1건 검증

2026-10-14, `feature/public-news` 브랜치에서 실제 유료 OpenAI API로 실행했다.

## 실행 환경과 데이터 분리

- Windows PowerShell, Temurin Java 25.0.4.1(Java 21 대상으로 빌드), Maven 3.9.9.
- PostgreSQL 16.4 + pgvector, `127.0.0.1:55432`, 사용자 `aicontent`, 기존 loopback trust 인증.
- 기존 `aicontent`: 실행 전후 기사 494건, 이슈 481개로 동일. 기존 데이터나 벡터를 복사하지 않았다.
- 새 DB `aicontent_openai_20261014_1`: 처음에 public 테이블 0개였으며 서버 시작 시 Flyway V1~V3 적용 성공.
- 검증 백엔드 포트 8081. 검증 완료 후 종료했으며, 이 DB와 기존 DB는 보존했다.

## 비밀 설정

- API 키는 `.local/openai-one-article/secrets.json`의 `OPENAI_API_KEY`에 사용자가 직접 입력했다.
- 이 파일과 실행 설정·로그는 `.local/` 아래에 있어 Git 제외 확인 완료. 키는 명령 인수나 문서에 넣지 않았다.
- `.local/openai-one-article/start.ps1`이 키를 읽어 자식 프로세스의 환경 변수로 전달한다.
- `.local/openai-one-article/application-local.yml`은 환경 변수의 키를 참조한다.
- 키의 존재와 형식만 출력했으며 서버 로그에 실제 키 문자열이 없는 것을 값 출력 없이 검사했다.

## 적용한 실행 설정

```text
AI_PROVIDER=openai
OPENAI_CHAT_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
app.news.scheduler-enabled=false
app.news.max-articles-per-run=1
app.worker.concurrency=1
app.worker.default-max-retries=0
app.ai.analysis-max-attempts=1
app.ai.classifier-max-attempts=1
app.ai.limits.daily-max-calls=2
app.ai.limits.max-calls-per-minute=2
app.ai.pricing={}
```

BBC World RSS 하나만 등록했다. 자동 수집을 실행하지 않고 수동으로 수집 작업 1개를 등록했다.
이 설정은 이번 빈 DB의 첫 기사 검증용이며 일반적인 후보 비교를 포함하는 파이프라인의 호출 수는 더 많을 수 있다.

## 실제 실행한 주요 명령

프로젝트 루트 기준. 준비 단계의 DB 생성과 빌드도 포함한다.

```powershell
& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata -l .local/postgres-server.log -o '-p 55432 -h 127.0.0.1' -w start
& .local/pg16/pgsql/bin/createdb.exe -h 127.0.0.1 -p 55432 -U aicontent aicontent_openai_20261014_1
```

빌드는 `backend/`에서 실행했다.

```powershell
$env:JAVA_HOME='C:/Users/User/.jdks/temurin-25.0.4.1'
& ../.local/apache-maven-3.9.9/bin/mvn.cmd '-Dmaven.repo.local=../.local/m2' -DskipTests package
```

루트에서 최종 성공한 서버 실행 명령:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .local/openai-one-article/start.ps1
```

기동 후 `/actuator/health`가 `UP`이고, 기사·작업·AI 호출이 모두 0건임을 SQL로 확인했다.
다음 SQL을 `psql.exe -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55432 -U aicontent -d aicontent_openai_20261014_1 -c`에 전달해 수집 작업 1개만 등록했다.

```sql
INSERT INTO async_job(job_type,payload,requested_by,max_retries,correlation_id,dedupe_key)
SELECT 'COLLECT_NEWS',jsonb_build_object('sourceId',id),'openai-one-article-check',0,
       'openai-one-article-20261014','openai-one-article-20261014'
FROM news_source
WHERE name='openai-one-bbc'
  AND NOT EXISTS(SELECT 1 FROM async_job)
  AND NOT EXISTS(SELECT 1 FROM news_article)
RETURNING id,job_type,status;
```

등록 결과는 `INSERT 0 1`. 빈 DB 조건은 중복 실행으로 추가 유료 호출이 발생하는 것을 막는다.

## 실제 결과

| 검증 항목 | 결과 |
|---|---|
| 수집 | BBC World RSS 기사 1건 |
| 기사 제목 | Pope diagnosed with lung cancer but had successful surgery, Vatican says |
| 작업 | `COLLECT_NEWS` → `ANALYZE_ARTICLE` → `EMBED_ARTICLE` → `CLASSIFY_ARTICLE`, 4건 모두 `SUCCESS` |
| 실제 분석 모델 | `gpt-4o-mini-2024-07-18` |
| 분석 토큰 | 입력 720, 출력 185 |
| 실제 임베딩 모델 | `text-embedding-3-small` |
| 임베딩 토큰 | 입력 100 (출력 토큰은 해당 없음) |
| 임베딩 저장 | `vector_dims(embedding)=1536` |
| 분류 | `NEW_ISSUE`, 방식 `NO_CANDIDATE`, 적용 성공 |
| 최종 DB | 기사 1건, ACTIVE 이슈 1개, OpenAI 로그 2건, 미완료 작업 0건 |
| 추정 비용 | 단가 미설정으로 `estimated_cost_usd=NULL`. 무료나 0원으로 보고하지 않는다 |

## 실패한 준비 단계와 수정

1. `start.ps1` 직접 호출은 PowerShell 실행 정책으로 거부됐다. 시스템 전체 설정을 변경하지 않고 자식 프로세스에만 `-ExecutionPolicy Bypass`를 적용했다.
2. PowerShell 5의 `ErrorActionPreference=Stop`이 Java 25 stderr 경고를 종료 오류로 처리했다. Java 실행 부분만 `Continue`로 변경하고 종료 코드를 확인하도록 수정했다.

두 실패 모두 유료 API 호출 전 기동 실패다. 최종 API 호출 2건은 실패와 재시도 없이 성공했다.

## 미검증 항목과 종료 상태

- 빈 DB의 첫 기사에는 후보 이슈가 없어 분류 작업은 로컬 `NO_CANDIDATE` 정책으로 새 이슈를 생성했다. LLM의 기존 후보 비교, SAME_ISSUE, REVIEW, 다중 기사 분류 품질은 미검증이다.
- RSS 제목과 설명문 기반 분석이며 기사 본문 전체 수집과 분석은 미검증이다.
- OpenAI 청구 명세 및 확정 과금액, 부하 및 레이트 제한 상황, 장기 운영은 미검증이다.
- 빌드는 테스트를 생략했다. 이번 성공 증거는 실제 API 호출과 DB 결과의 직접 확인이다.
- 완료 후 실행 세션에 Ctrl+C를 보내 백엔드를 종료했다. 8081 포트 대기 없음, 미완료 작업 0건을 확인했다.
- PostgreSQL은 실행 상태를 유지하며 두 DB를 보존했다.