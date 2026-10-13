# AI 정보 콘텐츠 플랫폼 — Phase 1: 뉴스 ISSUE 엔진

뉴스를 수집해 AI로 분석하고, **같은 사건의 기사를 하나의 ISSUE로 묶고**, 관리자가 검수·병합·분리·수정하는 백엔드 + 최소 관리자 UI입니다.
(Phase 1 범위 밖: 일반 사용자 계정, 좋아요/북마크, TV, 발행, SEO, 통계)

```
수집(COLLECT_NEWS) → URL/제목 중복 제거 → NEWS_ARTICLE 저장
  → AI 분석(ANALYZE_ARTICLE, JSON Schema 검증) → 임베딩(EMBED_ARTICLE, pgvector)
  → 후보 ISSUE 검색 → LLM 동일 사건 판단 + 가드(CLASSIFY_ARTICLE)
  → SAME_ISSUE: 기존 ISSUE에 연결 / NEW_ISSUE: 새 ISSUE / REVIEW: 임시 ISSUE(검수 대기)
  → 관리자: 확정·병합·분리·이동·수정 (감사 로그) → 판단 이력이 평가 데이터로 축적
```

> **먼저 읽을 것:** [docs/VERIFY.md](docs/VERIFY.md) — 이 코드에서 *실행해서 확인한 것*과 *아직 실행해 보지 못한 것*이 구분되어 있습니다.
> `live` 프로파일에 실제 연합뉴스·BBC RSS가 등록되고, `AI_PROVIDER=openai`로 LLM·임베딩 API를 연결할 수 있습니다. 실제 외부 AI 호출에는 별도 API 키가 필요합니다. 기본값 `none`은 AI 호출을 실패시키고 개발용 `stub`은 모델이 아닙니다. 검증 결과와 실행 방법은 [docs/VERIFY.md](docs/VERIFY.md)를 보세요.

## 구성
| 위치 | 내용 |
|---|---|
| `backend/` | Spring Boot 3.3 / Java 21 모듈러 모놀리스. 패키지: `news`(수집·중복 제거), `ai`(공급자 추상화·프롬프트·검증), `issue`(분류·집계·관리), `job`(ASYNC_JOB 워커), `admin`(REST), `security`, `lock`, `audit`, `common`, `config` |
| `backend/src/main/resources/db/migration` | Flyway 스키마 `V1__phase1_schema.sql` (PostgreSQL + pgvector), `V2__job_defer_count.sql` (락 대기 재큐 횟수), `V3__issue_title_source.sql` (제목 AUTO/MANUAL) |
| `backend/src/main/resources/prompts` | 버전 관리되는 프롬프트(`article-analysis/v1`, `issue-classifier/v1`: system/user/schema/meta) |
| `backend/src/test` | 단위·통합 테스트, 검증 SQL(`resources/sql`), 고정 평가 세트(`resources/eval`) |
| `admin-ui/` | Next.js + TypeScript 최소 관리자 화면(이슈/기사/작업) |
| `docker-compose.yml` | 로컬 PostgreSQL(pgvector) + Redis |
| `docs/` | [DECISIONS](docs/DECISIONS.md) 설계 결정 · [OPEN_ITEMS](docs/OPEN_ITEMS.md) 미확정 · [API](docs/API.md) · [VERIFY](docs/VERIFY.md) 검증/실행 · [EVALUATION](docs/EVALUATION.md) 평가 |

## 핵심 원칙(코드에 반영된 것)
- PostgreSQL이 진실 원천. 작업 큐는 `async_job` + `FOR UPDATE SKIP LOCKED`, 재시도 상한, 하트비트/재시작 복구. Redis는 선택적 락뿐이며 꺼져 있어도 동작.
- LLM 출력은 JSON Schema 검증을 통과해야만 저장. 시도마다 `ai_log`에 프롬프트 버전과 함께 기록.
- 임베딩 유사도만으로 판단하지 않음: 후보 검색 → LLM 판단 → 가드(신뢰도·후보 포함 여부·다중 사건) → 애매하면 REVIEW.
- 공급자(LLM·임베딩·뉴스 소스)는 인터페이스 뒤에 있고, 새 공급자 추가 위치는 OPEN_ITEMS에 있습니다.

## 빠른 시작

관리자 첫 화면은 실제 DB 현황을 보여주는 대시보드입니다. 기사·이슈·작업·RSS 소스 관리와 검수 화면으로 이동할 수 있습니다.
현재 Windows 로컬 DB의 생성 과정과 접속 설정은 [docs/DB_SETUP.md](docs/DB_SETUP.md)에 있습니다.

DB 비밀번호는 기본값이 없습니다. 실행 전에 `DB_PASSWORD`를 환경 변수로 지정하세요(값은 문서·Git에 남기지 않음). 비밀번호 없이 접속하는 로컬 trust DB에서도 설정 해석을 위해 임의 값을 지정해야 합니다.

```bash
export DB_PASSWORD='<로컬 DB 비밀번호>'
docker compose up -d
cd backend && ADMIN_USERNAME=admin ADMIN_PASSWORD='change-me' AI_PROVIDER=stub mvn spring-boot:run
cd ../admin-ui && cp .env.example .env.local && npm install && npm run dev     # http://localhost:3000
```
뉴스 소스는 `app.news.sources`(또는 `news_source` 테이블)에 추가합니다. 설정 키와 테스트 실행은 [docs/VERIFY.md](docs/VERIFY.md)를 보세요.

관리자 인증은 DB의 `app_user`와 `user_role`을 사용하는 HTTP Basic 방식입니다. `ADMIN_USERNAME`과 `ADMIN_PASSWORD`는 해당 아이디가 없을 때 최초 계정을 생성하는 설정이며, 기존 계정의 비밀번호를 변경하지 않습니다. 기존 DB에서는 기존 관리자 비밀번호로 `/login`에 로그인하세요.

로그인 정보는 현재 탭의 `sessionStorage`에 저장되고, 각 `/api/admin/*` 요청에 인증 헤더로 첨부됩니다. Next.js가 `BACKEND_URL`의 백엔드로 요청을 프록시합니다. 새 탭이나 다른 호스트(`localhost`와 `127.0.0.1` 포함)에서는 다시 로그인해야 합니다. 인증 정보가 없거나 유효하지 않으면 로그인 화면으로 이동합니다. 비밀번호나 인증 헤더를 로그 또는 Git에 남기지 마세요.

## 주요 설정(`application.yml`, 환경 변수)
`DB_URL/DB_USER/DB_PASSWORD`, `REDIS_HOST/REDIS_PORT`, `AI_PROVIDER`(none|stub|openai), `OPENAI_API_KEY/OPENAI_CHAT_MODEL/OPENAI_EMBEDDING_MODEL`, `LOCK_REDIS_ENABLED`, `ADMIN_USERNAME/ADMIN_PASSWORD`(초기 SYSTEM_ADMIN, 비우면 계정 없음), `app.worker.*`(동시성·재시도·하트비트), `app.news.*`, `app.classifier.*`(후보 수·임계값), `app.prompts.*`(프롬프트 버전), `app.ai.pricing`(모델별 단가, 비용 기록용).

운영 정책 설정(모두 **기본 꺼짐**, 값은 정책 결정 후 입력 — `docs/OPEN_ITEMS.md` #8~#10):

| 환경 변수 / 설정 키 | 효과 |
|---|---|
| `AI_LOG_RESPONSE_RETENTION_DAYS` / `app.retention.ai-log-response-days` | 이 일수보다 오래된 `ai_log`의 모델 응답 원문만 비움(토큰·비용 기록은 유지) |
| `ISSUE_AUTO_CLOSE_AFTER_DAYS` / `app.issue-lifecycle.auto-close-after-days` | 이 일수 동안 활동 없는 ACTIVE 이슈를 CLOSED로(감사 로그 `ISSUE_AUTO_CLOSE`). REVIEW는 제외 |
| `AI_DAILY_MAX_CALLS`, `AI_MAX_CALLS_PER_MINUTE`, `app.ai.limits.daily-budget-usd` | AI 호출 한도. 닿으면 작업이 실패하지 않고 대기(하루 한도는 다음 날까지). 예산은 `app.ai.pricing`이 있어야 동작 |
