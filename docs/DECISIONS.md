# 설계 결정 기록 (Phase 1)

지시서/Notion 문서가 아직 계획 단계라 비어 있던 부분을 구현하면서 정한 기본값과 그 이유입니다.
구조에 영향을 주는 선택(공급자, 모델, 임계값 최종값 등)은 여기가 아니라 [OPEN_ITEMS.md](OPEN_ITEMS.md)에 있습니다.
결정을 바꿀 때는 이 문서에 날짜와 이유를 추가하세요.

## 1. 범위
| 결정 | 이유 |
|---|---|
| 지시서 6~17절의 기능만 구현. 회원가입/로그인(일반 사용자), 좋아요/북마크, TV, 발행, SEO, 통계는 코드·테이블 모두 만들지 않음 | Phase 1 금지 목록. `issue`/`news_article`은 bigint id를 그대로 두어 나중에 CONTENT_SOURCE가 N:M으로 참조할 수 있게만 열어 둠 |
| 추가한 보조 테이블: `classification_run`, `admin_audit_log`, `app_user`/`user_role` | 요구된 "후보·판단 근거 추적", "병합/분리 이력", "관리자 인증" 흐름을 만들려면 최소한으로 필요 |

## 2. 구조
| 결정 | 이유 |
|---|---|
| Spring Boot 3.3.5 + Java 21 + Maven, 모듈러 모놀리스(패키지: `news`, `ai`, `issue`, `job`, `admin`, `security`, `lock`, `audit`, `common`, `config`) | 필수 스택. 패키지 간 호출은 서비스/리포지토리 경유 |
| 데이터 접근은 Spring `JdbcClient` + 명시적 SQL, JPA 없음 | pgvector 연산(`<=>`, `avg(vector)`), `FOR UPDATE SKIP LOCKED`, 부분 유니크 인덱스를 ORM 없이 그대로 쓰는 편이 정확하고 검토하기 쉬움 |
| 스키마 변경은 Flyway(`V1__phase1_schema.sql`) | 재현 가능한 스키마. V1은 새 DB 기준(아직 운영 데이터 없음) |
| Redis는 락 전용·선택. 기본 off(`app.lock.redis-enabled=false`) → JVM 내 락 | 지시서: Redis는 보조. 데이터 정합성은 DB 제약과 ASYNC_JOB 소유권 검사가 책임지고, 락은 안전망. Redis 장애 시 락 없이 계속(퇴화) |
| 비동기 큐는 `async_job` + Spring 워커 | PostgreSQL이 진실 원천. Kafka/RabbitMQ 금지 |

## 3. 비동기 작업 (ASYNC_JOB)
| 결정 | 이유 |
|---|---|
| claim: `WITH next AS (SELECT … FOR UPDATE SKIP LOCKED) UPDATE … RETURNING` | 여러 워커/스레드가 같은 작업을 잡을 수 없음. 로컬 PG에서 2세션 동시 claim 실험으로 확인 |
| 모든 상태 전이는 `status='RUNNING' AND locked_by=:worker` 조건 | 유실 판정된 워커가 새 소유자의 결과를 덮어쓰지 못함 |
| 재시도: 기본 3회, 대기 `min(600s, 5s·2^retry_count)`, 재시도 불가 오류는 즉시 FAILED | 일시 오류(소스 타임아웃/네트워크/429/5xx, 공급자가 retryable로 표시한 AI 오류)만 재시도. 검증 실패·설정 오류·4xx는 반복해도 같은 결과 |
| **2026-10-12 추가:** 분류 락 대기 실패(`LOCK_TIMEOUT`)는 실패가 아니라 재큐(`JobDeferredException` → `AsyncJobRepository.defer`). `retry_count`는 그대로 두고 `defer_count`(V2)만 올리며, 30초 뒤 다시 실행(`app.worker.defer-delay-seconds`). 20회(`app.worker.max-defers`)를 넘으면 FAILED로 끝내고 기존 최종 실패 처리(`onFinalFailure`)를 그대로 탐 | 기사에는 문제가 없는데 대량 수집 때 대기만으로 재시도 3회를 다 써서 FAILED가 되던 문제. 상한을 둔 이유는 락이 풀리지 않는 장애에서 작업이 끝없이 도는 것을 막기 위함 |
| **2026-10-13 추가:** 운영자가 정한 한도로 인한 대기는 `AsyncJobRepository.postpone`. `JobDeferredException(code, msg, delaySeconds, counted=false)`이면 `retry_count`도 `defer_count`도 올리지 않고 `available_at`만 미룸(오류 코드에 이유를 남김) | AI 예산·호출 한도는 의도한 멈춤이라 하루 종일 걸릴 수 있음. `max-defers`로 세면 한도가 걸린 날 대기 중인 작업이 모두 FAILED가 됨. 무한 대기가 싫으면 관리자가 작업을 취소할 수 있음 |
| 하트비트 5초, 300초 무응답이면 `WORKER_LOST`로 재큐(재시도 소진 시 FAILED). 서버 시작 시에도 1회 복구 | 재시작/크래시 복구 요구 |
| `dedupe_key` 부분 유니크(PENDING/RUNNING만) | 같은 기사에 분석 작업이 중복 쌓이지 않음. 끝난 뒤에는 재요청(재분석) 가능 |
| 작업 체인: COLLECT_NEWS → ANALYZE_ARTICLE → EMBED_ARTICLE → CLASSIFY_ARTICLE, 각 단계가 다음 작업을 등록 | 단계별 재시도·관측 가능. 핸들러는 멱등 |
| 최종 실패 시 `onFinalFailure` 콜백으로 기사 상태 반영(FAILED) | 관리자 목록에서 실패 기사를 찾을 수 있게 |

## 4. 수집·중복 제거
| 결정 | 이유 |
|---|---|
| 중복 제거 순서: ① 정규화 URL(DB 유니크 인덱스, 권위) → ② 같은 언론사 + 정규화 제목 해시(7일 창) → `DUPLICATE`로 저장하고 원본 id 기록, 분석하지 않음 | 지시서의 순서. 유사도 기반 중복 제거는 "선택" 항목이라 Phase 1에서 제외 |
| URL 정규화: http→https, `www.`/기본 포트/fragment/끝 슬래시 제거, 추적 파라미터(utm_*, fbclid 등) 제거, 쿼리 정렬 | 같은 기사의 다른 URL 변형을 같은 키로 |
| 제목 정규화: NFKC, 소문자, 앞뒤 `[속보]`/`[종합]` 류 태그와 구두점 제거 후 SHA-256 | 재전송 시 붙는 태그만 달라진 제목을 같은 것으로 |
| 제목 중복은 *같은 언론사*일 때만 | 서로 다른 언론사가 같은 제목을 쓰는 것은 흔하고(통신사 기사) 오히려 이슈 근거가 됨 |
| 기사 1건 = 1 트랜잭션(저장 + 분석 작업 등록) | 한 기사 오류가 나머지를 막지 않고, 기사만 있고 작업이 없는 상태가 생기지 않음 |
| 발행시각 파싱 실패 → `published_at=NULL`, 원문은 `published_at_raw`에 보존 | 시각을 추측해서 채우지 않음. 이슈 집계는 `COALESCE(published_at, collected_at)` |
| 소스 실패 3회 연속 → `DEGRADED`, 실패 횟수만큼 수집 간격을 늘림(최대 6배), 성공 시 복구 | 죽은 피드를 계속 두드리지 않기 |
| 기본 프로파일은 소스 0개. RSS 어댑터(범용)·FIXTURE 어댑터(로컬 JSON) 제공. **2026-10-07 변경:** opt-in `live` 프로파일에 연합뉴스·BBC World RSS 등록(소스당 초기 3건, 자동 스케줄러 기본 off) | 지시서: 임의 뉴스 데이터 생성 금지. 실제 흐름 검증용으로 공개 RSS만 사용. 추가 공급원·상용 이용 범위는 OPEN ITEM #1 |
| **2026-10-14 추가:** `live` 프로파일 소스를 7개로 확대 — 연합뉴스, 뉴시스(속보), SBS(정치·사회), 동아일보(전체), 경향신문(전체), BBC World. 같은 날 RSS 27곳을 받아 본 뒤, 같은 사건을 여러 곳이 다루고 RSS에 요약·본문이 있는 곳을 골랐다. 5곳 모두 `RssNewsSourceAdapter`로 수집·발행 시각 파싱 확인 | 한 언론사만으로는 같은 사건 기사가 모이지 않아 LLM 분류가 거의 실행되지 않음. 연합뉴스·한겨레·조선·한국경제는 RSS에 요약이 없어 제목만 분석됨. JTBC(2024년 이후 갱신 없음)·MBC(방송 편성 목록)·중앙(종료)은 제외 |

## 5. AI 계층
| 결정 | 이유 |
|---|---|
| `AiClient`/`EmbeddingClient` 인터페이스 뒤에 공급자를 숨김. 제공자는 `none`(기본, 호출 시 `AI_PROVIDER_NOT_CONFIGURED`), `stub`(개발/테스트 전용) | 공급자 선정은 OPEN ITEM. 설정이 없을 때 조용히 가짜 결과를 만들지 않고 큰 소리로 실패 |
| **2026-10-07 추가:** `openai` 제공자(`OpenAiClient`/`OpenAiEmbeddingClient`). 기본 모델 `gpt-4o-mini`·`text-embedding-3-small`(1536차원). JSON 모드로 받은 뒤 기존 전체 JSON Schema로 재검증(서버 strict 모드 미사용). `AI_PROVIDER=openai`인데 키가 없으면 기동 실패(stub 대체 없음). HTTP 오류 본문·키는 로그에 저장하지 않음 | 조건부 `allOf/if/then`이 있는 기존 스키마를 그대로 쓰기 위함. 실제 외부 호출·품질은 미검증, 모델·예산 확정은 OPEN ITEM #2·#3 |
| LLM 출력은 반드시 `StructuredOutputService`를 거침: JSON 추출 → 파싱 → JSON Schema(2020-12) 검증 → 실패 시 오류를 힌트로 재시도 → 그래도 실패하면 예외 | "검증 없는 LLM 출력 저장 금지". 시도마다 `ai_log`에 성공/실패·프롬프트 버전·원문 기록 |
| 분석 호출은 출력 검증 실패에 한해 최대 2회 시도(`app.ai.analysis-max-attempts`). 검증 실패로 끝나면 작업은 재시도 없이 FAILED(기사 FAILED) | 같은 프롬프트를 작업 단위로 다시 돌려도 같은 결과일 가능성이 큼. 관리자가 재분석 가능 |
| 프롬프트는 `src/main/resources/prompts/<key>/<version>/{system.md,user.md,schema.json,meta.json}` | 버전 디렉터리는 불변(변경 = 새 버전). 클래스패스에 두면 배포물과 함께 버전이 고정됨. 사용 버전은 `app.prompts.*` |
| 임베딩 입력 = 제목 + 요약 + 핵심 사실 + 개체명(최대 6000자). 차원 1536 고정, 불일치 시 비재시도 오류 | 지시서. 차원은 모델 선정 시 마이그레이션으로 변경(OPEN ITEM) |
| 프롬프트에 임베딩 유사도 값을 넣지 않음 | 모델이 숫자에 끌려 판단하는 것을 막고, "유사도만으로 판단 금지" 원칙을 프롬프트에서도 유지 |
| **2026-10-12 추가:** 호출 비용은 `AiCostCalculator`가 `app.ai.pricing`(모델별 100만 토큰당 USD)으로 계산해 `ai_log.estimated_cost_usd`에 기록. 단가는 코드·기본 설정에 넣지 않음. 모델명은 정확히 일치 → 가장 긴 접두사 순으로 찾고, 단가나 토큰 수를 모르면 0이 아니라 NULL | 가격은 바뀌므로 저장소에 고정하지 않음. 응답 모델명에는 날짜가 붙음(`gpt-4o-mini-2024-07-18`). 모르는 비용을 0으로 보이게 하지 않기 위함 |
| **2026-10-14 추가:** 기사 분석 프롬프트 v2(`prompts/article-analysis/v2`, 기본값). v1과 스키마는 같고, 9개 카테고리의 정의와 판단 기준(사건 내용으로 판단, 해외·외국 정치는 WORLD, 사건·사고는 SOCIETY, LIFE는 생활 정보, OTHER는 마지막)을 추가 | v1은 정의 없이 "허용 값 중 하나"만 요구해 해외 기사가 OTHER, 사건 기사가 LIFE로 가는 일이 많았음. 실제 기사 20건에서 v1 6/20 → v2 19/20(같은 20건으로 조정했으므로 과적합 가능, `docs/AI_QUALITY_20261014.md`) |
| **2026-10-14 추가:** 분석 모델에 보내는 본문만 정리(`AnalysisInputCleaner`): 저작권·재배포 금지·피드 이용 조건 문구, "▶ … 다운로드" 같은 링크 문구, 이메일이 든 끝 서명. 저장된 `analysis_text`는 바꾸지 않음. 언론사 이름 규칙 없이 표시·문구로만 판단 | 반복 문구가 요약·키 사실에 섞이는 것을 막되, 원문과 이용 조건은 보존. 정리는 콘텐츠 이용 허가가 아님(OPEN_ITEMS #1) |
| **2026-10-14 변경:** `live` 프로파일에 OpenAI 공식 단가를 넣음(확인일 2026-10-14, 출처 URL을 주석으로). 기본 프로파일에는 여전히 단가 없음 | 실제 공급자로 검증할 때 비용이 NULL로 남지 않게 하기 위함. 가격이 바뀌면 값과 날짜를 함께 갱신. 기록 값은 추정치이며 캐시 할인·Batch·세금은 반영하지 않음 |
| 모든 AI 호출은 DB 트랜잭션 밖에서 | 호출 실패/롤백과 무관하게 `ai_log`가 남도록 |
| **2026-10-13 추가:** `ai_log.response`(모델 응답 원문) 보존 정리 배치 `AiLogRetentionService`. `app.retention.ai-log-response-days`보다 오래된 행의 원문만 NULL로 비우고 행·토큰·비용·모델·성공 여부는 남김. 기본 0(무기한, 아무것도 지우지 않음). 1시간마다, 1,000행 단위 짧은 UPDATE를 실행당 최대 100번 | 보존 기간은 정책 결정이라 기본값으로 정하지 않음(OPEN ITEM #8). 사용량·비용 이력은 원문과 달리 운영에 계속 필요함. 큰 한 번의 UPDATE로 테이블을 오래 잠그지 않기 위함 |
| **2026-10-13 추가:** AI 호출 한도 `AiUsageLimiter`(`app.ai.limits`): 하루 예산(USD), 하루 호출 수, 분당 호출 수. 모든 AI 호출(분석·임베딩·분류, 스키마 재시도 포함) 직전에 `ai_log`로 오늘 사용량을 세어 한도에 닿으면 호출하지 않고 작업을 대기시킴(하루 한도: 다음 날 0시+5초까지, 분당: 60초). 하루 기준은 `time-zone`(기본 Asia/Seoul). 모두 기본 꺼짐. 예산 0 이하·음수 한도·잘못된 시간대는 기동 실패. 예산만 켜고 단가가 없으면 경고 로그 | 한도 값은 예산 결정이라 기본값으로 정하지 않음(OPEN ITEM #10). `ai_log` 기준이라 서버가 여러 대여도, 재시작해도 같은 값을 봄. 실패가 아니라 대기라 한도를 풀거나 날이 바뀌면 그대로 이어짐. 이미 실행 중인 호출은 막지 않으므로 최대 워커 동시성만큼 넘칠 수 있음(소프트 한도). 한도가 꺼져 있으면 조회도 하지 않음 |

## 6. ISSUE 판단
| 결정 | 이유 |
|---|---|
| 후보: 기사 임베딩 기준 코사인 상위 5개, 유사도 ≥ 0.60, 최근 30일, 상태 ACTIVE | 검색은 범위를 좁힐 뿐 판단하지 않음. 수치는 초기값(OPEN ITEM) |
| 후보 없음 → LLM 호출 없이 NEW_ISSUE(method `NO_CANDIDATE`) | 비교 대상이 없는데 모델에게 묻는 것은 비용과 환각 위험만 늘림 |
| `multiEvent=true` 기사 → LLM 호출 없이 REVIEW | 묶음 기사는 한 이슈에 속할 수 없음 |
| 가드(`DecisionPolicy`): SAME_ISSUE는 신뢰도 ≥ 0.85 **그리고** `matchedIssueId ∈ 후보`일 때만 자동 적용, NEW_ISSUE는 신뢰도 ≥ 0.70, 아니면 REVIEW로 강등(`GUARD_DOWNGRADE`) | 오병합(SAME 오판)이 가장 비싼 오류. 근거 없는 자동 연결 금지 |
| 스키마 위반/LLM 실패 → REVIEW(`INVALID_OUTPUT`) 또는 AI 오류는 작업 재시도 | 모르면 사람에게 넘김 |
| REVIEW는 **임시 이슈(status=REVIEW)** 를 만들어 기사를 연결 | 미연결 기사가 생기지 않고 관리자가 병합/확정으로 처리. REVIEW 이슈는 후보에서 제외되어 오염 전파를 막음 |
| 분류 단계 전체를 락(`issue-classification`) 안에서 실행 | 같은 새 사건의 두 기사가 동시에 "후보 없음"을 보고 이슈를 둘 만드는 경쟁 방지. 처리량보다 정확성 우선. 대기 300초/TTL 300초 기본(OPEN ITEM: 규모가 커지면 사건 클러스터 단위 락) |
| **2026-10-13 추가:** 락 범위 설정 `app.classifier.lock-scope`(`CLASSIFIER_LOCK_SCOPE`). `GLOBAL`(기본, 기존과 같음) 또는 `CATEGORY`(분석 결과의 카테고리마다 `issue-classification:<CATEGORY>` 락). 잘못된 값은 기동 실패 | 카테고리는 분석 스키마의 고정 목록이라 키가 흩어지지 않고, 서로 다른 분야 기사는 병렬로 분류됨. 대가: 같은 사건이 서로 다른 카테고리로 분석되어 동시에 들어오면 이슈가 둘 생길 수 있음(관리자 병합으로 복구). 후보 검색은 여전히 모든 카테고리를 보므로 순차로 들어오면 문제없음. 사건 클러스터 단위 락은 미구현 |
| **2026-10-14 추가:** 분류 프롬프트 v3(기본값). 모델이 가장 가까운 후보와 대상·행위·대상 기간·장소를 항목별로 비교(`comparison`)해 함께 답하고, `DecisionPolicy`는 SAME_ISSUE인데 그 비교에 DIFFERENT가 있거나 행위자·행위가 UNKNOWN이면 자동 적용하지 않고 REVIEW로 강등. v1·v2 프롬프트는 보존 | v1·v2에서 모델이 "서로 다른 분기"라고 이유에 쓰고도 SAME을 골라 오병합(E09)이 났다. 지시문만으로는 막지 못해, 모델 자신의 구조화된 비교와 모순되는 SAME을 코드에서 막는다. 대가로 재현율이 다소 흔들린다(`docs/AI_QUALITY_20261014.md` 8절) |
| **2026-10-14 추가:** 검수 대기(REVIEW) 이슈도 후보로 제시(`app.classifier.candidate-issue-statuses` 기본 `ACTIVE, REVIEW`, 묶음 기사로 생긴 검수 대기 이슈는 제외). 정책을 통과한 SAME_ISSUE가 검수 대기 이슈를 가리키면 기사를 그 이슈에 `LLM_REVIEW`로 보류 연결하고 기사·이슈는 REVIEW 유지. ACTIVE 전환·확정은 관리자만 | 첫 보도가 검수 대기가 되면 후속 기사가 붙을 곳이 없어 새 이슈로 흩어졌다(E10). 기존 검수 대기 이슈·관리자 확정/분리/병합 구조를 그대로 쓰는 최소 변경이고, 관리자 확정 후 재분류(추가 유료 호출)가 필요 없다. 대가로 REVIEW 건수가 늘어난다 |
| 이미 이슈에 연결된 기사의 재분류는 `classification_run`만 기록(적용 안 함) | 관리자가 고친 결과를 자동화가 되돌리지 않음 |
| 적용 직전 대상 이슈를 행 잠금으로 재확인(ACTIVE 아니면 REVIEW로) | 검색~적용 사이에 관리자가 병합/종료했을 수 있음 |
| 이슈 집계(기사 수, 언론사 수, 기간, 중심 임베딩 `avg(embedding)`, 핵심 사실/개체)는 멤버십 변경마다 한 곳(`IssueAggregateService.refresh`)에서 SQL로 재계산 | 병합/분리/이동 후 값이 어긋나지 않도록. 증분 갱신 대신 재계산 |
| 요약: `summary_source` AUTO/MANUAL. 관리자가 쓴 요약은 자동 갱신하지 않음 | 수동 수정 보호 |
| **2026-10-13 추가:** 제목도 `title_source` AUTO/MANUAL(V3). AUTO 제목 = 가장 이른 멤버 기사(`COALESCE(published_at, collected_at)`, id 순)의 제목이며 멤버십이 바뀔 때마다(분류·병합·분리·이동) `IssueAggregateService.refresh`에서 다시 맞춤. 관리자 수정 제목과 분리할 때 입력한 제목은 MANUAL로 바뀌지 않음. 제목이 실제로 달라질 때만 UPDATE. 기존 데이터는 감사 로그의 제목 수정 기록이 있거나 어떤 기사 제목과도 같지 않으면 MANUAL | 분리·이동으로 첫 기사가 빠져도 제목이 남던 한계 해소. 요약과 같은 규칙이라 예측 가능. 병합 시 AUTO 제목은 합쳐진 기사 중 가장 이른 기사를 따르므로 남는 이슈의 제목이 바뀔 수 있음(원치 않으면 제목을 직접 수정해 MANUAL로). 불필요한 UPDATE는 `updated_at`(자동 종료의 활동 시각)을 건드리므로 피함 |
| 멤버가 0명이 된 ACTIVE/REVIEW 이슈는 CLOSED | 빈 이슈 정리 |
| **2026-10-15 추가:** 콘텐츠(A1·A2) — `content`·`content_source`·`content_link`(V4, 기존 테이블 변경 없음). 생성은 `Idempotency-Key` 필수: 키는 `content.creation_key`(UNIQUE), 요청 해시는 `creation_fingerprint`. `INSERT ... ON CONFLICT (creation_key) DO NOTHING`과 이슈 행 잠금으로 동시 재요청도 1건, 다른 요청이면 409. 편집은 `version` 행 잠금 검사(409 + 현재 version·수정자·시각), REVIEW·APPROVED 편집 시 DRAFT 복귀, HOLD·EXCLUDED·ARCHIVED 편집 거부. 공개 API는 콘텐츠를 읽지 않음 | 계획 문서 `CONTENT_A_PLAN.md`. 재시도·더블클릭으로 초안이 늘지 않게 하면서 같은 이슈에 여러 콘텐츠를 만들 수 있게. 조용한 덮어쓰기 대신 충돌을 알림 |
| **2026-10-15 추가:** `AiProviderGuard` — `openai`는 설정된 채팅·임베딩 모델에 단가가 있어야 시작. DB에 다른 종류(stub↔openai)의 성공한 임베딩 기록이 있으면 시작 거부(`app.ai.embedding-guard-enabled`, 테스트 프로필만 끔) | 비용 NULL 기록과 stub·실제 벡터 혼합을 실행 방법과 무관하게 막음. 근거를 `ai_log`에서 읽으므로 기록이 지워진 DB는 판단하지 못함 |
| **2026-10-15 추가:** 관리자가 이슈를 ACTIVE로 만들면(검수 확정, 제외·종료 후 다시 활성화) 그 이슈 기사의 REVIEW 표시를 CLASSIFIED로 정리. 검수 대기(REVIEW) 이슈를 바로 CLOSED로 바꾸는 것은 거부(확정 또는 제외 먼저) | CLOSED는 공개 대상이라 검수 없이 공개되는 경로를 막음. 제외 → 다시 활성화 경로에서 기사가 영구히 "검수 대기"로 남던 문제 해소 |
| **2026-10-14 추가:** 기간 규칙 `PeriodGuard`. 실적·월별 통계 문장에만 적용. 대상 기간과 비교 기간(“~대비”, “전년 동기”, “지난해”)을 나누고, 날짜와 연도만 있는 표기는 기간으로 보지 않음. 대상 기간이 다르거나(CONFLICT) 대상 기간을 정할 수 없으면(UNCLEAR) AI의 SAME_ISSUE를 REVIEW(`GUARD_DOWNGRADE`)로 바꾸고 근거를 사유에 남김. 같은 분기라도 연도가 없으면 같은 사건의 근거로 쓰지 않음(규칙은 SAME을 막기만 하고 만들지 않음) | 모델이 기간 표시를 틀려 생긴 오병합(E09 재발)에 대응. 오병합은 이슈를 오염시키지만 병합 누락은 관리자가 나중에 병합할 수 있으므로 보수적으로 판단. 기간이 여러 번 나온다는 이유만으로 REVIEW를 늘리지 않음 |
| **2026-10-13 추가:** 오래된 이슈 자동 종료 `IssueAutoCloseService`. `app.issue-lifecycle.auto-close-after-days` 동안 활동이 없는 ACTIVE 이슈를 CLOSED로 바꾸고 이슈마다 감사 로그(`actor=system`, `ISSUE_AUTO_CLOSE`)를 같은 트랜잭션에 남김. 활동 시각 = `GREATEST(COALESCE(last_updated_at, created_at), updated_at)`(최신 기사 시각과 이슈 행의 마지막 변경 중 늦은 것). 기본 0(끔). REVIEW는 자동 종료하지 않음. 행 잠금은 `FOR UPDATE SKIP LOCKED`라 전역 락 없이 동작하고, 잠긴 이슈는 다음 실행에서 처리 | 종료 시점은 정책 결정이라 기본값으로 정하지 않음(OPEN ITEM #9). 기사 시각만 보면 과거 발행 기사가 늦게 연결된 이슈나 관리자가 방금 고친 이슈가 바로 닫힘. REVIEW는 사람의 판단이 남아 있음. 분류 적용은 이미 대상 이슈를 행 잠금으로 재확인하므로 방금 닫힌 이슈로의 연결은 REVIEW로 강등됨 |

## 7. 관리자 기능·API
| 결정 | 이유 |
|---|---|
| 병합: 경로 id = 남는 이슈(target), 본문 `sourceIssueIds`. 원본은 삭제하지 않고 `MERGED` + `merged_into_issue_id` | 이력 보존, 되돌리기 판단 가능 |
| 분리: 원본에 기사 1건 이상 남아야 함. 새 이슈는 ACTIVE | 빈 이슈 방지 |
| 병합/분리/이동/수정은 각각 1 트랜잭션, 이슈 행 잠금은 id 오름차순, 감사 로그를 같은 트랜잭션에 기록 | 동시 관리 작업 교착 방지, 부분 적용 없음 |
| 응답 형식 `{success,data,error}`, 페이지는 0부터, `size` 1~100(기본 20), 정렬은 화이트리스트 키만 | 지시서의 공통 형식. 정렬 키가 SQL로 직접 들어가지 않게 |
| 인증: HTTP Basic + `app_user`/`user_role`(BCrypt), 무상태. 조회·이슈/기사 수정 = OPERATOR 이상, 작업 재시도/취소·수동 수집 = SYSTEM_ADMIN | 최소 인증. 최종 로그인 방식은 OPEN ITEM (`SecurityConfig` 한 곳만 교체) |
| 초기 관리자는 `ADMIN_USERNAME`/`ADMIN_PASSWORD` 환경 변수가 있을 때만 생성. 기본 계정 없음 | 하드코딩 자격증명 금지 |
| 소스 목록 API는 `config`(자격증명이 들어갈 수 있음)를 반환하지 않음 | 비밀 노출 방지 |
| 브라우저는 Next.js 서버만 호출하고 `/api/*`를 백엔드로 프록시 | CORS 설정 불필요 |
| 관리자 UI의 자격증명은 `sessionStorage`(탭을 닫으면 사라짐) | Phase 1 최소. 최종 인증 방식 확정 시 교체 |

## 8. 알려진 한계 (의도적)
- 분류 락이 전역이라 LLM 호출 시간만큼 분류가 직렬화됩니다(대량 수집 시 병목 → OPEN ITEM). 2026-10-13부터 `lock-scope=CATEGORY`로 카테고리 단위까지 나눌 수 있지만 기본은 전역입니다.
- (2026-10-13 해소) 이슈 제목이 첫 기사 제목에 고정되던 문제는 `title_source` AUTO 규칙으로 바뀌었습니다. 기존 이슈의 제목은 다음 멤버십 변경 때 맞춰집니다(V3 마이그레이션이 제목을 직접 바꾸지는 않음).
- 임베딩 검색은 HNSW 근사 검색 뒤에 상태/기간 필터를 적용하므로, 후보가 많은 환경에서는 필터에 걸려 후보가 줄 수 있습니다(`ef_search` 조정 또는 후보 수 확대로 대응).
- 자동 종료된 이슈(CLOSED)는 분류 후보가 아니므로, 같은 사건의 후속 기사는 새 이슈가 됩니다. 관리자가 상태를 ACTIVE로 되돌리면 후보로 돌아오고 비활동 기간도 다시 셉니다. 대시보드의 "관리자 변경 기록" 건수에는 자동 종료 기록(`actor=system`)도 포함됩니다.
- 평가 세트의 후보 검색은 "최근 갱신 N개 이슈"로 단순화되어 있어 임베딩 검색 재현율은 측정하지 않습니다.
