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
> 실제 뉴스 공급원과 LLM/임베딩 공급자는 정해지지 않았습니다([docs/OPEN_ITEMS.md](docs/OPEN_ITEMS.md)). 기본 설정(`AI_PROVIDER=none`)에서는 AI 호출이 명확한 오류로 실패하며, 개발용 `stub` 제공자는 모델이 아닙니다.

## 구성
| 위치 | 내용 |
|---|---|
| `backend/` | Spring Boot 3.3 / Java 21 모듈러 모놀리스. 패키지: `news`(수집·중복 제거), `ai`(공급자 추상화·프롬프트·검증), `issue`(분류·집계·관리), `job`(ASYNC_JOB 워커), `admin`(REST), `security`, `lock`, `audit`, `common`, `config` |
| `backend/src/main/resources/db/migration` | Flyway 스키마 `V1__phase1_schema.sql` (PostgreSQL + pgvector) |
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
```bash
docker compose up -d
cd backend && ADMIN_USERNAME=admin ADMIN_PASSWORD='change-me' AI_PROVIDER=stub mvn spring-boot:run
cd ../admin-ui && cp .env.example .env.local && npm install && npm run dev     # http://localhost:3000
```
뉴스 소스는 `app.news.sources`(또는 `news_source` 테이블)에 추가합니다. 설정 키와 테스트 실행은 [docs/VERIFY.md](docs/VERIFY.md)를 보세요.

## 주요 설정(`application.yml`, 환경 변수)
`DB_URL/DB_USER/DB_PASSWORD`, `REDIS_HOST/REDIS_PORT`, `AI_PROVIDER`(none|stub), `LOCK_REDIS_ENABLED`, `ADMIN_USERNAME/ADMIN_PASSWORD`(초기 SYSTEM_ADMIN, 비우면 계정 없음), `app.worker.*`(동시성·재시도·하트비트), `app.news.*`, `app.classifier.*`(후보 수·임계값), `app.prompts.*`(프롬프트 버전).
