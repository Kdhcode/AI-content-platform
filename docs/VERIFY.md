# 실행 검증 기록 (2026-10-07)

## 결과

| 대상 | 결과와 범위 |
|---|---|
| 백엔드 컴파일·패키징 | Maven 3.9.9, 설치된 Temurin JDK 25로 Java 21 대상 컴파일·실행 JAR 생성 성공 |
| DB·Flyway | PostgreSQL 16.4 + pgvector 0.8.0에 원본 V1 적용 성공. vector 컬럼과 HNSW 인덱스를 대체하지 않음 |
| 전체 Maven 테스트 | **92개 통과, 실패 0**: 단위 63개 + DB 통합 29개. 대시보드 집계·인증·DB 정보 검증 포함 |
| SQL 검증 | 별도 `aicontent_sql` DB에서 `ALL SCHEMA ASSERTIONS PASSED`, `ALL QUERY ASSERTIONS PASSED` |
| 백엔드 실행 | `http://localhost:8080/actuator/health` → `UP` |
| 관리자 화면 | `npm run build`, `npm run typecheck` 성공. `http://localhost:3000` 실행 |
| 실제 RSS 등록·수집 | `live` 프로파일로 연합뉴스·BBC World 2개를 `news_source`에 등록. 실제 피드 HTTP 200, 기사 저장 성공 |
| 실제 RSS → 관리자 검수 | **AI는 stub**. 기사 9건, 수집 5건 + 분석/임베딩/분류 각 9건 = 작업 32건 모두 SUCCESS. 감사 로그에 REVIEW → ACTIVE 기록 |
| Chrome 관리자 흐름 | 로그인 → 뉴스 소스 → 지금 수집 → 기사 상세 → 검수 확정. 두 소스 각각 PASS, 브라우저 실행 오류 0 |
| 새 대시보드 화면 | 1440px 데스크톱·390px 모바일 검증 통과. 실제 DB의 기사 9건·이슈 8개·완료 작업 32건과 화면 집계 일치. API 오류 0, 모바일 가로 넘침 없음 |
| OpenAI 어댑터 | 인증 헤더, JSON 응답/사용량, 1536차원 임베딩, 401/429/503, 잘못된 응답·차원·키 누락 단위 테스트 통과 |
| OpenAI 어댑터 전체 흐름 | **로컬 HTTP 모의 서버** + 실제 PostgreSQL/pgvector. RSS → 분석 → 임베딩 → 후보 검색 → LLM REVIEW → 관리자 확정 → 감사 로그 통합 테스트 통과 |
| 실제 OpenAI 외부 호출 | **미검증**. 실행 환경에 API 키가 없어 유료 LLM·임베딩 호출과 실제 모델 품질은 확인하지 못함 |

V1 첫 줄의 기존 `git--` 오타는 작업 시작 시 이미 `--`로 수정되어 있었으며, 그 수정을 유지한 채 검증했다.
Redis는 선택 기능이므로 이번 실행에서는 `LOCK_REDIS_ENABLED=false`로 사용했다. Redis 락 실행 검증은 포함하지 않는다.

## 현재 로컬 실행 환경

Docker가 설치되어 있지 않아 프로젝트의 비추적 `.local/` 아래에 임시 도구·DB·로그를 준비했다.

- Maven: `.local/apache-maven-3.9.9/bin/mvn.cmd`, Maven 저장소 `.local/m2/`.
- PostgreSQL: `.local/pg16/pgsql/bin/`, 데이터 `.local/pgdata/`.
- DB: `127.0.0.1:55432`, 개발 DB `aicontent`, 테스트 DB `aicontent_test`.
- PostgreSQL 인증: 이 임시 개발 클러스터는 loopback만 수신하고 `trust` 인증을 사용한다.
- 백엔드: `live` 프로파일, `AI_PROVIDER=stub`, 관리자 `admin` / 비밀번호는 실행 시 `ADMIN_PASSWORD` 환경 변수로 지정 (문서·Git에 기록하지 않음).
- 관리자: `http://localhost:3000`의 **뉴스 소스** 화면에서 수집을 시작할 수 있다.

DB 생성·인증·데이터 위치와 접속 정보: [DB_SETUP.md](DB_SETUP.md).

로그: `.local/backend-dashboard-tests.log`, `.local/backend-all-tests.log`, `.local/backend-package.log`, `.local/backend-server.log`,
`.local/admin-build.log`, `.local/browser-flow.log`, `.local/browser-flow-yonhap.log`,
`.local/sql-schema.log`, `.local/sql-queries.log`.

추가 화면 검증 로그: `.local/browser-dashboard.log`, `.local/browser-flow-redesign.log`.
실제 화면 캡처: `.local/screenshots/newsroom-dashboard.png`, `newsroom-dashboard-preview.png`,
`newsroom-login.png`, `newsroom-mobile.png`.

PostgreSQL 바이너리는 [PostgreSQL 공식 Windows 다운로드 안내](https://www.postgresql.org/download/windows/)가
연결하는 EDB 배포본을 사용했다. 임시 검증용 pgvector DLL은
[portalcorp/pgvector_compiled](https://github.com/portalcorp/pgvector_compiled)의 PostgreSQL 16 Windows 패키지다.
표준 재현 환경은 저장소의 `docker-compose.yml`에 정의된 `pgvector/pgvector:pg16`이다.

## 표준 재실행

필요: JDK 21+, Maven 3.9+, Docker, Node 20+, Chrome (브라우저 흐름 검증용).
프로젝트 루트에서 DB를 준비하고 각 서버는 별도 터미널에서 실행한다.

```powershell
docker compose up -d
cd backend
mvn test
# DB 없이 단위 테스트만 실행하려면:
# mvn test -DexcludedGroups=integration

$env:ADMIN_USERNAME = 'admin'
$env:ADMIN_PASSWORD = '<로컬 관리자 비밀번호>'
$env:AI_PROVIDER = 'stub'
mvn spring-boot:run '-Dspring-boot.run.profiles=live'
```

```powershell
cd admin-ui
npm ci
npm run build
npm run start
```

이번 임시 DB를 다시 사용하면 `DB_URL=jdbc:postgresql://127.0.0.1:55432/aicontent`,
`TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/aicontent_test`를 설정한다.
DB를 중지/시작하려면 프로젝트 루트에서 다음 명령을 사용한다.

```powershell
& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata -m fast -w stop
& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata -l .local/postgres-server.log -o '-p 55432 -h 127.0.0.1' -w start
```

## 실제 OpenAI 연결

어댑터는 [Chat Completions 공식 API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)와
[Embeddings 공식 API](https://developers.openai.com/api/reference/resources/embeddings/methods/create)를 사용한다.
JSON 모드로 생성한 응답을 기존 전체 JSON Schema로 다시 검증한다.
기존 스키마의 조건부 `allOf/if/then`과 선택 필드를 유지하기 위해 서버의 strict schema 모드를 사용하지 않는다.
HTTP 오류의 응답 본문이나 API 키는 작업·AI 오류 로그에 저장하지 않는다.

**stub 벡터와 실제 모델 벡터를 혼합하지 않는다.** 실제 실행에는 별도 빈 DB를 사용한다.
모델 변경 시에도 기존 기사·이슈 벡터 전체를 재임베딩해야 한다.

```powershell
# Docker DB에서는 아래처럼 별도 DB를 만들 수 있다.
docker compose exec postgres createdb -U aicontent aicontent_live
cd backend
$env:DB_URL = 'jdbc:postgresql://localhost:5432/aicontent_live'
$env:ADMIN_USERNAME = 'admin'
$env:ADMIN_PASSWORD = '<로컬 관리자 비밀번호>'
$env:AI_PROVIDER = 'openai'
# OPENAI_API_KEY는 이 터미널의 환경 변수 또는 backend/application-local.yml에 별도로 설정한다.
# 비추적 YAML을 쓰면 -Dspring-boot.run.profiles=live,local 을 사용한다.
$env:OPENAI_CHAT_MODEL = 'gpt-4o-mini'
$env:OPENAI_EMBEDDING_MODEL = 'text-embedding-3-small'
mvn spring-boot:run '-Dspring-boot.run.profiles=live'
```

추가 설정: `OPENAI_BASE_URL`(기본 `https://api.openai.com/v1`), `OPENAI_TIMEOUT_SECONDS`(60),
`OPENAI_MAX_COMPLETION_TOKENS`(4096). 스키마 차원은 1536으로 고정한다.
`AI_PROVIDER=openai`에 키가 없으면 서버가 즉시 설정 오류로 종료하며 stub으로 대체하지 않는다.

`live` 프로파일은 초기 수집을 소스당 3건으로 제한하고 자동 스케줄러는 꺼 둔다.
뉴스 소스 화면에서 수동 수집한 뒤 필요하면 `NEWS_MAX_ARTICLES_PER_RUN`, `NEWS_SCHEDULER_ENABLED`를 설정한다.
등록되는 기사 본문은 RSS가 제공하는 설명/콘텐츠이며 별도의 원문 웹페이지 수집은 포함하지 않는다.

## 브라우저 흐름 재검증

**로컬 개발 DB에만 실행한다.** 뉴스 수집과 이슈 상태 변경·확정을 수행한다.
관리자 서버를 실행한 상태에서 별도 터미널을 사용한다.

```powershell
cd admin-ui
$env:ADMIN_USERNAME = 'admin'
$env:ADMIN_PASSWORD = '<실행 중인 관리자 비밀번호>'
$env:VERIFY_SOURCE_NAME = 'bbc-world' # 또는 yonhap-latest
npm run verify:flow
# 화면·실제 DB 집계·모바일·캡처 검증(기사 상태를 변경하지 않음):
npm run verify:dashboard
```

기본값은 실제 임베딩 모델을 요구하므로 stub 서버에서 실행하면 실패한다.
stub 흐름만 확인할 때는 `VERIFY_REQUIRE_REAL_AI=false`를 명시한다.
Chrome 대신 Playwright Chromium을 사용하려면 `npx playwright install chromium` 후
`VERIFY_BROWSER_CHANNEL=chromium`을 설정한다.
`VERIFY_TIMEOUT_SECONDS`(기본 180), `VERIFY_UI_URL`(기본 `http://localhost:3000`)도 설정할 수 있다.
유료 모델의 모든 호출·토큰을 최종 확인하려면 DB에서 `ai_log.provider`, `model`, `success`를 함께 확인한다.

```sql
SELECT job_type, status, count(*) FROM async_job GROUP BY 1,2;
SELECT provider, model, call_type, success, count(*) FROM ai_log GROUP BY 1,2,3,4;
SELECT actor, action, target_id, after_state->>'status' FROM admin_audit_log ORDER BY id;
```
