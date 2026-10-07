# 검증 현황과 실행 방법

이 코드는 **패키지 저장소(Maven Central, npm) 접근이 막힌 환경**에서 작성했습니다. 그래서 "여기서 실제로 실행해 확인한 것"과 "코드로만 작성되어 아직 실행해 보지 못한 것"이 분명히 나뉩니다. 아래 표를 기준으로 보세요.

## 1. 실제로 실행해서 확인한 것

| 대상 | 방법 | 결과 |
|---|---|---|
| DB 스키마(`V1__phase1_schema.sql`) | 로컬 PostgreSQL 16에 적용(pgvector가 없어 `vector(1536)`→`float8[]`로 바꾸고 extension·HNSW 줄 제외) | 오류 없이 적용 |
| 제약·트리거·작업 큐 SQL | `backend/src/test/resources/sql/verify_schema.sql` (중복 기사 CHECK, MERGED 일관성 CHECK, primary 연결 유니크, dedupe 부분 유니크, updated_at 트리거, claim 문, 하트비트 만료 복구 문) | `ALL SCHEMA ASSERTIONS PASSED` |
| 동시 claim | 두 세션에서 동시에 claim 실행 | 서로 다른 작업을 막힘 없이 가져감(`SKIP LOCKED`) |
| 리포지토리 핵심 SQL | `backend/src/test/resources/sql/verify_queries.sql` — 수집(ON CONFLICT 중복, 제목 중복, 소스 백오프/DEGRADED), 집계 재계산, 빈 이슈 CLOSED, 요약 MANUAL 보호, 병합 표시, 분류 이력, ai_job upsert, 감사 로그, 기사/이슈/작업 목록 쿼리(ILIKE ESCAPE, 정렬), 사용자명 대소문자 | `ALL QUERY ASSERTIONS PASSED` |
| 프레임워크 없는 Java 로직 | JDK `javac`로 컴파일하고 JUnit 부분집합 호환 러너로 실행. **56개 테스트 전부 통과**: URL/제목 정규화, 날짜 파서, RSS 어댑터(로컬 HTTP 서버로 정상/429/500/404/타임아웃/깨진 XML/DOCTYPE 거부), JVM 락, JSON 추출, 템플릿 렌더러, 임베딩 입력, 스텁 임베딩, `DecisionPolicy`, `IssueContentMerger`, 프롬프트 파일↔변수 일치(실제 `user.md` 렌더링 포함), 평가 지표·러너 | 56 passed, 0 failed |
| TypeScript 구문 | `tsc --noEmit` | 구문 오류 0 (남은 오류는 모두 `react`/`next` 타입이 없어서 나는 것) |
| Java 구문 | `javac` 파싱 | 구문 오류 0 (의존성 누락 오류만 존재) |

검증 중 잡아서 고친 결함:
- `InJvmDistributedLock`이 같은 스레드에서 재진입되던 문제 → 세마포어로 수정(테스트가 발견).
- 컴파일하지 못한 Spring 코드를 별도 에이전트가 읽기 전용으로 리뷰해 찾은 것(모두 수정 완료):
  1. `IssueClassificationService.apply`에서 재할당된 지역변수를 람다가 캡처(컴파일 오류).
  2. `NoneAiClient`가 `AiClient`와 `EmbeddingClient`를 동시에 구현해 기본 설정(`AI_PROVIDER=none`)에서 `NoUniqueBeanDefinitionException`으로 기동 실패 → `NoneAiClient`/`NoneEmbeddingClient`로 분리(회귀 테스트 추가).
  3. 통합 테스트 픽스처가 2026-10 고정 날짜라 30일 후보 창을 넘기면 실패 → 테스트 프로파일에서 창을 넓힘.
  4. 분류 락 대기(60초)가 워커 동시성×LLM 지연보다 짧으면 `LOCK_TIMEOUT`이 재시도 횟수를 소모 → 대기/TTL 기본값을 300초로 상향(근본 해결은 OPEN_ITEMS #7).
  5. 잘못된 비밀번호의 401이 JSON 봉투 없이 나가던 것 → Basic 인증 진입점도 같은 JSON 응답으로 통일.
  리뷰는 컴파일러가 아니므로 이 목록이 전부라는 뜻은 아닙니다. 첫 `mvn compile`이 최종 판정입니다.

## 2. 코드는 있지만 아직 실행하지 못한 것 (처음 실행할 때 확인하세요)

| 대상 | 이유 | 처음에 볼 곳 |
|---|---|---|
| **Spring 코드 전체의 컴파일/기동** | Maven Central 403으로 의존성을 받을 수 없었음 | `mvn -q -DskipTests compile` |
| pgvector 의존 SQL: `avg(embedding)`, `<=>` 후보 검색, `CAST(:v AS vector)`, HNSW 인덱스 | 로컬 PG에 pgvector 없음 | `IssueRepository.recomputeAggregates`, `IssueCandidateFinder`, `NewsArticleRepository.saveEmbedding`. `docker compose up -d`의 `pgvector/pgvector:pg16`에서 통합 테스트로 확인 |
| 통합 테스트 27개(`backend/src/test/java/.../it/*`) | DB + Spring 컨텍스트 필요 | 아래 3절 |
| 관리자 컨트롤러·보안(MockMvc 테스트 포함) | 위와 동일 | `AdminApiIntegrationTest` |
| networknt 스키마 검증, Redis 락(Lua), Flyway 연동 | 라이브러리 미설치 | `SchemaValidator`, `RedisDistributedLock` |
| `EvalDatasetLoader`/`EvalDatasetTest`/`EvalLlmIT`/`LlmJudge` | Jackson/Spring 필요 | 평가 세트 JSON 자체는 파이썬으로 구조 검사함(27건, id 유일, 사건 그룹의 첫 보도는 모호 아님) |
| 관리자 UI 빌드·화면 동작 | npm 패키지 미설치 | `cd admin-ui && npm install && npm run build` |

처음 돌릴 때 특히 의심해 볼 지점(작성자가 미리 짚은 위험): JdbcClient의 null 파라미터 타입 추론(PostgreSQL이 타입을 못 정하는 `:x IS NULL` 패턴은 `CAST`로 이미 처리), `IN (:statuses)` 컬렉션 전개, `@Scheduled` 플레이스홀더, `TransactionTemplate` 주입, networknt 1.5.1 API 이름.

## 3. 실행 방법

필요: JDK 21, Maven 3.9+, Docker, Node 20+.

```bash
docker compose up -d                      # PostgreSQL(pgvector) + Redis. 테스트용 DB aicontent_test도 함께 생성

cd backend
mvn test                                  # 단위 + 통합 테스트(통합은 localhost:5432의 aicontent_test 사용)
mvn test -DexcludedGroups=integration   # 단위 테스트만(DB 불필요; 통합 테스트는 @Tag("integration"))

# 서버 실행(개발용 스텁 AI, 관리자 계정 생성). 실제 AI 공급자는 OPEN ITEM이라 stub은 개발용일 뿐입니다.
ADMIN_USERNAME=admin ADMIN_PASSWORD='change-me' AI_PROVIDER=stub mvn spring-boot:run

# 로컬 JSON 파일에서 기사를 읽는 FIXTURE 소스 예시(파일 내용은 직접 준비: [{title,url,publisher,publishedAt,text}, ...])
#   --app.news.sources[0].name=local --app.news.sources[0].type=FIXTURE --app.news.sources[0].base-url=/abs/path/articles.json

cd ../admin-ui
cp .env.example .env.local                # BACKEND_URL
npm install && npm run dev                # http://localhost:3000
```

고정 평가 세트 실행(결과 지표 출력): `mvn -Dtest=EvalLlmIT -Deval.run=true -Dapp.ai.provider=<공급자> test` — 자세한 내용은 [EVALUATION.md](EVALUATION.md).

## 4. 검증 SQL 재실행(pgvector 없는 PostgreSQL)

```bash
sed -e 's/vector(1536)/float8[]/g' -e '/CREATE EXTENSION/d' -e '/USING hnsw/d' \
    backend/src/main/resources/db/migration/V1__phase1_schema.sql | psql -d <빈 DB> -v ON_ERROR_STOP=1
psql -d <같은 DB> -f backend/src/test/resources/sql/verify_schema.sql
psql -d <같은 DB> -f backend/src/test/resources/sql/verify_queries.sql
```
